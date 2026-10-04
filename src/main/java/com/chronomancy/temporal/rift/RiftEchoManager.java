package com.chronomancy.temporal.rift;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.PlayerEchoEntity;
import com.chronomancy.entity.RiftMakerEntity;
import com.chronomancy.entity.TimeRiftEntity;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.ChronoClock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Заклинание RIFT (оружие Rift Creator): рядом с целью открывается разрыв, и через секунду из него
 * выходит МЕДНАЯ КОПИЯ цели, которая нападает на оригинал.
 *
 * <ul>
 *   <li>Моб копируется со своими данными (вариант, размер, экипировка) — зомби бьёт зомби, скелет
 *       стреляет в скелета, крипер подходит к оригиналу и взрывается. Копия всегда держит оригинал
 *       своей целью (любая смена цели перенаправляется обратно на него). Мобы без атаки (корова,
 *       житель) таранят оригинал сами.</li>
 *   <li>Цель-игрок → медный двойник этого игрока с копией его оружия и брони ({@link PlayerEchoEntity}):
 *       вблизи бьёт, а отошедшего достаёт последним заклинанием самого игрока — любой школы, кроме
 *       неповторяемых ({@link com.chronomancy.temporal.LastSpellTracker#lastAnySchool}).</li>
 *   <li>Боссов (тег {@code c:bosses}) и Rift Maker скопировать нельзя — заклинание не срабатывает.</li>
 * </ul>
 *
 * <p>Копия живёт {@value #ECHO_LIFETIME} тиков (+1 с за каждые полные 10% силы заклинаний школы
 * хрономантии, см. {@link #lifetimeFor}) или пока жив оригинал, без добычи и опыта, с ней
 * нельзя взаимодействовать (торговля/стрижка/доение) и она не сохраняется в мир. Клиенты узнают о
 * копиях пакетом — рисуют их медными.
 */
public final class RiftEchoManager {

    public static final int ECHO_LIFETIME = 200;
    private static final int OPEN_DELAY = TimeRiftEntity.OPEN_TICKS;
    private static final String ECHO_TAG = "chronomancy.rift_echo";

    private record Pending(ResourceKey<Level> level, UUID caster, UUID target, Vec3 spot, long due, int lifetime) {}

    /** Время жизни копии: база 10 с и +1 с за каждые полные 10% силы заклинаний сверх 100%. */
    public static int lifetimeFor(LivingEntity caster) {
        double power;
        try {
            power = com.chronomancy.registry.ChronoSchools.totalSpellPower(caster);
        } catch (RuntimeException e) {
            power = 1.0; // у кастера нет атрибута силы заклинаний
        }
        int bonusSeconds = (int) Math.floor(Math.max(0.0, power - 1.0) * 10.0 + 1.0e-6);
        return ECHO_LIFETIME + bonusSeconds * 20;
    }

    private static final class Echo {
        final UUID original;
        final ResourceKey<Level> level;
        final long expires;
        final boolean ram;
        int ramCooldown;

        Echo(UUID original, ResourceKey<Level> level, long expires, boolean ram) {
            this.original = original;
            this.level = level;
            this.expires = expires;
            this.ram = ram;
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<UUID, Echo> ECHOES = new HashMap<>();
    private static final Random RANDOM = new Random();

    private RiftEchoManager() {
    }

    /** Можно ли создать копию этой цели. */
    public static boolean canCopy(LivingEntity target) {
        if (target == null || !target.isAlive() || target instanceof RiftMakerEntity
                || target instanceof PlayerEchoEntity || isEcho(target)) {
            return false;
        }
        if (target instanceof Player) {
            return true;
        }
        return target instanceof Mob && !target.getType().is(Tags.EntityTypes.BOSSES);
    }

    public static boolean isEcho(Entity entity) {
        return entity != null && ECHOES.containsKey(entity.getUUID());
    }

    // =========================================================
    // Каст
    // =========================================================

    /** Открыть разрыв возле цели; копия выйдет из него через {@value #OPEN_DELAY} тиков. */
    public static boolean openRift(ServerLevel level, LivingEntity caster, LivingEntity target) {
        if (!canCopy(target)) {
            return false;
        }
        Vec3 spot = findSpot(level, target);
        if (spot == null) {
            spot = target.position();
        }
        TimeRiftEntity portal = ChronoEntityTypeRegistry.TIME_RIFT.get().create(level);
        if (portal != null) {
            float yaw = (float) Math.toDegrees(Math.atan2(target.getX() - spot.x, spot.z - target.getZ()));
            portal.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
            portal.makeStandalone(OPEN_DELAY + 30);
            level.addFreshEntity(portal);
        }
        PENDING.add(new Pending(level.dimension(), caster.getUUID(), target.getUUID(), spot,
                ChronoClock.now() + OPEN_DELAY, lifetimeFor(caster)));
        return true;
    }

    private static Vec3 findSpot(ServerLevel level, LivingEntity target) {
        EntityType<?> type = target.getType();
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double dist = 2.0D + RANDOM.nextDouble() * 1.5D;
            double x = target.getX() + Math.cos(angle) * dist;
            double z = target.getZ() + Math.sin(angle) * dist;
            for (int dy = 1; dy >= -2; dy--) {
                double y = Math.floor(target.getY()) + dy;
                BlockPos below = BlockPos.containing(x, y - 1, z);
                AABB box = type.getSpawnAABB(x, y, z).inflate(0.1D, 0.0D, 0.1D);
                AABB portalBox = ChronoEntityTypeRegistry.TIME_RIFT.get().getSpawnAABB(x, y, z);
                if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                        || !level.noCollision(box) || !level.noCollision(portalBox) || level.containsAnyLiquid(box)) {
                    continue;
                }
                HitResult hit = level.clip(new ClipContext(new Vec3(x, y + 1, z), target.getEyePosition(),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
                if (hit.getType() == HitResult.Type.MISS) {
                    return new Vec3(x, y, z);
                }
            }
        }
        return null;
    }

    // =========================================================
    // Тик
    // =========================================================

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = ChronoClock.now();
        if (!PENDING.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            PENDING.removeIf(p -> {
                if (p.due() > now) return false;
                due.add(p);
                return true;
            });
            for (Pending p : due) {
                ServerLevel level = server.getLevel(p.level());
                if (level != null && level.getEntity(p.target()) instanceof LivingEntity target && target.isAlive()) {
                    spawnEcho(level, level.getEntity(p.caster()), target, p.spot(), p.lifetime());
                }
            }
        }
        if (ECHOES.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, Echo>> snapshot = new ArrayList<>(ECHOES.entrySet());
        for (Map.Entry<UUID, Echo> entry : snapshot) {
            Echo echo = entry.getValue();
            ServerLevel level = server.getLevel(echo.level);
            Entity entity = level == null ? null : level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity copy) || !copy.isAlive()) {
                forget(entry.getKey(), entity);
                continue;
            }
            Entity original = level.getEntity(echo.original);
            if (!(original instanceof LivingEntity target) || !target.isAlive() || now >= echo.expires) {
                collapse(level, copy);
                continue;
            }
            if (copy instanceof Mob mob) {
                huntOriginal(mob, target, echo);
            }
        }
    }

    /** Копия всегда охотится на оригинал: цель + память мозга; без атаки — таран. */
    private static void huntOriginal(Mob mob, LivingEntity target, Echo echo) {
        if (mob.getTarget() != target) {
            mob.setTarget(target);
        }
        if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET,
                net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
        if (!echo.ram) {
            return;
        }
        if (mob instanceof PathfinderMob pathfinder && mob.tickCount % 10 == 0) {
            pathfinder.getNavigation().moveTo(target, 1.3D);
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (echo.ramCooldown > 0) {
            echo.ramCooldown--;
        } else if (mob.distanceToSqr(target) < 2.2D * 2.2D) {
            target.hurt(mob.damageSources().mobAttack(mob), 3.0F);
            target.knockback(0.5D, mob.getX() - target.getX(), mob.getZ() - target.getZ());
            echo.ramCooldown = 20;
        }
    }

    private static void spawnEcho(ServerLevel level, Entity caster, LivingEntity target, Vec3 spot, int lifetime) {
        LivingEntity copy;
        boolean ram = false;
        if (target instanceof Player player) {
            PlayerEchoEntity echo = ChronoEntityTypeRegistry.PLAYER_ECHO.get().create(level);
            if (echo == null) return;
            echo.moveTo(spot.x, spot.y, spot.z, target.getYRot(), 0.0F);
            echo.configure(player, player, lifetime, false);
            echo.copyArmor(player); // двойник защищён так же, как оригинал
            com.chronomancy.temporal.LastSpellTracker.Cast lastSpell = com.chronomancy.temporal.LastSpellTracker.lastAnySchool(player);
            if (lastSpell != null) {
                echo.setSpellAtRange(lastSpell.spell(), lastSpell.level());
            }
            copy = echo;
        } else {
            copy = copyMob(level, (Mob) target, spot);
            if (copy == null) return;
            ram = !copy.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE);
        }
        copy.getPersistentData().putUUID(ECHO_TAG, target.getUUID());
        ECHOES.put(copy.getUUID(), new Echo(target.getUUID(), level.dimension(), ChronoClock.now() + lifetime, ram));
        if (copy instanceof Mob mob) {
            mob.setTarget(target);
        }
        level.addFreshEntity(copy);
        ChronoNetwork.broadcastRiftEcho(copy, true);
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_COPPER.get(), spot.x, spot.y + 1, spot.z, 30, 0.4, 0.8, 0.4, 0.0);
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), spot.x, spot.y + 1, spot.z, 2, 0.2, 0.4, 0.2, 0.0);
        level.playSound(null, spot.x, spot.y + 1, spot.z, ChronoSounds.DOUBLE_SUMMON.get(), SoundSource.PLAYERS, 1.0F, 0.9F);
    }

    /** Полная копия моба (вариант/размер/экипировка) с новым UUID, без поводка/пассажиров/лута. */
    private static Mob copyMob(ServerLevel level, Mob original, Vec3 spot) {
        Entity created = original.getType().create(level);
        if (!(created instanceof Mob copy)) {
            return null;
        }
        try {
            CompoundTag data = original.saveWithoutId(new CompoundTag());
            for (String key : new String[]{"UUID", "Passengers", "leash", "Leash", "Brain", "Pos", "Motion",
                    "Rotation", "DeathLootTable", "DeathLootTableSeed", "PersistenceRequired", "NeoForgeData",
                    "neoforge:attachments", "CustomName", "Offers", "Owner", "Tags"}) {
                data.remove(key);
            }
            copy.load(data);
        } catch (RuntimeException e) {
            ChronomancyMod.LOGGER.debug("[Rift] full copy of {} failed, using a fresh one", original.getType(), e);
        }
        copy.setUUID(UUID.randomUUID());
        copy.moveTo(spot.x, spot.y, spot.z, original.getYRot(), 0.0F);
        copy.setNoAi(false);
        copy.setHealth(copy.getMaxHealth());
        copy.setDeltaMovement(Vec3.ZERO);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            copy.setDropChance(slot, 0.0F);
        }
        return copy;
    }

    private static void collapse(ServerLevel level, LivingEntity copy) {
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_COPPER.get(), copy.getX(), copy.getY() + copy.getBbHeight() * 0.5, copy.getZ(),
                24, 0.3, 0.5, 0.3, 0.0);
        level.playSound(null, copy.getX(), copy.getY() + 1, copy.getZ(), ChronoSounds.DOUBLE_ECHO.get(),
                SoundSource.PLAYERS, 0.8F, 0.7F);
        forget(copy.getUUID(), copy);
        copy.discard();
    }

    private static void forget(UUID id, Entity entity) {
        ECHOES.remove(id);
        if (entity != null) {
            ChronoNetwork.broadcastRiftEcho(entity, false);
        }
    }

    // =========================================================
    // События
    // =========================================================

    /** Копия переживает перезагрузку чанка/мира только пока жив её учёт — иначе исчезает. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (entity.getPersistentData().contains(ECHO_TAG) && !ECHOES.containsKey(entity.getUUID())) {
            event.setCanceled(true);
        }
    }

    /** Любая смена цели копии перенаправляется обратно на оригинал. */
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        Echo echo = ECHOES.get(event.getEntity().getUUID());
        if (echo == null || event.getNewAboutToBeSetTarget() == null) return;
        if (!echo.original.equals(event.getNewAboutToBeSetTarget().getUUID())) {
            if (event.getEntity().level() instanceof ServerLevel level
                    && level.getEntity(echo.original) instanceof LivingEntity original) {
                event.setNewAboutToBeSetTarget(original);
            } else {
                event.setCanceled(true);
            }
        }
    }

    public static void onDrops(LivingDropsEvent event) {
        if (isEcho(event.getEntity()) || event.getEntity() instanceof PlayerEchoEntity) event.setCanceled(true);
    }

    public static void onExperience(LivingExperienceDropEvent event) {
        if (isEcho(event.getEntity()) || event.getEntity() instanceof PlayerEchoEntity) event.setCanceled(true);
    }

    /** Нельзя торговать с копией жителя, стричь медную овцу, доить медную корову и т.п. */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (isEcho(event.getTarget())) event.setCanceled(true);
    }

    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (isEcho(event.getTarget())) event.setCanceled(true);
    }

    /** Новый наблюдатель узнаёт, что сущность — медная копия. */
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && isEcho(event.getTarget())) {
            ChronoNetwork.sendRiftEchoToPlayer(player, event.getTarget(), true);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (Iterator<Map.Entry<UUID, Echo>> it = ECHOES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Echo> entry = it.next();
            ServerLevel level = event.getServer().getLevel(entry.getValue().level);
            Entity entity = level == null ? null : level.getEntity(entry.getKey());
            if (entity != null) entity.discard();
            it.remove();
        }
        PENDING.clear();
    }
}
