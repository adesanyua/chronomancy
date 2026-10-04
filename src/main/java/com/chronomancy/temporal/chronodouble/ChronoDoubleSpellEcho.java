package com.chronomancy.temporal.chronodouble;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.ChronoDoubleEntity;
import com.chronomancy.registry.ChronoAttributes;
import com.chronomancy.spell.ChronoDoubleSpell;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic ISS spell echo for ChronoDouble.
 *
 * <p>The school's own damage spells already have bespoke, projectile-level echoes (see
 * {@link ChronoDoubleProjectiles}); everything <em>else</em> in Iron's Spellbooks is repeated here by
 * re-running the spell's own server logic from the double's position, so it spawns its real
 * projectiles, beams and effects on its own rather than us faking each one.
 *
 * <p>How the constraints from the design are met:
 * <ul>
 *   <li><b>No mana / no cooldown churn:</b> we call {@link AbstractSpell#onCast} directly. The full
 *       {@code castSpell} pipeline (SpellOnCastEvent posting, mana subtraction, cooldown, client packets,
 *       recast bookkeeping) lives in ISS' casting manager and is deliberately bypassed.</li>
 *   <li><b>No infinite double chain:</b> the echo casts through a dedicated {@link FakePlayer} that is
 *       never registered as a real caster, and we only react to a real player's
 *       {@link SpellOnCastEvent}. A {@link #CASTING} re-entrancy flag also guards a same-tick self-loop.</li>
 *   <li><b>Repeated damage reduced, heals/summons keep full strength:</b> the recast produces the spell's
 *       natural healing/summoning/utility at full value; only damage is scaled, caught generically in
 *       {@link #reduceDamage(SpellDamageEvent)} because that damage is attributed to the fake caster.</li>
 * </ul>
 *
 * <p>Scope: {@link CastType#INSTANT} and {@link CastType#LONG} spells are re-cast. A LONG spell (one
 * that needs preparation) fires for the owner when the cast time has run out — that is when ISS posts
 * {@link SpellOnCastEvent} — so the double does not prepare anything itself: it releases its copy at
 * that same moment, with the pre-cast check (target acquisition) and the cast-complete clean-up run
 * around {@code onCast}. CONTINUOUS spells are a stream of pulses kept alive by ISS' casting state
 * machine and stay visual-only.
 */
public final class ChronoDoubleSpellEcho {

    /** Deterministic per-owner salt so the echo's fake UUID never collides with the real owner's UUID. */
    private static final long UUID_SALT = 0x4368726F6E6F4563L; // "ChronoEc"
    /** fake-caster UUID -> damage fraction currently in effect for that echo (its double's lifetime). */
    private static final Map<UUID, Float> ECHO_CASTERS = new HashMap<>();
    /** Owners whose cast is mid-recast this tick; prevents a same-tick re-entrant echo. */
    private static final java.util.Set<UUID> CASTING = new java.util.HashSet<>();

    /**
     * Spells that move the caster itself somewhere else. The double has no body of its own to move (it
     * casts through a headless fake player), so repeating them does nothing useful and only fails.
     */
    private static final java.util.Set<String> NOT_REPEATED = java.util.Set.of(
            "irons_spellbooks:pocket_dimension", "irons_spellbooks:recall");

    private ChronoDoubleSpellEcho() {}

    /** Resolve the fake caster UUID that belongs to a given real owner. */
    public static UUID casterId(UUID owner) {
        return new UUID(owner.getMostSignificantBits() ^ UUID_SALT, owner.getLeastSignificantBits() + UUID_SALT);
    }

    public static void onSpellOnCast(SpellOnCastEvent event) {
        Player player = event.getEntity();
        // Only a real, server-side player cast is echoed; the fake caster is excluded (blocks recursion).
        if (player.level().isClientSide || !(player instanceof ServerPlayer owner) || owner.isFakePlayer()) {
            return;
        }
        if (event.getSpellId() == null) return;
        ChronoDoubleEntity echo = ChronoDoubleManager.active(owner);
        if (echo == null) return;

        AbstractSpell spell = SpellRegistry.getSpell(event.getSpellId());
        if (spell == null || spell == SpellRegistry.none()) return;
        // Our own Chronomancy spells have bespoke echoes or dangerous global effects (World Stop) - never re-cast here.
        if (ChronomancyMod.MODID.equals(spell.getSpellResource().getNamespace())) return;
        // INSTANT and LONG spells complete inside a single onCast; continuous ones need the ISS casting state machine.
        if (spell.getCastType() != CastType.INSTANT && spell.getCastType() != CastType.LONG) return;
        if (NOT_REPEATED.contains(spell.getSpellId())) return;

        if (!CASTING.add(owner.getUUID())) return; // already echoing this tick for this owner
        try {
            castFrom(echo, owner, spell, event.getSpellLevel());
        } catch (Throwable t) {
            // Never let a bespoke third-party spell break the owner's real cast.
            ChronomancyMod.LOGGER.warn("ChronoDouble spell echo failed for {}", event.getSpellId(), t);
        } finally {
            CASTING.remove(owner.getUUID());
        }
    }

    private static void castFrom(ChronoDoubleEntity echo, ServerPlayer owner, AbstractSpell spell, int spellLevel) {
        if (!(echo.level() instanceof ServerLevel world)) return;
        FakePlayer caster = casterFor(owner, world);

        // Stand at the double, aim exactly like the owner so the spell fires parallel from the mirrored side.
        caster.setPos(echo.position());
        caster.setYRot(owner.getYRot());
        caster.setXRot(owner.getXRot());
        caster.yBodyRot = owner.yBodyRot;
        caster.yHeadRot = owner.yHeadRot;
        caster.setDeltaMovement(Vec3.ZERO);

        // The clone casts with the owner's spell power, so its echoed spells scale like the owner's would.
        inheritPower(owner, caster);

        // Register the reduced-damage fraction BEFORE casting so any synchronous onCast damage is scaled too.
        ECHO_CASTERS.put(caster.getUUID(), ChronoDoubleSpell.damageFraction(echo.getSpellLevel(), owner));
        MagicData magic = MagicData.getPlayerMagicData(caster);
        try {
            release(owner, caster, world, spell, spellLevel, magic);
        } finally {
            adoptSummons(caster, owner, world);
        }
    }

    /**
     * Whatever the double summoned belongs to the owner. The fake caster is not an entity of the world:
     * left as the summoner, it would leave the summons ownerless - standing idle until they expire.
     */
    private static void adoptSummons(FakePlayer caster, ServerPlayer owner, ServerLevel world) {
        try {
            for (UUID id : new java.util.ArrayList<>(
                    io.redspace.ironsspellbooks.capabilities.magic.SummonManager.getSummons(caster))) {
                Entity summon = world.getEntity(id);
                if (summon != null) {
                    io.redspace.ironsspellbooks.capabilities.magic.SummonManager.setOwner(summon, owner);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            ChronomancyMod.LOGGER.debug("ChronoDouble: could not hand the double's summons to the owner", e);
        }
    }

    private static void release(ServerPlayer owner, FakePlayer caster, ServerLevel world, AbstractSpell spell,
                                int spellLevel, MagicData magic) {
        if (spell.getCastType() != CastType.LONG) {
            spell.onCast(world, spellLevel, caster, CastSource.NONE, magic);
            return;
        }
        // A spell with a cast time. The owner has already prepared it; the double releases its copy now.
        // Such spells usually pick their target in the pre-cast check and read it back in onCast, so the
        // double first looks at whatever the owner is targeting and runs the same check for itself.
        aimAtOwnersTarget(owner, caster, world);
        magic.resetCastingState();
        if (!spell.checkPreCastConditions(world, spellLevel, caster, magic)) {
            magic.resetCastingState();
            return; // nothing for the double to cast at (no target in its line of sight)
        }
        magic.initiateCast(spell, spellLevel, 0, CastSource.NONE, "mainhand");
        try {
            spell.onCast(world, spellLevel, caster, CastSource.NONE, magic);
            spell.onServerCastComplete(world, spellLevel, caster, magic, false);
        } finally {
            magic.resetCastingState();
        }
    }

    /**
     * Turn the double towards the entity the owner's spell is locked on (ISS keeps it in the owner's
     * cast data until the cast completes), so a targeted spell cast from the double's side finds the
     * same target instead of looking past it along a parallel line.
     */
    private static void aimAtOwnersTarget(ServerPlayer owner, FakePlayer caster, ServerLevel world) {
        if (!(MagicData.getPlayerMagicData(owner).getAdditionalCastData()
                instanceof io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData targetData)) {
            return;
        }
        LivingEntity target = targetData.getTarget(world);
        if (target == null || !target.isAlive()) {
            return;
        }
        Vec3 from = caster.getEyePosition();
        Vec3 to = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0D / Math.PI));
        caster.setYRot(yaw);
        caster.setXRot(pitch);
        caster.yBodyRot = yaw;
        caster.yHeadRot = yaw;
    }

    /** Copy the owner's spell-power attributes onto the fake caster (generic + Chronomancy). */
    private static void inheritPower(ServerPlayer owner, FakePlayer caster) {
        copyAttribute(owner, caster, AttributeRegistry.SPELL_POWER);
        copyAttribute(owner, caster, ChronoAttributes.CHRONOMANCY_SPELL_POWER);
    }

    private static void copyAttribute(LivingEntity from, LivingEntity to, Holder<Attribute> attribute) {
        AttributeInstance instance = to.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(from.getAttributeValue(attribute));
    }

    private static FakePlayer casterFor(ServerPlayer owner, ServerLevel world) {
        GameProfile profile = new GameProfile(casterId(owner.getUUID()), fakeName(owner.getName().getString()));
        return FakePlayerFactory.get(world, profile);
    }

    private static String fakeName(String owner) {
        String n = owner + "~";
        return n.length() <= 16 ? n : n.substring(0, 16);
    }

    /** Generic damage-reduction hook: scales only the spell damage the echo caster deals. */
    public static void reduceDamage(SpellDamageEvent event) {
        Entity src = event.getSpellDamageSource().getEntity();
        if (src == null) return;
        Float fraction = ECHO_CASTERS.get(src.getUUID());
        if (fraction != null && fraction < 1.0f) {
            event.setAmount(event.getAmount() * fraction);
        }
    }

    /** Stop scaling and release the bookkeeping when a double is removed or expires. */
    public static void releaseFor(UUID owner) {
        ECHO_CASTERS.remove(casterId(owner));
        CASTING.remove(owner);
    }

    public static void clear() {
        ECHO_CASTERS.clear();
        CASTING.clear();
    }
}
