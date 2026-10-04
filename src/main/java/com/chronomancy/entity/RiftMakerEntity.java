package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import com.chronomancy.temporal.worldstop.WorldStopExempt;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * RIFT MAKER (Творец разломов) — босс испытания Time Rift: приходит после волны Chronomaly.
 *
 * <p>500 здоровья, 20 брони. Умения:
 * <ul>
 *   <li>ближний бой гигантской стрелкой-клинком;</li>
 *   <li>телепортируется к цели через разрыв (место назначения видно за полсекунды), если цель
 *       далеко, скрылась из виду или босс застрял — и изредка просто посреди боя;</li>
 *   <li>изредка (раз в 13–16 с, если цель не вплотную) бросает усиленный Time Dilation Field
 *       (радиус 6.5 блока, −80% темпа); вблизи предпочитает рубить;</li>
 *   <li>призывает медных двойников цели-игрока ({@link PlayerEchoEntity}) в копии его брони и с
 *       копией его оружия: один идёт врукопашную, второй повторяет последнее заклинание игрока
 *       (если игрок ничего не колдовал — тоже рубится). Не больше {@value #MAX_ECHOES} одновременно;</li>
 *   <li>пески времени: пять секунд стоит и сыплет перед собой конус песка — те же слои замедления и
 *       стазис в конце, что у заклинания Sands of Time ({@link com.chronomancy.temporal.SandsOfTime}).</li>
 * </ul>
 * Ниже 30% здоровья не чаще раза в 10 секунд перематывает себя на 3 секунды назад: возвращается и
 * место, и здоровье — урон последних трёх секунд «не случился». Свои приёмы перемоткой не сбивает
 * (ждёт конца замаха или канала) и приберегает её до большого урона.
 * Ниже половины здоровья умения перезаряжаются быстрее, и появляется удар о землю: замах обеими
 * руками (руны по кругу показывают зону) — и все игроки в 5 блоках от края босса в стазисе на 3 с.
 * Модель и хитбокс — вдвое больше обычного (2.2 × 6.4).
 *
 * <p>Живёт вне остановленного времени ({@link WorldStopExempt}); пока идёт стоп, охотится только
 * на кастера. Полоса босса видна всем, кто рядом.
 */
public class RiftMakerEntity extends Monster implements GeoEntity, WorldStopExempt {

    public static final int MAX_ECHOES = 4;
    private static final int ECHOES_PER_SUMMON = 2;
    private static final int ECHO_LIFETIME = 600;
    /** Поле босса: заметно больше и сильнее игрового (радиус 6.5 блока, −80% темпа, 10 с). */
    private static final double DILATION_RADIUS = 6.5D;
    private static final double DILATION_RATE = 0.20D;
    private static final int DILATION_LIFETIME = 200;
    /** Поле — редкий приём: раз в ~13–16 с и только когда цель не рядом (вблизи босс рубит). */
    private static final int DILATION_COOLDOWN = 260;
    private static final double DILATION_MIN_RANGE = 6.0D;
    /** Телепорт к цели: если она далеко/вне видимости или просто время от времени в бою. */
    private static final int TELEPORT_COOLDOWN = 90;
    private static final double TELEPORT_FAR = 9.0D;
    private static final int SUMMON_COOLDOWN = 440;
    private static final int DEATH_TICKS = 40;
    /** Ярость (< 50% здоровья): замах обеими руками и удар о землю, стазис 3 с всем игрокам рядом. */
    private static final int STASIS_SLAM_COOLDOWN = 180;
    /** Удар приходится на 1.15 с анимации «slam» (23 тика замаха). */
    private static final int STASIS_SLAM_WINDUP = 23;
    /** Радиус стазиса — 5 блоков от края босса. */
    private static final double STASIS_SLAM_REACH = 5.0D;
    private static final int STASIS_SLAM_TICKS = 60;
    /** Стазис разбивается, если по замершему игроку прошло столько урона (один удар босса). */
    private static final float STASIS_SLAM_DAMAGE_CAP = 20.0F;

    /** Пески времени: канал 5 с, конус до 9 блоков, начальный урон 12 в секунду. */
    private static final int SANDS_COOLDOWN = 360;
    /** Замах: секунда, за которую предупреждающая область уже видна, а песок ещё не идёт. */
    public static final int SANDS_WINDUP = 20;
    private static final int SANDS_CHANNEL = 100;
    public static final double SANDS_RANGE = 9.0D;
    private static final double SANDS_MIN_RANGE = 3.0D;
    /** Косинус половины угла конуса (~32°). */
    public static final double SANDS_CONE_COS = 0.85D;
    /** Что босс делает с песками прямо сейчас (синхронизируется: клиент рисует область и песок). */
    public static final int SANDS_STATE_NONE = 0, SANDS_STATE_WINDUP = 1, SANDS_STATE_CHANNEL = 2;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_SANDS_STATE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(RiftMakerEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    private static final net.minecraft.network.syncher.EntityDataAccessor<org.joml.Vector3f> DATA_SANDS_DIR =
            net.minecraft.network.syncher.SynchedEntityData.defineId(RiftMakerEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.VECTOR3);
    /** Сколько песчинок за тик клиент пускает вдоль струи. */
    private static final int SANDS_PARTICLES_PER_TICK = 40;

    /** Круги ударов по области (синхронизируются: клиент рисует их шейдером, см. BossGroundFxRendering). */
    public static final int FX_NONE = 0, FX_HEAVY_WINDUP = 1, FX_HEAVY_IMPACT = 2, FX_STASIS_WINDUP = 3, FX_STASIS_IMPACT = 4;
    /** Усиленный удар: замах 0.7 с (анимация «heavy» бьёт на 14-м тике), радиус — как у самого удара. */
    public static final int HEAVY_WINDUP = 14;
    public static final double HEAVY_RADIUS = 6.0D;
    private static final int HEAVY_IMPACT_TICKS = 12;
    private static final int STASIS_IMPACT_TICKS = 24;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_GROUND_FX =
            net.minecraft.network.syncher.SynchedEntityData.defineId(RiftMakerEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    /** Сервер: сколько тиков круг ещё показывается, если его раньше не сменит следующая фаза. */
    private int groundFxTicks;
    /** Босс фазирован — снаряды проходят насквозь (синхронизируется: клиент перекрашивает оболочку). */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_PHASED =
            net.minecraft.network.syncher.SynchedEntityData.defineId(RiftMakerEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    /** Клиент: насколько оболочка уже перекрашена (0..1) — быстро загорается, чуть медленнее гаснет. */
    private float immunityGlow;
    private float immunityGlowO;
    private static final float SANDS_DAMAGE_PER_SECOND = 12.0F;
    /** Градусов за тик: 40°/с — бегом вбок из струи можно выйти, шагом на дистанции уже нет. */
    private static final float SANDS_TURN_SPEED = 2.0F;
    /** Перемотка на исходе сил: ниже 30% здоровья, раз в 10 с, на 3 с назад. */
    private static final float REWIND_HEALTH_FRACTION = 0.30F;
    private static final int REWIND_COOLDOWN = 200;
    private static final int REWIND_TICKS = 60;
    /** «Большой урон»: столько здоровья (доля от полного) потеряно за окно перемотки — откат сразу. */
    private static final float REWIND_BIG_DAMAGE = 0.08F;
    /** Сколько тиков готовая перемотка ждёт большого урона, прежде чем сработать и на малом. */
    private static final int REWIND_PATIENCE = 60;
    /** До какого тика босс считается занятым приёмом (замах, канал) — перемотка его не прерывает. */
    private int busyUntil;
    private int rewindWaited;

    private record Moment(int tick, Vec3 pos, float health) {
    }

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.rift_maker.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.rift_maker.walk");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.rift_maker.death");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.rift_maker.attack");
    private static final RawAnimation CAST = RawAnimation.begin().thenPlay("animation.rift_maker.cast");
    private static final RawAnimation SUMMON = RawAnimation.begin().thenPlay("animation.rift_maker.summon");
    private static final RawAnimation SLAM = RawAnimation.begin().thenPlay("animation.rift_maker.slam");
    /** Пески времени: левая рука вытянута к цели на весь замах и канал. */
    private static final RawAnimation SANDS = RawAnimation.begin().thenPlay("animation.rift_maker.sands");
    /** Усиленный удар: клинок двумя руками над головой — и в землю. */
    private static final RawAnimation HEAVY = RawAnimation.begin().thenPlay("animation.rift_maker.heavy");
    /** Удар стазиса: руки вверх, «стой!» — и застывшая поза. */
    private static final RawAnimation STASIS = RawAnimation.begin().thenPlay("animation.rift_maker.stasis");


    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            Component.translatable("entity.chronomancy.rift_maker"),
            BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10).setDarkenScreen(true);
    private final MagicData magicData = new MagicData(true);
    private final List<UUID> echoes = new ArrayList<>();
    /** Босс испытания Time Rift: живёт только пока длится остановка времени, не сохраняется. */
    private boolean trialBoss;

    private int dilationCooldown = 160;
    private int summonCooldown = 160;
    private int teleportCooldown = 40;
    private int stasisSlamCooldown = 40;
    private int sandsCooldown = 240;
    private int rewindCooldown;
    /** Снимки места и здоровья за последние секунды — для собственной перемотки. */
    private final java.util.ArrayDeque<Moment> history = new java.util.ArrayDeque<>();
    /** Сколько тиков цель недосягаема для навигации (застрял, стена, обрыв). */
    private int stuckTicks;
    /** Каждый третий удар — удар о землю по кругу, остальные — широкий взмах перед собой. */
    private int swingCount;

    public RiftMakerEntity(EntityType<? extends RiftMakerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 250;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 500.0D)
                .add(Attributes.ARMOR, 20.0D)
                .add(Attributes.ARMOR_TOUGHNESS, 4.0D)
                .add(Attributes.ATTACK_DAMAGE, 20.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.351D) // 130% от прежних 0.27
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.9D)
                .add(Attributes.STEP_HEIGHT, 1.5D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new AbilityGoal(this));
        this.goalSelector.addGoal(2, new BossMeleeGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, PlayerEchoEntity.class, ChronomalyEntity.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                p -> !GlobalTimeStopManager.isActive() || GlobalTimeStopManager.isCaster(p)));
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        return other instanceof PlayerEchoEntity || ChronomalMob.is(other) || super.isAlliedTo(other);
    }

    // =========================================================
    // Тик / полоса босса
    // =========================================================

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (arriving > 0) {
            tickArrival();
            return;
        }
        boolean enraged = getHealth() < getMaxHealth() * 0.5F;
        int step = enraged ? 2 : 1;
        if (dilationCooldown > 0) dilationCooldown -= step;
        if (summonCooldown > 0) summonCooldown -= step;
        if (teleportCooldown > 0) teleportCooldown -= step;
        if (stasisSlamCooldown > 0) stasisSlamCooldown--;
        if (sandsCooldown > 0) sandsCooldown -= step;
        tickRewind();

        LivingEntity target = getTarget();
        if (target != null && GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(target)) {
            setTarget(null);
        }
        echoes.removeIf(id -> !(((ServerLevel) level()).getEntity(id) instanceof PlayerEchoEntity echo) || !echo.isAlive());
        bossEvent.setProgress(getHealth() / getMaxHealth());
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SANDS_STATE, (byte) SANDS_STATE_NONE);
        builder.define(DATA_SANDS_DIR, new org.joml.Vector3f(0.0F, 0.0F, 1.0F));
        builder.define(DATA_GROUND_FX, (byte) FX_NONE);
        builder.define(DATA_PHASED, false);
    }

    /** Сейчас снаряды проходят сквозь босса (он ушёл от выстрела шагом сквозь время). */
    public boolean isProjectileImmune() {
        return this.entityData.get(DATA_PHASED);
    }

    /** Клиент: 0..1 — сила «неуязвимой» окраски оболочки. */
    public float projectileImmunity(float partialTick) {
        return net.minecraft.util.Mth.lerp(partialTick, immunityGlowO, immunityGlow);
    }

    /** Какой круг удара сейчас показан ({@link #FX_NONE}, если никакого). */
    public int groundFx() {
        return this.entityData.get(DATA_GROUND_FX);
    }

    /** Сколько тиков длится фаза круга: по ней клиент считает её ход от 0 до 1. */
    public static float groundFxDuration(int fx) {
        return switch (fx) {
            case FX_HEAVY_WINDUP -> HEAVY_WINDUP;
            case FX_HEAVY_IMPACT -> HEAVY_IMPACT_TICKS;
            case FX_STASIS_WINDUP -> STASIS_SLAM_WINDUP;
            case FX_STASIS_IMPACT -> STASIS_IMPACT_TICKS;
            default -> 1.0F;
        };
    }

    /** Радиус удара стазиса от центра босса. */
    public double stasisSlamRadius() {
        return getBbWidth() * 0.5D + STASIS_SLAM_REACH;
    }

    void showGroundFx(int fx) {
        this.entityData.set(DATA_GROUND_FX, (byte) fx);
        // замах сменится ударом сам; запас в три тика — на случай, если замах сорвётся
        this.groundFxTicks = (int) groundFxDuration(fx) + (fx == FX_HEAVY_WINDUP || fx == FX_STASIS_WINDUP ? 3 : 0);
    }

    void clearGroundFx() {
        groundFxTicks = 0;
        if (groundFx() != FX_NONE) {
            this.entityData.set(DATA_GROUND_FX, (byte) FX_NONE);
        }
    }

    /** Следующий взмах — усиленный (каждый третий): решается до замаха, чтобы показать анимацию и круг. */
    boolean nextSwingIsHeavy() {
        return (swingCount + 1) % 3 == 0;
    }

    private int groundFxClient;
    private int groundFxAgeClient;

    /** Клиент: сколько тиков длится текущая фаза круга. */
    public float groundFxAge(float partialTick) {
        return Math.max(0.0F, groundFxAgeClient - 1 + partialTick);
    }

    private void tickGroundFxClient() {
        int fx = groundFx();
        if (fx != groundFxClient) {
            groundFxAgeClient = 0;
        }
        groundFxClient = fx;
        if (fx != FX_NONE) {
            groundFxAgeClient++;
        }
    }

    /** {@link #SANDS_STATE_NONE}, замах или канал. */
    public int sandsState() {
        return this.entityData.get(DATA_SANDS_STATE);
    }

    /** Сервер: показать клиентам область песков — замах или сам поток — в текущем направлении. */
    void showSands(int state, @javax.annotation.Nullable LivingEntity target) {
        Vec3 dir = sandsDirection(target);
        this.entityData.set(DATA_SANDS_DIR, new org.joml.Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
        this.entityData.set(DATA_SANDS_STATE, (byte) state);
    }

    void clearSands() {
        if (sandsState() != SANDS_STATE_NONE) {
            this.entityData.set(DATA_SANDS_STATE, (byte) SANDS_STATE_NONE);
        }
    }

    // --- клиент: сглаженное направление струи и возраст текущей фазы (для рендера) ---
    private Vec3 sandsDirClient;
    private Vec3 sandsDirClientO;
    private int sandsStateClient;
    private int sandsAgeClient;

    /** Клиент: направление струи между тиками; {@code null}, пока босс пески не наводит. */
    @javax.annotation.Nullable
    public Vec3 sandsDir(float partialTick) {
        if (sandsDirClient == null || sandsDirClientO == null) {
            return null;
        }
        Vec3 dir = sandsDirClientO.lerp(sandsDirClient, partialTick);
        return dir.lengthSqr() < 1.0e-6 ? sandsDirClient : dir.normalize();
    }

    /** Клиент: сколько тиков длится текущая фаза (замах или канал). */
    public float sandsAge(float partialTick) {
        return Math.max(0.0F, sandsAgeClient - 1 + partialTick);
    }

    private void tickSandsClient() {
        int state = sandsState();
        if (state == SANDS_STATE_NONE) {
            sandsDirClient = null;
            sandsDirClientO = null;
            sandsStateClient = state;
            sandsAgeClient = 0;
            return;
        }
        org.joml.Vector3f synced = this.entityData.get(DATA_SANDS_DIR);
        Vec3 now = new Vec3(synced.x(), synced.y(), synced.z());
        sandsDirClientO = sandsDirClient == null ? now : sandsDirClient;
        sandsDirClient = now;
        if (state != sandsStateClient) {
            sandsAgeClient = 0;
        }
        sandsStateClient = state;
        sandsAgeClient++;
        if (state != SANDS_STATE_CHANNEL) {
            return;
        }
        // сам песок: плотный поток из вытянутой левой руки по всему конусу. Удар считается от груди
        // босса, поэтому каждая песчинка летит из ладони в «свою» точку настоящего конуса.
        Vec3 origin = sandsOrigin();
        Vec3 forward = Vec3.directionFromRotation(0.0F, yBodyRot);
        Vec3 hand = position().add(forward.scale(1.7D)).add(forward.z * 1.1D, getBbHeight() * 0.54D, -forward.x * 1.1D);
        Vec3 side = Math.abs(now.y) > 0.98D ? new Vec3(1.0D, 0.0D, 0.0D) : now.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = side.cross(now).normalize();
        double tan = Math.sqrt(1.0D - SANDS_CONE_COS * SANDS_CONE_COS) / SANDS_CONE_COS;
        for (int i = 0; i < SANDS_PARTICLES_PER_TICK; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double spread = Math.sqrt(random.nextDouble()) * tan;
            Vec3 ray = now.add(side.scale(Math.cos(angle) * spread)).add(up.scale(Math.sin(angle) * spread)).normalize();
            Vec3 flight = origin.add(ray.scale(SANDS_RANGE)).subtract(hand).normalize();
            double start = 0.3D + random.nextDouble() * 2.5D;
            double speed = 0.35D + random.nextDouble() * 0.45D;
            level().addAlwaysVisibleParticle(random.nextFloat() < 0.85F ? ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get()
                            : ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    hand.x + flight.x * start, hand.y + flight.y * start, hand.z + flight.z * start,
                    flight.x * speed, flight.y * speed, flight.z * speed);
        }
    }

    /** Область песков тянется далеко от самого босса: без этого она пропадала бы, стоит ему уйти из кадра. */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        net.minecraft.world.phys.AABB box = super.getBoundingBoxForCulling();
        return sandsState() != SANDS_STATE_NONE ? box.inflate(SANDS_RANGE + 1.0D)
                : groundFx() != FX_NONE ? box.inflate(HEAVY_RADIUS + 1.5D) : box;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && groundFxTicks > 0 && --groundFxTicks == 0) {
            clearGroundFx();
        }
        if (!level().isClientSide) {
            boolean phased = com.chronomancy.temporal.PhaseDodge.isPhased(this);
            if (phased != isProjectileImmune()) {
                this.entityData.set(DATA_PHASED, phased);
            }
        }
        if (level().isClientSide) {
            immunityGlowO = immunityGlow;
            immunityGlow = net.minecraft.util.Mth.clamp(immunityGlow + (isProjectileImmune() ? 0.34F : -0.125F), 0.0F, 1.0F);
            tickSandsClient();
            tickGroundFxClient();
            for (int i = random.nextInt(2); i < 2; i++) {
                level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getRandomX(0.8D), getY() + random.nextDouble() * getBbHeight(),
                        getRandomZ(0.8D), 0, 0.02, 0);
            }
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    protected void tickDeath() {
        ++this.deathTime;
        if (level() instanceof ServerLevel serverLevel && deathTime % 4 == 0) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX(), getY() + 3.0, getZ(),
                    5, 1.2, 2.0, 1.2, 0.05);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 3.0, getZ(), 24, 1.4, 2.4, 1.4, 0.0);
        }
        if (deathTime >= DEATH_TICKS && !level().isClientSide && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        clearSands();
        clearGroundFx();
        if (level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Победа засчитывается всем, кто дрался рядом: добить босса может и парадокс, и стазис.
            com.chronomancy.advancement.ChronoAdvancements.grant(source.getEntity(),
                    com.chronomancy.advancement.ChronoAdvancements.RIFT_MAKER);
            for (net.minecraft.server.level.ServerPlayer near : serverLevel.getPlayers(
                    p -> p.isAlive() && !p.isSpectator() && p.distanceToSqr(this) <= 48.0D * 48.0D)) {
                com.chronomancy.advancement.ChronoAdvancements.grant(near,
                        com.chronomancy.advancement.ChronoAdvancements.RIFT_MAKER);
            }
        }
        collapseEchoes();
        bossEvent.removeAllPlayers();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (!level().isClientSide) {
            bossEvent.removeAllPlayers();
        }
    }

    // =========================================================
    // Появление: падение из врат над ареной
    // =========================================================

    /** Сколько тиков босс ещё «прибывает» (падает из врат); пока больше нуля — не дерётся. */
    private int arriving;
    private static final int ARRIVAL_MAX_TICKS = 100;

    /** Босс выпал из врат в воздухе: до приземления он не атакует и не колдует. */
    public void beginArrival() {
        this.arriving = ARRIVAL_MAX_TICKS;
        this.fallDistance = 0.0F;
    }

    public boolean isArriving() {
        return arriving > 0;
    }

    private void tickArrival() {
        arriving--;
        getNavigation().stop();
        if (level() instanceof ServerLevel serverLevel && tickCount % 2 == 0) {
            // шлейф падения: время рвётся вокруг тела
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 3.0, getZ(),
                    6, 0.9, 2.2, 0.9, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX(), getY() + 5.5, getZ(),
                    1, 0.8, 0.6, 0.8, 0.02);
        }
        if (onGround() || arriving <= 0) {
            arriving = 0;
            land();
        }
    }

    /** Приземление: удар о землю, кольцо осколков времени — и бой начинается. */
    private void land() {
        triggerAnim("action", "slam");
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int i = 0; i < 28; i++) {
            double angle = Math.PI * 2.0 * i / 28.0;
            double x = getX() + Math.cos(angle) * 2.6;
            double z = getZ() + Math.sin(angle) * 2.6;
            serverLevel.sendParticles(i % 2 == 0 ? ChronoParticleRegistry.TEMPORAL_CRACK.get()
                            : ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(),
                    x, getY() + 0.2, z, 2, 0.15, 0.1, 0.15, 0.08);
        }
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 1.0, getZ(),
                60, 1.6, 0.8, 1.6, 0.0);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 0.3, getZ(),
                4, 1.2, 0.0, 1.2, 0.0);
        level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.GENERIC_BIG_FALL,
                SoundSource.HOSTILE, 3.0F, 0.5F);
        level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.TEMPORAL_CRACK.get(),
                SoundSource.HOSTILE, 2.5F, 0.6F);
        level().playSound(null, getX(), getY() + 4, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                SoundSource.HOSTILE, 2.0F, 0.6F);
    }

    /** Помечает босса как часть испытания разлома (до {@code addFreshEntity}). */
    public void markTrialBoss() {
        this.trialBoss = true;
    }

    /**
     * Испытание сорвано (кастер The World Stop погиб, вышел, сменил измерение и т.п.):
     * босс уходит обратно в разлом — без добычи, опыта и анимации смерти; двойники рассыпаются.
     */
    public void vanish() {
        if (isRemoved() || isDeadOrDying()) {
            return;
        }
        collapseEchoes();
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX(), getY() + 3.0, getZ(),
                    16, 1.2, 2.0, 1.2, 0.05);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 3.0, getZ(), 70, 1.4, 2.4, 1.4, 0.0);
            serverLevel.playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                    net.minecraft.sounds.SoundSource.HOSTILE, 1.5F, 1.4F);
        }
        bossEvent.removeAllPlayers();
        discard();
    }

    // Босс испытания не переживает сохранение/выгрузку: без остановки времени ему не место в мире.
    @Override
    public boolean shouldBeSaved() {
        return !trialBoss && super.shouldBeSaved();
    }

    private void collapseEchoes() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (UUID id : echoes) {
            if (serverLevel.getEntity(id) instanceof PlayerEchoEntity echo) {
                echo.collapse();
            }
        }
        echoes.clear();
    }

    // =========================================================
    // Умения
    // =========================================================

    boolean dilationReady(LivingEntity target) {
        double d = distanceToSqr(target);
        return dilationCooldown <= 0 && d > DILATION_MIN_RANGE * DILATION_MIN_RANGE && d < 28 * 28;
    }

    /**
     * Пора ли телепортироваться к цели: она дальше {@value #TELEPORT_FAR} блоков, вне прямой
     * видимости или навигация к ней не продвигается; изредка — просто «рывок» посреди боя.
     */
    boolean teleportReady(LivingEntity target) {
        if (teleportCooldown > 0) {
            return false;
        }
        double distSqr = distanceToSqr(target);
        if (distSqr < 3.0D * 3.0D) {
            return false;
        }
        return distSqr > TELEPORT_FAR * TELEPORT_FAR
                || !getSensing().hasLineOfSight(target)
                || stuckTicks > 30
                || random.nextInt(160) == 0;
    }

    /** Точка рядом с целью (2–3.5 блока, на твёрдом полу), куда откроется разрыв. */
    Vec3 findTeleportSpot(LivingEntity target) {
        EntityType<?> type = getType();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 2.0D + random.nextDouble() * 1.5D;
            double x = target.getX() + Math.cos(angle) * dist;
            double z = target.getZ() + Math.sin(angle) * dist;
            for (int dy = 2; dy >= -3; dy--) {
                double y = Math.floor(target.getY()) + dy;
                BlockPos below = BlockPos.containing(x, y - 1, z);
                AABB box = type.getSpawnAABB(x, y, z);
                if (level().getBlockState(below).isFaceSturdy(level(), below, net.minecraft.core.Direction.UP)
                        && level().noCollision(this, box) && !level().containsAnyLiquid(box)) {
                    return new Vec3(x, y, z);
                }
            }
        }
        return null;
    }

    /** Разрыв на месте назначения во время замаха: игрок видит, куда шагнёт босс. */
    void telegraphTeleport(Vec3 spot) {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), spot.x, spot.y + 3.0, spot.z, 10, 0.8, 2.0, 0.8, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), spot.x, spot.y + 3.0, spot.z,
                    1, 0.6, 1.2, 0.6, 0.0);
        }
    }

    void blinkTo(Vec3 spot, LivingEntity target) {
        teleportCooldown = TELEPORT_COOLDOWN + random.nextInt(50);
        stuckTicks = 0;
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), getX(), getY() + 3.0, getZ(), 50, 1.2, 2.4, 1.2, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX(), getY() + 3.0, getZ(),
                    6, 1.0, 2.0, 1.0, 0.05);
        }
        level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                SoundSource.HOSTILE, 1.2F, 1.6F);
        getNavigation().stop();
        teleportTo(spot.x, spot.y, spot.z);
        setDeltaMovement(Vec3.ZERO);
        faceExactly(target);
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), spot.x, spot.y + 3.0, spot.z, 50, 1.2, 2.4, 1.2, 0.0);
        }
        level().playSound(null, spot.x, spot.y + 1, spot.z, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0F, 0.6F);
    }

    /** Навигация ведёт к цели, но босс почти не двигается — считаем «застрял». */
    void trackStuck(LivingEntity target) {
        double moved = (getX() - xo) * (getX() - xo) + (getZ() - zo) * (getZ() - zo);
        if (distanceToSqr(target) > 4.0D * 4.0D && moved < 0.0025D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
    }

    // =========================================================
    // Перемотка на исходе сил
    // =========================================================

    private void tickRewind() {
        if (tickCount % 2 == 0) {
            history.addLast(new Moment(tickCount, position(), getHealth()));
            while (!history.isEmpty() && tickCount - history.peekFirst().tick() > REWIND_TICKS + 10) {
                history.removeFirst();
            }
        }
        if (rewindCooldown > 0) {
            rewindCooldown--;
        }
        if (rewindCooldown > 0 || isDeadOrDying() || getHealth() >= getMaxHealth() * REWIND_HEALTH_FRACTION) {
            rewindWaited = 0;
            return;
        }
        // Перемотка готова. Приём она не сбивает: пока идёт замах или канал — ждёт, и срок ожидания
        // при этом не тратится.
        if (isPreparingAttack()) {
            return;
        }
        Moment past = rewindPoint();
        float regained = past == null ? 0.0F : past.health() - getHealth();
        rewindWaited++;
        // Сразу — после большого урона (его и стоит отменять); иначе выжидает, не прилетит ли он, и
        // лишь потом откатывает то немногое, что потеряно. Если терять нечего — перемотку бережёт.
        if (regained >= getMaxHealth() * REWIND_BIG_DAMAGE || (rewindWaited >= REWIND_PATIENCE && regained > 0.0F)) {
            rewindWaited = 0;
            selfRewind();
        }
    }

    /** Цели вызывают это каждый тик замаха или канала: босс занят приёмом. */
    void markBusy() {
        busyUntil = tickCount + 2;
    }

    /** Идёт замах удара, канал песков или другой приём — перемотка подождёт. */
    boolean isPreparingAttack() {
        return tickCount <= busyUntil || groundFx() == FX_HEAVY_WINDUP || groundFx() == FX_STASIS_WINDUP
                || sandsState() != SANDS_STATE_NONE;
    }

    /** Момент, к которому вернула бы перемотка: самый старый снимок не раньше трёх секунд назад. */
    @javax.annotation.Nullable
    private Moment rewindPoint() {
        int wanted = tickCount - REWIND_TICKS;
        for (Moment m : history) {
            if (m.tick() >= wanted) {
                return m;
            }
        }
        return null;
    }

    /**
     * Перемотка себя на 3 секунды назад: место и здоровье возвращаются к тому, какими были тогда.
     * Здоровье откатывается только в плюс — урон последних трёх секунд отменён.
     */
    void selfRewind() {
        rewindCooldown = REWIND_COOLDOWN;
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Moment past = rewindPoint();
        if (past == null) {
            return;
        }
        Vec3 from = position();
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), from.x, from.y + 3.0, from.z, 60, 1.2, 2.4, 1.2, 0.0);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), from.x, from.y + 0.3, from.z, 3, 0.8, 0.0, 0.8, 0.0);
        level().playSound(null, from.x, from.y + 1, from.z, ChronoSounds.TEMPORAL_REWIND.get(), SoundSource.HOSTILE, 2.0F, 0.6F);
        Vec3 to = past.pos();
        if (to.distanceToSqr(from) > 0.25D && level().noCollision(this, getType().getSpawnAABB(to.x, to.y, to.z))) {
            getNavigation().stop();
            teleportTo(to.x, to.y, to.z);
            setDeltaMovement(Vec3.ZERO);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), to.x, to.y + 3.0, to.z, 60, 1.2, 2.4, 1.2, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), to.x, to.y + 3.0, to.z, 6, 1.0, 2.0, 1.0, 0.05);
        }
        if (past.health() > getHealth()) {
            setHealth(Math.min(getMaxHealth(), past.health()));
        }
        clearFire();
        history.clear(); // отменённая линия времени больше недоступна
    }

    // =========================================================
    // Пески времени
    // =========================================================

    boolean sandsReady(LivingEntity target) {
        if (sandsCooldown > 0) {
            return false;
        }
        double d = distanceToSqr(target);
        return d > SANDS_MIN_RANGE * SANDS_MIN_RANGE && d < (SANDS_RANGE + 1.0D) * (SANDS_RANGE + 1.0D);
    }

    void startSands() {
        sandsCooldown = SANDS_COOLDOWN + random.nextInt(80);
        level().playSound(null, getX(), getY() + 4, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                SoundSource.HOSTILE, 1.2F, 0.8F);
    }

    /** Откуда и куда сыплется песок: из груди босса вдоль его взгляда. */
    private Vec3 sandsOrigin() {
        return position().add(0, getBbHeight() * 0.6D, 0);
    }

    private Vec3 sandsDirection(@javax.annotation.Nullable LivingEntity target) {
        Vec3 flat = Vec3.directionFromRotation(0.0F, yHeadRot);
        if (target == null) {
            return flat;
        }
        // по горизонтали — куда повёрнута голова (игрок может выйти из струи), по вертикали — на цель
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.5D, 0).subtract(sandsOrigin());
        double horizontal = Math.max(1.0D, Math.sqrt(to.x * to.x + to.z * to.z));
        return new Vec3(flat.x * horizontal, to.y, flat.z * horizontal).normalize();
    }

    /**
     * Один тик канала: раз в полсекунды — импульс по всем, кто в конусе. Сам песок и предупреждающую
     * область рисует клиент по синхронизированному направлению ({@link #showSands}).
     */
    void sandsTick(int elapsed, @javax.annotation.Nullable LivingEntity target) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        showSands(SANDS_STATE_CHANNEL, target);
        Vec3 origin = sandsOrigin();
        Vec3 dir = sandsDirection(target);
        if (elapsed % 10 != 0) {
            return;
        }
        level().playSound(null, getX(), getY() + 3, getZ(), net.minecraft.sounds.SoundEvents.SAND_BREAK,
                SoundSource.HOSTILE, 1.4F, 0.6F);
        DamageSource source = ChronoSpellRegistry.SANDS_OF_TIME_SPELL.getDamageSource(this);
        for (LivingEntity victim : serverLevel.getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(SANDS_RANGE + 1.0D, 4.0D, SANDS_RANGE + 1.0D))) {
            if (victim == this || !victim.isAlive() || isAlliedTo(victim) || victim.isAlliedTo(this)
                    || (victim instanceof Player p && (p.isCreative() || p.isSpectator()))
                    || (GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(victim))) {
                continue;
            }
            if (!SandsCone.contains(origin, dir, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5D, victim.getZ(),
                    SANDS_RANGE + victim.getBbWidth() * 0.5D, SANDS_CONE_COS)) {
                continue;
            }
            if (!hasLineOfSight(victim)) {
                continue; // за укрытием песок не достаёт
            }
            LivingEntity struck = victim;
            com.chronomancy.temporal.StasisFatigue.runWithStep(com.chronomancy.ChronoConfig.stasisFatigueRiftMaker(),
                    () -> com.chronomancy.temporal.SandsOfTime.pulse(this, struck, SANDS_DAMAGE_PER_SECOND, source));
        }
    }

    boolean summonReady(LivingEntity target) {
        return summonCooldown <= 0 && target instanceof Player && echoes.size() < MAX_ECHOES;
    }

    /** Бросает в цель сгусток Time Dilation Field с усиленными параметрами босса. */
    void castDilation(LivingEntity target) {
        faceExactly(target);
        // Поправка на дугу сгустка: чем дальше цель, тем выше целимся.
        double dist = Math.sqrt(distanceToSqr(target));
        setXRot(Mth.clamp(getXRot() - (float) (dist * 0.9D), -60.0F, 60.0F));
        com.chronomancy.spell.TimeDilationFieldSpell.spawnOrb(level(), this,
                DILATION_RADIUS, DILATION_RATE, DILATION_LIFETIME);
        level().playSound(null, getX(), getY() + 4, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                SoundSource.HOSTILE, 0.8F, 1.3F);
        dilationCooldown = DILATION_COOLDOWN + random.nextInt(60);
    }

    /** Двойники цели-игрока выходят из маленьких разрывов рядом с боссом. */
    void summonEchoes(LivingEntity target) {
        summonCooldown = SUMMON_COOLDOWN + random.nextInt(80);
        if (!(target instanceof Player player) || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int count = Math.min(ECHOES_PER_SUMMON, MAX_ECHOES - echoes.size());
        for (int i = 0; i < count; i++) {
            Vec3 spot = findSpotNear(position(), 2.0D, 4.0D);
            if (spot == null) {
                continue;
            }
            PlayerEchoEntity echo = ChronoEntityTypeRegistry.PLAYER_ECHO.get().create(serverLevel);
            if (echo == null) {
                continue;
            }
            echo.moveTo(spot.x, spot.y, spot.z, getYRot(), 0.0F);
            echo.configure(player, player, ECHO_LIFETIME, true);
            echo.copyArmor(player);
            // Второй двойник пары — маг: повторяет последнее заклинание игрока. Первый рубится.
            com.chronomancy.temporal.LastSpellTracker.Cast lastSpell =
                    i == 1 ? com.chronomancy.temporal.LastSpellTracker.last(player) : null;
            if (lastSpell != null) {
                echo.setSpell(lastSpell.spell(), lastSpell.level());
            }
            serverLevel.addFreshEntity(echo);
            echoes.add(echo.getUUID());
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), spot.x, spot.y + 1, spot.z, 20, 0.3, 0.8, 0.3, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), spot.x, spot.y + 1, spot.z,
                    2, 0.2, 0.4, 0.2, 0.0);
        }
        level().playSound(null, getX(), getY() + 1, getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                SoundSource.HOSTILE, 1.4F, 1.0F);
    }

    private Vec3 findSpotNear(Vec3 center, double min, double max) {
        EntityType<PlayerEchoEntity> type = ChronoEntityTypeRegistry.PLAYER_ECHO.get();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * (max - min);
            double x = center.x + Math.cos(angle) * dist;
            double z = center.z + Math.sin(angle) * dist;
            for (int dy = 1; dy >= -2; dy--) {
                double y = Math.floor(center.y) + dy;
                BlockPos below = BlockPos.containing(x, y - 1, z);
                AABB box = type.getSpawnAABB(x, y, z);
                if (level().getBlockState(below).isFaceSturdy(level(), below, net.minecraft.core.Direction.UP)
                        && level().noCollision(box) && !level().containsAnyLiquid(box)) {
                    return new Vec3(x, y, z);
                }
            }
        }
        return null;
    }

    void faceExactly(LivingEntity target) {
        Vec3 from = getEyePosition();
        Vec3 to = target.getEyePosition().add(0, -0.4D, 0);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        setXRot(pitch);
        yHeadRot = yaw;
        yBodyRot = yaw;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            playSound(ChronoSounds.CHRONOMALY_ATTACK.get(), 1.2F, 0.6F);
        }
        return hit;
    }

    /**
     * Удар по области: широкий взмах (дуга ~140° перед боссом, 5 блоков от центра) или каждый третий
     * раз — удар о землю по кругу радиусом 6 блоков. Основная цель получает полный удар, остальные — 70%
     * (удар о землю — 80% всем) и отлетают. Во время остановки времени бьёт только кастера стопа.
     */
    void areaAttack(LivingEntity primary) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean slam = ++swingCount % 3 == 0;
        double radius = slam ? 6.0D : 5.0D; // от центра; тело босса — 1.1 блока в полуширину
        float base = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
        Vec3 look = Vec3.directionFromRotation(0.0F, getYRot());
        boolean primaryHit = false;
        if (primary != null && isWithinMeleeAttackRange(primary)) {
            primaryHit = doHurtTarget(primary);
        }
        for (LivingEntity victim : serverLevel.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius, 1.5, radius))) {
            if (victim == this || victim == primary || !victim.isAlive() || isAlliedTo(victim) || victim.isAlliedTo(this)
                    || (victim instanceof Player p && (p.isCreative() || p.isSpectator()))
                    || (GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(victim))) {
                continue;
            }
            Vec3 to = victim.position().subtract(position()).multiply(1, 0, 1);
            double dist = to.length();
            if (dist > radius + victim.getBbWidth() * 0.5) {
                continue;
            }
            if (!slam && dist > 0.5 && to.normalize().dot(look) < 0.35) {
                continue; // взмах бьёт только перед собой
            }
            if (victim.hurt(damageSources().mobAttack(this), base * (slam ? 0.8F : 0.7F))) {
                Vec3 push = dist > 1.0e-3 ? to.normalize() : look;
                victim.knockback(slam ? 1.1D : 0.7D, -push.x, -push.z);
            }
        }
        if (slam) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX(), getY() + 0.2, getZ(),
                    24, radius * 0.45, 0.1, radius * 0.45, 0.05);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 0.3, getZ(), 2, 0.5, 0.0, 0.5, 0.0);
            level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.HOSTILE, 0.8F, 1.4F);
        } else {
            Vec3 c = position().add(look.scale(3.0)).add(0, 1.5, 0);
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 3, 0.8, 0.2, 0.8, 0.0);
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_SPARK.get(), c.x, c.y, c.z, 8, 1.0, 0.4, 1.0, 0.05);
            if (!primaryHit) {
                playSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.6F);
            }
        }
    }

    // =========================================================
    // Ярость: стазис-удар о землю
    // =========================================================

    boolean isEnraged() {
        return getHealth() < getMaxHealth() * 0.5F;
    }

    /** Только в ярости, по откату, и когда цель рядом — иначе удар уйдёт в пустоту. */
    boolean stasisSlamReady(LivingEntity target) {
        if (!isEnraged() || stasisSlamCooldown > 0) {
            return false;
        }
        double reach = getBbWidth() * 0.5D + STASIS_SLAM_REACH;
        return distanceToSqr(target) < reach * reach;
    }

    /** Замах: круг из рун на земле показывает границу стазиса — из него ещё можно выскочить. */
    void telegraphStasisSlam(int ticksLeft) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Саму область показывает циферблат на земле (FX_STASIS_WINDUP); здесь — только тиканье и руны.
        if (ticksLeft % 6 == 0) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 0.2D, getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            level().playSound(null, getX(), getY(), getZ(), ChronoSounds.CLOCK_TICK.get(), SoundSource.HOSTILE,
                    1.6F, 0.6F + (STASIS_SLAM_WINDUP - ticksLeft) * 0.03F);
        }
    }

    /** Удар о землю: все игроки в 5 блоках от края босса замирают во времени на 3 секунды. */
    void stasisSlam() {
        stasisSlamCooldown = STASIS_SLAM_COOLDOWN + random.nextInt(60);
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double r = getBbWidth() * 0.5D + STASIS_SLAM_REACH;
        for (Player player : serverLevel.getEntitiesOfClass(Player.class, getBoundingBox().inflate(STASIS_SLAM_REACH + 1.0D, 2.0D,
                STASIS_SLAM_REACH + 1.0D))) {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()
                    || (GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(player))
                    || player.getY() > getY() + 2.5D) {
                continue; // высоко в прыжке — волна прошла под ногами
            }
            double dx = player.getX() - getX(), dz = player.getZ() - getZ();
            if (Math.sqrt(dx * dx + dz * dz) > r + player.getBbWidth() * 0.5D) {
                continue;
            }
            if (player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_STASIS, STASIS_SLAM_TICKS, 0), this)) {
                // Замерший не тикает своих эффектов: срок задаётся мировыми часами стазиса.
                // Усталость от стазиса самого босса копится вдвое медленнее обычной.
                com.chronomancy.temporal.StasisFatigue.runWithStep(com.chronomancy.ChronoConfig.stasisFatigueRiftMaker(),
                        () -> com.chronomancy.effect.TemporalStasisEvents.initializeStasis(player, 1,
                                STASIS_SLAM_DAMAGE_CAP, STASIS_SLAM_TICKS));
            }
        }
        // Кольцо трещин по границе стазиса и вспышка в центре.
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2.0D / 48;
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), getX() + Math.cos(a) * r, getY() + 0.2D,
                    getZ() + Math.sin(a) * r, 1, 0.05, 0.05, 0.05, 0.0);
        }
        showGroundFx(FX_STASIS_IMPACT);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(), getX(), getY() + 0.3D, getZ(),
                40, r * 0.4D, 0.1D, r * 0.4D, 0.03D);
        serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), getX(), getY() + 0.3D, getZ(), 3, 0.6, 0.0, 0.6, 0.0);
        level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.HOSTILE, 1.2F, 0.7F);
        level().playSound(null, getX(), getY(), getZ(), ChronoSounds.TEMPORAL_RELEASE.get(), SoundSource.HOSTILE, 1.5F, 0.5F);
    }

    // =========================================================
    // Прочее
    // =========================================================

    /** Огромное тело: если разлом открылся в тесноте, стены не душат босса (он телепортируется). */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return ChronoSounds.RIFT_MAKER_AMBIENT.get();
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
    public float getVoicePitch() {
        return 0.55F + random.nextFloat() * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 2.0F;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 200;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(net.minecraft.sounds.SoundEvents.COPPER_STEP, 0.6F, 0.6F);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ListTag list = new ListTag();
        for (UUID id : echoes) {
            list.add(NbtUtils.createUUID(id));
        }
        tag.put("Echoes", list);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        echoes.clear();
        for (Tag t : tag.getList("Echoes", Tag.TAG_INT_ARRAY)) {
            echoes.add(NbtUtils.loadUUID(t));
        }
        if (hasCustomName()) {
            bossEvent.setName(getDisplayName());
        }
    }

    @Override
    public void setCustomName(Component name) {
        super.setCustomName(name);
        bossEvent.setName(getDisplayName());
    }

    // =========================================================
    // GeckoLib
    // =========================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, state -> {
            if (isDeadOrDying()) {
                return state.setAndContinue(DEATH);
            }
            double dx = getX() - xo, dz = getZ() - zo;
            return state.setAndContinue(dx * dx + dz * dz > 0.0004D ? WALK : IDLE);
        }));
        controllers.add(new AnimationController<>(this, "action", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", ATTACK)
                .triggerableAnim("cast", CAST)
                .triggerableAnim("summon", SUMMON)
                .triggerableAnim("slam", SLAM)
                .triggerableAnim("sands", SANDS)
                .triggerableAnim("heavy", HEAVY)
                .triggerableAnim("stasis", STASIS));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // =========================================================
    // AI
    // =========================================================

    /** Заклинания: короткий замах стоя на месте, затем Dilation Field или призыв двойников. */
    static final class AbilityGoal extends Goal {
        private enum Ability { TELEPORT, DILATION, SUMMON, STASIS_SLAM, SANDS }

        private final RiftMakerEntity boss;
        private Ability ability;
        private int windup;
        /** Сколько тиков ещё длится канал (только пески времени). */
        private int channel;
        private Vec3 teleportSpot;

        AbilityGoal(RiftMakerEntity boss) {
            this.boss = boss;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = boss.getTarget();
            if (target == null || !target.isAlive() || boss.isArriving()) {
                return false;
            }
            if (boss.teleportReady(target)) {
                teleportSpot = boss.findTeleportSpot(target);
                if (teleportSpot != null) {
                    ability = Ability.TELEPORT;
                    return true;
                }
                boss.teleportCooldown = 20;
            }
            if (!boss.getSensing().hasLineOfSight(target)) {
                return false;
            }
            if (boss.stasisSlamReady(target)) {
                ability = Ability.STASIS_SLAM;
                return true;
            }
            if (boss.summonReady(target)) {
                ability = Ability.SUMMON;
                return true;
            }
            if (boss.sandsReady(target)) {
                ability = Ability.SANDS;
                return true;
            }
            if (boss.dilationReady(target)) {
                ability = Ability.DILATION;
                return true;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return (windup > 0 || channel > 0) && boss.getTarget() != null && boss.getTarget().isAlive();
        }

        @Override
        public void stop() {
            if (ability == Ability.SANDS && (channel > 0 || windup > 0)) {
                boss.stopTriggeredAnim("action", "sands"); // канал сорвался — рука не остаётся вытянутой
            }
            if (boss.groundFx() == FX_STASIS_WINDUP) {
                boss.clearGroundFx(); // замах стазиса сорвался — циферблат гаснет
            }
            channel = 0;
            windup = 0;
            boss.clearSands();
        }

        @Override
        public void start() {
            boss.getNavigation().stop();
            windup = switch (ability) {
                case SUMMON -> 24;
                case DILATION -> 12;
                case TELEPORT -> 10;
                case STASIS_SLAM -> STASIS_SLAM_WINDUP;
                case SANDS -> SANDS_WINDUP;
            };
            channel = 0;
            boss.triggerAnim("action", switch (ability) {
                case SUMMON -> "summon";
                case STASIS_SLAM -> "stasis";
                case SANDS -> "sands";
                default -> "cast";
            });
            if (ability == Ability.STASIS_SLAM) {
                boss.showGroundFx(FX_STASIS_WINDUP);
            }
            if (ability == Ability.STASIS_SLAM) {
                boss.level().playSound(null, boss.getX(), boss.getY() + 4, boss.getZ(), ChronoSounds.RIFT_MAKER_SUMMON.get(),
                        SoundSource.HOSTILE, 1.6F, 0.5F);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = boss.getTarget();
            if (target == null) {
                windup = 0;
                channel = 0;
                return;
            }
            if (windup <= 0 && channel <= 0) {
                return; // приём уже окончен: цель дотикивает лишний тик до остановки
            }
            boss.markBusy();
            if (channel > 0) {
                // Канал песков: стоит на месте и медленно доворачивается — из струи можно выйти.
                boss.getNavigation().stop();
                boss.getLookControl().setLookAt(target, SANDS_TURN_SPEED, 30.0F);
                int elapsed = SANDS_CHANNEL - channel;
                boss.sandsTick(elapsed, target);
                channel--;
                return;
            }
            boss.getLookControl().setLookAt(target, 60.0F, 60.0F);
            if (ability == Ability.TELEPORT && windup % 2 == 0) {
                boss.telegraphTeleport(teleportSpot);
            }
            if (ability == Ability.STASIS_SLAM) {
                boss.telegraphStasisSlam(windup);
            }
            if (ability == Ability.SANDS) {
                boss.showSands(SANDS_STATE_WINDUP, target); // область видна ещё до первого импульса
            }
            if (--windup == 0) {
                switch (ability) {
                    case SUMMON -> boss.summonEchoes(target);
                    case DILATION -> boss.castDilation(target);
                    case STASIS_SLAM -> boss.stasisSlam();
                    case SANDS -> {
                        boss.startSands();
                        channel = SANDS_CHANNEL;
                    }
                    case TELEPORT -> {
                        // Место могли занять за время замаха — проверяем ещё раз.
                        Vec3 spot = boss.level().noCollision(boss, boss.getType().getSpawnAABB(
                                teleportSpot.x, teleportSpot.y, teleportSpot.z)) ? teleportSpot : boss.findTeleportSpot(target);
                        if (spot != null) {
                            boss.blinkTo(spot, target);
                        }
                    }
                }
            }
        }
    }

    /** Ближний бой. Готовность — по собственному счётчику (gameTime стоит при World Stop). */
    static final class BossMeleeGoal extends Goal {
        private final RiftMakerEntity boss;
        private int attackCooldown;
        private int repath;
        private int swingDelay;
        /** Идущий сейчас взмах — усиленный (удар о землю по кругу). */
        private boolean heavy;

        BossMeleeGoal(RiftMakerEntity boss) {
            this.boss = boss;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = boss.getTarget();
            return target != null && target.isAlive() && !boss.isArriving();
        }

        @Override
        public void start() {
            attackCooldown = 20;
            repath = 0;
            swingDelay = 0;
            heavy = false;
        }

        @Override
        public void stop() {
            boss.getNavigation().stop();
            if (heavy && swingDelay > 0) {
                boss.clearGroundFx(); // замах сорвался
                boss.stopTriggeredAnim("action", "heavy");
            }
            heavy = false;
            swingDelay = 0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = boss.getTarget();
            if (target == null) {
                return;
            }
            boss.getLookControl().setLookAt(target, 30.0F, 30.0F);
            boss.trackStuck(target);
            if (heavy && swingDelay > 0) {
                boss.getNavigation().stop(); // усиленный удар бьёт вокруг босса: на замахе он стоит
            } else if (--repath <= 0) {
                repath = 10;
                boss.getNavigation().moveTo(target, 1.0D);
            }
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            // Замах (анимация) → удар: обычный через 8 тиков, усиленный через 14.
            if (swingDelay > 0) {
                boss.markBusy();
                if (--swingDelay == 0) {
                    boss.swing(InteractionHand.MAIN_HAND);
                    boss.areaAttack(target);
                    if (heavy) {
                        boss.showGroundFx(FX_HEAVY_IMPACT);
                    }
                    heavy = false;
                }
                return;
            }
            if (attackCooldown <= 0 && boss.isWithinMeleeAttackRange(target)
                    && boss.getSensing().hasLineOfSight(target)) {
                heavy = boss.nextSwingIsHeavy();
                if (heavy) {
                    // каждый третий взмах: клинок над головой, круг на земле показывает, куда придётся удар
                    boss.triggerAnim("action", "heavy");
                    boss.showGroundFx(FX_HEAVY_WINDUP);
                    boss.level().playSound(null, boss.getX(), boss.getY() + 4, boss.getZ(), ChronoSounds.TEMPORAL_CRACK.get(),
                            SoundSource.HOSTILE, 1.4F, 0.5F);
                    swingDelay = HEAVY_WINDUP;
                    attackCooldown = 26;
                } else {
                    boss.triggerAnim("action", "attack");
                    swingDelay = 8;
                    attackCooldown = 16; // рубит часто: вблизи это его главная атака
                }
            }
        }
    }
}
