package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.RewindAfterimageEntity;
import com.chronomancy.network.RewindTrailPayload;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.sound.ChronoStasisSounds;
import com.chronomancy.temporal.history.RewindPlaybackManager;
import com.chronomancy.temporal.history.TemporalHistoryManager;
import com.chronomancy.temporal.history.TemporalSnapshot;
import com.chronomancy.util.ChronoSafeTeleport;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import com.chronomancy.registry.ChronoParticleRegistry;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewind — возврат состояния САМОГО КАСТЕРА на несколько секунд назад.
 *
 * <p>Это НЕ телепорт и НЕ хил: заклинание читает краткосрочную историю
 * {@link TemporalHistoryManager} и откатывает игрока к снимку состояния
 * нескольких секунд назад (глубина — см. {@link #BASE_REWIND_SECONDS}).
 *
 * <p>Восстанавливается: позиция/поворот/скорость, здоровье, поглощение,
 * голод/насыщение, воздух, огонь, fall distance и durability отслеживаемых
 * слотов экипировки.
 *
 * <p>НЕ восстанавливается (защита от дублирования предметов — критично):
 * мана и кулдауны (MagicData не трогаем), XP, съеденные/использованные
 * предметы, стрелы, поставленные блоки, inventory целиком.
 *
 * <p>v1-ограничения: без воскрешения (история умершего стирается), без
 * отката между измерениями (несовпадение измерения = безопасный отказ).
 */
public class RewindSpell extends AbstractSpell {

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rewind");

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    // minRarity=RARE + maxLevel=3 дают ровно три грейда редкости:
                    // уровень 1 = RARE, уровень 2 = EPIC, уровень 3 = LEGENDARY.
                    .setMinRarity(SpellRarity.RARE)
                    .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
                    .setMaxLevel(3)
                    .setCooldownSeconds(12)
                    .build();

    // =========================================================
    // ФОРМУЛА ГЛУБИНЫ
    // =========================================================

    /** База: 3.0 секунды для низшего грейда редкости (RARE, уровень 1). */
    public static final double BASE_REWIND_SECONDS = 3.0;
    /** +1.0 секунда за каждый уровень редкости: RARE=3, EPIC=4, LEGENDARY=5. */
    public static final double REWIND_SECONDS_PER_LEVEL = 1.0;
    /** Жёсткий потолок глубины — история дальше не читается, буфер не растёт. */
    public static final double MAX_REWIND_SECONDS = 5.0;

    /** Прореживание траектории для клиента: каждые N тиков, не больше M точек. */
    private static final int TRAIL_SAMPLE_STEP_TICKS = 4;
    private static final int TRAIL_MAX_POINTS = 25;

    public static final int[] MANA_COST_BY_LEVEL = {180, 220, 260};

    public RewindSpell() {
        this.baseManaCost = MANA_COST_BY_LEVEL[0];
        this.manaCostPerLevel = 0;
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;
        this.castTime = 8;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }

    /** Анимация каста: ладонь к груди — заклинание на себя. */
    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastStartAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.SELF_CAST_ANIMATION;
    }

    @Override
    public DefaultConfig getDefaultConfig() {
        return defaultConfig;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return spellId;
    }

    @Override
    public int getManaCost(int spellLevel) {
        int index = Math.min(Math.max(spellLevel, 1), MANA_COST_BY_LEVEL.length) - 1;
        return MANA_COST_BY_LEVEL[index];
    }

    // =========================================================
    // ФОРМУЛЫ
    // =========================================================

    /**
     * Глубина отката в секундах зависит от уровня (=редкости) заклинания:
     * RARE (уровень 1) -&gt; 3.0 с, EPIC (2) -&gt; 4.0 с, LEGENDARY (3) -&gt; 5.0 с.
     * Клампится в [{@link #BASE_REWIND_SECONDS}, {@link #MAX_REWIND_SECONDS}].
     */
    public double calculateRewindSeconds(int spellLevel) {
        double seconds = BASE_REWIND_SECONDS + (spellLevel - 1) * REWIND_SECONDS_PER_LEVEL;
        return Math.min(MAX_REWIND_SECONDS, Math.max(BASE_REWIND_SECONDS, seconds));
    }

    // =========================================================
    // TOOLTIP
    // =========================================================

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.rewind.depth",
                        Utils.stringTruncation((float) calculateRewindSeconds(spellLevel), 1) + "s"),
                Component.translatable("spell.chronomancy.rewind.restores"),
                Component.translatable("spell.chronomancy.rewind.excludes")
        );
    }

    // =========================================================
    // КАСТ (только сервер)
    // =========================================================

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {

        // ===== [RewindDiag] TEMP =====
        if (caster instanceof net.minecraft.server.level.ServerPlayer diagP) {
            ChronomancyMod.LOGGER.info("[RewindDiag] onCast ENTER level={} source={} hand={} offhand={} mdIsCasting={}",
                    spellLevel, castSource,
                    diagP.getMainHandItem().getItem() + " x" + diagP.getMainHandItem().getCount()
                            + " idHash@" + Integer.toHexString(System.identityHashCode(diagP.getMainHandItem())),
                    diagP.getOffhandItem().getItem() + " x" + diagP.getOffhandItem().getCount(),
                    playerMagicData.isCasting());
        } else if (!level.isClientSide && !(caster instanceof net.minecraft.server.level.ServerPlayer)) {
            ChronomancyMod.LOGGER.info("[RewindDiag] onCast ENTER NON-PLAYER caster={} level={} source={}",
                    caster.getClass().getSimpleName(), spellLevel, castSource);
        }
        // ===== [/RewindDiag] =====

        if (!level.isClientSide && caster instanceof ServerPlayer player && player.isAlive()) {
            performRewind((ServerLevel) level, player, spellLevel);
        } else if (!level.isClientSide && caster instanceof com.chronomancy.entity.ChronoMobCaster mob && caster.isAlive()) {
            mob.mobRewind(spellLevel); // Часовщик: своя история позиции и здоровья
        }
        // Мана и кулдаун списываются Iron's Spells как обычно — Rewind их НЕ откатывает.
        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }

    private void performRewind(ServerLevel level, ServerPlayer player, int spellLevel) {
        // Перемотка уже идёт — повторный каст игнорируем (не ломаем проигрывание).
        if (RewindPlaybackManager.isPlaying(player.getUUID())) {
            ChronomancyMod.LOGGER.info("[RewindDiag] performRewind EXIT=PLAYBACK-BUSY (silent ignore)"); // TEMP
            return;
        }

        long now = com.chronomancy.temporal.ChronoClock.now();
        long target = now - Math.round(calculateRewindSeconds(spellLevel) * 20.0);

        TemporalSnapshot snapshot = TemporalHistoryManager.findNearest(player.getUUID(), target);

        ChronomancyMod.LOGGER.info("[RewindDiag] performRewind now={} target={} snapshot={} handAtCast=x{} idHash@{}",
                now, target,
                snapshot == null ? "NULL" : ("gameTime=" + snapshot.gameTime()),
                player.getMainHandItem().getCount(),
                Integer.toHexString(System.identityHashCode(player.getMainHandItem()))); // TEMP

        // Отказ без частичных побочных эффектов: нет снимка / смена измерения (v1).
        if (snapshot == null || snapshot.dimension() != level.dimension()) {
            ChronomancyMod.LOGGER.info("[RewindDiag] performRewind EXIT=NO-SNAPSHOT (silent) dimMatch={}",
                    snapshot != null); // TEMP
            return;
        }

        // Сэмплы истории нужны и для клиентского afterimage, и для серверной
        // перемотки — берём один раз ДО truncate.
        List<TemporalSnapshot> samples = TemporalHistoryManager
                .trajectoryBetween(player.getUUID(), snapshot.gameTime(), now, TRAIL_SAMPLE_STEP_TICKS);
        List<Vector3f> trail = buildTrail(samples);

        Vec3 safe = ChronoSafeTeleport.findSafePosition(level, snapshot.pos());
        boolean canPlayback = safe != null
                && !player.isPassenger()
                && safe.distanceToSqr(player.position()) > 0.25;

        ChronomancyMod.LOGGER.info("[RewindDiag] performRewind CONTINUE safe={} canPlayback={} dist2={} samples={} prevSelected={} snapSelected={} trackedMain={}x{} idHashNow@{}",
                safe, canPlayback,
                safe == null ? -1 : safe.distanceToSqr(player.position()),
                samples.size(), player.getInventory().selected, snapshot.selectedSlot(),
                snapshot.tracked()[0].getItem(), snapshot.tracked()[0].getCount(),
                Integer.toHexString(System.identityHashCode(player.getMainHandItem()))); // TEMP

        // canPlayback: движение откладывается — тело поведёт назад
        // RewindPlaybackManager (старт — ниже, после витального отката).
        if (!canPlayback && safe != null) {
            // Путь почти нулевой или игрок на транспорте — мгновенный tp, как раньше.
            player.teleportTo(level, safe.x, safe.y, safe.z, snapshot.yaw(), snapshot.pitch());
        }
        // Если безопасной точки нет — позицию НЕ трогаем (никаких вслепых tp в блоки),
        // но витальные параметры всё равно откатываются.

        if (!canPlayback) {
            player.setDeltaMovement(snapshot.velocity());
        }
        if (snapshot.health() > 0.0F) {
            player.setHealth(Math.min(snapshot.health(), player.getMaxHealth()));
        }
        player.setAbsorptionAmount(snapshot.absorption());
        player.getFoodData().setFoodLevel(snapshot.food());
        player.getFoodData().setSaturation(snapshot.saturation());
        player.setAirSupply(snapshot.air());
        if (snapshot.fireTicks() > 0) {
            player.setRemainingFireTicks(snapshot.fireTicks());
            player.setSharedFlagOnFire(true);
        } else {
            player.clearFire();
        }
        player.fallDistance = snapshot.fallDistance();
        // ВЫБРАННЫЙ СЛОТ НЕ ВОССТАНАВЛИВАЕТСЯ: запись selected на сервере без
        // синхронизации с клиентом рассинхронизирует хотбар (клиент думает, что
        // держит один свиток, сервер резолвит MAINHAND по своему слоту и кастует
        // чужой предмет из другого слота). См. баг «из свитков rewind кастуются
        // другие заклинания».
        // player.getInventory().selected = snapshot.selectedSlot();

        restoreDurability(player, snapshot);

        // Отменённая временная линия больше недоступна для повторного каста.
        TemporalHistoryManager.truncateAfter(player.getUUID(), snapshot.gameTime());

        ChronomancyMod.LOGGER.info("[RewindDiag] performRewind REWIND APPLIED (history truncated at {}) handNow=x{} idHash@{}",
                snapshot.gameTime(), player.getMainHandItem().getCount(),
                Integer.toHexString(System.identityHashCode(player.getMainHandItem()))); // TEMP

        // === Визуал и звук ===
        if (!trail.isEmpty()) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    player, new RewindTrailPayload(player.getId(), trail));
        }
        ChronoStasisSounds.playRewind(level, player);
        burst(level, player.position());

        if (canPlayback) {
            // Образ, оставленный в отменённой временной линии: силуэт кастера
            // замирает на точке каста, пока тело перематывается назад.
            spawnAfterimage(level, player);

            // Старт перемотки: тело едет назад, скорость снимка и fall distance
            // применяются по прибытии (иначе игрок «уедет» в момент старта).
            float rewindFallDistance = snapshot.fallDistance();
            RewindPlaybackManager.start(player, buildWaypoints(player.position(), samples, safe),
                    snapshot.yaw(), snapshot.pitch(), snapshot.velocity(),
                    () -> {
                        player.fallDistance = rewindFallDistance;
                        ChronoStasisSounds.playRewindArrive(level, player);
                        arrivalBurst(level, player.position());
                    });
        } else if (safe != null) {
            burst(level, safe);
        }
    }

    /**
     * Оставленный образ: декоративная сущность точно в позиции и повороте
     * кастера на момент каста. Синхронизируется ванильным entity-tracking'ом,
     * сетевой пакет не нужен.
     */
    private static void spawnAfterimage(ServerLevel level, ServerPlayer player) {
        RewindAfterimageEntity afterimage =
                new RewindAfterimageEntity(ChronoEntityTypeRegistry.REWIND_AFTERIMAGE.get(), level);
        afterimage.configure(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
        level.addFreshEntity(afterimage);
    }

    /** Точки пути перемотки в порядке прохождения: текущая -> история -> финал. */
    private static List<Vec3> buildWaypoints(Vec3 current, List<TemporalSnapshot> samples, Vec3 safe) {
        List<Vec3> waypoints = new ArrayList<>(samples.size() + 2);
        waypoints.add(current);
        for (TemporalSnapshot s : samples) {
            waypoints.add(s.pos());
        }
        waypoints.add(safe);
        return waypoints;
    }

    // =========================================================
    // DURABILITY — безопасный откат без rollback ItemStack
    // =========================================================

    /**
     * Откатывает damage value ТОЛЬКО если в слоте сейчас стоит тот же предмет:
     * тот же Item, то же count, и все компоненты совпадают с точностью до
     * DAMAGE_VALUE (проверка через «пробник»: копию текущего стака приводим
     * к снимку и сравниваем). Ни добавления, ни замены стаков — дублирование
     * невозможно by construction. Неуверенность → слот пропускается.
     */
    private void restoreDurability(ServerPlayer player, TemporalSnapshot snapshot) {
        ItemStack[] then = snapshot.tracked();
        for (int i = 0; i < TemporalSnapshot.TRACKED_SLOTS.length; i++) {
            ItemStack past = then[i];
            ItemStack current = player.getItemBySlot(TemporalSnapshot.TRACKED_SLOTS[i]);

            if (past.isEmpty() || current.isEmpty()) {
                if (TemporalSnapshot.TRACKED_SLOTS[i] == EquipmentSlot.MAINHAND) {
                    ChronomancyMod.LOGGER.info("[RewindDiag] restoreDurability MAINHAND skip=EMPTY past={} current={}",
                            past, current); // TEMP
                }
                continue; // предмет пропал/появился — идентичность не подтверждена
            }
            if (!ItemStack.isSameItem(past, current) || past.getCount() != current.getCount()) {
                if (TemporalSnapshot.TRACKED_SLOTS[i] == EquipmentSlot.MAINHAND) {
                    ChronomancyMod.LOGGER.info("[RewindDiag] restoreDurability MAINHAND skip=MISMATCH past={}x{} current={}x{} sameItem={}",
                            past.getItem(), past.getCount(), current.getItem(), current.getCount(),
                            ItemStack.isSameItem(past, current)); // TEMP
                }
                continue;
            }
            int pastDamage = past.getDamageValue();
            if (current.getDamageValue() == pastDamage) {
                continue;
            }
            // Пробник: если у текущего стака поменять только урон и получить
            // стак, идентичный снимку, — значит различие было ТОЛЬКО в durability.
            ItemStack probe = current.copy();
            probe.setDamageValue(pastDamage);
            if (ItemStack.isSameItemSameComponents(probe, past)) {
                current.setDamageValue(pastDamage);
            }
        }
    }

    // =========================================================
    // БЕЗОПАСНАЯ ПОЗИЦИЯ — общая логика в {@link ChronoSafeTeleport}
    // (переиспользуется Backtrack: тот же критерий «можно ли встать в точку»).
    // =========================================================

    // =========================================================
    // ВИЗУАЛ
    // =========================================================

    /** Траектория из уже собранных сэмплов (от новой точки к старой) для afterimage. */
    private static List<Vector3f> buildTrail(List<TemporalSnapshot> samples) {
        // trajectoryBetween уже упорядочен новое -> старое — ровно тот порядок,
        // в котором клиент поведёт частицы от текущей позиции к старой.
        List<Vector3f> points = new ArrayList<>(samples.size());
        for (TemporalSnapshot sample : samples) {
            if (points.size() >= TRAIL_MAX_POINTS) {
                break;
            }
            Vec3 pos = sample.pos().add(0.0, 0.9, 0.0); // примерно по центру тела
            points.add(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
        }
        return points;
    }

    /** Небольшой золотой всплеск в точке (рождение/прибытие отката). */
    private static void burst(Level level, Vec3 center) {
        MagicManager.spawnParticles(
                level, ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                center.x, center.y + 0.9, center.z,
                10,
                0.15D, 0.25D, 0.15D,
                0.02D,
                false);
    }

    /**
     * Мощный всплеск в КОНЕЧНОЙ точке перемотки: тело «дошло» до старой точки.
     * Плотное золотое облако + вертикальный столп пыли + щётка энд-родов —
     * заметно плотнее обычного {@link #burst}, но всё ещё пакетом на один кадр
     * (порядка сотни частиц, не тысячи).
     */
    private static void arrivalBurst(Level level, Vec3 center) {
        MagicManager.spawnParticles(
                level, ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                center.x, center.y + 0.9, center.z,
                60,
                0.35D, 0.9D, 0.35D,
                0.04D,
                false);
        // Столп «сматываемой нити»: пыль поднимается вверх по телу.
        MagicManager.spawnParticles(
                level, ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                center.x, center.y + 0.6, center.z,
                25,
                0.12D, 0.1D, 0.12D,
                0.09D,
                false);
        // Искры фиксации из общей золотой палитры Chronomancy.
        MagicManager.spawnParticles(
                level, ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                center.x, center.y + 1.0, center.z,
                18,
                0.3D, 0.7D, 0.3D,
                0.02D,
                false);
    }
}
