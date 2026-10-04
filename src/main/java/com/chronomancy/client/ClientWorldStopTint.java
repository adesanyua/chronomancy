package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * THE WORLD STOP — аварийный FALLBACK-тинт экрана (простая сепия).
 *
 * <p>Основной визуальный эффект теперь — настоящий post-process шейдер
 * ({@link WorldStopPostProcess}, ванильный PostChain). Этот класс рисуется
 * ТОЛЬКО если шейдер не загрузился ({@link WorldStopPostProcess#isTintFallbackActive()}):
 * дешёвый полноэкранный wash в медной медиане палитры (§5 ТЗ #8A5A2B),
 * поверх мира и ДО HUD — интерфейс не окрашивается. Когда шейдер работает,
 * этот тинт выключен полностью, чтобы не было двойной цветокоррекции (§21).
 *
 * <p>Рисование через {@code position-color} шейдер: матрицы model-view и
 * projection временно сбрасываются в единичные, квад — в клип-пространстве
 * [-1,1], затем стек матриц восстанавливается в {@code finally} (ни при каких
 * условиях не оставляем глобальный RenderSystem-стейд испорченным).
 */
public final class ClientWorldStopTint {

    private ClientWorldStopTint() {
    }

    // Медианная медь палитры + умеренная альфа (~27%).
    private static final int R = 0x8A;
    private static final int G = 0x5A;
    private static final int B = 0x2B;
    private static final int A = 0x47;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        // ФАЗА B: полноценная цветокоррекция теперь делается настоящим
        // post-process шейдером (WorldStopPostProcess). Простой тинт остаётся
        // ИСКЛЮЧИТЕЛЬНО fallback-вариантом на случай, если шейдер не загрузился
        // (иначе получилась бы двойная цветокоррекция поверх медного grade).
        if (!WorldStopPostProcess.isTintFallbackActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Только в игровом мире (не в главном меню / на загрузке экрана).
        if (mc.level == null || mc.player == null) {
            return;
        }
        renderOverlay();
    }

    private static void renderOverlay() {
        // В 1.21.1 матрицыmodel-view/projection живут в RenderSystem-стеках:
        // model-view — Matrix4fStack (push/identity/pop), projection — одиночный
        // Matrix4f, ставится через setProjectionMatrix(.., VertexSorting).
        // Снапшотим текущие, рисуем в единичных (клип-куб [-1,1]), затем в
        // finally строго восстанавливаем — глобальный GL-стейд не пачкаем.
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        modelViewStack.pushMatrix();
        try {
            modelViewStack.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);

            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            buffer.addVertex(-1.0F, -1.0F, 0.0F).setColor(R, G, B, A);
            buffer.addVertex(-1.0F, 1.0F, 0.0F).setColor(R, G, B, A);
            buffer.addVertex(1.0F, 1.0F, 0.0F).setColor(R, G, B, A);
            buffer.addVertex(1.0F, -1.0F, 0.0F).setColor(R, G, B, A);
            BufferUploader.drawWithShader(buffer.build());
        } catch (RuntimeException e) {
            // Никогда не роняем рендер-кадр из-за косметики.
            ChronomancyMod.LOGGER.warn("[WorldStop] sepia overlay render failed", e);
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }
}
