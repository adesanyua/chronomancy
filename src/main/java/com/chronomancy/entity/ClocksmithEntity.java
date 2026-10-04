package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.spell.RewindSpell;
import com.chronomancy.spell.TimeWalkSpell;
import com.chronomancy.temporal.PhaseDodge;
import com.chronomancy.temporal.borrowed.BorrowedFutureStats;
import com.chronomancy.util.ChronoSafeTeleport;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMob;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.NeutralWizard;
import io.redspace.ironsspellbooks.entity.mobs.goals.FocusOnTradingPlayerGoal;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardRecoverGoal;
import io.redspace.ironsspellbooks.entity.mobs.wizards.IMerchantWizard;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ЧАСОВЩИК — старый мастер хрономантии, живущий в часовой башне.
 *
 * <p>Устроен как нейтральные маги Iron's Spells ({@link NeutralWizard} + {@link IMerchantWizard}):
 * та же модель и поведение, только одет в броню Clocksmith. Пока его не тронули — торгует за
 * Зёрна времени: механизмы, часы, свитки хрономантии и — изредка — Ring of Rifts. Ударили первым —
 * дерётся и уже не отступает: непрерывно мечет иглы, бьёт с руки тех, кто подошёл вплотную, шагает
 * сквозь время к цели в стазисе и колдует — в первую очередь стазис, затем Backtrack, остальное
 * изредка (без The World Stop, Chrono Double и Rift), с силой магии 120%.
 * С убитого гарантированно падает Ring of Rifts (см. loot_table/entities/clocksmith.json).
 */
public class ClocksmithEntity extends NeutralWizard implements IMerchantWizard, ChronoMobCaster {

    /** Сила заклинаний Часовщика: 120% от обычной. */
    public static final double SPELL_POWER = 1.2D;
    public static final double MAX_HEALTH = 60.0D;
    /** Удар с руки по тому, кто подошёл вплотную. */
    public static final double MELEE_DAMAGE = 10.0D;
    private static final double MELEE_RANGE = 2.6D;
    private static final int MELEE_COOLDOWN = 20;
    /** К цели в стазисе он шагает сквозь время и бьёт её, пока та не может ответить. */
    private static final int STASIS_BLINK_COOLDOWN = 60;
    /**
     * Иглы — его основное оружие: летят одна за другой, своим счётом, не дожидаясь очереди остальных
     * заклинаний (пауза {@value #NEEDLE_INTERVAL_MIN}–{@value #NEEDLE_INTERVAL_MAX} тиков).
     */
    private static final int NEEDLE_INTERVAL_MIN = 28;
    private static final int NEEDLE_INTERVAL_MAX = 40;
    private static final double NEEDLE_RANGE = 24.0D;
    /** Передышка между стазисами: столько тиков цель должна пробыть на свободе, прежде чем он заморозит её снова. */
    private static final int STASIS_RECAST = 240;
    /** Шанс, что в ассортименте окажется Ring of Rifts. */
    public static final float RING_OFFER_CHANCE = 0.10F;
    /** Радиус, в котором он бродит вокруг своего рабочего места. */
    private static final int HOME_RADIUS = 7;

    private static final ResourceLocation TEMPO_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(com.chronomancy.ChronomancyMod.MODID, "clocksmith_tempo");
    /** История для своего Rewind: снимок раз в {@value #HISTORY_STEP} тика, на 6 секунд назад. */
    private static final int HISTORY_STEP = 2;
    private static final int HISTORY_TICKS = 120;
    private static final int BORROWED_TICKS = 200;

    private record Moment(int tick, Vec3 pos, float health) {
    }

    private final ArrayDeque<Moment> history = new ArrayDeque<>();
    private int meleeCooldown;
    private int stasisBlinkCooldown;
    private int needleCooldown;
    private int stasisCooldown;
    private int borrowedTicks;
    private double borrowedRate;
    private int debtTicks;

    @javax.annotation.Nullable
    private Player tradingPlayer;
    @javax.annotation.Nullable
    protected MerchantOffers offers;
    private long lastRestockGameTime;
    private int numberOfRestocksToday;
    private long lastRestockCheckDayTime;
    @javax.annotation.Nullable
    private BlockPos homePos;

