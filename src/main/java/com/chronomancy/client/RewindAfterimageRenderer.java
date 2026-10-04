package com.chronomancy.client;

import com.chronomancy.entity.RewindAfterimageEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Остаточный образ Rewind: золотисто-медный проволочный силуэт игрока,
 * застрявший на точке каста, пока тело «перематывается» назад.
 *
 * <p>Собран из тех же визуальных примитивов, что поле и снаряд
 * ({@link RenderType#lines()} + золотой цвет {@code TimeDilationFieldRenderer}):
 * контурная «кукла» из рёбер (голова/торс/руки/ноги в пропорциях Стива) плюс
 * перематывающееся кольцо у ног — отсылка к размотке нити времени.
 *
 * <p>Альфа пульсирует и угасает к концу жизни образа: силуэт не исчезает резко,
 * а «растворяется» (пыль догоняет рендер — см. клиентский tick сущности).
 */
public class RewindAfterimageRenderer extends EntityRenderer<RewindAfterimageEntity> {

    /** Число тиков угасания в конце жизни (совпадает с LIFETIME_TICKS сущности по порядку). */
    private static final int FADE_TICKS = 12;
    private static final int MAX_AGE = RewindAfterimageEntity.LIFETIME_TICKS;

    /** Пропорции модели Стива, переведённые в блоки (1 px = 1/16). */
    private static final float HEAD = 0.25F;            // куб 4px
    private static final float TORSO_HALF_D = 0.125F;   // 2px — полуразмер по глубине
    private static final float LIMB_HALF = 0.0625F;    // 2px

    public RewindAfterimageRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F; // светящийся силуэт не отбрасывает тени
    }

    @Override
    public void render(RewindAfterimageEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        float age = entity.tickCount + partialTick;

        // Угасание: последние FADE_TICKS альфа линейно к 0; лёгкий «пульс» времени.
        float fade = Mth.clamp((MAX_AGE - age) / FADE_TICKS, 0.0F, 1.0F);
        float shimmer = 0.9F + 0.1F * Mth.sin(age * 0.9F);
        // Камера внутри образа (в начале шага кастер ещё стоит в нём) — прячем, иначе в лицо лезут
        // его руки и тело с шейдером.
        net.minecraft.world.phys.Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = cam.x - entity.getX(), dz = cam.z - entity.getZ();
        double dy = cam.y - Mth.clamp(cam.y, entity.getY(), entity.getY() + 1.9);
        float near = (float) Mth.clamp((Math.sqrt(dx * dx + dz * dz + dy * dy) - 0.7) / 1.0, 0.0, 1.0);
        int alpha = (int) (255.0F * fade * shimmer * near);
        if (alpha <= 4) {
            return;
        }

        // Шейдерный вариант: переливающийся силуэт самого кастера (как эхо-силуэты фазирования).
        com.chronomancy.client.VanillaPhase.AfterimageSource source = com.chronomancy.client.entity.TimePhaseRendering.available()
                ? com.chronomancy.client.VanillaPhase.rewindCasterAt(entity.position()) : null;
        if (source != null) {
            renderCasterEcho(entity, source, age, alpha, partialTick, poseStack, bufferSource, packedLight);
            return;
        }

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        // Диспетчер переводит в низ хитбокса — дальше рисуем от стоп, как сама модель.
        // Едва заметное «дрожание» незафиксированного во времени образа.
        poseStack.mulPose(Axis.YP.rotationDegrees(
                entityYaw + Mth.sin(age * 1.7F) * 1.5F));

        // Голова: куб 4x4x4px, центр y = 1.8 - 2px
        drawBox(poseStack, consumer, alpha,
                0.0F, 1.6875F, 0.0F, HEAD, HEAD, HEAD);
        // Торс: 8x12x4px, y от 1.5 до 0.75 (плечи)
        drawBox(poseStack, consumer, alpha,
                0.0F, 1.125F, 0.0F, 0.2F, 0.375F, TORSO_HALF_D);
        // Руки: 4x12px, свисают по швам
        drawBox(poseStack, consumer, alpha,
                -0.275F, 1.125F, 0.0F, LIMB_HALF, 0.375F, LIMB_HALF);
        drawBox(poseStack, consumer, alpha,
                0.275F, 1.125F, 0.0F, LIMB_HALF, 0.375F, LIMB_HALF);
        // Ноги: 4x12px
        drawBox(poseStack, consumer, alpha,
                -0.1F, 0.375F, 0.0F, LIMB_HALF, 0.375F, LIMB_HALF);
        drawBox(poseStack, consumer, alpha,
                0.1F, 0.375F, 0.0F, LIMB_HALF, 0.375F, LIMB_HALF);

        // «Катушка» у ног: кольцо крутится в обратную сторону — нить сматывается.
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.06F, 0.0F);
        poseStack.scale(0.55F, 0.55F, 0.55F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-age * 14.0F));
        TimeDilationFieldRenderer.circleXZ(poseStack, consumer, Math.min(alpha, 140), 0.0, 1.0, 32);
        poseStack.popPose();

        poseStack.popPose();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderCasterEcho(RewindAfterimageEntity entity, com.chronomancy.client.VanillaPhase.AfterimageSource source, float age,
                                  int alpha, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                                  int packedLight) {
        net.minecraft.world.entity.Entity caster = source.caster();
        EntityRenderer renderer = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(caster);
        float echoAlpha = Math.min(1.0F, alpha / 255.0F);
        poseStack.pushPose();
        // Образ застыл в позе момента каста — не повторяет движения кастера.
        // withSource: GeckoLib-броня рисуется в свой общий буфер — так она тоже попадает в эхо-шейдер.
        MultiBufferSource echo = com.chronomancy.client.VanillaPhase.echoSource(bufferSource, echoAlpha);
        source.pose().render(caster, partialTick, () -> com.chronomancy.client.VanillaPhase.withSource(echo,
                () -> renderer.render(caster, caster.getYRot(), partialTick, poseStack, echo, packedLight)));
        poseStack.popPose();
        // «Катушка» у ног — нить времени сматывается.
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.06F, 0.0F);
        poseStack.scale(0.55F, 0.55F, 0.55F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-age * 14.0F));
        TimeDilationFieldRenderer.circleXZ(poseStack, consumer, Math.min(alpha, 140), 0.0, 1.0, 32);
        poseStack.popPose();
    }

    /** 12 рёбер прямоугольного параллелепипеда с центром (cx, cy, cz). */
    private static void drawBox(PoseStack poseStack, VertexConsumer consumer, int alpha,
                                float cx, float cy, float cz, float hx, float hy, float hz) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;
        // Нижняя и верхняя рамки
        edge(poseStack, consumer, alpha, x0, y0, z0, x1, y0, z0);
        edge(poseStack, consumer, alpha, x1, y0, z0, x1, y0, z1);
        edge(poseStack, consumer, alpha, x1, y0, z1, x0, y0, z1);
        edge(poseStack, consumer, alpha, x0, y0, z1, x0, y0, z0);
        edge(poseStack, consumer, alpha, x0, y1, z0, x1, y1, z0);
        edge(poseStack, consumer, alpha, x1, y1, z0, x1, y1, z1);
        edge(poseStack, consumer, alpha, x1, y1, z1, x0, y1, z1);
        edge(poseStack, consumer, alpha, x0, y1, z1, x0, y1, z0);
        // Вертикальные стойки
        edge(poseStack, consumer, alpha, x0, y0, z0, x0, y1, z0);
        edge(poseStack, consumer, alpha, x1, y0, z0, x1, y1, z0);
        edge(poseStack, consumer, alpha, x1, y0, z1, x1, y1, z1);
        edge(poseStack, consumer, alpha, x0, y0, z1, x0, y1, z1);
    }

    private static void edge(PoseStack poseStack, VertexConsumer consumer, int alpha,
                             float x0, float y0, float z0, float x1, float y1, float z1) {
        PoseStack.Pose pose = poseStack.last();
        int color = TemporalStasisTint.TINT_RGB | (Math.min(alpha, 255) << 24);
        consumer.addVertex(pose, x0, y0, z0)
                .setColor(color)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(pose, x1, y1, z1)
                .setColor(color)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(RewindAfterimageEntity entity) {
        return null; // линии не текстурируются
    }
}
