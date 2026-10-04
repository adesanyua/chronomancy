package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.RewindAfterimageEntity;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.network.PhaseStepPayload;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.PhaseDodge;
import com.chronomancy.temporal.history.RewindPlaybackManager;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.AnimationHolder;
import io.redspace.ironsspellbooks.api.util.Utils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Time Walk — шаг сквозь время вперёд: тело кастера «тянется» по взгляду (тот же эффект, что у
 * Rewind, только вперёд) и на несколько секунд остаётся фазированным — снаряды проходят насквозь.
 *
 * <p>Обычное: 7 блоков и 3 с фазы; каждый уровень редкости +2 блока и +0.5 с; сила заклинаний
 * хрономантии умножает и дальность, и длительность.
 */
public class TimeWalkSpell extends AbstractSpell {

    public static final int MAX_LEVEL = 5;
    public static final double BASE_RANGE = 7.0;
    public static final double RANGE_PER_LEVEL = 2.0;
    public static final double MAX_RANGE = 30.0;
    public static final double BASE_PHASE_SECONDS = 3.0;
    public static final double PHASE_SECONDS_PER_LEVEL = 0.5;
    public static final double MAX_PHASE_SECONDS = 10.0;

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_walk");
    private final DefaultConfig config = new DefaultConfig()
            .setMinRarity(SpellRarity.COMMON)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
            .setMaxLevel(MAX_LEVEL)
            .setCooldownSeconds(10)
            .build();

    public TimeWalkSpell() {
        this.baseManaCost = 40;
        this.manaCostPerLevel = 8;
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;
        this.castTime = 0;
    }

    @Override public ResourceLocation getSpellResource() { return ID; }
    @Override public DefaultConfig getDefaultConfig() { return config; }
    @Override public CastType getCastType() { return CastType.INSTANT; }
    @Override public AnimationHolder getCastStartAnimation() { return SpellAnimations.ANIMATION_INSTANT_CAST; }

    private static double power(LivingEntity caster) {
        try {
            return Math.max(0.1, ChronoSchools.totalSpellPower(caster));
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    public static double range(int level, LivingEntity caster) {
        return Math.min(MAX_RANGE, (BASE_RANGE + RANGE_PER_LEVEL * Math.max(0, level - 1)) * power(caster));
    }

    public static double phaseSeconds(int level, LivingEntity caster) {
        return Math.min(MAX_PHASE_SECONDS,
                (BASE_PHASE_SECONDS + PHASE_SECONDS_PER_LEVEL * Math.max(0, level - 1)) * power(caster));
    }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("ui.irons_spellbooks.distance", Utils.stringTruncation(range(spellLevel, caster), 1)),
                Component.translatable("spell.chronomancy.time_walk.phase",
                        Utils.stringTruncation(phaseSeconds(spellLevel, caster), 1) + "s"),
                Component.translatable("spell.chronomancy.time_walk.pass"));
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster, CastSource source, MagicData data) {
        if (level instanceof ServerLevel serverLevel && caster instanceof ServerPlayer player && player.isAlive()) {
            walk(serverLevel, player, spellLevel);
        } else if (level instanceof ServerLevel && caster instanceof com.chronomancy.entity.ChronoMobCaster mob && caster.isAlive()) {
            mob.mobTimeWalk(spellLevel); // Часовщик: шаг к цели или от неё
        }
        super.onCast(level, spellLevel, caster, source, data);
    }

