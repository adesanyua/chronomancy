package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Медный двойник игрока: копия внешности (скин) и оружия конкретного игрока, которая
 * охотится на {@link #getHuntTarget() свою цель} в ближнем бою этим оружием.
 *
 * <p>Кто призывает:
 * <ul>
 *   <li>босс Rift Maker — двойники ЕГО цели-игрока, атакуют этого игрока ({@code fromBoss}:
 *       живут вне остановленного времени, как и сам босс);</li>
 *   <li>заклинание Rift, наложенное на игрока — медная копия этого игрока атакует оригинал:
 *       вблизи бьёт его оружием, а отошедшего достаёт его же последним заклинанием — любой
 *       школы ({@link #setSpellAtRange}).</li>
 * </ul>
 *
 * <p>Двойники босса копируют и броню игрока; один из пары вместо рукопашной повторяет последнее
 * заклинание игрока ({@link #setSpell}, {@link EchoSpellCaster}).
 *
 * <p>Живёт ограниченное время, без добычи и опыта; если цель пропала — рассыпается.
 * Рендер — модель игрока со скином {@link #getSkinOwner() владельца} в медном цвете.
 */
public class PlayerEchoEntity extends Monster implements WorldStopExempt {

    private static final EntityDataAccessor<Optional<UUID>> DATA_SKIN_OWNER =
            SynchedEntityData.defineId(PlayerEchoEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> DATA_FROM_BOSS =
            SynchedEntityData.defineId(PlayerEchoEntity.class, EntityDataSerializers.BOOLEAN);


    private UUID huntTarget;
    private int lifetime = 600;
    /** Двойник-маг: повторяет заклинание игрока; {@code null} — обычный рукопашный двойник. */
    @javax.annotation.Nullable
    private EchoSpellCaster caster;
    /** Двойник заклинания Rift: в первую очередь рукопашник — колдует, только пока цель поодаль. */
    private boolean spellAtRange;
    /** С какого расстояния до цели двойник Rift берётся за заклинание вместо погони. */
    public static final double SPELL_FROM_DISTANCE = 5.0D;

    public PlayerEchoEntity(EntityType<? extends PlayerEchoEntity> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 0.0F);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.33D)
                .add(Attributes.FOLLOW_RANGE, 40.0D)
                .add(Attributes.ARMOR, 4.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN_OWNER, Optional.empty());
        builder.define(DATA_FROM_BOSS, false);
    }

    /**
     * Настройка ДО {@code addFreshEntity}: чей облик и оружие копируем, на кого охотимся,
     * сколько живём и призван ли боссом.
     */
    public void configure(Player skinOwner, LivingEntity target, int lifetimeTicks, boolean fromBoss) {
        this.entityData.set(DATA_SKIN_OWNER, Optional.of(skinOwner.getUUID()));
        this.entityData.set(DATA_FROM_BOSS, fromBoss);
        this.huntTarget = target.getUUID();
        this.lifetime = lifetimeTicks;
        ItemStack weapon = skinOwner.getMainHandItem().copy();
        weapon.setCount(1);
        this.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        this.setTarget(target);
        this.setCustomName(skinOwner.getName().copy().withStyle(net.minecraft.ChatFormatting.GOLD));
        this.setCustomNameVisible(false);
    }

    /** Копия брони игрока (все четыре слота): двойник так же защищён, как оригинал. Ничего не роняет. */
    public void copyArmor(Player owner) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack piece = owner.getItemBySlot(slot).copy();
            this.setItemSlot(slot, piece);
            this.setDropChance(slot, 0.0F);
        }
    }

    /** Двойник становится магом: держится поодаль и повторяет это заклинание на этом уровне. */
    public void setSpell(io.redspace.ironsspellbooks.api.spells.AbstractSpell spell, int spellLevel) {
        this.caster = new EchoSpellCaster(this, spell, spellLevel);
    }

    /**
     * Двойник остаётся рукопашником: преследует цель и бьёт оружием, но если она отошла дальше
     * {@value #SPELL_FROM_DISTANCE} блоков — останавливается и повторяет это заклинание.
     */
    public void setSpellAtRange(io.redspace.ironsspellbooks.api.spells.AbstractSpell spell, int spellLevel) {
        setSpell(spell, spellLevel);
        this.spellAtRange = true;
    }

    public boolean isSpellcaster() {
        return caster != null && !caster.broken();
    }

    public UUID getSkinOwner() {
        return this.entityData.get(DATA_SKIN_OWNER).orElse(null);
    }

    public boolean isFromBoss() {
        return this.entityData.get(DATA_FROM_BOSS);
    }

    public UUID getHuntTarget() {
        return huntTarget;
    }

    /** Двойники босса живут вне остановленного времени; копии из заклинания — нет. */
    @Override
    public boolean isOutsideTime() {
        return isFromBoss();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new EchoMeleeGoal(this));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (random.nextInt(3) == 0) {
                level().addParticle(ChronoParticleRegistry.TEMPORAL_COPPER.get(), getRandomX(0.6D), getRandomY(), getRandomZ(0.6D), 0, 0.01, 0);
            }
            return;
        }
        if (--lifetime <= 0) {
            collapse();
            return;
        }
        LivingEntity target = resolveTarget();
        if (target == null) {
            collapse();
            return;
        }
        if (getTarget() != target) {
            setTarget(target);
        }
    }

    private LivingEntity resolveTarget() {
        if (huntTarget == null || !(level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(huntTarget);
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()
                && !(living instanceof Player player && player.isCreative())) {
            return living;
        }
        return null;
    }

    /** Цель ушла/исчезла или время вышло — двойник рассыпается медной пылью. */
    public void collapse() {
        if (caster != null) {
            caster.abort();
        }
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_COPPER.get(), getX(), getY() + 1, getZ(), 24, 0.3, 0.6, 0.3, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_SPARK.get(), getX(), getY() + 1, getZ(),
                    6, 0.3, 0.5, 0.3, 0.02);
            level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.DOUBLE_ECHO.get(),
                    SoundSource.HOSTILE, 0.8F, 0.7F);
        }
        discard();
    }

    /**
     * Убит или убран посреди каста — заклинание обрывается (струя гаснет), а те, кого двойник успел
     * призвать чужим заклинанием, исчезают вместе с ним.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (caster != null && !level().isClientSide) {
            caster.abort();
            dismissSummons();
        }
        super.remove(reason);
    }

    /** Призванные заклинаниями Iron's Spells (скелеты, вексы, мечи…) не переживают двойника. */
    private void dismissSummons() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        try {
            for (UUID id : new java.util.ArrayList<>(
                    io.redspace.ironsspellbooks.capabilities.magic.SummonManager.getSummons(this))) {
                Entity summon = serverLevel.getEntity(id);
                if (summon instanceof io.redspace.ironsspellbooks.entity.mobs.IMagicSummon magicSummon) {
                    magicSummon.onUnSummon();
                } else if (summon != null) {
                    summon.discard();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            com.chronomancy.ChronomancyMod.LOGGER.debug("Chronomancy: could not dismiss the double's summons", e);
        }
    }

    /** Двойник держится своей цели: чужие цели (в т.ч. атакующий его игрок) игнорируются. */
    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && huntTarget != null && !huntTarget.equals(target.getUUID())) {
            return;
        }
        super.setTarget(target);
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        return other instanceof PlayerEchoEntity || other instanceof RiftMakerEntity
                || other instanceof ChronomalyEntity || super.isAlliedTo(other);
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.COPPER_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ChronoSounds.DOUBLE_ECHO.get();
    }

    // Двойник не переживает сохранение мира: он часть заклинания/боя, а не обычный моб.
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /**
     * Ближний бой оружием игрока. Без {@code MeleeAttackGoal}: его проверка готовности
     * завязана на {@code level.getGameTime()}, который стоит во время The World Stop.
     */
    static final class EchoMeleeGoal extends Goal {
        private final PlayerEchoEntity mob;
        private int attackCooldown;
        private int repath;

        EchoMeleeGoal(PlayerEchoEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = mob.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            attackCooldown = 10;
            repath = 0;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
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
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            EchoSpellCaster caster = mob.caster;
            boolean mage = caster != null && !caster.broken();
            if (mage) {
                boolean canSee = mob.getSensing().hasLineOfSight(target);
                boolean chaser = mob.spellAtRange;
                // Двойник Rift начинает каст, только пока цель поодаль; вблизи он дерётся.
                boolean mayCast = canSee && (!chaser
                        || mob.distanceToSqr(target) > SPELL_FROM_DISTANCE * SPELL_FROM_DISTANCE);
                if (caster.tick(target, mayCast)) {
                    return; // колдует: стоит и целится
                }
                // Между кастами: двойник босса держится поодаль (подошедшего вплотную бьёт, как
                // обычный двойник), двойник Rift — догоняет цель.
                double range = caster.preferredRange();
                if (chaser) {
                    if (--repath <= 0) {
                        repath = 8;
                        mob.getNavigation().moveTo(target, 1.15D);
                    }
                } else if (canSee && mob.distanceToSqr(target) <= range * range) {
                    if (!mob.isWithinMeleeAttackRange(target)) {
                        mob.getNavigation().stop();
                    }
                } else if (--repath <= 0) {
                    repath = 8;
                    mob.getNavigation().moveTo(target, 1.15D);
                }
            } else if (--repath <= 0) {
                repath = 8;
                mob.getNavigation().moveTo(target, 1.15D);
            }
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (attackCooldown <= 0 && mob.isWithinMeleeAttackRange(target)
                    && mob.getSensing().hasLineOfSight(target)) {
                mob.swing(InteractionHand.MAIN_HAND);
                mob.doHurtTarget(target);
                attackCooldown = 16;
            }
        }
    }
}
