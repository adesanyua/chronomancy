package com.chronomancy.temporal.chronodouble;

import com.chronomancy.entity.*;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.spell.ChronoDoubleSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.*;

/** Real, one-generation echoes for bow/crossbow arrows and Chronomancy projectile spells.
 * Copies a launch snapshot, NOT a spell cast: no mana/cooldown recursion, ammo duplication or extra on-hit bonus.
 */
public final class ChronoDoubleProjectiles {
    private static final String ECHO = "chronomancy_double_projectile";
    private static final String CHAIN = "chronomancy_double_chain";
    private static final String FRACTION = "chronomancy_double_fraction";
    private static long clock;
    private record Shot(ServerLevel level, UUID owner, UUID echo, EntityType<?> type, CompoundTag data,
                        Vec3 aim, double speed, double height, float fraction, long due, boolean arrow) {}
    private static final List<Shot> SHOTS = new ArrayList<>();
    private ChronoDoubleProjectiles() {}
    public static boolean isEcho(Projectile projectile) { return projectile.getPersistentData().getBoolean(ECHO); }
    public static UUID chainOwner(Projectile projectile, LivingEntity owner) {
        CompoundTag tag = projectile.getPersistentData();
        return tag.hasUUID(CHAIN) ? tag.getUUID(CHAIN) : owner.getUUID();
    }
    public static double fraction(Projectile projectile) {
        return isEcho(projectile) ? Math.clamp(projectile.getPersistentData().getDouble(FRACTION), 0, 1) : 1;
    }
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof Projectile projectile) || isEcho(projectile) || projectile.tickCount != 0) return;
        boolean arrow = projectile instanceof Arrow || projectile instanceof SpectralArrow;
        // Temporal Needle is echoed by its own preparation rune / gate at the double (see TemporalNeedleManager),
        // so it can stack onto the owner's chain rather than being copied as a parallel weakened projectile.
        if (!arrow && !(projectile instanceof BacktrackBoltEntity) && !(projectile instanceof TimeDilationOrbEntity)) return;
        if (!(projectile.getOwner() instanceof ServerPlayer owner)) return;
        ChronoDoubleEntity echo = ChronoDoubleManager.active(owner);
        if (echo == null) return;
        Vec3 velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() < 1e-6) return;
        Vec3 from = projectile.position();
        Vec3 to = from.add(velocity.normalize().scale(64));
        to = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner)).getLocation();
        EntityHitResult target = ProjectileUtil.getEntityHitResult(level, projectile, from, to,
                new AABB(from, to).inflate(1), e -> e instanceof LivingEntity && e != owner && e.isAlive()
                        && !e.isSpectator() && !owner.isAlliedTo(e), 0.1f);
        if (target != null) to = target.getLocation();
        CompoundTag data = projectile.saveWithoutId(new CompoundTag());
        data.remove("UUID");
        data.remove("Passengers");
        if (projectile instanceof SpectralArrow) data.putBoolean("ChronoSpectral", true);
        SHOTS.add(new Shot(level, owner.getUUID(), echo.getUUID(), projectile.getType(), data,
                to, velocity.length(), projectile.getY() - owner.getY(),
                ChronoDoubleSpell.damageFraction(echo.getSpellLevel(), owner), clock + 2, arrow));
    }
    public static void tick() {
        clock++;
        List<Shot> due = new ArrayList<>();
        SHOTS.removeIf(s -> { if (clock < s.due) return false; due.add(s); return true; });
        for (Shot shot : due) fire(shot);
    }
    private static void fire(Shot shot) {
        ServerPlayer owner = shot.level.getServer().getPlayerList().getPlayer(shot.owner);
        if (owner == null) return;
        ChronoDoubleEntity echo = ChronoDoubleManager.active(owner);
        if (echo == null || !echo.getUUID().equals(shot.echo) || echo.level() != shot.level) return;
        var created = shot.arrow ? ChronoEntityTypeRegistry.CHRONO_DOUBLE_ARROW.get().create(shot.level) : shot.type.create(shot.level);
        if (!(created instanceof Projectile copy)) return;
        copy.load(shot.data.copy());
        copy.setUUID(UUID.randomUUID());
        copy.setOwner(owner);
        copy.getPersistentData().putBoolean(ECHO, true);
        copy.getPersistentData().putUUID(CHAIN, echo.getUUID());
        copy.getPersistentData().putDouble(FRACTION, shot.fraction);
        Vec3 origin = echo.position().add(0, shot.height, 0);
        if (!shot.level.noCollision(new AABB(origin.subtract(.05, .05, .05), origin.add(.05, .05, .05)))) return;
        Vec3 motion = shot.aim.subtract(origin).normalize().scale(shot.speed);
        copy.setPos(origin);
        copy.setDeltaMovement(motion);
        copy.setYRot((float)Math.toDegrees(Math.atan2(motion.x, motion.z)));
        copy.setXRot((float)Math.toDegrees(Math.atan2(motion.y, motion.horizontalDistance())));
        copy.yRotO = copy.getYRot(); copy.xRotO = copy.getXRot();
        if (copy instanceof AbstractArrow arrow) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            arrow.setBaseDamage(arrow.getBaseDamage() * shot.fraction);
        } else if (copy instanceof BacktrackBoltEntity bolt) {
            bolt.setDamage(bolt.getDamage() * shot.fraction);
            bolt.setBonusPerBlock(bolt.getBonusPerBlock() * shot.fraction);
        } else if (copy instanceof TimeDilationOrbEntity) {
            // Utility echoes use reduced field lifetime; overlapping fields do not multiply slow strength.
            CompoundTag state = copy.saveWithoutId(new CompoundTag());
            state.putInt("FieldLifetime", Math.max(1, Math.round(state.getInt("FieldLifetime") * shot.fraction)));
            copy.load(state);
        }
        // Needle power is scaled only at its own independent chain's collapse, not on every stack.
        if (shot.level.addFreshEntity(copy) && shot.arrow) {
            shot.level.playSound(null, origin.x, origin.y, origin.z, com.chronomancy.registry.ChronoSounds.PROJECTILE_LAUNCH.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.55f, 1.25f);
        }
    }
    public static void removeFor(UUID owner) { SHOTS.removeIf(s -> s.owner.equals(owner)); }
    public static void unload(ServerLevel level) { SHOTS.removeIf(s -> s.level == level); }
    public static void clear() { SHOTS.clear(); clock = 0; }
}
