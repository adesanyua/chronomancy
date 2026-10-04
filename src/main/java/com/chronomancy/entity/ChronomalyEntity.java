package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.chronomancy.registry.ChronoSpellRegistry;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;

/**
 * CHRONOMALY — временная аномалия, вырвавшаяся из Time Rift.
 *
 * <p>Не летает обычным способом, а ТЕЛЕПОРТИРУЕТСЯ: появляется рядом с целью (или на дистанции
 * заклинания), бьёт/колдует, какое-то время ждёт — и снова исчезает ({@link BlinkCombatGoal}).
 *
 * <p>Парящий латунно-бирюзовый механизм: треснувший циферблат-голова, светящееся ядро,
 * две стрелки-клинка вместо рук и хвост из осколков. Без pathfinding'а — поэтому не зависит от
 * {@code level.getGameTime()}, который заморожен во время The World Stop.
 *
 * <p>{@link WorldStopExempt}: тикает, двигается и атакует внутри остановленного времени,
 * а его удары (и удары по нему) не попадают в буфер урона мирового стопа. Пока стоп
 * активен, охотится только на кастера — остальные игроки заморожены и в бою не участвуют.
 *
 * <p>Кроме клинков применяет заклинания 1 уровня — Backtrack и Time-Piercing Needle
 * (не чаще раза в 7–9 с, у каждого свой кулдаун).
 *
 * <p>После возобновления времени продолжает бой как обычный монстр; если за
 * {@link #DECAY_TICKS} без цели так и не нашёл противника — рассыпается без добычи.
 */
public class ChronomalyEntity extends Monster implements GeoEntity, WorldStopExempt {

    /** Длина анимации появления из разлома (тики). */
    public static final int EMERGE_TICKS = 20;
    /** Без цели и вне стопа дольше этого — аномалия распадается (без лута). */
    private static final int DECAY_TICKS = 1200;
    /** Клиентский entity event: исчезновение перед телепортом (частицы + скрытие на время интерполяции). */
    private static final byte EVENT_BLINK = 88;
    /** Сколько клиентских тиков модель скрыта после телепорта (покрывает 3-тиковую интерполяцию). */
    private static final int BLINK_HIDE_TICKS = 4;

    private static final EntityDataAccessor<Boolean> DATA_EMERGING =
            SynchedEntityData.defineId(ChronomalyEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.chronomaly.idle");
    private static final RawAnimation MOVE = RawAnimation.begin().thenLoop("animation.chronomaly.move");
    private static final RawAnimation EMERGE = RawAnimation.begin().thenPlayAndHold("animation.chronomaly.emerge");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.chronomaly.death");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.chronomaly.attack");
    private static final RawAnimation CAST = RawAnimation.begin().thenPlay("animation.chronomaly.cast");


    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private boolean fromRift;
    private int emergeTicksLeft;
    private int idleTicks;

    // --- заклинания (уровень 1): Backtrack и Time-Piercing Needle ---
    /** Общая цепочка Time-Piercing Needle всех Chronomaly: их иглы складываются на цели в один стак. */
    public static final java.util.UUID NEEDLE_CHAIN_ID = java.util.UUID.fromString("5c1e7a2b-4d1f-4f0e-9b8a-c4f0d0a17e11");
        /** Уровень заклинаний Chronomaly. */
    public static final int SPELL_LEVEL = 1;
    /** Общая пауза после ЛЮБОГО заклинания (тики): 5 с. */
    private static final int SPELL_GLOBAL_COOLDOWN = 100;
    /** Time-Piercing Needle — не чаще раза в 7 с. */
    private static final int NEEDLE_COOLDOWN_TICKS = 45; // втрое чаще прежних 140: руна-прицел видна, увернуться можно
    /** Общая пауза после иглы — короткая, иначе общий кулдаун съел бы ускорение игл. */
    private static final int NEEDLE_GLOBAL_COOLDOWN = 30;
    /** Backtrack — не чаще раза в 10 с. */
    private static final int BACKTRACK_COOLDOWN_TICKS = 200;
    /**
     * Ожидание на месте после удара/заклинания перед следующим телепортом: 1.6 с. Вместе с замахом
     * (8 тиков) цикл «телепорт — удар» длится 40 тиков вместо прежних 28 — телепортов на 30% меньше.
     */
    static final int WAIT_TICKS = 32;

    private final MagicData magicData = new MagicData(true);
    private int spellCooldown = 80;
    private int backtrackCooldown = 120;
    private int needleCooldown = 60;
    /** Клиент: модель скрыта сразу после телепорта. */
    private int clientHiddenTicks;

