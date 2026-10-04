package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.mixin.PostChainAccessor;
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
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.List;

/**
 * TIME DILATION FIELD — client-side spherical volume post-process (Phase B).
 *
 * <p>Тот же ванильный {@link PostChain}-стек, что и у World Stop (без Iris), но
 * принципиально другой эффект: не глобальный grade, а попиксельная spatial-маска
 * по world position, восстановленной из текстуры глубины {@code minecraft:main}
 * (привязана через {@code auxtargets} в цепочке). Точка применения та же, что у
 * World Stop — сразу после {@code doEntityOutline} внутри {@code GameRenderer#render}:
 * весь мир (включая руку) уже в main-таргете, HUD ещё не рисуется.
 *
 * <p>Приоритет (§ ТЗ): если активен World Stop-шейдер — dilation-объём НЕ
 * применяется вовсе (не складываем два grade'а). При отсутствии полей цепочка
 * полностью освобождается. Переход camera-inside рампится по РЕАЛЬНОМУ времени
 * ({@link Util#getNanos()}), никогда по world time.
 *
 * <p>Матрицы реконструкции и список полей берутся из {@link ClientTemporalFieldManager}
 * (снимает точные кадры уровня на {@code AFTER_LEVEL}). Матричные/пофield'овые
 * униформы пишутся напрямую в {@link EffectInstance} через {@link PostChainAccessor}
 * (ванильный {@code PostChain#setUniform} умеет только скалярные float).
 */
public final class TemporalVolumePostProcess {

    private static final ResourceLocation CHAIN =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "shaders/post/time_dilation.json");

    private static final String UNIFORM_FIELD_COUNT = "FieldCount";
    private static final String UNIFORM_INSIDE = "CameraInsideStrength";
    private static final String UNIFORM_DOMINANT_SLOW = "DominantSlow";
    private static final String UNIFORM_INV_VIEW_PROJ = "InvViewProj";

    private static final float RAMP_SECONDS = 0.22F;

    private static PostChain chain;
    private static boolean broken;
    private static float cameraInsideStrength;
    private static long lastNanos = Long.MIN_VALUE;
    private static int lastWidth;
    private static int lastHeight;

    private TemporalVolumePostProcess() {
    }

    public static void onRender(DeltaTracker deltaTracker) {
        float dt = realTimeDeltaSeconds();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        ClientTemporalFieldManager.collect(level);
        int fieldCount = ClientTemporalFieldManager.fieldCount();

        // Мир-стоп имеет приоритет: не красим два grade одновременно.
        if (WorldStopPostProcess.isShaderActive()) {
            cameraInsideStrength = 0.0F;
            closeChain();
            return;
        }

        if (broken || fieldCount == 0 || !ClientTemporalFieldManager.hasMatrices()) {
            cameraInsideStrength = approach(cameraInsideStrength, 0.0F, dt);
            closeChain();
            return;
        }

        float insideTarget = ClientTemporalFieldManager.isCameraInside() ? 1.0F : 0.0F;
        cameraInsideStrength = approach(cameraInsideStrength, insideTarget, dt);

        if (chain == null && !tryLoad(mc)) {
            return;
        }

        RenderTarget screen = mc.getMainRenderTarget();
        if (screen.width != lastWidth || screen.height != lastHeight) {
            chain.resize(screen.width, screen.height);
            lastWidth = screen.width;
            lastHeight = screen.height;
        }

        chain.setUniform(UNIFORM_FIELD_COUNT, fieldCount);
        chain.setUniform(UNIFORM_INSIDE, cameraInsideStrength);
        chain.setUniform(UNIFORM_DOMINANT_SLOW, ClientTemporalFieldManager.dominantSlow());

        feedMatrixUniforms(fieldCount, ClientTemporalFieldManager.invViewProj());

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(deltaTracker.getGameTimeDeltaTicks());
    }

    /**
     * Пишет {@code InvViewProj} (mat4) и {@code Field0..N} (по mat4 на поле) напрямую в
     * effect первого прохода цепочки. {@code InvViewProj} нужен шейдеру только для
     * НАПРАВЛЕНИЯ луча (без текстуры глубины). Поле упаковано column-major:
     * col0 = (relX, relY, relZ, radius), col1.x = slow.
     */
    private static void feedMatrixUniforms(int fieldCount, Matrix4f invViewProj) {
        List<PostPass> passes = ((PostChainAccessor) chain).chronomancy$getPasses();
        if (passes.isEmpty()) {
            return;
        }
        EffectInstance effect = passes.get(0).getEffect();
        Uniform inv = effect.getUniform(UNIFORM_INV_VIEW_PROJ);
        if (inv != null) {
            inv.set(invViewProj);
        }
        float[] packed = new float[16];
        int limit = Math.min(fieldCount, ClientTemporalFieldManager.MAX_TEMPORAL_FIELDS);
        for (int i = 0; i < limit; i++) {
            Uniform fu = effect.getUniform("Field" + i);
            if (fu == null) {
                continue;
            }
            java.util.Arrays.fill(packed, 0.0F);
            packed[0] = (float) ClientTemporalFieldManager.relX(i);  // col0.x
            packed[1] = (float) ClientTemporalFieldManager.relY(i);  // col0.y
            packed[2] = (float) ClientTemporalFieldManager.relZ(i);  // col0.z
            packed[3] = (float) ClientTemporalFieldManager.radius(i);// col0.w
            packed[4] = ClientTemporalFieldManager.slow(i);          // col1.x
            fu.set(packed);
        }
    }

    private static boolean tryLoad(Minecraft mc) {
        try {
            PostChain newChain = new PostChain(
                    mc.getTextureManager(),
                    mc.getResourceManager(),
                    mc.getMainRenderTarget(),
                    CHAIN
            );
            RenderTarget screen = mc.getMainRenderTarget();
            newChain.resize(screen.width, screen.height);
            chain = newChain;
            lastWidth = screen.width;
            lastHeight = screen.height;
            ChronomancyMod.LOGGER.debug("[TimeDilation] volume post-process shader enabled ({})", CHAIN);
            return true;
        } catch (IOException | RuntimeException e) {
            // Не крашим: шейдер опционален, граница-сфера (геометрия) всё равно покажет поле.
            ChronomancyMod.LOGGER.warn(
                    "[TimeDilation] volume post-process shader failed to load ({})", CHAIN, e);
            closeChain();
            broken = true;
            return false;
        }
    }

    private static float approach(float current, float target, float dt) {
        float rate = RAMP_SECONDS > 0.0F ? 1.0F / RAMP_SECONDS : 100.0F;
        return Mth.clamp(current + Math.signum(target - current) * rate * dt, 0.0F, 1.0F);
    }

    private static float realTimeDeltaSeconds() {
        long now = Util.getNanos();
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = now;
            return 0.0F;
        }
        float dt = (now - lastNanos) / 1_000_000_000.0F;
        lastNanos = now;
        return Mth.clamp(dt, 0.0F, 0.1F);
    }

    private static void closeChain() {
        if (chain != null) {
            try {
                chain.close();
            } catch (RuntimeException e) {
                ChronomancyMod.LOGGER.warn("[TimeDilation] failed to close volume chain cleanly", e);
            }
            chain = null;
        }
        lastWidth = 0;
        lastHeight = 0;
    }

    /** Регистрация reload-слушателя (шина мода, только клиент). */
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ReloadListener());
    }

    /**
     * Переживает F3+T: на render-потоке после перевыпуска ванильных shared-шейдеров
     * закрываем свою цепочку и сбрасываем аварию — пересоздание ленивое.
     */
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
            return "chronomancy:time_dilation_volume";
        }
    }
}
