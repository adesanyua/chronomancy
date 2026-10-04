package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.BacktrackBoltEntity;
import com.chronomancy.network.BacktrackStreakPayload;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.sound.ChronoBacktrackSounds;
import com.chronomancy.temporal.BacktrackDamageQueue;
import com.chronomancy.temporal.history.EntityPositionHistory;
import com.chronomancy.temporal.history.TemporalHistoryManager;
import com.chronomancy.temporal.history.TemporalSnapshot;
import com.chronomancy.util.ChronoSafeTeleport;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.AnimationHolder;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * BACKTRACK — основной low-cooldown DPS спелл школы Chronomancy.
 *
 * <p>Механика: быстрый темпоральный болт при попадании в LivingEntity
 * мгновенно ВОЗВРАЩАЕТ ЦЕЛЬ на позицию, где та была N секунд назад, а ровно
 * через 1 секунду мирового времени цель получает magic damage. t=0: hit, B→A;
 * t=+1s: damage догоняет. Глубина отката N растёт с уровнем (=редкостью) и со
 * спелл-пауэром кастера — см. {@link #calculateDepthSeconds}.
 *
 * <p>Это НЕ полный Rewind цели: откатывается ТОЛЬКО позиция+поворот. HP,
 * эффекты, голод, экипировка, inventory, кулдауны — не трогаются. Снимок
 * игроков читается из общего {@link TemporalHistoryManager} (только pos —
 * полный player-restore недоступен этому спеллу), позиции мобов — из
 * лёгкого {@link EntityPositionHistory} (on-demand запись с первого попадания).
 *
 * <p>Отложенный урон живёт в {@link BacktrackDamageQueue} (внешний серверный
 * планировщик, НЕ tickCount цели) и проходит через штатный SpellDamageSource
 * поток, поэтому корректно буферизуется Stasis/World Stop. Каждое попадание —
 * собственный event: спам-режим (hit каждые 0.4 с) даёт независимые дедлайны.
 *
 * <p>Safe backtrack: цель НИКОГДА не телепортируется внутрь блока — точка из
 * истории проверяется через {@link ChronoSafeTeleport}; безопасной точки нет —
 * teleport пропускается, но отложенный урон всё равно наступает. Между
 * измерениями откат запрещён. Цели в Temporal Stasis не перемещаются (время
 * их не двигает), урон при этом буферизуется.
 */
public class BacktrackSpell extends AbstractSpell {

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "backtrack");

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    .setMinRarity(SpellRarity.UNCOMMON)
                    .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
                    .setMaxLevel(5)
                    // Кулдаун одинаков на всех уровнях — 4,5 секунды.
                    .setCooldownSeconds(4.5)
                    .build();

    // =========================================================
    // БАЛАНС (сила спелла — в глубине отката, а не в цифре урона)
    // =========================================================

    /**
     * Глубина позиционного отката (секунды мирового времени) растёт с уровнем
     * (=редкостью) и со спелл-пауэром кастера: uncommon (L1) = {@value
     * #BASE_DEPTH_SECONDS} c, +{@value #DEPTH_SECONDS_PER_LEVEL} c за уровень
     * (legendary L5 = 3.0 c), плюс {@value
     * #DEPTH_SECONDS_PER_TEN_PERCENT_POWER} c за каждые 10% спелл-пауэра.
     * Жёсткий потолок — {@link #MAX_DEPTH_SECONDS} (дальше история не читается).
     */
    public static final float BASE_DEPTH_SECONDS = 1.0F;
    public static final float DEPTH_SECONDS_PER_LEVEL = 0.5F;
    public static final float DEPTH_SECONDS_PER_TEN_PERCENT_POWER = 0.2F;
    public static final float MAX_DEPTH_SECONDS = 4.0F;

    /** Доля Spell Power в отложенном уроне: L1≈3.0 … L5≈4.2 — скромно, по дизайну. */
    public static final float DAMAGE_POWER_FRACTION = 0.3F;
    /** Доп. урон за каждый блок пути, который цель прошла за откатанные секунды (× сила магии). */
    public static final float BONUS_DAMAGE_PER_BLOCK = 1.5F;
    /** Скачок длиннее этого между соседними снимками — телепорт, а не путь. */
    private static final double MAX_STEP_PER_TICK = 4.0D;

    /** Число контрольных точки короткой обратной нити B→A. */
    private static final int STREAK_POINTS = 8;

    public BacktrackSpell() {
        this.baseManaCost = 40; // изначальное (uncommon, уровень 1) — 40 маны
        this.manaCostPerLevel = 2;
        this.baseSpellPower = 10;
        this.spellPowerPerLevel = 1;
        this.castTime = 0;
    }

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
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
    public AnimationHolder getCastStartAnimation() {
        return SpellAnimations.ONE_HANDED_HORIZONTAL_SWING_ANIMATION; // рывок-«хлыст» рукой
    }

    // =========================================================
    // ФОРМУЛЫ / TOOLTIP
    // =========================================================

    /** Итоговый отложенный урон (уровень × общий Spell Power × сила школы × конфиг). */
    public float getDamage(int spellLevel, @Nullable LivingEntity caster) {
        return getSpellPower(spellLevel, caster) * DAMAGE_POWER_FRACTION;
    }

    /**
     * Доп. урон за блок пройденного пути: {@value #BONUS_DAMAGE_PER_BLOCK} × сила магии кастера.
     * Действует у любого кастера — и когда хрономаль попадает Backtrack'ом по игроку.
     */
    public float getBonusPerBlock(@Nullable LivingEntity caster) {
        double power = caster == null ? 1.0 : getEntityPowerMultiplier(caster);
        return (float) (BONUS_DAMAGE_PER_BLOCK * Math.max(0.0, power));
    }

    /**
     * Глубина позиционного отката в секундах: база + уровень + спелл-пауэр,
     * клампится в [{@value #BASE_DEPTH_SECONDS} .. {@value #MAX_DEPTH_SECONDS}].
     * Считается ОДИН раз на касте и замораживается в болте (см. {@link #onCast}),
     * чтобы снаряд откатывал ровно на ту глубину, что была видна в тултипе.
     */
    public float calculateDepthSeconds(int spellLevel, @Nullable LivingEntity caster) {
        float levelPart = BASE_DEPTH_SECONDS + DEPTH_SECONDS_PER_LEVEL * (spellLevel - 1);
        float powerPart = 0.0F;
        if (caster != null) {
            double powerMultiplier = getEntityPowerMultiplier(caster); // 1.0 = без бонуса
            double bonusPercent = Math.max(0.0, (powerMultiplier - 1.0) * 100.0);
            powerPart = DEPTH_SECONDS_PER_TEN_PERCENT_POWER * (float) (bonusPercent / 10.0);
        }
        return Math.min(MAX_DEPTH_SECONDS, Math.max(BASE_DEPTH_SECONDS, levelPart + powerPart));
    }

    /** Секунды глубины → СЕРВЕРНЫЕ тики (смещение targetGameTime при поиске снимка). */
    public static int depthSecondsToTicks(double seconds) {
        return (int) Math.round(seconds * 20.0);
    }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.backtrack.damage",
                        Utils.stringTruncation(getDamage(spellLevel, caster), 1)),
                Component.translatable("spell.chronomancy.backtrack.distance_bonus",
                        Utils.stringTruncation(getBonusPerBlock(caster), 1)),
                // Глубина отката растёт с уровнем и спелл-пауэром — показываем
                // реальное значение для данного кастера.
                Component.translatable("spell.chronomancy.backtrack.depth",
                        Utils.stringTruncation(calculateDepthSeconds(spellLevel, caster), 1) + "s"),
                Component.translatable("spell.chronomancy.backtrack.delay",
                        Utils.stringTruncation(BacktrackDamageQueue.DAMAGE_DELAY_TICKS / 20.0F, 1) + "s")
        );
    }

    // =========================================================
    // КАСТ — выстрел болта (только сервер)
    // =========================================================

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {

        if (!level.isClientSide) {
            BacktrackBoltEntity bolt = new BacktrackBoltEntity(level, caster);
            bolt.setPos(caster.position().add(0,
                    caster.getEyeHeight() - bolt.getBoundingBox().getYsize() * 0.5F, 0));
            bolt.shoot(caster.getLookAngle());
            // damage болта = БУДУЩИЙ отложенный урон; при касании не бьёт ничего
            bolt.setDamage(getDamage(spellLevel, caster));
            // Глубина позиционного отката замораживается на касте (уровень +
            // спелл-пауэр) и едет в болте — попадание откатывает ровно на неё.
            bolt.setRewindTicks(depthSecondsToTicks(calculateDepthSeconds(spellLevel, caster)));
            bolt.setBonusPerBlock(getBonusPerBlock(caster));
            level.addFreshEntity(bolt);
            // короткий часовой tick — «пружина отпущена»
            ChronoBacktrackSounds.playCast(level, caster);
        }
        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }

    // =========================================================
    // ПОПАДАНИЕ БОЛТА — вся серверная механика (вызывается из Bolt)
    // =========================================================

    public static void onBoltHit(ServerLevel level, LivingEntity caster,
                                 LivingEntity target, float damage, int rewindTicks) {
        onBoltHit(level, caster, target, damage, rewindTicks,
                ChronoSpellRegistry.BACKTRACK_SPELL.getBonusPerBlock(caster), false);
    }

    public static void onBoltHit(ServerLevel level, LivingEntity caster,
                                 LivingEntity target, float damage, int rewindTicks, boolean echo) {
        onBoltHit(level, caster, target, damage, rewindTicks,
                ChronoSpellRegistry.BACKTRACK_SPELL.getBonusPerBlock(caster), echo);
    }

    public static void onBoltHit(ServerLevel level, LivingEntity caster, LivingEntity target, float damage,
                                 int rewindTicks, float bonusPerBlock, boolean echo) {
        if (target == caster || !target.isAlive()) {
            return;
        }

        // Сопротивление магии времени: цель откатывается на меньшую глубину (урон режет сам ISS).
        rewindTicks = (int) com.chronomancy.temporal.TimeMagicResist.scaleTicks(target, rewindTicks);
        Vec3 hitPosition = target.position(); // B — откуда вырвали
        // Позиционный откат (может не состояться); возвращает путь, «отменённый» откатом.
        double undone = backtrackPosition(level, target, rewindTicks);
        Vec3 landedPosition = target.position(); // A (или B, если телепорта не было)
        // Чем больше цель успела пройти за откатанные секунды, тем сильнее её догонит удар.
        damage += (float) (undone * bonusPerBlock);

        // Визуал и звук момента откатa
        playHitFeedback(level, target, hitPosition, landedPosition);

        // Отложенный удар — СОБСТВЕННЫЙ event этого попадания, ровно через
        // 20 серверных тиков мирового времени (не tickCount цели).
        BacktrackDamageQueue.schedule(level, target, caster, damage,
                ChronoSpellRegistry.BACKTRACK_SPELL, echo);

        // Мобам включаем on-demand запись истории (идемпотентно; игроки уже
        // пишутся в TemporalHistoryManager постоянно).
        EntityPositionHistory.begin(target);
    }

    // =========================================================
    // ПОЗИЦИОННЫЙ ОТКАТ B -> A
    // =========================================================

    /**
     * Откатывает цель к позиции на {@code rewindTicks} серверных тиков назад
     * (глубина задаётся на касте: уровень + спелл-пауэр). Только позиция+поворот,
     * velocity ОБНУЛЯЕТСЯ (старая не возвращаем в v1). Без снимка / цель в
     * стазисе / нет безопасной точки — молча пропускаем (урон всё равно придёт).
     */
    private static double backtrackPosition(ServerLevel level, LivingEntity target, int rewindTicks) {
        // Temporal Stasis: цель вне времени — перемещение запрещено, чтобы не
        // ломать семантику стазиса; отложенный урон отбуферизуется штатно.
        if (target.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
            return 0.0;
        }
        // Иммунные к магии времени (Rift Maker, его двойники, Chronomaly) не откатываются.
        if (com.chronomancy.temporal.TimeMagicImmunity.isImmune(target)) {
            return 0.0;
        }

        long targetGameTime = com.chronomancy.temporal.ChronoClock.now() - rewindTicks;

        Vec3 historicalPos;
        float yaw;
        float pitch;

        if (target instanceof Player player) {
            // Игроки: общий TemporalHistoryManager, читаем ТОЛЬКО pos/yaw/pitch.
            // Полный player-restore (HP/inventory/...) этому спеллу недоступен
            // осознанно — это прерогатива Rewind.
            TemporalSnapshot snapshot = TemporalHistoryManager.findNearest(player.getUUID(), targetGameTime);
            if (snapshot == null || snapshot.dimension() != level.dimension()) {
                return 0.0; // нет истории / цель сменила измерение — межизмеренческий откат запрещён
            }
            historicalPos = snapshot.pos();
            yaw = snapshot.yaw();
            pitch = snapshot.pitch();
        } else {
            EntityPositionHistory.Sample sample =
                    EntityPositionHistory.findNearest(level, target.getUUID(), targetGameTime);
            if (sample == null) {
                return 0.0; // первое попадание по этому мобу — откатывать ещё некуда
            }
            historicalPos = new Vec3(sample.x(), sample.y(), sample.z());
            yaw = sample.yaw();
            pitch = sample.pitch();
        }

        // SAFE: историческая точка могла перестать быть проходимой (блок
        // поставили, дверь закрыли). Нет безопасной точки — НЕ телепортируем,
        // отложенный урон при этом всё равно наступает.
        Vec3 safe = ChronoSafeTeleport.findSafePosition(level, historicalPos);
        if (safe == null) {
            return 0.0;
        }

        target.teleportTo(level, safe.x, safe.y, safe.z, java.util.Set.of(), yaw, pitch);
        // Не оставляем старую скорость — иначе цель мгновенно «уедет» обратно
        // вперёд. Историческую velocity в v1 НЕ возвращаем.
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true; // сервер -> клиент: сбросить клиентскую инерцию
        target.resetFallDistance();
        // Путь, который цель прошла за откатанные секунды (по истории позиций).
        long now = com.chronomancy.temporal.ChronoClock.now();
        return target instanceof Player
                ? TemporalHistoryManager.pathLength(target.getUUID(), targetGameTime, now, MAX_STEP_PER_TICK)
                : EntityPositionHistory.pathLength(target.getUUID(), targetGameTime, now, MAX_STEP_PER_TICK);
    }

    // =========================================================
    // ВИЗУАЛ / ЗВУК ПОПАДАНИЯ
    // =========================================================

    /**
     * B: короткая Temporal Crack/искра; линия B→A: обратная нить (~0.3 с,
     * клиентский короткий trail); A: вспышка mote/sand; обратный metallic/sand
     * звук. Разнесённый с уроном момент — игрок видит: «позиция ушла назад,
     * повреждение догонит позже».
     */
    private static void playHitFeedback(ServerLevel level, LivingEntity target,
                                        Vec3 from /*B*/, Vec3 to /*A*/) {
        double midHeight = target.getBbHeight() * 0.6D;

        // Трещина времени в точке, где цель стояла (B)
        MagicManager.spawnParticles(level, ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                from.x, from.y + midHeight, from.z,
                6, 0.12, 0.2, 0.12, 0.05D, false);

        // Направленная обратная нить B -> A (если откат состоялся и он заметен)
        if (from.distanceToSqr(to) > 0.4) {
            List<Vector3f> points = new ArrayList<>(STREAK_POINTS);
            Vec3 head = to.add(0.0, midHeight, 0.0);
            Vec3 tail = from.add(0.0, midHeight, 0.0);
            for (int i = 0; i < STREAK_POINTS; i++) {
                double t = i / (double) (STREAK_POINTS - 1);
                Vec3 p = tail.lerp(head, t); // порядок: ОТ точки попадания B К исторической A — нить «убегает в прошлое», схлопывание в A
                points.add(new Vector3f((float) p.x, (float) p.y, (float) p.z));
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    target, new BacktrackStreakPayload(target.getId(), points));
        }

        // Вспышка песчинок в исторической точке A
        MagicManager.spawnParticles(level, ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                to.x, to.y + midHeight, to.z,
                5, 0.15, 0.25, 0.15, 0.04D, false);

        ChronoBacktrackSounds.playRewindHit(level, target);
    }
}