    public ClocksmithEntity(EntityType<? extends AbstractSpellCastingMob> type, Level level) {
        super(type, level);
        this.xpReward = 30;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.ATTACK_DAMAGE, MELEE_DAMAGE)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0D)
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.FOLLOW_RANGE, 28.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D);
    }

    // =========================================================
    // AI
    // =========================================================

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FocusOnTradingPlayerGoal(this));
        this.goalSelector.addGoal(1, new FloatGoal(this));
        // Все заклинания школы, кроме The World Stop, Chrono Double и Rift (Rift — заклинание меча
        // Rift Creator, которого у него нет). Иглы он мечет сам, непрерывно (см. tickNeedles); здесь —
        // остальные заклинания, с паузами в полтора раза длиннее обычных для магов ISS (35–70 тиков).
        // Какое именно атакующее заклинание уйдёт в ход, решает pickAttackSpell.
        this.goalSelector.addGoal(2, new ClocksmithAttackGoal(this, 1.25D, 53, 105)
                .setSpells(
                        List.<AbstractSpell>of(
                                ChronoSpellRegistry.TEMPORAL_STASIS_BEAM_SPELL,
                                ChronoSpellRegistry.BACKTRACK_SPELL,
                                ChronoSpellRegistry.TIME_DILATION_FIELD_SPELL),
                        List.<AbstractSpell>of(ChronoSpellRegistry.REWIND_SPELL),
                        List.<AbstractSpell>of(ChronoSpellRegistry.TIME_WALK_SPELL),
                        List.<AbstractSpell>of(ChronoSpellRegistry.BORROWED_FUTURE_SPELL))
                .setSpellQuality(0.5F, 1.0F)
                .setAllowFleeing(false)); // не убегает от того, кто подошёл, — бьёт с руки
        this.goalSelector.addGoal(3, new MoveTowardsRestrictionGoal(this, 0.8D));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(10, new WizardRecoverGoal(this));

        // Нейтрален: нападает только на того, кто ударил первым.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                this::isHostileTowards));
        this.targetSelector.addGoal(5, new ResetUniversalAngerTargetGoal<>(this, false));
    }

    /** Один удар — и он уже враг (у магов ISS по умолчанию сначала предупреждение). */
    @Override
    public int getAngerThreshold() {
        return 1;
    }

    @Override
    public Optional<SoundEvent> getAngerSound() {
        return Optional.of(SoundEvents.WANDERING_TRADER_NO);
    }

    /** Интервал между заклинаниями зависит от «заёмного времени»: быстрее в разгоне, медленнее в долге. */
    float castDelayFactor() {
        return borrowedTicks > 0 ? 0.5F : debtTicks > 0 ? 1.6F : 1.0F;
    }

    static final class ClocksmithAttackGoal extends WizardAttackGoal {
        private final ClocksmithEntity clocksmith;

        ClocksmithAttackGoal(ClocksmithEntity mob, double speedModifier, int minInterval, int maxInterval) {
            super(mob, speedModifier, minInterval, maxInterval);
            this.clocksmith = mob;
        }

        /** Атаку выбирает сам Часовщик: стазис, как только тот готов, иначе иглы, Backtrack или поле. */
        @Override
        protected AbstractSpell getNextSpellType() {
            AbstractSpell pick = super.getNextSpellType();
            return attackSpells.contains(pick) ? clocksmith.pickAttackSpell() : pick;
        }

        /** Цель в стазисе — не кружит и не пятится, а подходит вплотную и остаётся рядом, чтобы бить. */
        @Override
        protected void doMovement(double distanceSquared) {
            if (target != null && target.hasEffect(com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
                mob.lookAt(target, 30.0F, 30.0F);
                if (distanceSquared > 2.0D * 2.0D) {
                    mob.getNavigation().moveTo(target, speedModifier);
                } else {
                    mob.getNavigation().stop();
                }
                return;
            }
            super.doMovement(distanceSquared);
        }

        // Rewind, Time Walk и Borrowed Future — реже: их категории весят втрое меньше обычного,
        // так что почти все касты достаются атаке (стазис, Backtrack).
        @Override
        protected int getDefenseWeight() {
            return super.getDefenseWeight() / 3;
        }

        @Override
        protected int getMovementWeight() {
            return super.getMovementWeight() / 3;
        }

        @Override
        protected int getSupportWeight() {
            return super.getSupportWeight() / 3;
        }

        @Override
        protected void resetSpellAttackTimer(double distanceSquared) {
            super.resetSpellAttackTimer(distanceSquared);
            this.spellAttackDelay = Math.max(1, Math.round(this.spellAttackDelay * clocksmith.castDelayFactor()));
        }
    }

    // =========================================================
    // Спавн / экипировка / дом
    // =========================================================

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @javax.annotation.Nullable SpawnGroupData spawnData) {
        populateDefaultEquipmentSlots(level.getRandom(), difficulty);
        setHome(blockPosition());
        setPersistenceRequired();
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData);
        tunePower();
        return data;
    }

    /** Одет в броню Clocksmith; с него она не падает (трофей — кольцо). */
    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        equip(EquipmentSlot.HEAD, ChronoItemRegistry.CLOCKSMITH_HELMET.get());
        equip(EquipmentSlot.CHEST, ChronoItemRegistry.CLOCKSMITH_CHESTPLATE.get());
        equip(EquipmentSlot.LEGS, ChronoItemRegistry.CLOCKSMITH_LEGGINGS.get());
        equip(EquipmentSlot.FEET, ChronoItemRegistry.CLOCKSMITH_BOOTS.get());
        equip(EquipmentSlot.MAINHAND, Items.CLOCK); // в руке — обычные часы
    }

    private void equip(EquipmentSlot slot, Item item) {
        setItemSlot(slot, new ItemStack(item));
        setDropChance(slot, 0.0F);
    }

    public void setHome(@javax.annotation.Nullable BlockPos pos) {
        this.homePos = pos;
        if (pos != null) {
            restrictTo(pos, HOME_RADIUS);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // =========================================================
    // Тик
    // =========================================================

    /**
     * Держит итоговую силу заклинаний ровно на {@value #SPELL_POWER}: Iron's Spells заново добавляет свой
     * атрибут всем сущностям с базой 1.0, а броня Clocksmith ещё и прибавляет к нему проценты —
     * подгоняем базу так, чтобы вместе с бронёй выходило ровно 120%. Заодно приводит к текущим
     * значениям Часовщиков, сохранённых в мире раньше (120 здоровья, Clock Hand в руке).
     */
    private void tunePower() {
        AttributeInstance health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null && health.getBaseValue() != MAX_HEALTH) {
            health.setBaseValue(MAX_HEALTH);
            if (getHealth() > getMaxHealth()) {
                setHealth(getMaxHealth());
            }
        }
        if (getMainHandItem().is(ChronoItemRegistry.CLOCK_HAND.get())) {
            equip(EquipmentSlot.MAINHAND, Items.CLOCK);
        }
        AttributeInstance melee = getAttribute(Attributes.ATTACK_DAMAGE);
        if (melee != null && melee.getBaseValue() != MELEE_DAMAGE) {
            melee.setBaseValue(MELEE_DAMAGE);
        }
        AttributeInstance power = getAttribute(AttributeRegistry.SPELL_POWER);
        if (power == null) {
            return;
        }
        double value = power.getValue();
        if (value > 1.0e-6 && Math.abs(value - SPELL_POWER) > 1.0e-3) {
            power.setBaseValue(power.getBaseValue() * SPELL_POWER / value);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (tickCount % 40 == 1) {
            tunePower();
        }
        tickMelee();
        tickNeedles();
        if (tickCount % HISTORY_STEP == 0) {
            history.addLast(new Moment(tickCount, position(), getHealth()));
            while (!history.isEmpty() && tickCount - history.peekFirst().tick() > HISTORY_TICKS) {
                history.removeFirst();
            }
        }
        if (borrowedTicks > 0) {
            if (--borrowedTicks == 0) {
                debtTicks = BORROWED_TICKS;
                applyTempo(-0.3D);
                level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.BORROWED_FUTURE_DEBT.get(),
                        SoundSource.HOSTILE, 0.8F, 1.0F);
            } else if (tickCount % 4 == 0 && level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(), getX(), getY() + 1.0, getZ(),
                        2, 0.3, 0.6, 0.3, 0.01);
            }
        } else if (debtTicks > 0 && --debtTicks == 0) {
            applyTempo(0.0D);
        }
        // В бою не торгует; разозлили во время сделки — лавка закрыта.
        if (tradingPlayer != null && (isAggressive() || getTarget() != null)) {
            stopTrading();
        }
    }

    /**
     * Ближний бой: кто подошёл вплотную — получает удар с руки ({@value #MELEE_DAMAGE} урона, раз в
     * секунду). Цель в стазисе он не ждёт: шагает к ней сквозь время и бьёт, пока та заморожена.
     */
    private void tickMelee() {
        if (meleeCooldown > 0) {
            meleeCooldown--;
        }
        if (stasisBlinkCooldown > 0) {
            stasisBlinkCooldown--;
        }
        LivingEntity target = getTarget();
        boolean frozen = target != null && target.isAlive()
                && target.hasEffect(com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_STASIS);
        // Отсчёт до следующего стазиса идёт, только пока цель на свободе.
        if (frozen) {
            stasisCooldown = STASIS_RECAST;
        } else if (stasisCooldown > 0) {
            stasisCooldown--;
        }
        if (target == null || !target.isAlive() || isCasting()) {
            return;
        }
        double distSqr = distanceToSqr(target);
        if (frozen && distSqr > MELEE_RANGE * MELEE_RANGE && stasisBlinkCooldown <= 0) {
            Vec3 toMe = position().subtract(target.position()).multiply(1, 0, 1);
            Vec3 side = toMe.lengthSqr() > 1.0e-4 ? toMe.normalize() : new Vec3(1, 0, 0);
            stasisBlinkCooldown = STASIS_BLINK_COOLDOWN;
            if (PhaseDodge.blink(this, target.position().add(side.scale(1.5D)))) {
                getLookControl().setLookAt(target, 60.0F, 60.0F);
                meleeCooldown = Math.min(meleeCooldown, 6); // короткий замах после шага
                distSqr = distanceToSqr(target);
            }
        }
        if (meleeCooldown <= 0 && distSqr <= MELEE_RANGE * MELEE_RANGE && hasLineOfSight(target)) {
            getLookControl().setLookAt(target, 60.0F, 60.0F);
            swing(InteractionHand.MAIN_HAND);
            doHurtTarget(target);
            meleeCooldown = MELEE_COOLDOWN;
        }
    }

    /**
     * Иглы — основное заклинание: пока цель видна, он мечет их постоянно, в промежутках между
     * остальными кастами и ударами.
     */
    private void tickNeedles() {
        if (needleCooldown > 0) {
            needleCooldown--;
            return;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive() || isCasting() || isDrinkingPotion()) {
            return;
        }
        if (distanceToSqr(target) > NEEDLE_RANGE * NEEDLE_RANGE || !hasLineOfSight(target)) {
            return;
        }
        AbstractSpell needle = ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL;
        int spellLevel = Math.max(1, (int) (needle.getMaxLevel() * Mth.lerp(getRandom().nextFloat(), 0.5F, 1.0F)));
        initiateCastSpell(needle, spellLevel);
        int pause = NEEDLE_INTERVAL_MIN + getRandom().nextInt(NEEDLE_INTERVAL_MAX - NEEDLE_INTERVAL_MIN + 1);
        needleCooldown = Math.max(1, Math.round(pause * castDelayFactor()));
    }

    /**
     * Очередное атакующее заклинание (помимо потока игл). Стазис — как только цель отгуляла передышку
     * после прошлого; в остальное время — ещё игла (половина случаев), Backtrack (треть) или поле
     * замедления (изредка). Так стазис выходит вторым по частоте после игл, Backtrack — третьим.
     */
    AbstractSpell pickAttackSpell() {
        LivingEntity target = getTarget();
        boolean frozen = target != null
                && target.hasEffect(com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_STASIS);
        if (stasisCooldown <= 0 && !frozen) {
            return ChronoSpellRegistry.TEMPORAL_STASIS_BEAM_SPELL;
        }
        float roll = getRandom().nextFloat();
        if (roll < 0.50F) {
            return ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL;
        }
        return roll < 0.83F ? ChronoSpellRegistry.BACKTRACK_SPELL : ChronoSpellRegistry.TIME_DILATION_FIELD_SPELL;
    }

    /** Взмах руки: как у магов ISS — клиент сам отсчитывает кадры взмаха для их модели. */
    @Override
    public void swing(InteractionHand hand) {
        if (level().isClientSide) {
            this.swingTime = 10;
        } else {
            super.swing(hand);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && this.swingTime > 0) {
            this.swingTime--;
        }
    }

    /** Множитель скорости «личного времени»: {@code > 0} — разгон, {@code < 0} — долг, 0 — снять. */
    private void applyTempo(double amount) {
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(TEMPO_SPEED_ID);
        if (amount != 0.0D) {
            speed.addTransientModifier(new AttributeModifier(TEMPO_SPEED_ID, amount,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    // =========================================================
    // Заклинания школы в исполнении моба (см. ChronoMobCaster)
    // =========================================================

    @Override
    public void mobRewind(int spellLevel) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double seconds = Math.min(RewindSpell.MAX_REWIND_SECONDS,
                RewindSpell.BASE_REWIND_SECONDS + RewindSpell.REWIND_SECONDS_PER_LEVEL * Math.max(0, spellLevel - 1));
        int wanted = tickCount - (int) Math.round(seconds * 20.0D);
        Moment past = null;
        for (Moment m : history) {
            if (m.tick() >= wanted) {
                past = m; // самый старый снимок не раньше нужного момента
                break;
            }
        }
        Vec3 from = position();
        serverLevel.playSound(null, from.x, from.y + 1, from.z, ChronoSounds.TEMPORAL_REWIND.get(), SoundSource.HOSTILE,
                1.0F, 0.9F);
        if (past == null) {
            return;
        }
        Vec3 safe = ChronoSafeTeleport.findSafePosition(serverLevel, past.pos());
        if (safe != null && safe.distanceToSqr(from) > 0.25D) {
            RewindAfterimageEntity afterimage =
                    new RewindAfterimageEntity(ChronoEntityTypeRegistry.REWIND_AFTERIMAGE.get(), serverLevel);
            afterimage.configure(from.x, from.y, from.z, getYRot(), getXRot());
            serverLevel.addFreshEntity(afterimage);
            getNavigation().stop();
            teleportTo(safe.x, safe.y, safe.z);
            setDeltaMovement(Vec3.ZERO);
            resetFallDistance();
            com.chronomancy.network.ChronoNetwork.broadcastPhaseStep(this,
                    com.chronomancy.network.PhaseStepPayload.BLINK, 5, List.of(from, safe));
        }
        // Откатывается и здоровье — но только в плюс: раны последних секунд «не случились».
        if (past.health() > getHealth()) {
            setHealth(Math.min(getMaxHealth(), past.health()));
        }
        clearFire();
        history.clear(); // отменённая линия времени больше недоступна
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 1, getZ(),
                14, 0.35, 0.6, 0.35, 0.0);
    }

    /** Темп заёмного времени, пока часовщик разогнан Borrowed Future; 0 — не разогнан. */
    public double borrowedFutureRate() {
        return borrowedTicks > 0 ? borrowedRate : 0.0D;
    }

    @Override
    public void mobBorrowedFuture(int spellLevel) {
        if (!(level() instanceof ServerLevel serverLevel) || borrowedTicks > 0) {
            return;
        }
        BorrowedFutureStats stats = BorrowedFutureStats.calculate(spellLevel, this);
        borrowedRate = stats.empoweredRate();
        borrowedTicks = BORROWED_TICKS;
        debtTicks = 0;
        // Разгон тела — по силе заклинания (x2.5 личного времени ≈ +50% скорости шага), долг — потом.
        applyTempo(Math.min(0.8D, (stats.empoweredRate() - 1.0D) / 3.0D));
        serverLevel.playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.BORROWED_FUTURE_BORROW.get(),
                SoundSource.HOSTILE, 1.0F, 1.0F);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 1.2, getZ(), 2, 0.3, 0.2, 0.3, 0);
    }

    @Override
    public void mobTimeWalk(int spellLevel) {
        if (!(level() instanceof ServerLevel)) {
            return;
        }
        double range = TimeWalkSpell.range(spellLevel, this);
        int phaseTicks = (int) Math.round(TimeWalkSpell.phaseSeconds(spellLevel, this) * 20.0D);
        LivingEntity target = getTarget();
        Vec3 dest;
        if (target != null) {
            Vec3 away = position().subtract(target.position()).multiply(1, 0, 1);
            double dist = away.length();
            Vec3 dir = dist > 1.0e-3 ? away.scale(1.0D / dist) : getLookAngle().multiply(-1, 0, -1).normalize();
            if (dist < 7.0D) {
                dest = position().add(dir.scale(Math.min(range, 10.0D)));          // прижали — шаг назад
            } else {
                dest = target.position().add(dir.scale(6.0D));                      // далеко — шаг к цели
                if (dest.distanceTo(position()) > range) {
                    dest = position().add(dest.subtract(position()).normalize().scale(range));
                }
            }
        } else {
            dest = position().add(getLookAngle().multiply(1, 0, 1).normalize().scale(Math.min(range, 6.0D)));
        }
        // Дома он не уходит дальше своей башни.
        if (homePos != null && dest.distanceToSqr(Vec3.atBottomCenterOf(homePos)) > 24.0D * 24.0D) {
            dest = position();
        }
        if (dest.distanceToSqr(position()) > 1.0D) {
            PhaseDodge.blink(this, dest);
        }
        PhaseDodge.phase(this, phaseTicks);
    }

    // =========================================================
    // Торговля
    // =========================================================

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean preventTrade = isAggressive() || getTarget() != null || isHostileTowards(player)
                || (!level().isClientSide && getOffers().isEmpty());
        if (hand == InteractionHand.MAIN_HAND && preventTrade && !level().isClientSide) {
            playSound(SoundEvents.WANDERING_TRADER_NO, 1.0F, getVoicePitch());
        }
        if (!preventTrade) {
            if (!level().isClientSide && !getOffers().isEmpty()) {
                if (shouldRestock()) {
                    restock();
                }
                startTrading(player);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    private void startTrading(Player player) {
        setTradingPlayer(player);
        getLookControl().setLookAt(player);
        openTradingScreen(player, getDisplayName(), 0);
    }

    @Override
    public MerchantOffers getOffers() {
        if (this.offers == null) {
            this.offers = new MerchantOffers();
            this.offers.addAll(createOffers());
        }
        return this.offers;
    }

    /** Товар по базовой цене, умноженной на {@code clocksmith.priceMultiplier} (не дороже стака Зёрен). */
    private static MerchantOffer sell(ItemStack result, int grains, int maxUses) {
        int price = (int) Math.round(grains * com.chronomancy.ChronoConfig.clocksmithPriceMultiplier());
        return sellFixed(result, Math.max(1, Math.min(64, price)), maxUses);
    }

    /** Товар по твёрдой цене: множитель из конфига на него не действует (Ring of Rifts). */
    private static MerchantOffer sellFixed(ItemStack result, int grains, int maxUses) {
        return new MerchantOffer(new ItemCost(ChronoItemRegistry.GRAIN_OF_TIME.get(), grains), result, maxUses, 5, 0.0F);
    }

    private static ItemStack scroll(AbstractSpell spell, int level) {
        ItemStack stack = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, level, stack);
        return stack;
    }

    /** Цена свитка в Зёрнах: чем выше редкость заклинания на этом уровне, тем дороже. */
    private static int scrollPrice(AbstractSpell spell, int level) {
        return 2 + spell.getRarity(level).getValue() * 2;
    }

    /**
     * Ассортимент: часы и материалы (всегда), три случайных механизма, три случайных свитка школы и,
     * если повезёт, Ring of Rifts. Формируется один раз и сохраняется вместе с мобом.
     */
    private List<MerchantOffer> createOffers() {
        RandomSource random = getRandom();
        List<MerchantOffer> list = new ArrayList<>();
        // часы и материалы
        list.add(sell(new ItemStack(Items.CLOCK, 4), 1, 6));
        list.add(sell(new ItemStack(ChronoItemRegistry.TIME_RUNE.get()), 2, 6));

        // механизмы
        List<MerchantOffer> mechanisms = new ArrayList<>(List.of(
                sell(new ItemStack(ChronoItemRegistry.CLOCKWORK_KEY.get()), 6, 1),
                sell(new ItemStack(ChronoItemRegistry.HOURGLASS_OF_FOCUS.get()), 6, 1),
                sell(new ItemStack(ChronoItemRegistry.CRACKED_DIAL.get()), 7, 1),
                sell(new ItemStack(ChronoItemRegistry.TEMPORAL_ANCHOR.get()), 7, 1),
                sell(new ItemStack(ChronoItemRegistry.DEFERRED_PENDULUM.get()), 8, 1),
                sell(new ItemStack(ChronoItemRegistry.TIMELESS_SEAL.get()), 8, 1),
                sell(new ItemStack(ChronoItemRegistry.SECOND_CHANCE_WATCH.get()), 10, 1)));
        Collections.shuffle(mechanisms, new java.util.Random(random.nextLong()));
        list.addAll(mechanisms.subList(0, 3));

        // свитки хрономантии (Rift — заклинание оружия, свитков у него нет)
        List<AbstractSpell> spells = new ArrayList<>(List.of(
                ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL, ChronoSpellRegistry.BACKTRACK_SPELL,
                ChronoSpellRegistry.TEMPORAL_STASIS_BEAM_SPELL, ChronoSpellRegistry.TIME_DILATION_FIELD_SPELL,
                ChronoSpellRegistry.REWIND_SPELL, ChronoSpellRegistry.BORROWED_FUTURE_SPELL,
                ChronoSpellRegistry.TIME_WALK_SPELL, ChronoSpellRegistry.SANDS_OF_TIME_SPELL,
                ChronoSpellRegistry.ACCELERATED_ZONE_SPELL));
        spells.removeIf(spell -> !spell.isEnabled());
        Collections.shuffle(spells, new java.util.Random(random.nextLong()));
        for (AbstractSpell spell : spells.subList(0, Math.min(3, spells.size()))) {
            int span = spell.getMaxLevel() - spell.getMinLevel() + 1;
            int level = spell.getMinLevel() + random.nextInt(Math.max(1, span));
            list.add(sell(scroll(spell, level), scrollPrice(spell, level), 2));
        }
        // изредка — один из легендарных свитков, которыми сам он в бою не пользуется
        if (random.nextFloat() < 0.15F) {
            AbstractSpell legendary = random.nextBoolean() ? ChronoSpellRegistry.THE_WORLD_STOP_SPELL
                    : ChronoSpellRegistry.CHRONO_DOUBLE_SPELL;
            if (legendary.isEnabled()) {
                list.add(sell(scroll(legendary, legendary.getMinLevel()), 24, 1));
            }
        }
        // редкая удача — кольцо разломов
        if (random.nextFloat() < RING_OFFER_CHANCE) {
            list.add(sellFixed(new ItemStack(ChronoItemRegistry.RING_OF_RIFTS.get()), 16, 1));
        }
        return list;
    }

    @Override
    public void overrideOffers(MerchantOffers offers) {
    }

    @Override
    public void setTradingPlayer(@javax.annotation.Nullable Player player) {
        this.tradingPlayer = player;
    }

    @javax.annotation.Nullable
    @Override
    public Player getTradingPlayer() {
        return this.tradingPlayer;
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        if (getTradingPlayer() instanceof net.minecraft.server.level.ServerPlayer buyer) {
            com.chronomancy.advancement.ChronoAdvancements.grant(buyer, com.chronomancy.advancement.ChronoAdvancements.CLOCKSMITH);
        }
        this.ambientSoundTime = -getAmbientSoundInterval();
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
        if (!level().isClientSide && this.ambientSoundTime > -getAmbientSoundInterval() + 20) {
            this.ambientSoundTime = -getAmbientSoundInterval();
            playSound(stack.isEmpty() ? SoundEvents.WANDERING_TRADER_NO : SoundEvents.WANDERING_TRADER_YES,
                    getSoundVolume(), getVoicePitch());
        }
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.WANDERING_TRADER_YES;
    }

    @Override
    public int getRestocksToday() {
        return numberOfRestocksToday;
    }

    @Override
    public void setRestocksToday(int restocks) {
        this.numberOfRestocksToday = restocks;
    }

    @Override
    public long getLastRestockGameTime() {
        return lastRestockGameTime;
    }

    @Override
    public void setLastRestockGameTime(long time) {
        this.lastRestockGameTime = time;
    }

    @Override
    public long getLastRestockCheckDayTime() {
        return lastRestockCheckDayTime;
    }

    @Override
    public void setLastRestockCheckDayTime(long time) {
        this.lastRestockCheckDayTime = time;
    }

    @Override
    public Level level() {
        return super.level();
    }

    // =========================================================
    // Звуки / сохранение
    // =========================================================

    @Override
    protected SoundEvent getAmbientSound() {
        return isAggressive() ? null : SoundEvents.WANDERING_TRADER_AMBIENT;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 260;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WANDERING_TRADER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WANDERING_TRADER_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.75F + random.nextFloat() * 0.1F; // старик: голос ниже, чем у торговца
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        serializeMerchant(tag, this.offers, this.lastRestockGameTime, this.numberOfRestocksToday);
        if (homePos != null) {
            tag.put("ClocksmithHome", NbtUtils.writeBlockPos(homePos));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        deserializeMerchant(tag, loaded -> this.offers = loaded);
        NbtUtils.readBlockPos(tag, "ClocksmithHome").ifPresent(this::setHome);
    }
}
