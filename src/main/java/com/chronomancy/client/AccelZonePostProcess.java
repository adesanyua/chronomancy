package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.entity.TimeParadoxEntity;
import com.chronomancy.mixin.PostChainAccessor;
import com.chronomancy.temporal.TemporalRate;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ACCELERATED ZONE и вспышка ПАРАДОКСА — объёмный пост-эффект, зеркальный {@link TemporalVolumePostProcess}.
 *
 * <p>Отдельная цепочка и отдельный шейдер ({@code time_acceleration}): если он не соберётся на
 * какой-то видеокарте, поле замедления продолжит рисоваться как прежде — эффекты не зависят друг от
 * друга. Матрицы кадра берутся у {@link ClientTemporalFieldManager} (он снимает их каждый кадр).
 * Зона красит куб холодным «спешащим» цветом; вспышка парадокса — рябь и полосы по расходящейся
 * сфере. Во время The World Stop не применяется (как и купол поля).
 */
public final class AccelZonePostProcess {

    private static final ResourceLocation CHAIN =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "shaders/post/time_acceleration.json");
    private static final int MAX_VOLUMES = 4;
    private static final double SCAN_RADIUS = 192.0;

    private static PostChain chain;
    private static boolean broken;
    private static int lastWidth;
    private static int lastHeight;
    /** Пока зон и вспышек нет, список сущностей просматривается не каждый кадр, а раз в несколько. */
    private static int idleFrames;

    /** Один объём для шейдера: 16 чисел в порядке столбцов mat4 (см. шапку time_acceleration.fsh). */
    private record Volume(float[] packed, double distSqr, boolean blast) {
    }

    private AccelZonePostProcess() {
    }

    public static void onRender(DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || broken || WorldStopPostProcess.isShaderActive()
                || !ClientTemporalFieldManager.matricesReady()) {
            closeChain();
            return;
        }
        if (chain == null && idleFrames++ % 6 != 0) {
            return;
        }
        List<Volume> volumes = collect(level, deltaTracker.getGameTimeDeltaPartialTick(false));
        if (volumes.isEmpty()) {
            closeChain();
            return;
        }
        idleFrames = 0;
        if (chain == null && !tryLoad(mc)) {
            return;
        }
        RenderTarget screen = mc.getMainRenderTarget();
        if (screen.width != lastWidth || screen.height != lastHeight) {
            chain.resize(screen.width, screen.height);
            lastWidth = screen.width;
            lastHeight = screen.height;
        }
        chain.setUniform("VolumeCount", volumes.size());
        chain.setUniform("Time", (Util.getMillis() % 3_600_000L) / 1000.0F);

        List<PostPass> passes = ((PostChainAccessor) chain).chronomancy$getPasses();
        if (!passes.isEmpty()) {
            EffectInstance effect = passes.get(0).getEffect();
            Uniform inv = effect.getUniform("InvViewProj");
            if (inv != null) {
                inv.set(ClientTemporalFieldManager.invViewProj());
            }
            for (int i = 0; i < volumes.size(); i++) {
                Uniform uniform = effect.getUniform("Volume" + i);
                if (uniform != null) {
                    uniform.set(volumes.get(i).packed());
                }
            }
        }
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(deltaTracker.getGameTimeDeltaTicks());
    }

    /** Зоны и вспышки вокруг камеры; вспышки важнее, дальше — по близости. Не больше {@value #MAX_VOLUMES}. */
    private static List<Volume> collect(ClientLevel level, float partialTick) {
        Vec3 camera = ClientTemporalFieldManager.cameraPos();
        AABB scan = new AABB(camera, camera).inflate(SCAN_RADIUS);
        List<Volume> found = new ArrayList<>();
        Matrix4f viewProj = null;
        for (Entity entity : level.getEntities((Entity) null, scan,
                e -> e instanceof TimeDilationFieldEntity || e instanceof TimeParadoxEntity)) {
            float[] packed = new float[16];
            if (entity instanceof TimeDilationFieldEntity field) {
                if (!field.isAccelerating() || field.getRadius() <= 0.0) {
                    continue;
                }
                Vec3 center = field.getRenderCenter();
                if (center == null) {
                    center = field.position();
                }
                Vec3 rel = center.subtract(camera);
                packed[0] = (float) rel.x;
                packed[1] = (float) rel.y;
                packed[2] = (float) rel.z;
                packed[3] = (float) field.getRadius();
                packed[4] = (float) Mth.clamp((field.getTemporalRate() - TemporalRate.NORMAL)
                        / TemporalRate.MAX_DILATION_SLOW, 0.0, 1.0);
                packed[5] = 1.0F;
                found.add(new Volume(packed, rel.lengthSqr(), false));
            } else if (entity instanceof TimeParadoxEntity blast) {
                float progress = blast.progress(partialTick);
                if (progress >= 1.0F) {
                    continue;
                }
                Vec3 rel = blast.getPosition(partialTick).add(0.0, 0.6, 0.0).subtract(camera);
                float radius = blast.getRadius();
                packed[0] = (float) rel.x;
                packed[1] = (float) rel.y;
                packed[2] = (float) rel.z;
                packed[3] = radius;
                packed[4] = 1.0F;
                packed[5] = 2.0F;
                packed[6] = progress;
                packed[7] = radius * shellFraction(progress);
                if (viewProj == null) {
                    viewProj = ClientTemporalFieldManager.viewProj();
                }
                Vector4f clip = viewProj.transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F));
                if (clip.w > 1.0E-4F) {
                    packed[8] = clip.x / clip.w * 0.5F + 0.5F;
                    packed[9] = clip.y / clip.w * 0.5F + 0.5F;
                    packed[10] = 1.0F;
                }
                found.add(new Volume(packed, rel.lengthSqr(), true));
            }
        }
        found.sort(Comparator.<Volume, Boolean>comparing(v -> !v.blast()).thenComparingDouble(Volume::distSqr));
        return found.size() > MAX_VOLUMES ? new ArrayList<>(found.subList(0, MAX_VOLUMES)) : found;
    }

    /** Доля полного радиуса, до которой оболочка вспышки дошла к этому моменту: быстро и с затуханием. */
    public static float shellFraction(float progress) {
        float t = Mth.clamp(progress * 1.8F, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    private static boolean tryLoad(Minecraft mc) {
        try {
            PostChain newChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                    mc.getMainRenderTarget(), CHAIN);
            RenderTarget screen = mc.getMainRenderTarget();
            newChain.resize(screen.width, screen.height);
            chain = newChain;
            lastWidth = screen.width;
            lastHeight = screen.height;
            return true;
        } catch (IOException | RuntimeException e) {
            ChronomancyMod.LOGGER.warn("[AcceleratedZone] volume post-process shader failed to load ({})", CHAIN, e);
            closeChain();
            broken = true;
            return false;
        }
    }

    private static void closeChain() {
        if (chain != null) {
            try {
                chain.close();
            } catch (RuntimeException e) {
                ChronomancyMod.LOGGER.warn("[AcceleratedZone] failed to close volume chain cleanly", e);
            }
            chain = null;
        }
        lastWidth = 0;
        lastHeight = 0;
    }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ReloadListener());
    }

    private static final class ReloadListener extends SimplePreparableReloadListener<Void> {
        @Override
        protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
            return null;
        }

        @Override
        protected void apply(Void object, ResourceManager resourceManager, ProfilerFiller profiler) {
            closeChain();
            broken = false;
        }

        @Override
        public String getName() {
            return "chronomancy:time_acceleration_volume";
        }
    }
}
