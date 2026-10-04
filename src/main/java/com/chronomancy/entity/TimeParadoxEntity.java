package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Вспышка временного парадокса — чисто визуальная сущность: живёт {@value #LIFETIME} тиков, урона не
 * наносит (его уже нанёс {@link com.chronomancy.temporal.TimeParadoxBlast}). Клиент рисует по ней
 * расходящиеся оболочки (золотая сфера поля и голубой куб зоны, рвущие друг друга), рябь на экране
 * и выброс частиц. Радиус синхронизируется.
 */
public class TimeParadoxEntity extends Entity implements WorldStopExempt {
    public static final int LIFETIME = 44;

    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(TimeParadoxEntity.class, EntityDataSerializers.FLOAT);

    public TimeParadoxEntity(EntityType<? extends TimeParadoxEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, 4.0F);
    }

    public void configure(float radius) {
        this.getEntityData().set(DATA_RADIUS, Math.max(1.0F, radius));
    }

    public float getRadius() {
        return this.getEntityData().get(DATA_RADIUS);
    }

    /** Вспышка идёт и во время остановки мира: парадокс времени ей не подчиняется. */
    @Override
    public boolean isOutsideTime() {
        return true;
    }

    /** 0..1 — насколько вспышка прожита (с учётом кадра). */
    public float progress(float partialTick) {
        return Math.min(1.0F, (this.tickCount + partialTick) / LIFETIME);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            spawnParticles();
        } else if (this.tickCount >= LIFETIME) {
            this.discard();
        }
    }

    private void spawnParticles() {
        RandomSource random = this.getRandom();
        float radius = getRadius();
        Vec3 c = this.position().add(0.0D, 0.6D, 0.0D);
        if (this.tickCount <= 2) {
            // выброс: осколки циферблатов и искры во все стороны, песок — веером по земле
            int count = (int) Math.min(90.0F, 30.0F + radius * 7.0F);
            for (int i = 0; i < count; i++) {
                double theta = random.nextDouble() * Math.PI * 2.0;
                double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
                double sx = Math.sin(phi) * Math.cos(theta), sy = Math.abs(Math.cos(phi)), sz = Math.sin(phi) * Math.sin(theta);
                double speed = 0.25 + random.nextDouble() * 0.12 * radius;
                int kind = random.nextInt(4);
                this.level().addParticle(kind == 0 ? ChronoParticleRegistry.TEMPORAL_CRACK.get()
                                : kind == 1 ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                                : kind == 2 ? ChronoParticleRegistry.TEMPORAL_TRAIL.get()
                                : ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(),
                        c.x + sx * 0.4, c.y + sy * 0.4, c.z + sz * 0.4, sx * speed, sy * speed * 0.7, sz * speed);
            }
            for (int i = 0; i < 6; i++) {
                this.level().addParticle(ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                        c.x + (random.nextDouble() - 0.5) * radius, c.y + random.nextDouble() * radius * 0.5,
                        c.z + (random.nextDouble() - 0.5) * radius, 0.0D, 0.03D, 0.0D);
            }
            return;
        }
        // послесвечение: пыль висит в объёме взрыва и медленно гаснет
        float t = progress(0.0F);
        int linger = t < 0.7F ? 5 : 2;
        for (int i = 0; i < linger; i++) {
            double theta = random.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(random.nextDouble()) * radius * Math.min(1.0F, t * 2.2F);
            this.level().addParticle(random.nextBoolean() ? ChronoParticleRegistry.TEMPORAL_MOTE.get()
                            : ChronoParticleRegistry.TEMPORAL_WISP.get(),
                    c.x + Math.cos(theta) * dist, c.y + random.nextDouble() * radius * 0.45, c.z + Math.sin(theta) * dist,
                    0.0D, random.nextBoolean() ? 0.05D : -0.01D, 0.0D);
        }
    }

    // вспышка не переживает сохранение: это мгновение, а не объект мира
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
