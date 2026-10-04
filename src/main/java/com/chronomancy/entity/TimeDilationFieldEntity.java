package com.chronomancy.entity;

import com.chronomancy.temporal.TemporalRate;
import com.chronomancy.registry.ChronoParticleRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Time Dilation Field — автономная временная область.
 *
 * <p>Существует независимо от кастера (обычный {@link Entity}, синхронизируется
 * ванильным entity-tracking'ом, поэтому отдельный сетевой пакет не нужен).
 * Хранит в {@link SynchedEntityData} центр (= собственная позиция), радиус и
 * temporal rate, чтобы и сервер, и клиент одинаково считывали параметры области.
 *
 * <p>Само поле НЕ замедляется (это не Mob и не Projectile), поэтому его тик и
 * срок жизни идут в нормальном мировом времени.
 *
 * <p>Та же сущность служит и противоположностью поля — <b>Accelerated Zone</b>
 * ({@link #isAccelerating()}): темп внутри БОЛЬШЕ единицы, а область не сфера, а куб со стороной
 * в два «радиуса». Если зона и поле соприкасаются, хотя бы краями,
 * обе схлопываются во временной парадокс — см. {@link com.chronomancy.temporal.TimeParadoxBlast}.
 */
public class TimeDilationFieldEntity extends Entity
        implements io.redspace.ironsspellbooks.api.entity.NoKnockbackProjectile {
    // NoKnockbackProjectile: урон за пересечение границы зоны не отбрасывает цель. Иначе вошедшего
    // выталкивало бы обратно, и он бился бы о границу, как о стену.

    // --- Synched: читаются и сервером, и клиентом ---
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(TimeDilationFieldEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RATE =
            SynchedEntityData.defineId(TimeDilationFieldEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(TimeDilationFieldEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    // Следящий режим (Shift-каст) синхронизируется: клиентский рендерер должен
    // знать, что сфера следящая, чтобы сглаживать её центр по позиции владельца.
    private static final EntityDataAccessor<Boolean> DATA_FOLLOWING =
            SynchedEntityData.defineId(TimeDilationFieldEntity.class, EntityDataSerializers.BOOLEAN);
    /** Accelerated Zone: ускоряющий куб вместо замедляющей сферы. */
    private static final EntityDataAccessor<Boolean> DATA_ACCELERATING =
            SynchedEntityData.defineId(TimeDilationFieldEntity.class, EntityDataSerializers.BOOLEAN);

    /** Цвет хрономантии (#E6B84A). */


    /** Полное время жизни в тиках (серверная логика; -1 = бессрочно). */
    private int lifetimeTicks = -1;

    /**
     * Сервер, только зона: кто был внутри на прошлом тике. {@code null} до первого обхода — те, кого
     * зона накрыла при появлении (или после загрузки мира), границу не пересекали и урона не получают.
     */
    private java.util.Set<UUID> inside;
    /** Сервер, только зона: до какого момента ({@code ChronoClock}) цель не получает урон за пересечение. */
    private final java.util.Map<UUID, Long> crossingReady = new java.util.HashMap<>();

    /**
     * Клиент: плавный визуальный центр сферы. Обновляется рендерером каждый кадр
     * из интерполированной позиции owner; пока owner не найден — интерполированная
     * позиция самого поля. Нужен, потому что серверные {@code setPos} приходят
     * пакетами 20Гц и дают заметное отставание/дёрганье следящей сферы.
     */
    private Vec3 renderCenter;

    /**
     * Клиент-only «пульс» границы (0..1): задел на будущее — когда сущность/
     * снаряд пересекает границу, ближайшие сегменты могут коротко вспыхивать.
     * Сейчас НИКТО не вызывает {@link #addBoundaryPulse(float)} — gameplay не
     * тронут; рендерер лишь читает и плавно гасит значение.
     */
    private float boundaryPulse;

    public TimeDilationFieldEntity(EntityType<? extends TimeDilationFieldEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, 1.0F);
        builder.define(DATA_RATE, (float) TemporalRate.BASE_DILATION);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_FOLLOWING, false);
        builder.define(DATA_ACCELERATING, false);
        builder.define(DATA_OUTSIDE_TIME, false);
    }

    /**
     * Поле, выпущенное сущностью вне времени (Rift Maker), само живёт вне The World Stop:
     * тикает и истекает во время остановки. Синхронизируется, чтобы клиент тоже его не морозил.
     */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_OUTSIDE_TIME =
            net.minecraft.network.syncher.SynchedEntityData.defineId(TimeDilationFieldEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    public boolean isOutsideTime() {
        return this.getEntityData().get(DATA_OUTSIDE_TIME);
    }

    /** Заполнить параметры поля сразу после создания (до addFreshEntity на сервере). */
    public void configure(double radius, double temporalRate, int lifetimeTicks, UUID owner, boolean followOwner) {
        this.getEntityData().set(DATA_RADIUS, (float) Math.max(0.5, radius));
        this.getEntityData().set(DATA_RATE, (float) TemporalRate.clampDilation(temporalRate));
        this.lifetimeTicks = lifetimeTicks;
        this.getEntityData().set(DATA_FOLLOWING, followOwner);
        if (owner != null) {
            this.getEntityData().set(DATA_OWNER, Optional.of(owner));
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && com.chronomancy.temporal.worldstop.WorldStopExempt.is(serverLevel.getEntity(owner))) {
                this.getEntityData().set(DATA_OUTSIDE_TIME, true);
            }
        }
    }

    /**
     * То же для Accelerated Zone: темп {@code 1..}{@link TemporalRate#MAX_ACCELERATION_RATE}, область — куб.
     * Вызывать вместо {@link #configure} до addFreshEntity.
     */
    public void configureZone(double halfSize, double temporalRate, int lifetimeTicks, UUID owner, boolean followOwner) {
        configure(halfSize, TemporalRate.NORMAL, lifetimeTicks, owner, followOwner);
        this.getEntityData().set(DATA_ACCELERATING, true);
        this.getEntityData().set(DATA_RATE, (float) Math.min(TemporalRate.MAX_ACCELERATION_RATE,
                Math.max(TemporalRate.NORMAL, temporalRate)));
    }

    /** Accelerated Zone (куб, темп больше 1), а не поле замедления (сфера, темп меньше 1). */
    public boolean isAccelerating() {
        return this.getEntityData().get(DATA_ACCELERATING);
    }

    /** @return {@code true}, если поле следует за владельцем (режим Shift-каста). */
    public boolean isFollowingOwner() {
        return this.getEntityData().get(DATA_FOLLOWING);
    }

    /** Клиент: плавный визуальный центр (см. {@link #renderCenter}). */
    public Vec3 getRenderCenter() {
        return this.renderCenter != null ? this.renderCenter : this.getPosition(1.0F);
    }

    /** Клиент: выставляется рендерером каждый кадр. */
    public void setRenderCenter(Vec3 center) {
        this.renderCenter = center;
    }

    /** Клиент-only: величина текущего пульса границы (0..1). */
    public float getBoundaryPulse() {
        return this.boundaryPulse;
    }

    /**
     * Клиент-only: заложить короткий визуальный пульс границы (пересечение
     * сущностью/снарядом). Пока не вызывается из gameplay — только механизм.
     */
    public void addBoundaryPulse(float amount) {
        this.boundaryPulse = Math.min(1.0F, this.boundaryPulse + amount);
    }

    public double getRadius() {
        return this.getEntityData().get(DATA_RADIUS);
    }

    /** Итоговый темп времени внутри поля (уже с жёстким полом). */
    public double getTemporalRate() {
        return this.getEntityData().get(DATA_RATE);
    }

    public Optional<UUID> getChronoOwnerUUID() {
        return this.getEntityData().get(DATA_OWNER);
    }

    /**
     * Насколько центр области выше земли, на которую она поставлена (и выше ног владельца у следящей
     * области). Куб зоны стоит на земле дном — раньше он был врыт наполовину, и на первом уровне от
     * него оставалась плита в блок высотой. Сфера поля поднята не больше чем на блок: маленькая стоит
     * на земле целиком, большая остаётся куполом и накрывает землю так же широко, как раньше.
     */
    public double centerLift() {
        return centerLift(getRadius(), isAccelerating());
    }

    public static double centerLift(double radius, boolean accelerating) {
        return accelerating ? radius : Math.min(radius, 1.0D);
    }

    /**
     * Находится ли сущность внутри области: сферы поля или куба зоны. Считается по телу, а не по
     * точке у ног: область теперь приподнята над землёй, и стоящего рядом задевает на уровне пояса.
     */
    public boolean affects(Entity entity) {
        net.minecraft.world.phys.AABB body = entity.getBoundingBox();
        Vec3 c = this.position();
        return contains(new Vec3(net.minecraft.util.Mth.clamp(c.x, body.minX, body.maxX),
                net.minecraft.util.Mth.clamp(c.y, body.minY, body.maxY),
                net.minecraft.util.Mth.clamp(c.z, body.minZ, body.maxZ)));
    }

    /**
     * Соприкасается ли эта область с другой — хотя бы краями. Куб с кубом и сфера со сферой — по
     * своим границам; сфера с кубом — по расстоянию от центра сферы до ближайшей точки куба.
     */
    public boolean touches(TimeDilationFieldEntity other) {
        Vec3 a = this.position(), b = other.position();
        double ra = this.getRadius(), rb = other.getRadius();
        if (this.isAccelerating() == other.isAccelerating()) {
            if (this.isAccelerating()) {
                return Math.abs(a.x - b.x) <= ra + rb && Math.abs(a.y - b.y) <= ra + rb && Math.abs(a.z - b.z) <= ra + rb;
            }
            return a.distanceToSqr(b) <= (ra + rb) * (ra + rb);
        }
        Vec3 cube = this.isAccelerating() ? a : b, sphere = this.isAccelerating() ? b : a;
        double half = this.isAccelerating() ? ra : rb, radius = this.isAccelerating() ? rb : ra;
        double dx = Math.max(0.0D, Math.abs(sphere.x - cube.x) - half);
        double dy = Math.max(0.0D, Math.abs(sphere.y - cube.y) - half);
        double dz = Math.max(0.0D, Math.abs(sphere.z - cube.z) - half);
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Точка внутри области: у поля — сфера радиуса {@link #getRadius()}, у зоны — куб с такой полустороной. */
    public boolean contains(Vec3 point) {
        double r = getRadius();
        Vec3 c = this.position();
        if (isAccelerating()) {
            return Math.abs(point.x - c.x) <= r && Math.abs(point.y - c.y) <= r && Math.abs(point.z - c.z) <= r;
        }
        return c.distanceToSqr(point) <= r * r;
    }

    /**
     * Эффект для данной сущности: её собственный temporal rate, либо
     * {@link Double#NaN}, если поле на неё не действует (вне радиуса, это owner
     * или игрок).
     */
    public double rateFor(Entity entity) {
        // Rift Maker, его двойники и Chronomaly иммунны к магии времени (в т.ч. к полю самого босса).
        if (com.chronomancy.temporal.TimeMagicImmunity.isImmune(entity)) {
            return Double.NaN;
        }
        if (getChronoOwnerUUID().isPresent()
                && getChronoOwnerUUID().get().equals(entity.getUUID())) {
            return Double.NaN;
        }
        if (!affects(entity)) {
            return Double.NaN;
        }
        return getTemporalRate();
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            // Режим «личной сферы»: центр следует за владельцем. Позиция синхронно
            // расходится клиентам через обычный entity-tracking (тот же приём,
            // что и у ванильной AreaEffectCloud, привязанной к движущемуся source).
            if (this.isFollowingOwner() && this.level() instanceof ServerLevel serverLevel) {
                UUID owner = this.getChronoOwnerUUID().orElse(null);
                if (owner != null) {
                    Entity ownerEntity = serverLevel.getEntity(owner);
                    if (ownerEntity != null && ownerEntity.isAlive()) {
                        // область стоит у ног владельца, а не врыта в землю наполовину
                        this.setPos(ownerEntity.getX(), ownerEntity.getY() + centerLift(), ownerEntity.getZ());
                    }
                }
            }

            if (this.lifetimeTicks >= 0 && this.tickCount >= this.lifetimeTicks) {
                this.discard();
                return;
            }
            // Зона и поле соприкоснулись, хотя бы краями, — временной парадокс (обе области исчезают).
            if ((this.tickCount & 1) == 0 && this.level() instanceof ServerLevel serverLevel) {
                com.chronomancy.temporal.TimeParadoxBlast.check(serverLevel, this);
            }
            if (this.isAlive() && this.isAccelerating() && this.level() instanceof ServerLevel serverLevel) {
                tickCrossing(serverLevel);
            }
        } else {
            // Пока рендерер не посчитал плавный центр — используем штатную
            // интерполяцию ваниля (xo/yo/zo), частицы не должны дёргаться.
            this.renderCenter = this.getPosition(1.0F);
            spawnVisualParticles();
        }
    }

    /**
     * Граница зоны рвёт темп: враг, вошедший в куб или вышедший из него, получает урон — долю от
     * ускорения зоны ({@code accelerated_zone.crossingDamageShare}). На входе цель уже внутри, но
     * усиление урона зоной намеренно НЕ применяется; на выходе цель ещё несёт её «лихорадку», поэтому
     * урон домножается на тот же коэффициент, что и любой другой урон внутри. Зона, которая просто
     * истекла или схлопнулась в парадокс, никого не ранит: границу никто не пересекал.
     */
    private void tickCrossing(ServerLevel level) {
        double reach = getRadius() + 1.0D;
        java.util.Map<UUID, net.minecraft.world.entity.LivingEntity> now = new java.util.HashMap<>();
        for (net.minecraft.world.entity.LivingEntity living : level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(position(), position()).inflate(reach),
                e -> e.isAlive() && !e.isSpectator() && !Double.isNaN(rateFor(e)))) {
            now.put(living.getUUID(), living);
        }
        java.util.Set<UUID> before = this.inside;
        this.inside = new java.util.HashSet<>(now.keySet());
        if (before == null) {
            return;
        }
        for (java.util.Map.Entry<UUID, net.minecraft.world.entity.LivingEntity> entry : now.entrySet()) {
            if (!before.contains(entry.getKey())) {
                cross(level, entry.getValue(), false);
            }
        }
        for (UUID id : before) {
            if (!now.containsKey(id) && level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity left
                    && left.isAlive() && !left.isSpectator()
                    && !com.chronomancy.temporal.TimeMagicImmunity.isImmune(left)) {
                cross(level, left, true);
            }
        }
        if (!crossingReady.isEmpty() && (this.tickCount % 100) == 0) {
            long clock = com.chronomancy.temporal.ChronoClock.now();
            crossingReady.values().removeIf(ready -> ready <= clock);
        }
    }

    private void cross(ServerLevel level, net.minecraft.world.entity.LivingEntity target, boolean leaving) {
        long clock = com.chronomancy.temporal.ChronoClock.now();
        Long ready = crossingReady.get(target.getUUID());
        if (ready != null && clock < ready) {
            return;
        }
        double rate = getTemporalRate();
        float base = com.chronomancy.spell.AcceleratedZoneSpell.crossingDamage(rate);
        if (base <= 0.0F) {
            return;
        }
        float amount = leaving
                ? (float) (base * com.chronomancy.temporal.TemporalDilationHandler.zoneDamageFactor(rate))
                : base;
        Entity owner = getChronoOwnerUUID().map(level::getEntity).orElse(null);
        crossingReady.put(target.getUUID(), clock + com.chronomancy.ChronoConfig.zoneCrossingCooldown());
        com.chronomancy.temporal.TemporalDilationHandler.runWithoutZoneAmplification(() -> {
            if (owner != null) {
                // от имени владельца: своих и союзников Iron's Spells сам отбросит как огонь по своим
                io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, amount,
                        com.chronomancy.registry.ChronoSpellRegistry.ACCELERATED_ZONE_SPELL.getDamageSource(this, owner));
            } else {
                target.hurt(level.damageSources().magic(), amount);
            }
        });
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                target.getX(), target.getY(0.5D), target.getZ(), 6, 0.25D, 0.35D, 0.25D, 0.04D);
    }

    /**
     * Клиентская визуализация: вязкое «текучее песочное время». Немного пылинок
     * на поверхности сферы + чуть внутри, дрейфующих медленно. Намеренно мало
     * частиц за тик (не тысячи).
     */
    private void spawnVisualParticles() {
        RandomSource random = this.getRandom();
        double radius = getRadius();
        Vec3 center = getRenderCenter();

        if (isAccelerating()) {
            // Зона: искры срываются с граней куба и уносятся вверх — время здесь спешит.
            for (int i = 0; i < 4; i++) {
                double u = (random.nextDouble() * 2.0 - 1.0) * radius;
                double w = (random.nextDouble() * 2.0 - 1.0) * radius;
                boolean alongX = random.nextBoolean();
                double side = random.nextBoolean() ? radius : -radius;
                double x = center.x + (alongX ? u : side);
                double z = center.z + (alongX ? side : u);
                double y = center.y + w;
                this.level().addParticle(
                        random.nextInt(3) == 0 ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                                : ChronoParticleRegistry.TEMPORAL_TRAIL.get(),
                        x, y, z, 0.0, 0.10 + random.nextDouble() * 0.12, 0.0);
            }
            return;
        }

        for (int i = 0; i < 3; i++) {
            // случайное направление на сфере
            double theta = random.nextDouble() * Math.PI * 2.0;
            double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
            double rr = radius * (0.85 + random.nextDouble() * 0.15);

            double x = center.x + rr * Math.sin(phi) * Math.cos(theta);
            double y = center.y + rr * Math.cos(phi);
            double z = center.z + rr * Math.sin(phi) * Math.sin(theta);

            // очень медленный дрейф — ощущение густого, замедленного времени
            this.level().addParticle(
                    ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    x, y, z,
                    (random.nextDouble() - 0.5) * 0.01,
                    (random.nextDouble() - 0.5) * 0.01,
                    (random.nextDouble() - 0.5) * 0.01
            );
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.lifetimeTicks = tag.getInt("LifetimeTicks");
        this.getEntityData().set(DATA_FOLLOWING, tag.getBoolean("Following"));
        this.getEntityData().set(DATA_ACCELERATING, tag.getBoolean("Accelerating"));
        if (tag.contains("Radius")) {
            this.getEntityData().set(DATA_RADIUS, tag.getFloat("Radius"));
            this.getEntityData().set(DATA_RATE, tag.getFloat("Rate"));
        }
        if (tag.hasUUID("Owner")) {
            this.getEntityData().set(DATA_OWNER, Optional.of(tag.getUUID("Owner")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("LifetimeTicks", this.lifetimeTicks);
        tag.putBoolean("Following", this.isFollowingOwner());
        tag.putBoolean("Accelerating", this.isAccelerating());
        tag.putFloat("Radius", (float) this.getRadius());
        tag.putFloat("Rate", (float) this.getTemporalRate());
        this.getChronoOwnerUUID().ifPresent(owner -> tag.putUUID("Owner", owner));
    }
}
