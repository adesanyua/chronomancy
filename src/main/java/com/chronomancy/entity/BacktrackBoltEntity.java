package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.spell.BacktrackSpell;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import com.chronomancy.registry.ChronoSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Backtrack Bolt — небольшой быстрый темпоральный снаряд.
 *
 * <p>Наследник {@link AbstractMagicProjectile}, а значит БЕСПЛАТНО получает
 * всю существующую темпоральную механику движения (по требованию дизайна —
 * никакого отдельного movement-система для Backtrack):
 * <ul>
 *   <li><b>Time Dilation:</b> как {@code Projectile} замедляется серверным
 *   {@code TemporalDilationHandler.shouldSkipTick} и клиентским move-scale;</li>
 *   <li><b>World Stop:</b> зависает через {@code ProjectilesSuspension} —
 *   тем же путём, что и остальные снаряды.</li>
 * </ul>
 *
 * <p>Сам болт ничего не откатывает и не наносит урон при попадании — он лишь
 * доставляет факт касания до {@link BacktrackSpell#onBoltHit}, где живёт вся
 * серверная логика (history → safe teleport → визуал → отложенный урон).
 * В v1 не пробивает: первое же существо/блок его уничтожает.
 */
public class BacktrackBoltEntity extends AbstractMagicProjectile {

    /** Живёт недолго: ~2.5 блока/тик ⇒ дальность действия ~50 блоков. */
    private static final int MAX_AGE_TICKS = 20;
    private static final float BOLT_SPEED = 2.5F;

    /** Флаг «болт уже коснулся существа» — свой звук/визуал удара делает спелл. */
    private boolean entityImpact;

    /**
     * Глубина позиционного отката в СЕРВЕРНЫХ тиках — заморожена на касте
     * (уровень + спелл-пауэр) и передана сюда из {@link BacktrackSpell#onCast}.
     * Используется только на сервере в момент попадания, клиенту не синхронится.
     */
    private int rewindTicks = 20;
    /** Доп. урон за каждый блок пути, «отменённого» откатом (заморожен на касте: 3 × сила магии). */
    private float bonusPerBlock;

    public void setBonusPerBlock(float bonus) {
        this.bonusPerBlock = Math.max(0.0F, bonus);
    }

    public float getBonusPerBlock() {
        return bonusPerBlock;
    }

    public void setRewindTicks(int ticks) {
        this.rewindTicks = ticks;
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("RewindTicks", rewindTicks);
        tag.putFloat("BonusPerBlock", bonusPerBlock);
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        rewindTicks = tag.contains("RewindTicks") ? Math.max(1, tag.getInt("RewindTicks")) : 20;
        bonusPerBlock = tag.getFloat("BonusPerBlock");
    }

    public BacktrackBoltEntity(EntityType<? extends BacktrackBoltEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true); // настильный болт, а не бросок
    }

    public BacktrackBoltEntity(Level level, LivingEntity shooter) {
        this(ChronoEntityTypeRegistry.BACKTRACK_BOLT.get(), level);
        this.setOwner(shooter);
    }

    @Override
    public float getSpeed() {
        return BOLT_SPEED;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && this.tickCount > MAX_AGE_TICKS && !this.isRemoved()) {
            // Истёк срок — болт «рассыпается» в песок времени.
            this.impactParticles(this.getX(), this.getY(), this.getZ());
            this.discard();
        }
    }

    // =========================================================
    // ПОПАДАНИЯ (вся механика — в BacktrackSpell)
    // =========================================================

    @Override
    protected void onHitBlock(@NotNull BlockHitResult blockHitResult) {
        super.onHitBlock(blockHitResult);
        this.discard(); // не отскакивает, не пролетает насквозь
    }

    @Override
    protected void onHitEntity(@NotNull EntityHitResult entityHitResult) {
        super.onHitEntity(entityHitResult);
        if (!level().isClientSide
                && entityHitResult.getEntity() instanceof LivingEntity target
                && getOwner() instanceof LivingEntity caster) {
            entityImpact = true;
            BacktrackSpell.onBoltHit((ServerLevel) level(), caster, target, getDamage(), rewindTicks,
                    bonusPerBlock, com.chronomancy.temporal.chronodouble.ChronoDoubleProjectiles.isEcho(this));
        }
        // v1: без пробития — расходует болт на любом живом попадании.
        this.consumeEntityImpact(entityHitResult, true);
    }

    // =========================================================
    // ВИЗУАЛ
    // =========================================================

    /** Момент удара/смерти (сервер): короткая TEMPORAL_SPARK — «трещина времени». */
    @Override
    public void impactParticles(double x, double y, double z) {
        // ISS broadcasts particles via the server player list; never call it on ClientLevel.
        if (!(level() instanceof ServerLevel serverLevel)) return;
        MagicManager.spawnParticles(serverLevel, ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                x, y, z, 5, 0.12, 0.16, 0.12, 0.05D, false);
    }

    /**
     * Полёт (клиент): маленький Temporal Mote core + пара песчинок Sand Grain
     * + короткий обратный Temporal Trail — болт «вытекает» из собственного
     * прошлого, а не тащит обычный огненный хвост.
     */
    @Override
    public void trailParticles() {
        if (tickCount < 2) {
            return;
        }
        Vec3 vel = getDeltaMovement();
        if (vel.lengthSqr() < 1.0E-6) {
            return; // в World Stop скорость не обнуляется, но на всякий случай
        }
        Vec3 current = position();
        if (trailFrom == null || trailFrom.distanceToSqr(current) > 36.0) {
            trailFrom = current.subtract(vel);
        }
        // зигзагообразная струя: огоньки без направления, излом каждые ~1.1 блока
        trailTravel = com.chronomancy.util.ProjectileStreams.zigzag(level()::addParticle,
                ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(), trailFrom, current, trailTravel, 0.22, 1.1, 9.0, 24);
        // ядро болта — мягкая песчинка
        level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(), current.x, current.y, current.z, 0, 0.004, 0);
        trailFrom = current;
    }

    private Vec3 trailFrom;
    private double trailTravel;

    // =========================================================
    // ЗВУК
    // =========================================================

    /**
     * Удар о блок — сухой часовой tick (попадание по существу звук отыгрывает
     * сам спелл: обратный metallic/sand, а не generic impact).
     */
    @Override
    public Optional<Holder<SoundEvent>> getImpactSound() {
        if (entityImpact) {
            return Optional.empty();
        }
        return Optional.of(ChronoSounds.CLOCK_TICK);
    }

    @Override
    protected void doImpactSound(Holder<SoundEvent> sound) {
        // тихий «щиколоточный» вариант штатного удара (болт маленький)
        level().playSound(null, getX(), getY(), getZ(), sound, SoundSource.PLAYERS,
                0.7F, 1.5F + Utils.random.nextFloat() * 0.3F);
    }
}
