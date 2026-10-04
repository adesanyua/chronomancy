package com.chronomancy.client.particle;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.client.BorrowedFutureClientState;
import com.chronomancy.client.RewindTrailClientState;
import com.chronomancy.client.RewindTrailHandler;
import com.chronomancy.client.TemporalStasisClientState;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.borrowed.BorrowedFuturePhase;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Частицы хрономантии в стиле «фазирования сквозь время» (смешанный набор):
 * <ul>
 *   <li><b>осколки</b> — удары и попадания ({@code temporal_spark}, {@code temporal_crack});</li>
 *   <li><b>пыль фазы с эхом</b> — фон, ауры, следы ({@code temporal_mote}, {@code temporal_sand_grain},
 *       ауры стазиса и Borrowed Future, медная пыль копий Rift — {@code temporal_copper});</li>
 *   <li><b>мини-циферблаты</b> — касты и вспышки ({@code temporal_rune}, приход Rewind, снятие стазиса).</li>
 * </ul>
 * Все рисуются одним атласом и одним core-шейдером {@code chronomancy_particle}: переливание
 * синий → голубой → золото, растворение по пикселям со светящимся краем, аддитивное свечение —
 * тот же язык, что у оболочек и растворения сущностей. Один батч на слой, бюджет спавна на тик.
 */
public final class ChronoParticles {

