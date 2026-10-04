package com.chronomancy.temporal.needle;

import com.chronomancy.entity.ChronoDoubleEntity;
import com.chronomancy.entity.TemporalNeedleEntity;
import com.chronomancy.network.TemporalNeedlePayload;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.spell.TimePiercingNeedleSpell;
import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import com.chronomancy.temporal.chronodouble.ChronoDoubleManager;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** External server clock, independent of target ticks, mob effects and frozen level gameTime. */
public final class TemporalNeedleManager {
    public static final int FIRE_DELAY = 20, COLLAPSE_DELAY = NeedleCharge.COLLAPSE_DELAY;
    /**
     * Иглы хрономалий держатся дольше (10 с после последнего попадания). Предел игл на цели у мобов
     * (общая цепочка хрономалий и цепочка Часовщика) — {@value #MOB_MAX_STACKS}, как у игрока на
     * максимальном уровне заклинания, и от уровня каста не зависит.
     */
    public static final int MOB_COLLAPSE_DELAY = 200, MOB_MAX_STACKS = 12;
    private static long clock;
    private record Key(UUID caster, UUID target, ResourceKey<Level> dimension) {}
    /**
     * A temporal gate is a fully world-space, caster-independent snapshot taken at cast time.
     * Position, aim direction and dimension are frozen here; the gate never follows the caster.
     * {@code world} is only the cast-time handle used to deliver packets after the caster is gone
     * (death/disconnect/dimension change); the shot fires purely off {@code deadline}.
     */
    private record Gate(UUID id, UUID caster, int level, ServerLevel world, ResourceKey<Level> dimension,
                        Vec3 position, Vec3 direction, long deadline) {}
    private static final List<Gate> GATES = new ArrayList<>();
    private static final Map<Key, Chain> CHAINS = new HashMap<>();
    private static final class Chain {
        final Key key;
        final LivingEntity target;
        final int max;
        final double power;
        final DamageSource source;
        final ListTag needles = new ListTag();
        final NeedleCharge charge;
        Chain(Key key, LivingEntity caster, LivingEntity target, int level, double damageFraction) {
            this.key = key;
            this.target = target;
            boolean mobChain = com.chronomancy.entity.ChronomalyEntity.NEEDLE_CHAIN_ID.equals(key.caster());
            // У мобов предел один на всех — 12 игл: и у общей цепочки хрономалий (10 с до схлопывания),
            // и у Часовщика (обычные 4 с).
            boolean mobCaster = mobChain || caster instanceof com.chronomancy.entity.ClocksmithEntity;
            this.max = mobCaster ? MOB_MAX_STACKS : TimePiercingNeedleSpell.maxStacks(level);
            this.charge = mobChain ? new NeedleCharge(max, MOB_COLLAPSE_DELAY) : new NeedleCharge(max);
            // Snapshot once, never compound power on subsequent hits.
            // Иглы мобов (стая хрономалий, Часовщик) бьют по той же кривой, что и иглы игрока.
            this.power = ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL.getEntityPowerMultiplier(caster) * damageFraction;
            this.source = ChronoSpellRegistry.TIME_PIERCING_NEEDLE_SPELL.getDamageSource(caster, caster);
        }
    }
    private TemporalNeedleManager() {}

    public static Vec3 gatePosition(LivingEntity caster) {
        return caster.getEyePosition().add(caster.getLookAngle().scale(0.85)).add(0, -0.16, 0);
    }
    /**
     * Freezes the shot at t=0: world position, normalized look direction, dimension and caster UUID.
     * After this the gate is an independent timed event; the caster may leave, turn or change
     * dimension and the future trajectory is unchanged (a deliberate skill-shot window).
     */
    public static void enqueue(LivingEntity caster, int level) {
        Vec3 direction = caster.getLookAngle().normalize();
        addGate(caster, level, gatePosition(caster), direction);
        if (!(caster instanceof ServerPlayer player)) {
            return; // мобы-кастеры (Chronomaly) без ChronoDouble
        }
        // ChronoDouble: the mirror telegraphs its own preparation rune and fires a needle that stacks
        // onto the SAME target chain, so the clone visibly charges and adds to the owner's needle stacks.
        ChronoDoubleEntity echo = ChronoDoubleManager.active(player);
        if (echo != null && echo.level() == caster.level()) {
            double eyeHeight = caster.getEyeY() - caster.getY();
            Vec3 cloneEye = echo.position().add(0, eyeHeight, 0);
            addGate(player, level, cloneEye.add(direction.scale(0.85)).add(0, -0.16, 0), direction);
        }
    }

