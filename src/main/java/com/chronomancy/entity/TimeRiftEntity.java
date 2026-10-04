package com.chronomancy.entity;

import com.chronomancy.ChronoConfig;
import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import com.chronomancy.temporal.worldstop.TimeRiftManager;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * TIME RIFT — разрыв в остановленном времени, открывающийся рядом с кастером The World Stop.
 *
 * <p>Жизненный цикл (серверный, по собственным тикам сущности — она {@link WorldStopExempt}):
 * <ol>
 *   <li>{@link Phase#OPENING} — раскрытие ({@value #OPEN_TICKS} тиков): анимация, частицы, звук;</li>
 *   <li>{@link Phase#OPEN} — выпускает Chronomaly с интервалом, пока идёт испытание разлома
 *       (сколько и когда — решает {@link TimeRiftManager});</li>
 *   <li>{@link Phase#CLOSING} — схлопывание ({@value #CLOSE_TICKS} тиков) и discard.</li>
 * </ol>
 *
 * <p>Закрытие наступает при ЛЮБОМ окончании стопа: менеджер зовёт {@link #beginClosing()} из
 * {@code GlobalTimeStopManager.end()}, а сам разлом каждый тик сверяет серийный номер своего стопа
 * ({@link TimeRiftManager#currentStopSerial()}) — страховка на случай выгрузки чанка, смены
 * измерения, выхода кастера или перезапуска сервера. Разлом никогда не сохраняется на диск
 * ({@code noSave()} в типе + {@link #shouldBeSaved()}).
 */
public class TimeRiftEntity extends Entity implements GeoEntity, WorldStopExempt {

    public static final int OPEN_TICKS = 20;
    public static final int CLOSE_TICKS = 15;

    public enum Phase { OPENING, OPEN, CLOSING, DORMANT }

    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(TimeRiftEntity.class, EntityDataSerializers.INT);
    /** Во сколько раз разлом больше обычного (врата босса). */
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(TimeRiftEntity.class, EntityDataSerializers.FLOAT);
    /** Разлом лежит горизонтально над головой — из него падают, а не выходят. */
    private static final EntityDataAccessor<Boolean> DATA_OVERHEAD =
            SynchedEntityData.defineId(TimeRiftEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation OPEN_ANIM = RawAnimation.begin()
            .thenPlay("animation.time_rift.open").thenLoop("animation.time_rift.idle");
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("animation.time_rift.idle");
    private static final RawAnimation CLOSE_ANIM = RawAnimation.begin().thenPlayAndHold("animation.time_rift.close");


    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private long stopSerial = -1;
    private UUID casterId;
    private int phaseTicks;
    private int openDelay;
    private int spawnCooldown;
    /** > 0: одиночный разрыв заклинания Rift — живёт столько тиков, не связан со стопом. */
    private int standaloneLifetime;

    public TimeRiftEntity(EntityType<? extends TimeRiftEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Привязка к конкретному стопу и кастеру. Вызывать ДО {@code addFreshEntity}. */
    public void bind(long stopSerial, ServerPlayer caster) {
        this.stopSerial = stopSerial;
        this.casterId = caster.getUUID();
        this.spawnCooldown = 0;
    }

    /** Одиночный разрыв (заклинание Rift): раскрывается, живёт {@code lifetime} тиков и схлопывается. */
    public void makeStandalone(int lifetime) {
        this.standaloneLifetime = Math.max(OPEN_TICKS + 1, lifetime);
    }

    /** Задержка раскрытия (тики) — несколько разломов открываются вразнобой. */
    public void delayOpening(int ticks) {
        this.openDelay = Math.max(0, ticks);
        if (openDelay > 0) {
            setPhase(Phase.DORMANT);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PHASE, Phase.OPENING.ordinal());
        builder.define(DATA_SCALE, 1.0F);
        builder.define(DATA_OVERHEAD, false);
    }

    /**
     * Врата босса: огромный разлом, раскрывающийся горизонтально в воздухе, — из него выпадает
     * Rift Maker. Мобов такие врата не выпускают. Вызывать до {@code addFreshEntity}.
     */
    public void makeBossGate(float scale) {
        this.entityData.set(DATA_SCALE, Math.max(1.0F, scale));
        this.entityData.set(DATA_OVERHEAD, true);
    }

    public float getRiftScale() {
        return this.entityData.get(DATA_SCALE);
    }

    public boolean isOverhead() {
        return this.entityData.get(DATA_OVERHEAD);
    }

    /** Большой разлом виден далеко за пределами маленького хитбокса — не отсекаем его по нему. */
    @Override
    public AABB getBoundingBoxForCulling() {
        float scale = getRiftScale();
        return scale > 1.0F ? getBoundingBox().inflate(3.0D * scale) : super.getBoundingBoxForCulling();
    }

    public Phase getPhase() {
        return Phase.values()[Math.floorMod(this.entityData.get(DATA_PHASE), Phase.values().length)];
    }

    private void setPhase(Phase phase) {
        this.entityData.set(DATA_PHASE, phase.ordinal());
        this.phaseTicks = 0;
    }

    /** Идемпотентно: разлом начинает схлопываться (время возобновилось / стоп прерван). */
    public void beginClosing() {
        if (getPhase() != Phase.CLOSING) {
            setPhase(Phase.CLOSING);
            if (!level().isClientSide) {
                level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.TIME_RIFT_CLOSE.get(),
                        SoundSource.HOSTILE, 1.0F, 1.0F);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        phaseTicks++;
        if (level().isClientSide) {
            tickClientParticles();
            return;
        }

        if (standaloneLifetime > 0) {
            if (tickCount >= standaloneLifetime && getPhase() != Phase.CLOSING) {
                beginClosing();
            }
        } else {
            // Разлом испытания живёт только внутри «своего» стопа.
            boolean ownStopRunning = GlobalTimeStopManager.isActive()
                    && stopSerial == TimeRiftManager.currentStopSerial();
            if (!ownStopRunning && getPhase() != Phase.CLOSING) {
                beginClosing();
            }
        }

        switch (getPhase()) {
            case DORMANT -> {
                if (--openDelay <= 0) {
                    setPhase(Phase.OPENING);
                }
            }
            case OPENING -> {
                if (phaseTicks == 1) {
                    boolean gate = isOverhead();
                    level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.TIME_RIFT_OPEN.get(),
                            SoundSource.HOSTILE, gate ? 3.0F : 1.2F, gate ? 0.55F : 1.0F);
                }
                if (phaseTicks >= OPEN_TICKS) {
                    setPhase(Phase.OPEN);
                }
            }
            case OPEN -> {
                if (standaloneLifetime <= 0 && !isOverhead()) {
                    tickReleasing();
                }
            }
            case CLOSING -> {
                if (phaseTicks >= CLOSE_TICKS) {
                    discard();
                }
            }
        }
    }

    /** Выпуск регулирует испытание: общий лимит живых и сколько ещё осталось победить. */
    private void tickReleasing() {
        if (--spawnCooldown > 0) {
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel) || serverLevel.getDifficulty() == Difficulty.PEACEFUL
                || !TimeRiftManager.canRelease(stopSerial)) {
            spawnCooldown = 5; // проверим снова чуть позже
            return;
        }
        spawnCooldown = ChronoConfig.riftSpawnInterval();
        releaseMob(serverLevel);
    }

    /** Кого выпустить: Chronomaly или фазирующего зомби/скелета/крипера (доля — в конфиге). */
    private EntityType<? extends net.minecraft.world.entity.Mob> pickType() {
        if (random.nextDouble() >= ChronoConfig.phasingShare()) {
            return ChronoEntityTypeRegistry.CHRONOMALY.get();
        }
        float roll = random.nextFloat();
        if (roll < 0.45F) {
            return ChronoEntityTypeRegistry.PHASING_ZOMBIE.get();
        }
        return roll < 0.78F ? ChronoEntityTypeRegistry.PHASING_SKELETON.get() : ChronoEntityTypeRegistry.PHASING_CREEPER.get();
    }

    /** Выпускает одного моба из центра разлома, только если там есть место (не в стене/жидкости). */
    private boolean releaseMob(ServerLevel level) {
        EntityType<? extends net.minecraft.world.entity.Mob> type = pickType();
        double[][] offsets = {{0, 0}, {0.6, 0}, {-0.6, 0}, {0, 0.6}, {0, -0.6}};
        for (double[] o : offsets) {
            double x = getX() + o[0];
            double y = getY() + 0.2D;
            double z = getZ() + o[1];
            AABB box = type.getSpawnAABB(x, y, z);
            if (!level.noCollision(box) || level.containsAnyLiquid(box)) {
                continue;
            }
            net.minecraft.world.entity.Mob mob = type.create(level);
            if (mob == null) {
                return false;
            }
            mob.moveTo(x, y, z, random.nextFloat() * 360.0F, 0.0F);
            EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(blockPosition()),
                    MobSpawnType.MOB_SUMMONED, null);
            if (mob instanceof ChronomalyEntity chronomaly) {
                chronomaly.markEmergingFromRift();
            } else if (mob instanceof com.chronomancy.entity.PhasingMob phasing) {
                phasing.markFromRift();
                if (mob instanceof net.minecraft.world.entity.monster.Zombie zombie) {
                    zombie.setBaby(false);
                }
            }
            TimeRiftManager.onReleased(mob);
            ServerPlayer caster = casterId == null ? null : level.getServer().getPlayerList().getPlayer(casterId);
            if (caster != null && caster.level() == level && caster.isAlive() && !caster.isSpectator()
                    && !caster.isCreative()) {
                mob.setTarget(caster);
            }
            level.addFreshEntity(mob);
            level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), x, y + 1, z, 6, 0.3, 0.5, 0.3, 0.02);
            level.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), x, y + 1, z, 2, 0.3, 0.5, 0.3, 0.0);
            level.playSound(null, x, y + 1, z, ChronoSounds.CHRONOMALY_AMBIENT.get(), SoundSource.HOSTILE,
                    1.0F, mob instanceof ChronomalyEntity ? 0.8F : 1.2F);
            ChronomancyMod.LOGGER.debug("[TimeRift] released {} at {}", type, blockPosition());
            return true;
        }
        return false;
    }

    private void tickClientParticles() {
        Phase phase = getPhase();
        if (phase == Phase.DORMANT) {
            return;
        }
        if (isOverhead()) {
            // Врата над головой: из прорехи сыплется пыль времени и осколки — вниз, на арену.
            float scale = getRiftScale();
            int count = phase == Phase.OPENING ? 10 : phase == Phase.OPEN ? 5 : 6;
            for (int i = 0; i < count; i++) {
                double px = getX() + (random.nextDouble() - 0.5) * 1.1 * scale;
                double pz = getZ() + (random.nextDouble() - 0.5) * 2.5 * scale;
                level().addParticle(random.nextInt(4) == 0 ? ChronoParticleRegistry.TEMPORAL_CRACK.get()
                                : ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                        px, getY() - 0.1, pz, 0, -0.06 - random.nextDouble() * 0.08, 0);
            }
            if (random.nextInt(phase == Phase.OPENING ? 2 : 6) == 0) {
                level().addParticle(ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                        getX() + (random.nextDouble() - 0.5) * scale, getY() - 0.3,
                        getZ() + (random.nextDouble() - 0.5) * 2.0 * scale, 0, -0.03, 0);
            }
            return;
        }
        int count = phase == Phase.OPENING ? 6 : phase == Phase.OPEN ? 2 : 4;
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = phase == Phase.CLOSING ? 0.3 : 0.5 + random.nextDouble() * 0.6;
            double height = random.nextDouble() * getBbHeight();
            double px = getX() + Math.cos(angle) * radius;
            double pz = getZ() + Math.sin(angle) * radius;
            // Раскрытие — частицы разлетаются наружу; закрытие — втягиваются внутрь.
            double dir = phase == Phase.CLOSING ? -0.06 : 0.03;
            level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(), px, getY() + height, pz,
                    Math.cos(angle) * dir, 0, Math.sin(angle) * dir);
        }
        if (random.nextInt(phase == Phase.OPENING ? 2 : 8) == 0) {
            level().addParticle(ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                    getX() + (random.nextDouble() - 0.5), getY() + random.nextDouble() * getBbHeight(),
                    getZ() + (random.nextDouble() - 0.5), 0, 0.02, 0);
        }
    }

    // Не физична, не выбирается, не толкается, не горит, не сохраняется.
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // =========================================================
    // GeckoLib
    // =========================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 2, state -> switch (getPhase()) {
            case OPENING -> state.setAndContinue(OPEN_ANIM);
            case OPEN -> state.setAndContinue(state.getController().getCurrentRawAnimation() == OPEN_ANIM
                    ? OPEN_ANIM : IDLE_ANIM);
            case CLOSING -> state.setAndContinue(CLOSE_ANIM);
            case DORMANT -> software.bernie.geckolib.animation.PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