    /**
     * Куда встать, если взгляд упёрся в блок: на его верх (если над ним есть место), иначе — перед
     * гранью, опустившись до пола. {@code null} — подходящего места нет.
     */
    private static Vec3 standSpot(ServerLevel level, ServerPlayer player, net.minecraft.world.phys.BlockHitResult hit,
                                  double range) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        net.minecraft.core.Direction face = hit.getDirection();
        Vec3 at = hit.getLocation();
        List<Vec3> candidates = new ArrayList<>();
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        double top = pos.getY() + (shape.isEmpty() ? 1.0 : shape.max(net.minecraft.core.Direction.Axis.Y));
        double cx = Math.max(pos.getX() + 0.3, Math.min(pos.getX() + 0.7, at.x));
        double cz = Math.max(pos.getZ() + 0.3, Math.min(pos.getZ() + 0.7, at.z));
        if (face == net.minecraft.core.Direction.UP) {
            candidates.add(new Vec3(at.x, top, at.z));
        }
        candidates.add(new Vec3(cx, top, cz)); // на сам блок
        if (face.getAxis().isHorizontal()) {
            // перед гранью: на полу у стены
            double off = player.getBbWidth() * 0.5 + 0.05;
            Vec3 front = at.add(face.getStepX() * off, 0, face.getStepZ() * off);
            for (int dy = 0; dy >= -3; dy--) {
                candidates.add(new Vec3(front.x, Math.floor(front.y) + dy, front.z));
            }
        } else if (face == net.minecraft.core.Direction.DOWN) {
            candidates.add(new Vec3(at.x, at.y - player.getBbHeight() - 0.01, at.z)); // под потолком
        }
        Vec3 start = player.position();
        for (Vec3 c : candidates) {
            AABB box = player.getDimensions(player.getPose()).makeBoundingBox(c);
            if (c.distanceTo(start) <= range + 1.5 && level.noCollision(player, box)
                    && !level.containsAnyLiquid(box)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Путь шага без прохода сквозь блоки: прямо, а если прямая задевает угол — сначала вверх и
     * потом вперёд (на возвышение) или вперёд и потом вниз (с обрыва).
     */
    private static List<Vec3> route(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 dest) {
        List<Vec3> straight = List.of(start, dest);
        if (clear(level, player, straight)) {
            return straight;
        }
        Vec3 corner = dest.y > start.y ? new Vec3(start.x, dest.y, start.z) : new Vec3(dest.x, start.y, dest.z);
        List<Vec3> bent = List.of(start, corner, dest);
        return clear(level, player, bent) ? bent : straight;
    }

    private static boolean clear(ServerLevel level, ServerPlayer player, List<Vec3> points) {
        var dims = player.getDimensions(player.getPose());
        for (int k = 1; k < points.size(); k++) {
            Vec3 a = points.get(k - 1), b = points.get(k);
            int n = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 0.25));
            for (int i = 1; i <= n; i++) {
                if (!level.noCollision(player, dims.makeBoundingBox(a.lerp(b, i / (double) n)).deflate(0.01))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void walk(ServerLevel level, ServerPlayer player, int spellLevel) {
        double range = range(spellLevel, player);
        int phaseTicks = (int) Math.round(phaseSeconds(spellLevel, player) * 20.0);
        Vec3 start = player.position();
        Vec3 dir = player.getLookAngle().normalize();
        // Взгляд упирается в блок в пределах дальности — встаём на него (или перед ним).
        Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
                eye.add(dir.scale(range)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        Vec3 dest = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? standSpot(level, player, hit, range) : null;
        if (dest == null) {
            // Точка в воздухе: шагаем по взгляду, пока не упрёмся.
            dest = start;
            AABB box = player.getBoundingBox();
            for (double s = 0.25; s <= range + 1.0e-6; s += 0.25) {
                Vec3 offset = dir.scale(s);
                if (!level.noCollision(player, box.move(offset))) {
                    break;
                }
                dest = start.add(offset);
            }
        }

        PhaseDodge.phase(player, phaseTicks);
        double dist = dest.distanceTo(start);
        level.playSound(null, start.x, start.y + 1, start.z, ChronoSounds.TEMPORAL_REWIND.get(), SoundSource.PLAYERS,
                0.9F, 1.6F);
        if (dist < 0.75 || player.isPassenger() || RewindPlaybackManager.isPlaying(player.getUUID())) {
            return; // некуда шагнуть — остаётся только фаза
        }

        // образ, оставленный в точке шага (как у Rewind), и «тянущееся» вперёд тело
        RewindAfterimageEntity afterimage = new RewindAfterimageEntity(ChronoEntityTypeRegistry.REWIND_AFTERIMAGE.get(), level);
        afterimage.configure(start.x, start.y, start.z, player.getYRot(), player.getXRot());
        level.addFreshEntity(afterimage);

        int ticks = Math.max(3, Math.min(8, (int) Math.round(dist / 2.5)));
        List<Vec3> route = route(level, player, start, dest);
        List<Vec3> path = new ArrayList<>();
        for (int k = 1; k < route.size(); k++) {
            Vec3 a = route.get(k - 1), b = route.get(k);
            int samples = Math.max(2, (int) Math.ceil(a.distanceTo(b)));
            for (int i = k == 1 ? 0 : 1; i <= samples; i++) {
                path.add(a.lerp(b, i / (double) samples));
            }
        }
        ChronoNetwork.broadcastPhaseStep(player, PhaseStepPayload.TIME_WALK, ticks, path);
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), start.x, start.y + 1, start.z, 1, 0, 0, 0, 0);
        Vec3 carry = dir.multiply(0.25, 0.1, 0.25);
        RewindPlaybackManager.start(player, route, player.getYRot(), player.getXRot(), carry, ticks,
                () -> {
                    player.fallDistance = 0.0F;
                    level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                            player.getX(), player.getY() + 1, player.getZ(), 6, 0.3, 0.5, 0.3, 0.03);
                    level.playSound(null, player.getX(), player.getY() + 1, player.getZ(),
                            ChronoSounds.TEMPORAL_RELEASE.get(), SoundSource.PLAYERS, 0.7F, 1.5F);
                });
    }
}
