package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.spell.TimeDilationFieldSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Сгусток времени — снаряд заклинания {@link TimeDilationFieldSpell}.
 *
 * <p>Визуально: маленький «клубок» переплетённых золотых нитей (отрисовка в
 * {@code TimeDilationOrbRenderer}). Физика — штатный {@link ThrowableProjectile}:
 * полёт по дуге с гравитацией, сервер сам детектирует столкновение с блоком/сущностью.
 *
 * <p>Сам сгусток НИЧЕГО не замедляет. Он лишь доставляет параметры поля
 * (радиус/temporal rate/длительность, вычисленные на касте) до точки приземления,
 * и в {@link #onHit(HitResult)} разворачивает там статичное
 * {@link TimeDilationFieldEntity}. Это отличает обычный каст от «личной сферы»
 * (Shift-каст), которая создаётся мгновенно и без снаряда.
 */
public class TimeDilationOrbEntity extends ThrowableProjectile {

    /** Страховка: сгусток, не нашедший опору, гаснет через 6 секунд без поля. */
    private static final int MAX_AGE = 120;

    /** Параметры будущего поля. Серверные; клиенту нужны только для рендера шлейфа. */
    private double fieldRadius = TimeDilationFieldSpell.MIN_FIELD_RADIUS;
    private double fieldRate = 0.6;
    private int fieldLifetime = 160;
    /** Сгусток Accelerated Zone: разворачивает ускоряющий куб вместо замедляющей сферы. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_ACCELERATING =
            SynchedEntityData.defineId(TimeDilationOrbEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private Vec3 trailFrom;
    private double trailTravel;

    public TimeDilationOrbEntity(EntityType<? extends TimeDilationOrbEntity> type, Level level) {
        super(type, level);
    }

    public TimeDilationOrbEntity(Level level, LivingEntity shooter) {
        super(ChronoEntityTypeRegistry.TIME_DILATION_ORB.get(), shooter, level);
    }

    /**
     * Переносит на снаряд параметры поля, которое он развернёт при ударе.
     * Вызывать ДО {@code addFreshEntity}, чтобы значения попали в спавн-пакет/сохранение.
     */
    public void configure(double radius, double rate, int lifetime) {
        this.fieldRadius = radius;
        this.fieldRate = rate;
        this.fieldLifetime = lifetime;
    }

    /** Сгусток несёт не поле замедления, а Accelerated Zone. Вызывать до {@code addFreshEntity}. */
    public void setAccelerating(boolean accelerating) {
        this.getEntityData().set(DATA_ACCELERATING, accelerating);
    }

    public boolean isAccelerating() {
        return this.getEntityData().get(DATA_ACCELERATING);
    }

    /** Мягкая дуга: чуть меньше стандартной гравитации снежка (0.03). */
    @Override
    protected double getDefaultGravity() {
        return 0.02;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // параметры поля сервер использует сам в onHit; клиенту нужен только вид сгустка
        builder.define(DATA_ACCELERATING, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            spawnTrail();
        } else if (this.tickCount > MAX_AGE) {
            this.discard();
        }
    }

    /**
     * Хвост сгустка — массивная струя крупных сгустков фазы (без направления, см.
     * {@link com.chronomancy.util.ProjectileStreams#massive}) плюс мягкое свечение и искры.
     */
    private void spawnTrail() {
        // частицы сыплются от ЦЕНТРА кластера (рендерер поднимает его на ~0.2),
        // иначе шлейф визуально «отстёгнут» от снаряда
        Vec3 pos = this.position().add(0.0, 0.2, 0.0);
        if (this.trailFrom == null || this.trailFrom.distanceToSqr(pos) > 36.0) {
            this.trailFrom = pos.subtract(this.getDeltaMovement());
        }
        if (isAccelerating()) {
            // Сгусток зоны: тонкий быстрый след из искр вместо вязкого облака.
            for (int i = 0; i < 3; i++) {
                Vec3 p = this.trailFrom.lerp(pos, this.random.nextDouble());
                this.level().addParticle(i == 0 ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                                : ChronoParticleRegistry.TEMPORAL_TRAIL.get(),
                        p.x, p.y, p.z, 0.0D, 0.03D, 0.0D);
            }
            this.trailFrom = pos;
            return;
        }
        // массивная струя: плотный столб крупных сгустков вокруг траектории
        this.trailTravel = com.chronomancy.util.ProjectileStreams.massive(this.level()::addParticle,
                ChronoParticleRegistry.TEMPORAL_CLOUD.get(), ChronoParticleRegistry.TEMPORAL_WISP.get(),
                this.trailFrom, pos, this.trailTravel, 0.32, 9.0, 14, this.random);
        this.trailFrom = pos;
        // мягкое glow-окошко вокруг сгустка («песок времени» сыплется)
        this.level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                pos.x + (this.random.nextDouble() - 0.5) * 0.2,
                pos.y + (this.random.nextDouble() - 0.5) * 0.2,
                pos.z + (this.random.nextDouble() - 0.5) * 0.2,
                0.0D, 0.01D, 0.0D);
        if (this.random.nextFloat() < 0.15F) {
            this.level().addParticle(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    pos.x, pos.y, pos.z,
                    (this.random.nextDouble() - 0.5) * 0.04,
                    0.02 + this.random.nextDouble() * 0.04,
                    (this.random.nextDouble() - 0.5) * 0.04);
        }
    }

    /**
     * Момент истины: сгусток коснулся блока/сущности — в точке удара разворачивается
     * статичное поле дилейшена, а сам снаряд гаснет. Только на сервере.
     */
    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (this.level().isClientSide || this.isRemoved()) {
            return;
        }
        Vec3 center = result.getLocation();
        if (result instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
            // чуть от грани наружу: точка на самой грани стены попадала бы в колонку блоков стены,
            // и опора для области искалась бы внутри неё
            net.minecraft.core.Direction face = blockHit.getDirection();
            center = center.add(face.getStepX() * 0.05D, face.getStepY() * 0.05D, face.getStepZ() * 0.05D);
        }
        Entity owner = this.getOwner();
        UUID ownerUUID = owner != null ? owner.getUUID() : null;

        TimeDilationFieldSpell.spawnStaticField(
                this.level(), center, this.fieldRadius, this.fieldRate,
                this.fieldLifetime, ownerUUID, false, isAccelerating());
        this.discard();
    }

    // =========================================================
    // ПЕРСИСТЕНТНОСТЬ (снаряд короткоживущий, но поле-параметры переживут сейв)
    // =========================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("FieldRadius", this.fieldRadius);
        tag.putDouble("FieldRate", this.fieldRate);
        tag.putInt("FieldLifetime", this.fieldLifetime);
        tag.putBoolean("Accelerating", isAccelerating());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.fieldRadius = tag.getDouble("FieldRadius");
        this.fieldRate = tag.getDouble("FieldRate");
        this.fieldLifetime = tag.getInt("FieldLifetime");
        setAccelerating(tag.getBoolean("Accelerating"));
    }
}