    public ChronomalyEntity(EntityType<? extends ChronomalyEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.xpReward = 24;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 50.0D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.ARMOR, 8.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.6D)
                .add(Attributes.FLYING_SPEED, 0.9D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_EMERGING, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new BlinkCombatGoal(this));
        this.goalSelector.addGoal(6, new BlinkWanderGoal(this));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, ChronomalyEntity.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                ChronomalyEntity::isHuntableDuringStop));
    }

    /** Во время стопа в бою участвует только кастер: остальные игроки заморожены. */
    private static boolean isHuntableDuringStop(LivingEntity candidate) {
        return !GlobalTimeStopManager.isActive() || GlobalTimeStopManager.isCaster(candidate);
    }

    /** Аномалии — одна «команда»: не бьют и не цепляют снарядами друг друга. */
    @Override
    public boolean isAlliedTo(Entity other) {
        return ChronomalMob.is(other) || super.isAlliedTo(other);
    }

    // =========================================================
    // Появление из разлома
    // =========================================================

    /** Вызывается разломом ДО {@code addFreshEntity}. */
    public void markEmergingFromRift() {
        this.fromRift = true;
        this.emergeTicksLeft = EMERGE_TICKS;
        this.entityData.set(DATA_EMERGING, true);
    }

    public boolean isEmerging() {
        return this.entityData.get(DATA_EMERGING);
    }

    public boolean isFromRift() {
        return fromRift;
    }

    // =========================================================
    // Тик
    // =========================================================

    @Override
    public void tick() {
        super.tick();
        this.setNoGravity(true);
        if (this.level().isClientSide) {
            if (clientHiddenTicks > 0) clientHiddenTicks--;
            tickClientParticles();
        }
        // Не летает сама по себе — только телепортируется; гасим остаточный дрейф/отбрасывание.
        setDeltaMovement(getDeltaMovement().scale(0.6D));
    }

    /** Клиент: не рисовать модель, пока идёт интерполяция телепорта. */
    public boolean isHiddenForBlink() {
        return clientHiddenTicks > 0;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (spellCooldown > 0) spellCooldown--;
        if (backtrackCooldown > 0) backtrackCooldown--;
        if (needleCooldown > 0) needleCooldown--;

        if (emergeTicksLeft > 0 && --emergeTicksLeft == 0) {
            this.entityData.set(DATA_EMERGING, false);
        }

        LivingEntity target = getTarget();
        // Стоп активен — все, кроме кастера, вне боя (заморожены): бросаем такую цель.
        if (target != null && !isHuntableDuringStop(target)) {
            setTarget(null);
            target = null;
        }

        if (target == null && !GlobalTimeStopManager.isActive() && !isPersistenceRequired()) {
            if (++idleTicks >= DECAY_TICKS) {
                decay();
            }
        } else {
            idleTicks = 0;
        }
    }

    /** Аномалия без противника распадается во времени: частицы, звук, без добычи и опыта. */
    private void decay() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    getX(), getY() + getBbHeight() * 0.5, getZ(), 16, 0.3, 0.5, 0.3, 0.02);
            playSound(ChronoSounds.TIME_RIFT_CLOSE.get(), 0.6F, 1.4F);
        }
        this.discard();
    }

    private void tickClientParticles() {
        if (this.tickCount % 2 == 0) {
            double ox = (random.nextDouble() - 0.5) * getBbWidth();
            double oz = (random.nextDouble() - 0.5) * getBbWidth();
            double oy = random.nextDouble() * getBbHeight() * 0.6;
            level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX() + ox, getY() + oy, getZ() + oz, 0, -0.02, 0);
        }
        if (random.nextInt(12) == 0) {
            level().addParticle(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    getX(), getY() + getBbHeight() * 0.7, getZ(),
                    (random.nextDouble() - 0.5) * 0.05, 0.02, (random.nextDouble() - 0.5) * 0.05);
        }
        if (isEmerging() && this.tickCount % 3 == 0) {
            for (int i = 0; i < 4; i++) {
                level().addParticle(ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                        getX() + (random.nextDouble() - 0.5), getY() + random.nextDouble() * getBbHeight(),
                        getZ() + (random.nextDouble() - 0.5), 0, 0.03, 0);
            }
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_BLINK) {
            clientHiddenTicks = BLINK_HIDE_TICKS;
            for (int i = 0; i < 14; i++) {
                level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                        getX() + (random.nextDouble() - 0.5) * 0.8, getY() + random.nextDouble() * getBbHeight(),
                        getZ() + (random.nextDouble() - 0.5) * 0.8, 0, 0, 0);
            }
            level().addParticle(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                    getX(), getY() + getBbHeight() * 0.5, getZ(), 0, 0, 0);
            return;
        }
        super.handleEntityEvent(id);
    }

    // =========================================================
    // Бой
    // =========================================================

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            triggerAnim("attack", "attack");
            playSound(ChronoSounds.CHRONOMALY_ATTACK.get(), 0.9F, 0.9F + random.nextFloat() * 0.2F);
        }
        return hit;
    }

    // =========================================================
    // Заклинания
    // =========================================================

    /**
     * Готовое заклинание (или null). Общая пауза после любого каста + собственные кулдауны:
     * игла — приоритет (раз в ~2.5 с, пауза 1.5 с), Backtrack — не чаще раза в 10 с (пауза 5 с).
     */
    AbstractSpell readySpell() {
        if (spellCooldown > 0) {
            return null;
        }
        // Игла — главная атака: готова игла — колем ею, а не Backtrack'ом и не рукой.
        if (needleCooldown <= 0) {
            return ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL;
        }
        return backtrackCooldown <= 0 ? ChronoSpellRegistry.BACKTRACK_SPELL : null;
    }

    boolean needleReady() {
        return spellCooldown <= 0 && needleCooldown <= 0;
    }

    /** Разворачивается к цели и применяет заклинание 1 уровня (как ISS-моб, CastSource.MOB). */
    void castAt(AbstractSpell spell, LivingEntity target) {
        faceExactly(target);
        spell.onCast(level(), SPELL_LEVEL, this, CastSource.MOB, magicData);
        triggerAnim("attack", "cast");
        if (spell == ChronoSpellRegistry.BACKTRACK_SPELL) {
            spellCooldown = SPELL_GLOBAL_COOLDOWN;
            backtrackCooldown = BACKTRACK_COOLDOWN_TICKS + random.nextInt(20);
        } else {
            spellCooldown = NEEDLE_GLOBAL_COOLDOWN;
            needleCooldown = NEEDLE_COOLDOWN_TICKS + random.nextInt(10);
        }
    }

    /** Точный поворот головы/тела/взгляда на глаза цели: снаряды летят по getLookAngle(). */
    void faceExactly(LivingEntity target) {
        Vec3 from = getEyePosition();
        Vec3 to = target.getEyePosition().add(0, -0.2D, 0);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        setXRot(pitch);
        yHeadRot = yaw;
        yBodyRot = yaw;
    }

    // =========================================================
    // Телепорт
    // =========================================================

    /**
     * Ищет точку для телепорта на расстоянии {@code minDist..maxDist} от {@code center}: свободный
     * объём без жидкости, в пределах границы мира и в прямой видимости {@code lookAt}
     * (цели или самой аномалии). Высота — около уровня центра, немного выше/ниже при препятствии.
     */
    Vec3 findBlinkSpot(Vec3 center, double minDist, double maxDist, Vec3 lookAt) {
        double[] heights = {0.2D, 1.2D, -0.8D, 2.2D};
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            double x = center.x + Math.cos(angle) * dist;
            double z = center.z + Math.sin(angle) * dist;
            for (double dy : heights) {
                double y = center.y + dy;
                BlockPos pos = BlockPos.containing(x, y, z);
                if (!level().isLoaded(pos) || !level().getWorldBorder().isWithinBounds(pos)) {
                    continue;
                }
                AABB box = getDimensions(getPose()).makeBoundingBox(x, y, z);
                if (!level().noCollision(this, box) || level().containsAnyLiquid(box)) {
                    continue;
                }
                Vec3 eye = new Vec3(x, y + getEyeHeight(), z);
                if (level().clip(new ClipContext(eye, lookAt, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) {
                    continue;
                }
                return new Vec3(x, y, z);
            }
        }
        return null;
    }

    /** Клиентам: модель скрыть на время интерполяции телепорта (рывок уклонения и т.п.). */
    public void signalBlink() {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.broadcastEntityEvent(this, EVENT_BLINK);
        }
    }

    /** Мгновенный телепорт с «разрывом»: частицы и звук в точке ухода и прибытия. */
    void blinkTo(Vec3 dest) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.broadcastEntityEvent(this, EVENT_BLINK); // исчезновение в старой точке
        playSound(ChronoSounds.TEMPORAL_CRACK.get(), 0.6F, 1.6F);
        teleportTo(dest.x, dest.y, dest.z);
        setDeltaMovement(Vec3.ZERO);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), dest.x, dest.y + getBbHeight() * 0.5D, dest.z,
                14, 0.3D, 0.6D, 0.3D, 0.0D);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                dest.x, dest.y + getBbHeight() * 0.5D, dest.z, 1, 0, 0, 0, 0);
        level().playSound(null, dest.x, dest.y + 1, dest.z, ChronoSounds.TEMPORAL_CRACK.get(),
                SoundSource.HOSTILE, 0.6F, 1.9F);
    }

    // Летает: без урона от падения и без шагов.
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return ChronoSounds.CHRONOMALY_AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ChronoSounds.CHRONOMALY_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ChronoSounds.CHRONOMALY_DEATH.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 160;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }

    // =========================================================
    // NBT
    // =========================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("FromRift", fromRift);
        tag.putInt("DecayTicks", idleTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        fromRift = tag.getBoolean("FromRift");
        idleTicks = tag.getInt("DecayTicks");
    }

    // =========================================================
    // GeckoLib
    // =========================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            if (isDeadOrDying()) {
                return state.setAndContinue(DEATH);
            }
            if (isEmerging()) {
                return state.setAndContinue(EMERGE);
            }
            double dx = getX() - xo;
            double dz = getZ() - zo;
            return state.setAndContinue(dx * dx + dz * dz > 0.0016D ? MOVE : IDLE);
        }));
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", ATTACK)
                .triggerableAnim("cast", CAST));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // =========================================================
    // AI: атака → ожидание → телепорт
    // =========================================================

    /**
     * Бой без обычного перемещения. Цикл: телепорт к цели (вплотную для удара или на 5–9 блоков
     * для заклинания) → короткий замах → удар / заклинание → ожидание 1 с → следующий
     * телепорт. Во время ожидания стоит на месте и смотрит на цель.
     */
    static final class BlinkCombatGoal extends Goal {
        private enum Stage { WAIT, WINDUP }

        private static final int MELEE_WINDUP = 8;
        private static final int SPELL_WINDUP = 12;

        private final ChronomalyEntity mob;
        private Stage stage = Stage.WAIT;
        private int timer;
        private AbstractSpell pendingSpell;

        BlinkCombatGoal(ChronomalyEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = mob.getTarget();
            return target != null && target.isAlive() && !mob.isEmerging();
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = mob.getTarget();
            return target != null && target.isAlive() && mob.distanceToSqr(target) < 64 * 64;
        }

        @Override
        public void start() {
            stage = Stage.WAIT;
            timer = WAIT_TICKS;
            pendingSpell = null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = mob.getTarget();
            if (target == null) {
                return;
            }
            mob.getLookControl().setLookAt(target, 60.0F, 60.0F);
            if (--timer > 0) {
                return;
            }
            if (stage == Stage.WAIT) {
                beginAction(target);
            } else {
                performAction(target);
            }
        }

        /** Конец ожидания: выбираем действие и телепортируемся на нужную дистанцию. */
        private void beginAction(LivingEntity target) {
            // Готовая игла — всегда; Backtrack — иногда; в остальное время — удар рукой.
            AbstractSpell spell = mob.needleReady() || mob.getRandom().nextFloat() < 0.4F ? mob.readySpell() : null;
            Vec3 targetEye = target.getEyePosition();
            Vec3 spot = spell != null
                    ? mob.findBlinkSpot(target.position(), 5.0D, 9.0D, targetEye)
                    : mob.findBlinkSpot(target.position(), 1.3D, 2.3D, targetEye);
            if (spot == null && spell != null) {
                spell = null; // для каста места нет — пробуем вплотную
                spot = mob.findBlinkSpot(target.position(), 1.3D, 2.3D, targetEye);
            }
            if (spot == null && !mob.isWithinMeleeAttackRange(target)) {
                timer = 10; // негде появиться — попробуем чуть позже
                return;
            }
            if (spot != null) {
                mob.blinkTo(spot);
            }
            mob.faceExactly(target);
            pendingSpell = spell;
            stage = Stage.WINDUP;
            timer = spell != null ? SPELL_WINDUP : MELEE_WINDUP;
        }

        /** Удар или заклинание после замаха, затем ожидание перед следующим телепортом. */
        private void performAction(LivingEntity target) {
            if (pendingSpell != null) {
                if (mob.getSensing().hasLineOfSight(target)) {
                    mob.castAt(pendingSpell, target);
                }
                timer = WAIT_TICKS;
            } else {
                if (mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target)) {
                    mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    mob.doHurtTarget(target);
                }
                timer = WAIT_TICKS;
            }
            pendingSpell = null;
            stage = Stage.WAIT;
        }
    }

    /** Без цели: время от времени «перескакивает» на несколько блоков в сторону. */
    static final class BlinkWanderGoal extends Goal {
        private final ChronomalyEntity mob;

        BlinkWanderGoal(ChronomalyEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getTarget() == null && !mob.isEmerging()
                    && mob.getRandom().nextInt(reducedTickDelay(120)) == 0;
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            Vec3 center = mob.position();
            Vec3 spot = mob.findBlinkSpot(center, 3.0D, 7.0D, mob.getEyePosition());
            if (spot != null) {
                mob.blinkTo(spot);
            }
        }
    }
}