    private static void addGate(LivingEntity caster, int level, Vec3 position, Vec3 direction) {
        Gate gate = new Gate(UUID.randomUUID(), caster.getUUID(), level, (ServerLevel) caster.level(),
                caster.level().dimension(), position, direction, clock + FIRE_DELAY);
        GATES.add(gate);
        sendAt(gate.world, gate.position(), gatePacket(gate, "gate"));
        if (!(caster instanceof ServerPlayer)) {
            // Моб целится: звонкий щелчок руны — у игрока есть секунда, чтобы уйти с линии выстрела.
            gate.world.playSound(null, position.x, position.y, position.z,
                    com.chronomancy.registry.ChronoSounds.CLOCK_TICK.get(), net.minecraft.sounds.SoundSource.HOSTILE,
                    1.2F, 1.7F);
        }
    }
    /** Цель, чьё время расшатано: под эффектом Парадокса или внутри чужой Accelerated Zone. */
    private static boolean unstable(LivingEntity target) {
        return com.chronomancy.temporal.TimeParadox.afflicted(target)
                || com.chronomancy.temporal.TemporalDilationHandler.zoneAcceleration(target.level(), target) > 1.0D;
    }
    public static void hit(LivingEntity caster, LivingEntity target, int level, Vec3 position, Vec3 incoming) {
        // Все Chronomaly колют в ОДНУ общую цепочку на цели: иглы стаи накапливаются вместе.
        UUID owner = caster instanceof com.chronomancy.entity.ChronomalyEntity
                ? com.chronomancy.entity.ChronomalyEntity.NEEDLE_CHAIN_ID : caster.getUUID();
        hit(caster, target, level, position, incoming, owner, 1);
    }
    /** Echo needles build their own reduced chain; they never double the owner's stack progression. */
    public static void hit(LivingEntity caster, LivingEntity target, int level, Vec3 position, Vec3 incoming,
                           UUID chainOwner, double damageFraction) {
        if (!(target.level() instanceof ServerLevel world) || !target.isAlive() || target.isRemoved()
                || caster == target || caster.isAlliedTo(target) || target.isAlliedTo(caster)) return;
        if (caster instanceof ServerPlayer a && target instanceof ServerPlayer b && !a.canHarmPlayer(b)) return;
        if (caster instanceof com.chronomancy.entity.ChronomalyEntity && chainOwner.equals(caster.getUUID())) {
            // Снаряд хрономали приходит сюда с UUID самого моба: переводим в общую цепочку стаи
            // (иначе у каждой хрономали своя цепочка с обычными 4 секундами).
            chainOwner = com.chronomancy.entity.ChronomalyEntity.NEEDLE_CHAIN_ID;
        }
        Key key = new Key(chainOwner, target.getUUID(), world.dimension());
        Chain chain = CHAINS.computeIfAbsent(key, k -> new Chain(k, caster, target, level, damageFraction));
        // Расшатанное время (эффект Парадокса или Accelerated Zone) принимает иглы вдвое охотнее:
        // каждое попадание кладёт несколько зарядов сразу (needles.stacksPerHitWhenUnstable).
        int charges = unstable(target) ? com.chronomancy.ChronoConfig.needleStacksWhenUnstable() : 1;
        for (int charged = 0; charged < charges; charged++) {
            if (chain.charge.hit(clock)) {
                // Body-local coordinates preserve placement and direction while the target turns.
                float angle = target.yBodyRot * ((float) Math.PI / 180);
                Vec3 offset = position.subtract(target.position());
                // Slight surface spread keeps repeated shots at the same point individually readable.
                int index = chain.needles.size();
                if (index > 0) {
                    Vec3 side = incoming.cross(new Vec3(0, 1, 0));
                    if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
                    side = side.normalize();
                    Vec3 up = side.cross(incoming).normalize();
                    double angleOnSurface = index * 2.3999632297;
                    double radius = 0.035 * Math.sqrt(index);
                    offset = offset.add(side.scale(Math.cos(angleOnSurface) * radius)).add(up.scale(Math.sin(angleOnSurface) * radius));
                }
                double width = Math.max(0.1, target.getBbWidth());
                offset = new Vec3(Math.clamp(offset.x, -width, width), Math.clamp(offset.y, 0, target.getBbHeight()), Math.clamp(offset.z, -width, width));
                CompoundTag needle = new CompoundTag();
                vector(needle, "p", offset.yRot(angle));
                vector(needle, "d", incoming.yRot(angle));
                chain.needles.add(needle);
            }
        }
        send(target, chainPacket(chain, "stack"));
        // No stuck needles are shown: the caster sees a Roman-numeral charge above the target, the
        // target "glitches" through time harder with every stack, and the status effect shows the total.
        updateEffect(target);
        // Visible impact spark at the exact hit point, broadcast to everyone tracking the target.
        send(target, impactPacket(world, position, incoming));
        // Characteristic temporal-ting on hit; pitch rises with the growing stack count.
        com.chronomancy.sound.ChronoNeedleSounds.playHit(world, position, chain.needles.size());
        // No automatic echo damage here: the double's independently colliding projectile owns its chain.
    }
    public static void onTick(ServerTickEvent.Post event) {
        clock++;
        MinecraftServer server = event.getServer();
        for (Iterator<Gate> iterator = GATES.iterator(); iterator.hasNext();) {
            Gate gate = iterator.next();
            // No caster-alive/dimension check: the gate is already paid for and fires regardless.
            if (clock >= gate.deadline) {
                iterator.remove();
                ServerLevel world = gate.world != null ? gate.world : server.getLevel(gate.dimension);
                if (world != null) fire(world, gate);
                else sendAt(null, gate.position(), gatePacket(gate, "gate_clear"));
            }
        }
        List<Chain> expired = new ArrayList<>();
        for (Iterator<Chain> iterator = CHAINS.values().iterator(); iterator.hasNext();) {
            Chain chain = iterator.next();
            if (!valid(chain)) {
                iterator.remove();
                updateEffect(chain.target);
                send(chain.target, chainPacket(chain, "clear"));
            } else if (chain.charge.expired(clock)) {
                iterator.remove();
                expired.add(chain);
            }
        }
        // Apply outside map iteration: hurt can synchronously trigger death and cleanup events.
        for (Chain chain : expired) {
            if (!valid(chain)) continue;
            updateEffect(chain.target);
            send(chain.target, chainPacket(chain, "collapse"));
            com.chronomancy.sound.ChronoNeedleSounds.playCollapse(chain.target.level(), chain.target, chain.charge.stacks());
            float damage = TimePiercingNeedleSpell.collapseDamage(chain.charge.stacks(), chain.power);
            // Independent casters collapsing in the same tick must not eat each other's damage.
            int previousImmunity = chain.target.invulnerableTime;
            chain.target.invulnerableTime = 0;
            try {
                BacktrackKnockbackSuppression.runWithoutKnockback(chain.target,
                        () -> DamageSources.applyDamage(chain.target, damage, chain.source));
            } finally {
                chain.target.invulnerableTime = Math.max(previousImmunity, chain.target.invulnerableTime);
            }
        }
    }
    private static boolean valid(Chain c) {
        return c.target.isAlive() && !c.target.isRemoved() && c.target.level().dimension().equals(c.key.dimension);
    }
    /**
     * Status effect «Временные иглы» on the target: level = total needle stacks across all chains
     * on it, duration = time until the latest collapse. Removed when no chain is left.
     */
    private static void updateEffect(LivingEntity target) {
        if (target.level().isClientSide) return;
        int total = 0;
        long deadline = clock;
        for (Chain c : CHAINS.values()) {
            if (c.target == target && valid(c)) {
                total += c.charge.stacks();
                deadline = Math.max(deadline, c.charge.deadline());
            }
        }
        var effect = com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_NEEDLES;
        target.removeEffect(effect);
        if (total > 0 && target.isAlive()) {
            int duration = (int) Math.max(1, deadline - clock);
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, duration,
                    Math.min(254, total - 1), false, false, true));
        }
    }
    /** Spawns the needle from the frozen gate position along the frozen trajectory. */
    private static void fire(ServerLevel world, Gate gate) {
        // Damage attribution still remembers the caster while they remain a valid living player here.
        LivingEntity caster = world.getServer().getPlayerList().getPlayer(gate.caster);
        if (caster == null && world.getEntity(gate.caster) instanceof LivingEntity mob && mob.isAlive()) {
            caster = mob; // моб-кастер (Chronomaly)
        }
        var needle = new TemporalNeedleEntity(world, caster, gate.level);
        needle.setPos(gate.position());
        needle.shoot(gate.direction());
        world.addFreshEntity(needle);
        sendAt(world, gate.position(), gatePacket(gate, "fire"));
    }
    private static CompoundTag base(Entity entity, String kind) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind);
        tag.putString("dimension", entity.level().dimension().location().toString());
        tag.putInt("entity", entity.getId());
        tag.putUUID("uuid", entity.getUUID());
        return tag;
    }
    private static TemporalNeedlePayload gatePacket(Gate g, String kind) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind);
        tag.putString("dimension", g.dimension.location().toString());
        tag.putUUID("gate", g.id);
        tag.putUUID("caster", g.caster);
        tag.putLong("deadline", g.deadline);
        vector(tag, "pos", g.position);
        vector(tag, "dir", g.direction);
        tag.putInt("remaining", (int) Math.max(0, g.deadline - clock));
        return new TemporalNeedlePayload(tag);
    }
    private static TemporalNeedlePayload chainPacket(Chain c, String kind) {
        CompoundTag tag = base(c.target, kind);
        tag.putUUID("caster", c.key.caster);
        tag.putInt("max", c.max);
        tag.putInt("remaining", (int) Math.max(0, c.charge.deadline() - clock));
        tag.put("needles", c.needles.copy());
        return new TemporalNeedlePayload(tag);
    }
    /** Absolute hit point + incoming direction; the client turns this into a spark burst. */
    private static TemporalNeedlePayload impactPacket(ServerLevel world, Vec3 position, Vec3 incoming) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "impact");
        tag.putString("dimension", world.dimension().location().toString());
        vector(tag, "pos", position);
        vector(tag, "dir", incoming);
        return new TemporalNeedlePayload(tag);
    }
    public static void vector(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "x", v.x); tag.putDouble(key + "y", v.y); tag.putDouble(key + "z", v.z);
    }
    private static void send(Entity entity, TemporalNeedlePayload packet) {
        if (entity.level().isClientSide) return;
        PacketDistributor.sendToPlayersTrackingEntity(entity, packet);
        if (entity instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, packet);
    }
    /** World-space delivery: reaches every player tracking the gate's chunk, independent of the caster. */
    private static void sendAt(ServerLevel world, Vec3 position, TemporalNeedlePayload packet) {
        if (world == null) return;
        PacketDistributor.sendToPlayersTrackingChunk(world, new net.minecraft.world.level.ChunkPos(BlockPos.containing(position)), packet);
    }
    public static void onTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        for (Chain c : CHAINS.values()) if (c.target == target) PacketDistributor.sendToPlayer(player, chainPacket(c, "stack"));
        // Gates are world-space; a player who begins tracking the caster is nearby, so resend pending gates.
        for (Gate g : GATES) if (g.caster.equals(target.getUUID())) PacketDistributor.sendToPlayer(player, gatePacket(g, "gate"));
    }
    private static void remove(Entity entity) {
        if (entity.level().isClientSide) return;
        CHAINS.values().removeIf(c -> {
            if (c.target != entity) return false;
            if (c.target.isAlive()) c.target.removeEffect(com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_NEEDLES);
            send(entity, chainPacket(c, "clear"));
            return true;
        });
        // Gates are independent of the caster: death/leave/unload of the caster must NOT cancel them.
    }
    public static void onDeath(LivingDeathEvent event) { remove(event.getEntity()); }
    public static void onLeave(EntityLeaveLevelEvent event) { remove(event.getEntity()); }
    public static void onUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CHAINS.values().removeIf(c -> c.key.dimension.equals(level.dimension()));
        GATES.removeIf(g -> g.dimension.equals(level.dimension()));
    }
    public static void onStop(ServerStoppedEvent event) { GATES.clear(); CHAINS.clear(); clock = 0; }
}
