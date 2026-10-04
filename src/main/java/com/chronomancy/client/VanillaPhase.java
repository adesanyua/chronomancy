package com.chronomancy.client;

import com.chronomancy.client.entity.TimePhaseRendering;
import com.chronomancy.entity.ChronoDoubleEntity;
import com.chronomancy.entity.PlayerEchoEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * «Фазирование сквозь время» для сущностей с обычными рендерерами (и для модовых GeckoLib-мобов,
 * кроме Rift Maker/Chronomaly, у которых свой слой). Кому и как — решает {@link #profileOf}:
 * <ul>
 *   <li>медные копии Rift, медные двойники игрока, Chrono Double — медь, оболочка, выпадения, эхо;</li>
 *   <li>Temporal Stasis — «застывшее золото» (шейдер stasis_echo) и неподвижная оболочка;</li>
 *   <li>внутри Time Dilation Field — слабая оболочка и шлейф замедленных силуэтов;</li>
 *   <li>Borrowed Future: бафф — яркая оболочка и шлейф скорости, долг — частые выпадения частей;</li>
 *   <li>Backtrack (до прихода отложенного урона) — оболочка, выпадения и силуэт в точке, откуда
 *       цель вырвало;</li>
 *   <li>Rewind (во время перемотки) — оболочка и силуэты вдоль пути.</li>
 * </ul>
 *
 * <p>Рендер сущности вызывается несколько раз с разными буферами:
 * <ol>
 *   <li>основной проход (медь) — одна часть модели ({@link ModelPart}: голова, рука, нога…) в нём
 *       пропускается и запоминается вместе с позой;</li>
 *   <li>пропущенная часть дорисовывается шейдером растворения со светящейся кромкой;</li>
 *   <li>переливающаяся оболочка — весь рендер ещё раз в «оболочечный» буфер;</li>
 *   <li>эхо-силуэты — то же, со сдвигом в прошлые позиции и затуханием.</li>
 * </ol>
 * Части выбираются по порядку вызовов {@code ModelPart#render}: у гуманоидов это верхние части
 * (голова, тело, руки, ноги), у иерархических моделей — дети корня. Для модовых GeckoLib-мобов
 * (копий) работают оболочка и эхо, растворения частей нет.
 */
public final class VanillaPhase {

    private record Captured(ModelPart part, Matrix4f pose, Matrix3f normal, RenderType type,
                            int light, int overlay) {
    }

    private static boolean mainPass;
    private static boolean replaying;
    private static int depth;
    private static final int[] counts = new int[2];
    private static int targetDepth = -1;
    private static int targetIndex = -1;
    private static RenderType lastType;
    private static final List<Captured> CAPTURED = new ArrayList<>();
    /** Сколько частей на глубине 0/1 было в прошлом кадре — для выбора выпадающей части. */
    private static final Map<Entity, int[]> PART_COUNTS = new WeakHashMap<>();

    /**
     * Буфер текущего прохода рендера сущности (медь/стазис/оболочка/эхо). GeckoLib-броня
     * ({@code GeoArmorRenderer#renderToBuffer}) игнорирует переданный ей буфер и берёт общий
     * {@code RenderBuffers#bufferSource()} — миксин {@code GeoArmorPhaseMixin} подставляет этот.
     */
    @org.jetbrains.annotations.Nullable
    private static MultiBufferSource activeSource;

    private VanillaPhase() {
    }

    @org.jetbrains.annotations.Nullable
    public static MultiBufferSource activeSource() {
        return activeSource;
    }

    /** Выполняет рендер с подменённым буфером (вложенные вызовы восстанавливают предыдущий). */
    public static void withSource(@org.jetbrains.annotations.Nullable MultiBufferSource source, Runnable draw) {
        MultiBufferSource prev = activeSource;
        activeSource = source;
        try {
            draw.run();
        } finally {
            activeSource = prev;
        }
    }

    /** Основной проход: 0 — как есть, 1 — медь, 2 — застывшее золото (стазис). */
    public static final int STYLE_NONE = 0, STYLE_COPPER = 1, STYLE_STASIS = 2;
    /** Смерть под Needle/Stasis: тело дематериализуется — растворяется с мерцающим голубым краем. */
    public static final int STYLE_DEMAT = 3;

    /**
     * Набор эффектов для сущности.
     *
     * @param shell         яркость оболочки (0 — нет)
     * @param dissolveCycle период выпадений частей в тиках (0 — без выпадений)
     * @param trailSpeed    скорость, с которой тянется шлейф силуэтов ({@code < 0} — нет шлейфа)
     */
    public record Profile(int style, float shell, int dissolveCycle, double trailSpeed, int trailEvery,
                          int trailLife, boolean teleportGhosts, float unrest) {
        public Profile(int style, float shell, int dissolveCycle, double trailSpeed, int trailEvery,
                       int trailLife, boolean teleportGhosts) {
            this(style, shell, dissolveCycle, trailSpeed, trailEvery, trailLife, teleportGhosts, 0.0F);
        }
    }

    /**
     * Метка «эффект действует до такого-то tickCount». Привязана к самому объекту сущности, а не
     * только к её id: игрок после возрождения (и чужой игрок, заново вошедший в зону видимости)
     * получает ТОТ ЖЕ id, но новый объект с tickCount от нуля — старая метка «до тика 50 000»
     * оставалась бы в силе почти час, и на игроке навсегда повисало фазирование.
     */
    private record Mark(java.lang.ref.WeakReference<Entity> entity, int until) {
    }

    /** Метки Backtrack/Rewind: id сущности -> до какого tickCount сущности действует эффект. */
    private static final Map<Integer, Mark> BACKTRACK_UNTIL = new java.util.HashMap<>();
    private static final Map<Integer, Mark> REWIND_UNTIL = new java.util.HashMap<>();
    /** Фазирование (снаряды проходят насквозь): id -> до какого tickCount. */
    private static final Map<Integer, Mark> PHASED_UNTIL = new java.util.HashMap<>();

    private static void mark(Map<Integer, Mark> marks, Entity entity, int ticks) {
        marks.put(entity.getId(), new Mark(new java.lang.ref.WeakReference<>(entity), entity.tickCount + ticks));
    }

    /** Действует ли метка для ЭТОГО объекта сущности; истёкшая или чужая метка сразу убирается. */
    private static boolean marked(Map<Integer, Mark> marks, Entity entity) {
        Mark mark = marks.get(entity.getId());
        if (mark == null) {
            return false;
        }
        if (mark.entity().get() != entity || mark.until() < entity.tickCount) {
            marks.remove(entity.getId());
            return false;
        }
        return true;
    }

    private static void sweep(Map<Integer, Mark> marks, net.minecraft.client.multiplayer.ClientLevel level) {
        marks.entrySet().removeIf(e -> {
            Entity now = level.getEntity(e.getKey());
            return now == null || e.getValue().entity().get() != now || e.getValue().until() < now.tickCount;
        });
    }
    /** Последний игровой тик, когда сущность была в стазисе или с иглами (для дематериализации). */
    private static final Map<Integer, Long> TEMPORAL_MARK = new java.util.HashMap<>();
    /** Сущности, которые сейчас дематериализуются (умерли под Needle/Stasis). */
    private static final java.util.Set<Integer> DEMAT = new java.util.HashSet<>();
    /** Сколько тиков после стазиса/игл смерть ещё считается «под эффектом». */
    private static final long MARK_WINDOW = 15L;

    private static int sweepTimer;

    /** Клиентский тик: метки стазиса/игл, пыль дематериализации, уборка. */
    public static void clientTick(net.minecraft.client.multiplayer.ClientLevel level) {
        long now = level.getGameTime();
        for (int id : TemporalStasisClientState.snapshotIds()) {
            TEMPORAL_MARK.put(id, now);
        }
        for (int id : TemporalNeedleClient.chargedIds()) {
            TEMPORAL_MARK.put(id, now);
        }
        TEMPORAL_MARK.values().removeIf(t -> now - t > 200L);
        java.util.Iterator<Integer> it = DEMAT.iterator();
        while (it.hasNext()) {
            Entity e = level.getEntity(it.next());
            // Игрок после возрождения получает ТОТ ЖЕ id сущности: без проверки «ещё умирает» его
            // запись оставалась здесь навсегда, и пыль дематериализации сыпалась с живого игрока
            // до перезахода в мир.
            if (e == null || e.isRemoved()
                    || !(e instanceof net.minecraft.world.entity.LivingEntity living) || !living.isDeadOrDying()
                    || living.deathTime > 20) {
                it.remove();
            } else {
                com.chronomancy.client.particle.ChronoParticles.spawnDematerialize(e, false);
            }
        }
        if (++sweepTimer >= 40) {
            // свой счётчик, а не игровое время: в остановленном мире оно стоит
            sweepTimer = 0;
            sweep(PHASED_UNTIL, level);
            sweep(BACKTRACK_UNTIL, level);
            sweep(REWIND_UNTIL, level);
        }
    }

    /** Сущность умирает и недавно была в стазисе или под иглами — дематериализуется. */
    private static boolean dematerializing(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || !living.isDeadOrDying()) {
            return false;
        }
        int id = entity.getId();
        if (DEMAT.contains(id)) {
            return true;
        }
        Long mark = TEMPORAL_MARK.get(id);
        if (mark == null || entity.level().getGameTime() - mark > MARK_WINDOW) {
            return false;
        }
        DEMAT.add(id);
        com.chronomancy.client.particle.ChronoParticles.spawnDematerialize(entity, true);
        return true;
    }

    /** Фазирование на {@code ticks} тиков (Time Walk, уклонение моба). */
    public static void onPhased(int entityId, int ticks) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity != null) {
            mark(PHASED_UNTIL, entity, ticks);
        }
    }

    /** Рывок сквозь время: силуэт в точке, откуда сущность исчезла. */
    public static void onBlink(int entityId, List<net.minecraft.world.phys.Vec3> points) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity == null || points.isEmpty()
                || entity instanceof com.chronomancy.entity.ChronomalyEntity
                || entity instanceof com.chronomancy.entity.RiftMakerEntity) {
            return; // у GeckoLib-аномалий свой шлейф телепорта
        }
        net.minecraft.world.phys.Vec3 from = points.get(0);
        TimePhaseRendering.addGhost(entity, from.x, from.y, from.z, 0, TimePhaseRendering.GHOST_LIFE);
    }

    /** Time Walk: тело «тянется» вперёд по {@code points} за {@code ticks} тиков (Rewind наоборот). */
    public static void onTimeWalk(int entityId, List<net.minecraft.world.phys.Vec3> points, int ticks) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity == null || points.isEmpty()) {
            return;
        }
        mark(REWIND_UNTIL, entity, ticks + 8);
        REWIND_ORIGINS.add(new RewindOrigin(entityId, points.get(0), net.minecraft.Util.getMillis(),
                com.chronomancy.client.entity.FrozenPose.of(entity)));
        REWIND_ORIGINS.removeIf(o -> net.minecraft.Util.getMillis() - o.time() > ORIGIN_MILLIS);
        int n = Math.min(4, points.size());
        // k = 0 (точка каста) пропускаем: там стоит остаточный образ, второй силуэт мерцал бы с ним
        for (int k = 1; k < n; k++) {
            net.minecraft.world.phys.Vec3 p = points.get(Math.min(points.size() - 1, k * (points.size() - 1) / Math.max(1, n - 1)));
            TimePhaseRendering.addGhost(entity, p.x, p.y, p.z, k, TimePhaseRendering.GHOST_LIFE);
        }
    }

    /** Кому и какой эффект положен; {@code null} — рисовать как обычно. */
    public static Profile profileOf(Entity entity) {
        if (!TimePhaseRendering.available() || entity == null
                || entity instanceof com.chronomancy.entity.RiftMakerEntity
                || entity instanceof com.chronomancy.entity.ChronomalyEntity) {
            return null;
        }
        // Свой персонаж от первого лица: Iron's Spells (playerAnimator) рисует его руки через обычный
        // рендер во время анимации каста — оболочка и копии вылезали бы прямо в лицо камере.
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
            return null;
        }
        int id = entity.getId();
        if (dematerializing(entity)) {
            return new Profile(STYLE_DEMAT, 0.6F, 0, -1, 3, 10, false, 0.0F);
        }
        boolean playerCopy = entity instanceof PlayerEchoEntity || entity instanceof ChronoDoubleEntity;
        // фазирующие мобы разлома — тот же язык, что у Chronomaly: оболочка, выпадения, силуэты рывков
        boolean phasingMob = entity instanceof com.chronomancy.entity.ChronomalMob;
        boolean phased = marked(PHASED_UNTIL, entity);
        // медные копии мобов Rift: только медь и выпадения частей — без голубого контура и шлейфа
        // (на многих мобах оболочка смотрится чужеродно)
        boolean mobCopy = !playerCopy && RiftEchoClient.isCopper(entity);
        boolean copy = playerCopy || mobCopy;
        boolean stasis = TemporalStasisClientState.isFrozen(id);
        boolean backtrack = marked(BACKTRACK_UNTIL, entity);
        boolean rewind = marked(REWIND_UNTIL, entity);
        com.chronomancy.temporal.borrowed.BorrowedFuturePhase borrowed =
                entity instanceof net.minecraft.world.entity.player.Player
                        ? BorrowedFutureClientState.phase(id) : com.chronomancy.temporal.borrowed.BorrowedFuturePhase.NORMAL;
        boolean buff = borrowed == com.chronomancy.temporal.borrowed.BorrowedFuturePhase.EMPOWERED;
        boolean debt = borrowed == com.chronomancy.temporal.borrowed.BorrowedFuturePhase.DEBT;
        boolean dilated = !stasis && com.chronomancy.temporal.TemporalDilationHandler.isClientDilated(entity);
        // Time-Piercing Needle: чем больше игл, тем сильнее цель «глючит» сквозь время
        float unrest = stasis ? 0.0F : TemporalNeedleClient.chargeFraction(id);
        boolean needled = unrest > 0.0F;
        // Парадокс: время на существе то несётся, то вязнет — и выглядит оно по-разному в каждой фазе
        boolean paradox = !stasis && ParadoxClient.afflicted(entity);
        boolean paradoxFast = paradox && ParadoxClient.fast(entity);
        if (!copy && !stasis && !backtrack && !rewind && !buff && !debt && !dilated && !needled
                && !phasingMob && !phased && !paradox) {
            // Эффект закончился, но остаточные копии ещё гаснут — дорисовываем только их.
            return TimePhaseRendering.hasGhosts(entity)
                    ? new Profile(STYLE_NONE, 0.0F, 0, -1, 3, 10, false, 0.0F) : null;
        }
        int style = stasis ? STYLE_STASIS : copy ? STYLE_COPPER : STYLE_NONE;
        float shell = 0.0F;
        if (playerCopy) shell = Math.max(shell, 0.5F);
        if (stasis) shell = Math.max(shell, 0.4F);
        if (dilated) shell = Math.max(shell, 0.3F);
        if (debt) shell = Math.max(shell, 0.15F);
        if (buff) shell = Math.max(shell, 0.6F);
        if (backtrack) shell = Math.max(shell, 0.7F);
        if (rewind) shell = Math.max(shell, 0.75F);
        if (needled && unrest >= 0.4F) shell = Math.max(shell, 0.1F + 0.3F * unrest);
        if (phasingMob) shell = Math.max(shell, 0.45F);
        if (phased) shell = Math.max(shell, 0.6F);
        if (paradox) shell = Math.max(shell, paradoxFast ? 0.65F : 0.3F);
        int cycle = stasis ? 0 : debt ? 36 : backtrack ? 34 : copy ? TimePhaseRendering.CYCLE_TICKS : 0;
        if (needled) {
            int needleCycle = Math.round(Mth.lerp(unrest, 64.0F, 32.0F));
            cycle = cycle == 0 ? needleCycle : Math.min(cycle, needleCycle);
        }
        if (phasingMob && !stasis) cycle = cycle == 0 ? 60 : Math.min(cycle, 60);
        if (phased && !stasis) cycle = cycle == 0 ? 34 : Math.min(cycle, 34);
        if (paradox) {
            // части тела выпадают из времени, а в вязкой фазе тело ещё и дёргается на месте
            cycle = cycle == 0 ? 30 : Math.min(cycle, 30);
            unrest = Math.max(unrest, paradoxFast ? 0.5F : 0.95F);
        }
        double trail = -1;
        // все остаточные копии живут одинаково — 1.5 с
        int every = 4, life = TimePhaseRendering.GHOST_LIFE;
        if (playerCopy) { trail = 0.3D; }
        if (dilated) { trail = 0.02D; every = 5; }
        if (buff) { trail = 0.12D; every = 4; }
        if (rewind) { trail = 0.0D; every = 3; }
        if (paradox) { trail = 0.02D; every = paradoxFast ? 2 : 6; }
        if (stasis) { trail = -1; }
        return new Profile(style, shell, cycle, trail, every, life, playerCopy || backtrack || rewind || phasingMob, unrest);
    }

    /**
     * Дематериализация: всё тело растворяется по пикселям за время смерти (20 тиков), край
     * растворения мерцает голубым, поверх — гаснущая переливающаяся оболочка. Красная вспышка
     * смерти убирается — тело уходит из времени, а не просто падает.
     */
    private static void renderDematerialize(Entity entity, float partialTick, MultiBufferSource buffer,
                                            Consumer<MultiBufferSource> draw) {
        int deathTime = entity instanceof net.minecraft.world.entity.LivingEntity living ? living.deathTime : 20;
        float p = Mth.clamp((deathTime + partialTick) / 19.0F, 0.0F, 1.0F);
        float progress = Mth.clamp((float) Math.pow(p, 0.75) * 1.05F, 0.0F, 1.0F);
        float alpha = 1.0F - progress;
        if (alpha <= 0.01F) {
            return;
        }
        draw.accept(type -> new DematConsumer(buffer, TimePhaseRendering.vanillaDissolve(type, false), alpha));
        float flicker = 0.65F + 0.35F * Mth.sin((entity.tickCount + partialTick) * 1.7F);
        float shell = 0.65F * (1.0F - p) * flicker;
        if (shell > 0.03F) {
            draw.accept(shellSource(buffer, shell));
        }
    }

    /**
     * Буфер дематериализации: альфа = 1 − прогресс растворения, без красного оверлея смерти.
     *
     * <p>Буфер берётся заново на КАЖДУЮ вершину, а не один раз. Общий буфер ванили закрывается,
     * как только кто-то запрашивает другой тип рендера, — а GeckoLib посреди обхода костей рисует
     * слои (броню, предметы в руках) и потом продолжает писать в прежний буфер. Свой буфер он
     * перепроверяет ({@code instanceof BufferBuilder && !building}), но обёртку распознать не может:
     * у моба с GeoArmor/предметом (маги Iron's Spells, Часовщик) запись шла в уже закрытый буфер —
     * «Not building!» и вылет. Повторный запрос того же типа дёшев: это тот же открытый буфер.
     */
    private static final class DematConsumer implements VertexConsumer {
        private final MultiBufferSource source;
        private final RenderType type;
        private final float alpha;
        private VertexConsumer delegate;

        DematConsumer(MultiBufferSource source, RenderType type, float alpha) {
            this.source = source;
            this.type = type;
            this.alpha = alpha;
            this.delegate = source.getBuffer(type);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate = source.getBuffer(type); // см. комментарий к классу
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            int noOverlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
            delegate.setUv1(noOverlay & 0xFFFF, noOverlay >> 16 & 0xFFFF);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    /** Кадр сбоя всего тела (по 2 тика), вероятность растёт с зарядом игл. */
    private static boolean jitterFrame(Entity entity, float unrest) {
        int x = entity.getId() * 0x9E3779B1 + (entity.tickCount / 2) * 0x85EBCA6B;
        x ^= x >>> 15;
        x *= 0x2C1B3C6D;
        x ^= x >>> 12;
        return (x & 0xFF) < (int) (255 * (0.06F + 0.3F * unrest));
    }

    /** Backtrack вырвал сущность из B назад в A ({@code points}: B -> A): силуэты в B и по пути. */
    public static void onBacktrack(int entityId, List<net.minecraft.world.phys.Vec3> points) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity == null || points.isEmpty()) {
            return;
        }
        mark(BACKTRACK_UNTIL, entity, 22);
        net.minecraft.world.phys.Vec3 b = points.get(0);
        net.minecraft.world.phys.Vec3 mid = points.get(points.size() / 2);
        TimePhaseRendering.addGhost(entity, b.x, b.y, b.z, 0, TimePhaseRendering.GHOST_LIFE);
        if (points.size() > 2) {
            TimePhaseRendering.addGhost(entity, mid.x, mid.y, mid.z, 2, TimePhaseRendering.GHOST_LIFE);
        }
    }

    /** Rewind: тело перематывается по {@code points} (от текущей позиции к старой) ~25 тиков. */
    public static void onRewind(int entityId, List<net.minecraft.world.phys.Vec3> points) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity == null || points.isEmpty()) {
            return;
        }
        mark(REWIND_UNTIL, entity, 27);
        REWIND_ORIGINS.add(new RewindOrigin(entityId, points.get(0), net.minecraft.Util.getMillis(),
                com.chronomancy.client.entity.FrozenPose.of(entity)));
        REWIND_ORIGINS.removeIf(o -> net.minecraft.Util.getMillis() - o.time() > ORIGIN_MILLIS);
        int n = Math.min(4, points.size());
        // k = 0 (точка каста) пропускаем: там стоит остаточный образ, второй силуэт мерцал бы с ним
        for (int k = 1; k < n; k++) {
            net.minecraft.world.phys.Vec3 p = points.get(Math.min(points.size() - 1, k * (points.size() - 1) / Math.max(1, n - 1)));
            TimePhaseRendering.addGhost(entity, p.x, p.y, p.z, k * 5, TimePhaseRendering.GHOST_LIFE);
        }
    }

    public static void clear() {
        BACKTRACK_UNTIL.clear();
        REWIND_UNTIL.clear();
        PHASED_UNTIL.clear();
        TEMPORAL_MARK.clear();
        DEMAT.clear();
        REWIND_ORIGINS.clear();
    }

    /** Откуда начиналась недавняя перемотка Rewind и кто её кастовал (для остаточного образа). */
    private record RewindOrigin(int entityId, net.minecraft.world.phys.Vec3 pos, long time,
                                com.chronomancy.client.entity.FrozenPose pose) {
    }

    /** Кастер остаточного образа и его поза в момент каста. */
    public record AfterimageSource(Entity caster, com.chronomancy.client.entity.FrozenPose pose) {
    }

    private static final List<RewindOrigin> REWIND_ORIGINS = new ArrayList<>();
    /** Сколько помним кастера остаточного образа (образ живёт 1.5 с + запас). */
    private static final long ORIGIN_MILLIS = 4000L;

    /** Кастер Rewind/Time Walk, чей остаточный образ стоит в {@code pos}, с застывшей позой (или {@code null}). */
    public static AfterimageSource rewindCasterAt(net.minecraft.world.phys.Vec3 pos) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long now = net.minecraft.Util.getMillis();
        for (int i = REWIND_ORIGINS.size() - 1; i >= 0; i--) {
            RewindOrigin o = REWIND_ORIGINS.get(i);
            if (now - o.time() < ORIGIN_MILLIS && o.pos().distanceToSqr(pos) < 2.5D * 2.5D) {
                Entity caster = level.getEntity(o.entityId());
                return caster == null ? null : new AfterimageSource(caster, o.pose());
            }
        }
        return null;
    }

    /**
     * Полный рендер сущности со всеми проходами. {@code draw} — исходный вызов рендерера
     * сущности с подставленным буфером.
     */
    public static void render(Entity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                              Profile profile, Consumer<MultiBufferSource> draw) {
        if (profile.style() == STYLE_DEMAT) {
            renderDematerialize(entity, partialTick, buffer, draw);
            return;
        }
        boolean copper = profile.style() == STYLE_COPPER;
        TimePhaseRendering.Phase phase = profile.dissolveCycle() > 0
                ? TimePhaseRendering.phaseOf(entity, partialTick, profile.dissolveCycle(), profile.unrest()) : null;
        Iterable<TimePhaseRendering.Ghost> ghosts = TimePhaseRendering.track(entity, profile.trailSpeed(),
                profile.trailEvery(), profile.trailLife(), profile.teleportGhosts());

        // 1) основной проход с выбором выпадающей части
        int[] prev = PART_COUNTS.computeIfAbsent(entity, e -> new int[2]);
        targetDepth = -1;
        targetIndex = -1;
        if (phase != null) {
            targetDepth = prev[0] >= 3 ? 0 : 1;
            int n = prev[targetDepth];
            targetIndex = n > 0 ? phase.selector() % n : -1;
        }
        counts[0] = counts[1] = 0;
        depth = 0;
        lastType = null;
        CAPTURED.clear();
        float sand = copper || profile.style() == STYLE_STASIS ? 0.0F : SandStacksClient.strength(entity);
        MultiBufferSource inner = copper ? CopperEchoRendering.wrap(buffer)
                : profile.style() == STYLE_STASIS ? type -> buffer.getBuffer(TimePhaseRendering.vanillaStasis(type))
                : sand > 0.0F ? CopperEchoRendering.wrap(buffer, sand)
                : buffer;
        MultiBufferSource tracking = type -> {
            lastType = type;
            return inner.getBuffer(type);
        };
        mainPass = true;
        // заряд игл: тело целиком изредка «перескакивает» на кадр — чем больше игл, тем чаще и дальше
        boolean jitter = profile.unrest() > 0.0F && jitterFrame(entity, profile.unrest());
        if (jitter) {
            int h = entity.getId() * 31 + entity.tickCount / 2;
            poseStack.pushPose();
            poseStack.translate(((h & 1) == 0 ? 1 : -1) * 0.07F * profile.unrest(), 0.0F,
                    ((h & 2) == 0 ? 1 : -1) * 0.04F * profile.unrest());
        }
        try {
            draw.accept(tracking);
        } finally {
            if (jitter) {
                poseStack.popPose();
            }
            mainPass = false;
            depth = 0;
        }
        prev[0] = counts[0];
        prev[1] = counts[1];

        // 2) растворение пропущенной части
        if (phase != null && !CAPTURED.isEmpty()) {
            replaying = true;
            try {
                for (Captured c : CAPTURED) {
                    PoseStack ps = new PoseStack();
                    ps.last().pose().set(c.pose());
                    ps.last().normal().set(c.normal());
                    if (phase.glitchFrame()) {
                        ps.translate(phase.glitchSign() * 0.07F, 0.0F, 0.0F);
                    }
                    VertexConsumer vc = buffer.getBuffer(TimePhaseRendering.vanillaDissolve(c.type(), copper));
                    c.part().render(ps, vc, c.light(), c.overlay(),
                            TimePhaseRendering.whiteWithAlpha(1.0F - phase.progress()));
                }
            } finally {
                replaying = false;
                CAPTURED.clear();
            }
        }

        // 3) оболочка
        if (profile.shell() > 0.02F) {
            draw.accept(shellSource(buffer, profile.shell()));
        }

        // 4) эхо-силуэты
        double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double y = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        float now = entity.tickCount + partialTick;
        for (TimePhaseRendering.Ghost g : ghosts) {
            float alpha = g.alpha(now);
            if (alpha <= 0.02F || (g.x - x) * (g.x - x) + (g.z - z) * (g.z - z) < 0.25D) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(g.x - x, g.y - y, g.z - z);
            MultiBufferSource echo = echoSource(buffer, alpha);
            if (g.pose != null) {
                g.pose.render(entity, partialTick, () -> draw.accept(echo)); // застывшая поза
            } else {
                draw.accept(echo);
            }
            poseStack.popPose();
        }
    }

    /** Буфер для остаточных копий: плотный эхо-шейдер вместо полупрозрачной оболочки. */
    public static MultiBufferSource echoSource(MultiBufferSource buffer, float alpha) {
        return type -> {
            RenderType echo = TimePhaseRendering.vanillaEcho(type, alpha);
            return echo == null ? NoopConsumer.INSTANCE : buffer.getBuffer(echo);
        };
    }

    /** Буфер, где все entity-типы рисуются оболочкой; текст, блики и прочее — отбрасываются. */
    public static MultiBufferSource shellSource(MultiBufferSource buffer, float alpha) {
        return type -> {
            RenderType shell = TimePhaseRendering.vanillaShell(type, alpha);
            return shell == null ? NoopConsumer.INSTANCE : buffer.getBuffer(shell);
        };
    }

    // =========================================================
    // Хуки ModelPart#render (см. PhaseModelPartMixin)
    // =========================================================

    /** @return {@code true} — пропустить отрисовку части (она выпадает из времени). */
    public static boolean onPartHead(ModelPart part, PoseStack poseStack, int light, int overlay) {
        if (!mainPass || replaying) {
            return false;
        }
        int d = depth;
        if (d <= 1) {
            int index = counts[d]++;
            if (d == targetDepth && index == targetIndex && lastType != null) {
                PoseStack.Pose pose = poseStack.last();
                CAPTURED.add(new Captured(part, new Matrix4f(pose.pose()), new Matrix3f(pose.normal()),
                        lastType, light, overlay));
                return true;
            }
        }
        depth++;
        return false;
    }

    public static void onPartReturn() {
        if (mainPass && !replaying && depth > 0) {
            depth--;
        }
    }

    private enum NoopConsumer implements VertexConsumer {
        INSTANCE;

        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