    private static final ResourceLocation ATLAS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "textures/particle/chrono_atlas.png");
    private static final ResourceLocation PARTICLE_SHADER =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chronomancy_particle");

    /** Потолок частиц, порождаемых клиентскими эффектами за тик (ауры, Rewind, вспышки). */
    private static final int MAX_SPAWNS_PER_TICK = 64;
    private static final double VIEW_DIST_SQR = 576.0;
    private static int spawnedThisTick;

    /** Позиции замороженных сущностей с прошлого тика — чтобы поймать момент снятия стазиса. */
    private static final Map<Integer, Vec3> STASIS_SEEN = new HashMap<>();

    @Nullable
    private static ShaderInstance shader;
    private static boolean shaderTried;

    /** Основной слой: синий/золотой. */
    public static final ParticleRenderType SHEET = new ChronoSheet(false, "CHRONO_PHASE");
    /** Медная пыль копий Rift — тот же шейдер с медной палитрой. */
    public static final ParticleRenderType COPPER_SHEET = new ChronoSheet(true, "CHRONO_PHASE_COPPER");

    private ChronoParticles() {
    }

    private record ChronoSheet(boolean copper, String label) implements ParticleRenderType {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            ShaderInstance phase = currentShader();
            textureManager.getTexture(ATLAS_TEXTURE).setFilter(false, false);
            RenderSystem.setShaderTexture(0, ATLAS_TEXTURE);
            RenderSystem.enableBlend();
            if (phase != null) {
                // обычное смешивание: аддитивное свечение днём на светлом небе/облаках почти не видно
                // (белое + голубое = белое), а так частица держит свой цвет на любом фоне; ночью она
                // всё равно «светится» — шейдер рисует её без учёта освещения
                RenderSystem.defaultBlendFunc();
                RenderSystem.depthMask(false);
                phase.safeGetUniform("PhaseTime").set((Util.getMillis() % 3_600_000L) / 1000.0F);
                phase.safeGetUniform("Copper").set(this.copper ? 1.0F : 0.0F);
                RenderSystem.setShader(() -> phase);
            } else {
                RenderSystem.defaultBlendFunc();
                RenderSystem.depthMask(true);
                RenderSystem.setShader(GameRenderer::getParticleShader);
            }
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    @Nullable
    private static ShaderInstance currentShader() {
        if (!shaderTried) {
            shaderTried = true;
            try {
                shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                        PARTICLE_SHADER, DefaultVertexFormat.PARTICLE);
            } catch (Throwable t) {
                shader = null;
                ChronomancyMod.LOGGER.warn("Chronomancy particle shader не загрузился, частицы рисуются без фазы: {}",
                        t.getMessage());
            }
        }
        return shader;
    }

    /** Есть ли фазовый шейдер (иначе частицы пишут в вершины обычный цвет). */
    static boolean phaseShaderActive() {
        return currentShader() != null;
    }

    /** После частиц возвращаем обычное смешивание — аддитивный режим не должен утечь дальше. */
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
        }
    }

    // =========================================================
    // ПРОВАЙДЕРЫ
    // =========================================================

    public static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpecial(ChronoParticleRegistry.NEEDLE_WAKE.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.wake(level, x, y, z));
        // руна — мини-циферблат каста
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                (options, level, x, y, z, dx, dy, dz) -> level.random.nextInt(3) == 0
                        ? PhaseParticle.hourglass(level, x, y, z, dx, dy, dz)
                        : PhaseParticle.clock(level, x, y, z, dx, dy, dz).scaled(0.55F));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.sand(level, x, y, z, dx, dy, dz));
        // трещина времени — осколки циферблата/шестерни
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.shard(level, x, y, z,
                        dx + (level.random.nextDouble() - 0.5) * 0.08, dy + 0.04 + level.random.nextDouble() * 0.05,
                        dz + (level.random.nextDouble() - 0.5) * 0.08));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.dust(level, x, y, z, dx,
                        dx == 0.0 && dy == 0.0 && dz == 0.0 ? 0.004 : dy, dz));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_TRAIL.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.streak(level, new Vec3(x, y, z), new Vec3(dx, dy, dz)));
        // искра попадания — осколок, изредка яркая звезда
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                (options, level, x, y, z, dx, dy, dz) -> level.random.nextInt(5) == 0
                        ? PhaseParticle.star(level, x, y, z)
                        : PhaseParticle.shard(level, x, y, z, dx, dy, dz).scaled(0.8F));
        // струи снарядов: точки без направления
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_WISP.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.wisp(level, x, y, z, dx, dy, dz,
                        level.random.nextFloat() * 0.35F));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.wisp(level, x, y, z, dx, dy, dz,
                        0.75F + level.random.nextFloat() * 0.2F));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_CLOUD.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.cloud(level, x, y, z, dx, dy, dz));
        event.registerSpecial(ChronoParticleRegistry.TEMPORAL_COPPER.get(),
                (options, level, x, y, z, dx, dy, dz) -> PhaseParticle.copper(level, x, y, z, dx, dy, dz));
    }

    // =========================================================
    // SPAWN API (клиент, бюджет на тик)
    // =========================================================

    private static boolean claim() {
        if (spawnedThisTick >= MAX_SPAWNS_PER_TICK) {
            return false;
        }
        spawnedThisTick++;
        return true;
    }

    private static void add(Particle particle) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.particleEngine.add(particle);
        }
    }

    private static boolean near(Vec3 p) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && p.distanceToSqr(mc.player.position()) <= VIEW_DIST_SQR;
    }

    /** Ауры стазиса и Borrowed Future, детекция снятия стазиса, следы Rewind. */
    public static void onClientTick(ClientTickEvent.Post event) {
        spawnedThisTick = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            STASIS_SEEN.clear();
            RewindTrailClientState.clear();
            return;
        }
        ClientLevel level = mc.level;

        // Rewind первым: направленный поток важнее фоновой пыли
        RewindTrailHandler.tick(level);
        spawnBorrowedFuture(level, mc);
        com.chronomancy.client.VanillaPhase.clientTick(level);

        List<Integer> ids = TemporalStasisClientState.snapshotIds();
        Set<Integer> frozenNow = new HashSet<>(ids);
        Iterator<Map.Entry<Integer, Vec3>> it = STASIS_SEEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Vec3> e = it.next();
            if (!frozenNow.contains(e.getKey())) {
                spawnStasisBreak(level, e.getValue());
                it.remove();
            }
        }
        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                STASIS_SEEN.put(id, entity.position());
            }
        }

        if (level.getGameTime() % 2 != 0) {
            return;
        }
        for (int id : ids) {
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity.distanceToSqr(mc.player) > VIEW_DIST_SQR) {
                continue;
            }
            // стазис — «застывшее золото»: орбита и подвешенная пыль почти чистого золота
            if (claim()) {
                add(PhaseParticle.orbit(level, entity, 0.75F));
            }
            if (level.random.nextFloat() < 0.34F && claim()) {
                add(PhaseParticle.suspended(level, entity, 0.85F));
            }
            if (level.random.nextFloat() < 0.05F && claim()) {
                // редкий циферблат с остановленной стрелкой над головой
                add(PhaseParticle.clock(level, entity.getX(), entity.getY() + entity.getBbHeight() + 0.35,
                        entity.getZ(), 0, 0.004, 0).scaled(0.5F));
            }
        }
    }

    private static void spawnBorrowedFuture(ClientLevel level, Minecraft mc) {
        long time = level.getGameTime();
        for (int id : BorrowedFutureClientState.activeIds()) {
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity.distanceToSqr(mc.player) > VIEW_DIST_SQR) continue;
            BorrowedFuturePhase phase = BorrowedFutureClientState.phase(id);
            boolean ownFirstPerson = entity == mc.player && mc.options.getCameraType().isFirstPerson();
            if (phase == BorrowedFuturePhase.EMPOWERED) {
                // бафф: голубая фаза кружит вокруг, бег оставляет следы
                if (!ownFirstPerson && claim()) add(PhaseParticle.orbit(level, entity, 0.15F));
                if (time % 3 == 0 && claim()) {
                    double angle = level.random.nextDouble() * Math.PI * 2;
                    double radius = ownFirstPerson ? 0.7 : 0.45;
                    add(PhaseParticle.dust(level,
                            entity.getX() + Math.cos(angle) * radius,
                            entity.getY() + (ownFirstPerson ? 0.25 : 0.2 + level.random.nextDouble() * 1.6),
                            entity.getZ() + Math.sin(angle) * radius,
                            Math.cos(angle) * 0.03, 0.04, Math.sin(angle) * 0.03));
                }
                if (entity.getDeltaMovement().horizontalDistanceSqr() > 0.0025 && time % 3 == 0 && claim()) {
                    Vec3 behind = entity.position().add(entity.getDeltaMovement().scale(-0.6)).add(0, 0.9, 0);
                    add(PhaseParticle.streak(level, behind, entity.getDeltaMovement().scale(-1)));
                }
            } else if (phase == BorrowedFuturePhase.DEBT && time % 6 == 0 && !ownFirstPerson && claim()) {
                // долг: пыль осыпается вниз и растворяется, как само тело
                add(PhaseParticle.dust(level,
                        entity.getX() + (level.random.nextDouble() - 0.5) * entity.getBbWidth() * 1.2,
                        entity.getY() + level.random.nextDouble() * entity.getBbHeight(),
                        entity.getZ() + (level.random.nextDouble() - 0.5) * entity.getBbWidth() * 1.2,
                        0, -0.015, 0));
            }
        }
    }

    /**
     * Дематериализация (смерть под Needle/Stasis): в начале — вспышка осколков и пыли,
     * затем каждый тик пыль фазы поднимается из растворяющегося тела.
     */
    public static void spawnDematerialize(Entity entity, boolean start) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !near(entity.position())) return;
        ClientLevel level = mc.level;
        double w = Math.max(0.4, entity.getBbWidth());
        double h = entity.getBbHeight();
        if (start) {
            Vec3 c = entity.position().add(0, h * 0.5, 0);
            burstShards(level, c, 6, 0.09);
            if (claim()) add(PhaseParticle.star(level, c.x, c.y, c.z));
        }
        int count = start ? 10 : 3;
        for (int i = 0; i < count && claim(); i++) {
            add(PhaseParticle.dust(level,
                    entity.getX() + (level.random.nextDouble() - 0.5) * w,
                    entity.getY() + level.random.nextDouble() * h,
                    entity.getZ() + (level.random.nextDouble() - 0.5) * w,
                    (level.random.nextDouble() - 0.5) * 0.02, 0.03 + level.random.nextDouble() * 0.04,
                    (level.random.nextDouble() - 0.5) * 0.02));
        }
    }

    /** Долг Borrowed Future наступил: циферблат + осколки. */
    public static void spawnBorrowedSnap(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Entity entity = mc.level.getEntity(entityId);
        if (entity == null || entity.distanceToSqr(mc.player) > VIEW_DIST_SQR) return;
        ClientLevel level = mc.level;
        Vec3 center = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
        if (claim()) add(PhaseParticle.clock(level, center.x, center.y + 0.2, center.z, 0, 0.01, 0));
        for (int i = 0; i < 8 && claim(); i++) add(PhaseParticle.implode(level, entity.position(), 0.7));
        burstShards(level, center, 5, 0.12);
    }

    /** Цель застыла в стазисе: над ней встаёт циферблат, вокруг осыпается золотой песок. */
    public static void spawnStasisFreeze(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity entity = mc.level.getEntity(entityId);
        if (entity == null || !near(entity.position())) return;
        ClientLevel level = mc.level;
        double top = entity.getY() + entity.getBbHeight() + 0.4;
        if (claim()) add(PhaseParticle.clock(level, entity.getX(), top, entity.getZ(), 0, 0.0, 0).longer(1.4F));
        for (int i = 0; i < 8 && claim(); i++) {
            double a = i / 8.0 * Math.PI * 2.0;
            double r = Math.max(0.5, entity.getBbWidth() * 0.8);
            add(PhaseParticle.sand(level, entity.getX() + Math.cos(a) * r,
                    entity.getY() + level.random.nextDouble() * entity.getBbHeight(),
                    entity.getZ() + Math.sin(a) * r, -Math.cos(a) * 0.02, 0.0, -Math.sin(a) * 0.02));
        }
    }

    /** The World Stop: большой циферблат над кастером и кольцо малых циферблатов вокруг. */
    public static void spawnWorldStop(int casterId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity caster = mc.level.getEntity(casterId);
        if (caster == null || !near(caster.position())) return;
        ClientLevel level = mc.level;
        Vec3 c = caster.position().add(0, caster.getBbHeight() + 0.6, 0);
        if (claim()) add(PhaseParticle.clock(level, c.x, c.y, c.z, 0, 0.0, 0).scaled(2.4F).longer(1.8F));
        for (int i = 0; i < 8 && claim(); i++) {
            double a = i / 8.0 * Math.PI * 2.0;
            add(PhaseParticle.clock(level, caster.getX() + Math.cos(a) * 1.6, caster.getY() + 1.0,
                    caster.getZ() + Math.sin(a) * 1.6, Math.cos(a) * 0.05, 0.01, Math.sin(a) * 0.05).scaled(0.8F));
        }
        burstShards(level, caster.position().add(0, 1.0, 0), 10, 0.2);
    }

    /** Снятие стазиса: циферблат «запускается» и разлетаются золотые осколки. */
    private static void spawnStasisBreak(ClientLevel level, Vec3 p) {
        if (!near(p)) {
            return;
        }
        Vec3 c = p.add(0, 0.8, 0);
        if (claim()) {
            add(PhaseParticle.clock(level, c.x, c.y, c.z, 0, 0.01, 0));
        }
        burstShards(level, c, 7, 0.13);
        if (claim()) {
            add(PhaseParticle.star(level, c.x, c.y, c.z));
        }
    }

    private static void burstShards(ClientLevel level, Vec3 c, int count, double speed) {
        for (int i = 0; i < count && claim(); i++) {
            double a = level.random.nextDouble() * Math.PI * 2.0;
            double r = speed * (0.5 + level.random.nextDouble() * 0.6);
            add(PhaseParticle.shard(level, c.x, c.y, c.z,
                    Math.cos(a) * r, 0.04 + level.random.nextDouble() * 0.08, Math.sin(a) * r));
        }
    }

    /** Rewind: полоски летят вдоль траектории к точке отката, за ними — пыль фазы. */
    public static void spawnRewindTrail(ClientLevel level, Vec3 pos, Vec3 forward) {
        if (claim()) {
            add(PhaseParticle.streak(level, pos, forward));
        }
        if (level.random.nextFloat() < 0.45F && claim()) {
            add(PhaseParticle.streak(level, pos, forward));
        }
        if (level.random.nextFloat() < 0.3F && claim()) {
            add(PhaseParticle.dust(level, pos.x, pos.y, pos.z,
                    (level.random.nextDouble() - 0.5) * 0.02, 0.01, (level.random.nextDouble() - 0.5) * 0.02));
        }
    }

    /** Rewind, финал: схлопывание в точку прихода и циферблат, стрелка которого бежит назад. */
    public static void spawnRewindImplode(ClientLevel level, Vec3 dest) {
        for (int i = 0; i < 12 && claim(); i++) {
            add(PhaseParticle.implode(level, dest, 0.85 + level.random.nextDouble() * 0.35));
        }
        if (claim()) {
            add(PhaseParticle.clock(level, dest.x, dest.y + 0.9, dest.z, 0, 0.0, 0).reversed());
        }
        if (claim()) {
            add(PhaseParticle.star(level, dest.x, dest.y + 0.9, dest.z));
        }
    }
}
