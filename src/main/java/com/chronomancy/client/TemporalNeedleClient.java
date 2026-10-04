package com.chronomancy.client;

import com.chronomancy.network.TemporalNeedlePayload;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import java.util.*;

/**
 * Lightweight stack attachments and pending gates. No ticking decorative entities or separate
 * particle engine. A gate is a fully world-space, caster-independent telegraph: it is pinned to
 * the position/direction frozen at cast time and never follows the caster.
 */
public final class TemporalNeedleClient {
    /** A frozen gate snapshot. Position/direction are absolute; nothing here reads the caster. */
    private static final class Gate {
        final Vec3 position;
        final Vec3 direction;
        final long received;
        final long fireTick;
        Gate(Vec3 position, Vec3 direction, long fireTick) {
            this.position = position; this.direction = direction; this.received = clock; this.fireTick = fireTick;
        }
        float progress(float partial) {
            long span = Math.max(1, fireTick - received);
            return Mth.clamp((clock + partial - received) / (float) span, 0, 1);
        }
    }
    private static final Map<UUID, Gate> GATES = new HashMap<>();
    /** Заряд одной цепочки (один кастер) на цели. */
    private static final class Charge {
        int stacks;
        int max = 1;
        long changed;
    }
    /** Цель -> (кастер цепочки -> заряд). Глитч цели растёт с самым сильным зарядом на ней. */
    private static final Map<Integer, Map<UUID, Charge>> STACKS = new HashMap<>();
    private static long clock;
    private static ClientLevel world;
    private static final RenderType GATE_TYPE = RenderType.entityTranslucentEmissive(ResourceLocation.fromNamespaceAndPath(
            "chronomancy", "textures/entity/time_piercing_gate.png"));
    private static final int INDICATOR_LENGTH = 5; // forward segments along the frozen trajectory
    private TemporalNeedleClient() {}
    public static void clear() {
        GATES.clear(); STACKS.clear(); clock = 0; world = null;
    }
    private static void checkWorld() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != world) { clear(); world = current; }
    }
    public static void apply(TemporalNeedlePayload packet) {
        checkWorld();
        if (world == null) return;
        CompoundTag tag = packet.data();
        if (!tag.getString("dimension").equals(world.dimension().location().toString())) return;
        String kind = tag.getString("kind");
        if (kind.equals("impact")) { impactBurst(vector(tag, "pos"), vector(tag, "dir")); return; }
        if (tag.hasUUID("gate")) {
            UUID id = tag.getUUID("gate");
            if (kind.equals("gate")) {
                int remaining = tag.getInt("remaining");
                GATES.put(id, new Gate(vector(tag, "pos"), vector(tag, "dir").normalize(), clock + Math.max(1, remaining)));
            } else {
                Gate gate = GATES.remove(id);
                Vec3 position = gate != null ? gate.position : vector(tag, "pos");
                if (kind.equals("fire") && gate != null) { flash(position); decay(position); }
            }
        } else if (tag.hasUUID("caster")) {
            int targetId = tag.getInt("entity");
            UUID caster = tag.getUUID("caster");
            if (kind.equals("clear")) {
                removeCharge(targetId, caster);
            } else if (kind.equals("collapse")) {
                // No stuck needles are rendered; the charge bursts apart with force scaled by stacks.
                int stacks = tag.getList("needles", 10).size();
                removeCharge(targetId, caster);
                Entity target = entity(tag);
                if (target != null) collapseBurst(target.position().add(0, target.getBbHeight() * .5, 0), stacks);
            } else {
                Charge charge = STACKS.computeIfAbsent(targetId, k -> new HashMap<>()).computeIfAbsent(caster, k -> new Charge());
                int stacks = tag.getList("needles", 10).size();
                if (stacks != charge.stacks) charge.changed = clock;
                charge.stacks = stacks;
                charge.max = Math.max(1, tag.getInt("max"));
            }
        }
    }
    private static Entity entity(CompoundTag data) {
        if (world == null) return null;
        Entity entity = world.getEntity(data.getInt("entity"));
        return entity != null && entity.getUUID().equals(data.getUUID("uuid")) ? entity : null;
    }
    private static Vec3 vector(CompoundTag data, String prefix) {
        return new Vec3(data.getDouble(prefix + "x"), data.getDouble(prefix + "y"), data.getDouble(prefix + "z"));
    }
    private static void removeCharge(int targetId, UUID caster) {
        Map<UUID, Charge> charges = STACKS.get(targetId);
        if (charges == null) return;
        charges.remove(caster);
        if (charges.isEmpty()) STACKS.remove(targetId);
    }

    /** Цели, на которых сейчас есть заряд игл. */
    public static java.util.Set<Integer> chargedIds() {
        return STACKS.keySet();
    }

    /** Самый сильный заряд на цели, 0..1 (стаки / предел цепочки). */
    public static float chargeFraction(int entityId) {
        Map<UUID, Charge> charges = STACKS.get(entityId);
        if (charges == null) return 0f;
        float best = 0f;
        for (Charge c : charges.values()) best = Math.max(best, c.stacks / (float) c.max);
        return Mth.clamp(best, 0f, 1f);
    }

    /** Charge strength for the copper/gold body tint (fallback without shaders): each stack contributes double. */
    static float stackTintStrength(int entityId) {
        return Mth.clamp(2f * chargeFraction(entityId), 0f, 1f);
    }
    /** Compact, bright spark at the exact impact point, biased along the incoming direction. */
    private static void impactBurst(Vec3 pos, Vec3 dir) {
        var random = world.random;
        Vec3 n = dir.lengthSqr() > 1e-6 ? dir.normalize() : new Vec3(0, 1, 0);
        for (int i = 0; i < 7; i++) {
            double back = -0.08 - random.nextDouble() * 0.05;
            double spread = (random.nextDouble() - .5) * 0.12;
            Vec3 v = n.scale(back).add(spread, spread + random.nextDouble() * 0.05, spread);
            var type = i % 3 == 0 ? ChronoParticleRegistry.TEMPORAL_CRACK.get()
                    : i % 3 == 1 ? ChronoParticleRegistry.TEMPORAL_SPARK.get() : ChronoParticleRegistry.TEMPORAL_MOTE.get();
            world.addParticle(type, pos.x, pos.y, pos.z, v.x, v.y, v.z);
        }
    }
    /** Damage scatter: more and faster particles fly apart the higher the stack count. */
    private static void collapseBurst(Vec3 center, int stacks) {
        var random = world.random;
        int count = 6 + Math.min(42, stacks * 4);
        double power = 0.10 + Math.min(0.45, stacks * 0.03);
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2, u = random.nextDouble() * 2 - 1, s = Math.sqrt(Math.max(0, 1 - u * u));
            Vec3 unit = new Vec3(Math.cos(a) * s, u, Math.sin(a) * s);
            Vec3 v = unit.scale(power * (0.4 + random.nextDouble()));
            var type = i % 4 == 0 ? ChronoParticleRegistry.TEMPORAL_CRACK.get()
                    : i % 4 == 1 ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                    : i % 4 == 2 ? ChronoParticleRegistry.TEMPORAL_MOTE.get() : ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get();
            double rad = 0.2 + random.nextDouble() * 0.4;
            Vec3 p = center.add(unit.scale(rad));
            world.addParticle(type, p.x, p.y, p.z, v.x, v.y + 0.05, v.z);
        }
    }
    /** Short, bright flash the instant the gate releases the needle. */
    private static void flash(Vec3 center) {
        var random = world.random;
        for (int i = 0; i < 8; i++) {
            Vec3 offset = new Vec3(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5).scale(.3);
            world.addParticle(i % 2 == 0 ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                    : ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    center.x + offset.x, center.y + offset.y, center.z + offset.z, offset.x, offset.y, offset.z);
        }
    }
    /** The rune collapses into falling sand grains once it has fired. */
    private static void decay(Vec3 center) {
        var random = world.random;
        for (int i = 0; i < 16; i++) {
            double angle = random.nextDouble() * Math.PI * 2, radius = .2 + random.nextDouble() * .25;
            world.addParticle(ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(),
                    center.x + Math.cos(angle) * radius, center.y + (random.nextDouble() - .5) * .3,
                    center.z + Math.sin(angle) * radius, (random.nextDouble() - .5) * .02, -.03 - random.nextDouble() * .03,
                    (random.nextDouble() - .5) * .02);
        }
    }
    public static void tick(ClientTickEvent.Post event) {
        checkWorld();
        if (world == null || Minecraft.getInstance().isPaused()) return;
        clock++;
        // Drop charge for targets that died or left view; re-arrival is repopulated by tracking packets.
        STACKS.keySet().removeIf(id -> {
            Entity e = world.getEntity(id);
            return e == null || !e.isAlive();
        });
        // Safety net: a missed fire packet must not strand a rune, but never re-fires a live gate.
        GATES.entrySet().removeIf(e -> clock > e.getValue().fireTick + 20);
        for (Gate gate : GATES.values()) {
            float progress = gate.progress(0f);
            world.addParticle(clock % 4 == 0 ? ChronoParticleRegistry.TEMPORAL_RUNE.get()
                    : ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    gate.position.x, gate.position.y, gate.position.z, 0, .005, 0);
            // Gold motes drift forward along the frozen trajectory, hinting the incoming firing line.
            Vec3 head = gate.position.add(gate.direction.scale(.2 + progress * .7));
            world.addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(), head.x, head.y, head.z,
                    gate.direction.x * .02, gate.direction.y * .02, gate.direction.z * .02);
        }
    }
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || world == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poses = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        for (Gate gate : GATES.values()) {
            float progress = gate.progress(partial);
            Vec3 p = gate.position.subtract(camera);
            poses.pushPose();
            poses.translate(p.x, p.y, p.z);
            // Rune plane is perpendicular to the trajectory: rotate the sprite's +Z normal onto the aim dir.
            poses.mulPose(new Quaternionf().rotationTo(0, 0, 1, (float) gate.direction.x, (float) gate.direction.y, (float) gate.direction.z));
            poses.mulPose(Axis.ZP.rotationDegrees(progress * 120f));
            TemporalProjectileSprite.drawAssembling(poses, buffers.getBuffer(GATE_TYPE), .65f, progress);
            // Short directional indicator: small forward segments that fill in as the rune charges.
            poses.mulPose(Axis.ZP.rotationDegrees(-progress * 120f));
            for (int i = 0; i < INDICATOR_LENGTH; i++) {
                float at = (i + 1) / (float) INDICATOR_LENGTH;
                if (progress < at - .12f) continue; // reveal progressively, a hair before full charge
                float fade = Mth.clamp((progress - (at - .12f)) / .12f, 0, 1);
                poses.pushPose();
                poses.translate(0, 0, .35f + at * .65f); // local +Z is the firing direction
                poses.scale(.06f * fade, .06f * fade, 1);
                TemporalProjectileSprite.draw(poses, buffers.getBuffer(GATE_TYPE), 1);
                poses.popPose();
            }
            poses.popPose();
        }
        buffers.endBatch(GATE_TYPE);
        renderNumerals(mc, poses, camera, partial);
    }

    /**
     * Римская цифра заряда над целью — видна только тому, кто наложил иглы (своя цепочка).
     * Золото с тёмной обводкой, при новом стаке цифра коротко «подпрыгивает».
     */
    private static void renderNumerals(Minecraft mc, PoseStack poses, Vec3 camera, float partial) {
        if (mc.player == null || STACKS.isEmpty()) return;
        UUID me = mc.player.getUUID();
        var buffers = mc.renderBuffers().bufferSource();
        boolean drawn = false;
        for (Map.Entry<Integer, Map<UUID, Charge>> entry : STACKS.entrySet()) {
            Charge charge = entry.getValue().get(me);
            if (charge == null || charge.stacks <= 0) continue;
            Entity target = world.getEntity(entry.getKey());
            if (target == null || target == mc.player || target.distanceToSqr(mc.player) > 48 * 48) continue;
            double x = Mth.lerp(partial, target.xOld, target.getX()) - camera.x;
            double y = Mth.lerp(partial, target.yOld, target.getY()) + target.getBbHeight() + 0.55 - camera.y;
            double z = Mth.lerp(partial, target.zOld, target.getZ()) - camera.z;
            float since = clock - charge.changed + partial;
            float pop = since < 6 ? 1f + 0.45f * (1f - since / 6f) : 1f;
            float full = charge.stacks / (float) charge.max;
            String text = roman(charge.stacks);
            poses.pushPose();
            poses.translate(x, y, z);
            poses.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            float s = 0.03f * pop;
            poses.scale(s, -s, s);
            var font = mc.font;
            float w = font.width(text);
            // чем ближе к пределу, тем ближе цифра к голубому свечению фазы
            int color = full >= 1f ? 0xA8DCFF : 0xFFD36B;
            font.drawInBatch8xOutline(net.minecraft.network.chat.Component.literal(text).getVisualOrderText(),
                    -w / 2f, 0f, color, 0x2A1606, poses.last().pose(), buffers, 0xF000F0);
            poses.popPose();
            drawn = true;
        }
        if (drawn) buffers.endBatch();
    }

    /** 1 -> I, 4 -> IV, 9 -> IX, 14 -> XIV ... */
    static String roman(int n) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length && n > 0; i++) {
            while (n >= values[i]) {
                sb.append(symbols[i]);
                n -= values[i];
            }
        }
        return sb.toString();
    }
}
