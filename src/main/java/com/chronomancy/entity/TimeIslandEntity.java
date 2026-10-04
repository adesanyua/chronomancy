package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Островок времени — пятно на земле, которое время от времени открывается в испытании разлома.
 *
 * <p>Мир остановлен, и личное время кастера стоит вместе с ним: здоровье не восстанавливается, мана не
 * копится. Внутри островка оно идёт снова — обычная регенерация здоровья от сытости, обычный расход
 * сытости и обычное восстановление маны, без каких-либо прибавок (см. {@code CasterPersonalTime} и
 * {@code WorldStopEvents#onChangeMana}). Островок живёт недолго и закрывается; открывает их
 * {@code TimeRiftManager}.
 *
 * <p>Выглядит как цилиндр: по его стенам вверх бежит свет, а у того, кто стоит внутри, экран снова
 * цветной, а не медный — остановка мира здесь не действует.
 *
 * <p>Сам ничего не делает — только существует и рисуется: правила спрашивают {@link #covers}.
 */
public class TimeIslandEntity extends Entity implements WorldStopExempt {
    /** За сколько тиков островок проявляется и за сколько гаснет в конце срока. */
    public static final int FADE_IN = 12, FADE_OUT = 20;
    /** Высота цилиндра островка над землёй: до неё он действует (и до неё рисуются его стены). */
    public static final double HEIGHT = 3.0D;
    /** Насколько выше и ниже пятна оно ещё действует: прыжок не выводит из островка, этаж выше — выводит. */
    private static final double REACH_UP = HEIGHT, REACH_DOWN = 1.0D;
    /** Запас поиска вокруг сущности: наибольший радиус островка из конфига. */
    private static final double SEARCH = 17.0D;

    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(TimeIslandEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(TimeIslandEntity.class, EntityDataSerializers.INT);

    public TimeIslandEntity(EntityType<? extends TimeIslandEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, 3.0F);
        builder.define(DATA_LIFETIME, 200);
    }

    /** Настройка ДО {@code addFreshEntity}. */
    public void configure(double radius, int lifetimeTicks) {
        this.getEntityData().set(DATA_RADIUS, (float) Math.max(1.0D, radius));
        this.getEntityData().set(DATA_LIFETIME, Math.max(FADE_IN + FADE_OUT, lifetimeTicks));
    }

    public float getRadius() {
        return this.getEntityData().get(DATA_RADIUS);
    }

    public int getLifetime() {
        return this.getEntityData().get(DATA_LIFETIME);
    }

    /** Островок живёт вне остановленного времени — иначе он не открылся бы посреди остановки. */
    @Override
    public boolean isOutsideTime() {
        return true;
    }

    /** Доля оставшегося срока, 1..0. */
    public float remaining(float partialTick) {
        return Mth.clamp(1.0F - (this.tickCount + partialTick) / getLifetime(), 0.0F, 1.0F);
    }

    /** Яркость рисунка, 0..1: плавно проявляется и плавно гаснет. */
    public float strength(float partialTick) {
        float age = this.tickCount + partialTick;
        float in = Mth.clamp(age / FADE_IN, 0.0F, 1.0F);
        float out = Mth.clamp((getLifetime() - age) / FADE_OUT, 0.0F, 1.0F);
        return Math.min(in, out);
    }

    /** Стоит ли сущность в этом островке (пока он не закрылся). */
    public boolean covers(Entity entity) {
        if (!this.isAlive() || this.tickCount >= getLifetime() || entity.level() != this.level()) {
            return false;
        }
        double dx = entity.getX() - this.getX(), dz = entity.getZ() - this.getZ();
        double dy = entity.getY() - this.getY();
        double r = getRadius();
        return dx * dx + dz * dz <= r * r && dy <= REACH_UP && dy >= -REACH_DOWN;
    }

    /** Стоит ли сущность хоть в каком-нибудь островке времени. */
    public static boolean anyCovers(Entity entity) {
        return entity != null && !entity.level().getEntitiesOfClass(TimeIslandEntity.class,
                entity.getBoundingBox().inflate(SEARCH, REACH_UP + 1.0D, SEARCH), island -> island.covers(entity)).isEmpty();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            spawnParticles();
        } else if (this.tickCount >= getLifetime()) {
            this.discard();
        }
    }

    /** Пылинки поднимаются над пятном: здесь время идёт. Собственные частицы мода под стопом не замирают. */
    private void spawnParticles() {
        float strength = strength(0.0F);
        if (strength <= 0.05F) {
            return;
        }
        RandomSource random = this.getRandom();
        double radius = getRadius();
        for (int i = 0; i < 2; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            this.level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    this.getX() + Math.cos(angle) * dist, this.getY() + 0.1D + random.nextDouble() * 0.3D,
                    this.getZ() + Math.sin(angle) * dist, 0.0D, 0.03D + random.nextDouble() * 0.03D, 0.0D);
        }
        if (random.nextInt(3) == 0) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            this.level().addParticle(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    this.getX() + Math.cos(angle) * radius, this.getY() + 0.1D, this.getZ() + Math.sin(angle) * radius,
                    0.0D, 0.06D + random.nextDouble() * 0.06D, 0.0D);
        }
    }

    // островок — часть испытания, а не объект мира: сохранение он не переживает
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
}
