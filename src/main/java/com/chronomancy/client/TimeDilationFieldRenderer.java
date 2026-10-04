package com.chronomancy.client;

import com.chronomancy.entity.TimeDilationFieldEntity;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Рендер поля Time Dilation: граница радиуса + золотой tint блоков.
 *
 * <p>Три слоя поверх эффективного центра сферы:
 * <ul>
 *   <li><b>жёлтая «плёнка» на блоках</b> — каждый видимый блок внутри сферы
 *   накрывается чуть раздутым полупрозрачным золотым кубом (depth-test LESS,
 *   без записи в depth buffer), поэтому блоки ПОД полем реально приобретают
 *   жёлтый tint, а скрытые рельефом блоки не просвечивают;</li>
 *   <li><b>чёткий проволочный экватор</b> ровно на радиусе поля + полуденные
 *   и параллельные окружности для читаемого силуэта сферы;</li>
 *   <li>фоновые пылинки спавнит сама сущность в {@code tick()}.</li>
 * </ul>
 *
 * <p>Плавное следование (Shift-каст): сервер переносит поле {@code setPos} раз
 * в тик (20Гц) — сфера визуально отстаёт и дёргается за игроком. Поэтому при
 * {@code isFollowingOwner()} центр берётся из ИНТЕРПОЛИРОВАННОЙ позиции самого
 * owner'а ({@link Entity#getPosition(float)}) — ровно там же в этот кадр рисуется
 * и игрок. Остальные слои (плёнка, линии) сдвигаются на дельту к этому центру.
 */
public class TimeDilationFieldRenderer extends EntityRenderer<TimeDilationFieldEntity> {

    /** Та же 1x1 белая текстура, что использовалась для стазис-проходов. */
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");

    /** Цвет хрономантии (#FFE24A) — тот же, что у tint мобов (золотая плёнка на блоках). */
    private static final int GOLD_RGB = TemporalStasisTint.TINT_RGB;

    /** Пиксельный атлас глифов границы: 8 тайлов 16x16, grayscale, hard-edged. */
    private static final ResourceLocation GLYPHS =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/temporal_boundary_glyphs.png");

    // Палитра границы (TЗ): тёмная бронза / песок / золото / хайлайт. Без чистого жёлтого.
    private static final int C_BRONZE = 0x75552A;
    private static final int C_SAND = 0xB58C45;
    private static final int C_GOLD = 0xD0AC58;
    private static final int C_HILITE = 0xE4CD7B;
    // Синие акценты — как у остальной хрономантии (золото + синий).
    private static final int C_BLUE = 0x3D8CF0;
    private static final int C_SKY = 0xA8DCFF;

    /** Заметность золотой плёнки на блоках (0..255). Плёнка пишется с depth-write,
     *  поэтому по одному слою на луч — альфа здесь это итоговая интенсивность tint'а. */
    private static final int FILM_ALPHA = 70;
    /** Раздутие кубика, чтобы плёнка не z-fight'ила с текстурой блока. */
    private static final double FILM_INFLATE = 0.0025;
    /** Страховка от pathological-радиусов: не больше N кубиков за кадр. */
    private static final int MAX_FILM_BLOCKS = 700;

    private static RenderType filmType;
    private static RenderType glyphType;

    public TimeDilationFieldRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** Область много больше хитбокса сущности: отсекать её по нему нельзя — купол пропадал бы с края экрана. */
    @Override
    public boolean shouldRender(TimeDilationFieldEntity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void render(TimeDilationFieldEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        double radius = entity.getRadius();
        if (radius <= 0.0) {
            return;
        }

        // Диспетчер уже перевёл poseStack в entity.getPosition(partialTick).
        Vec3 base = entity.getPosition(partialTick);
        Vec3 center = base;
        if (entity.isFollowingOwner()) {
            Entity owner = findClientOwner(entity);
            // центр следящей области приподнят над ногами владельца — так же, как на сервере
            center = owner != null ? owner.getPosition(partialTick).add(0.0D, entity.centerLift(), 0.0D)
                    : entity.getRenderCenter();
        }
        entity.setRenderCenter(center);

        poseStack.pushPose();
        poseStack.translate(center.x - base.x, center.y - base.y, center.z - base.z);
        if (entity.isAccelerating()) {
            // Accelerated Zone — куб со своим, противоположным полю, обликом
            AcceleratedZoneRendering.render(entity, poseStack, bufferSource, center, radius, partialTick);
            poseStack.popPose();
            return;
        }

        renderBlockFilm(entity.level(), poseStack, bufferSource, center, radius);

        renderBoundary(entity, poseStack, bufferSource, center, radius, partialTick);

        renderBubble(entity, poseStack, bufferSource, radius);

        poseStack.popPose();
    }

    // =========================================================
    // ПЕРЕЛИВАЮЩИЙСЯ «ПУЗЫРЬ ВРЕМЕНИ» (тот же шейдер, что у фазирования сущностей)
    // =========================================================

    private static final int BUBBLE_LAT = 16;
    private static final int BUBBLE_LON = 32;

    /**
     * Сфера радиуса поля шейдером оболочки фазы: края светятся как мыльный пузырь, цвет течёт
     * синий -> голубой -> золотой, бегут полосы. Чем сильнее замедление, тем ярче.
     */
    private static void renderBubble(TimeDilationFieldEntity entity, PoseStack poseStack,
                                     MultiBufferSource bufferSource, double radius) {
        if (!com.chronomancy.client.entity.TimePhaseRendering.available()) {
            return;
        }
        double rate = entity.getTemporalRate();
        float alpha = (float) Math.min(0.85, 0.35 + 0.45 * (1.0 - rate));
        int argb = ((int) (alpha * 255.0F) << 24) | 0xFFFFFF;
        VertexConsumer consumer = bufferSource.getBuffer(
                com.chronomancy.client.entity.TimePhaseRendering.fieldBubble(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float r = (float) radius;
        for (int i = 0; i < BUBBLE_LAT; i++) {
            double t0 = Math.PI * i / BUBBLE_LAT, t1 = Math.PI * (i + 1) / BUBBLE_LAT;
            for (int j = 0; j < BUBBLE_LON; j++) {
                double p0 = 2 * Math.PI * j / BUBBLE_LON, p1 = 2 * Math.PI * (j + 1) / BUBBLE_LON;
                bubbleVertex(pose, consumer, argb, r, t0, p0, j, i);
                bubbleVertex(pose, consumer, argb, r, t1, p0, j, i + 1);
                bubbleVertex(pose, consumer, argb, r, t1, p1, j + 1, i + 1);
                bubbleVertex(pose, consumer, argb, r, t0, p1, j + 1, i);
            }
        }
    }

    private static void bubbleVertex(PoseStack.Pose pose, VertexConsumer consumer, int argb, float r,
                                     double theta, double phi, int u, int v) {
        float nx = (float) (Math.sin(theta) * Math.cos(phi));
        float ny = (float) Math.cos(theta);
        float nz = (float) (Math.sin(theta) * Math.sin(phi));
        consumer.addVertex(pose, nx * r, ny * r, nz * r)
                .setColor(argb)
                .setUv(u * 2.0F, v * 4.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    /** Клиентский поиск владельца поля (для плавного центрирования следящей сферы). */
    private static Entity findClientOwner(TimeDilationFieldEntity field) {
        UUID uuid = field.getChronoOwnerUUID().orElse(null);
        if (uuid == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && uuid.equals(minecraft.player.getUUID())) {
            return minecraft.player;
        }
        for (LivingEntity living : field.level().getEntitiesOfClass(
                LivingEntity.class, field.getBoundingBox().inflate(24.0))) {
            if (living.getUUID().equals(uuid)) {
                return living;
            }
        }
        return null;
    }

    // =========================================================
    // ЗОЛОТАЯ ПЛЁНКА НА БЛОКАХ ВНУТРИ СФЕРЫ
    // =========================================================

    private static void renderBlockFilm(Level level, PoseStack poseStack,
                                        MultiBufferSource bufferSource, Vec3 center,
                                        double radius) {
        VertexConsumer consumer = bufferSource.getBuffer(filmType());
        double r2 = radius * radius;

        int minX = (int) Math.floor(center.x - radius);
        int maxX = (int) Math.ceil(center.x + radius);
        int minY = (int) Math.floor(center.y - radius);
        int maxY = (int) Math.ceil(center.y + radius);
        int minZ = (int) Math.floor(center.z - radius);
        int maxZ = (int) Math.ceil(center.z + radius);

        int drawn = 0;
        for (int x = minX; x <= maxX; x++) {
            double dx = x + 0.5 - center.x;
            for (int y = minY; y <= maxY; y++) {
                double dy = y + 0.5 - center.y;
                if (dx * dx + dy * dy > r2) {
                    continue;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    double dz = z + 0.5 - center.z;
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (!isFilmCandidate(level.getBlockState(pos), level, pos)) {
                        continue;
                    }
                    // Полностью засыпанные блоки не рисуем: их плёнка всё равно
                    // не проходит depth-test, а вершин жаль.
                    if (isBuried(level, pos)) {
                        continue;
                    }
                    // Плёнка берёт РЕАЛЬНОЕ освещение блока (в ночи/пещерах тускнеет
                    // вместе с миром), иначе она выглядела бы как самоподсвеченная.
                    // Значение кладём в sky-канал lightmap — он нейтрально-белый,
                    // без оранжевого подмеса от факелов.
                    int light = LightTexture.pack(level.getRawBrightness(pos, 0), 0);
                    filmCube(poseStack.last(), consumer, light,
                            x - center.x - FILM_INFLATE,
                            y - center.y - FILM_INFLATE,
                            z - center.z - FILM_INFLATE,
                            x + 1.0 - center.x + FILM_INFLATE,
                            y + 1.0 - center.y + FILM_INFLATE,
                            z + 1.0 - center.z + FILM_INFLATE);
                    if (++drawn >= MAX_FILM_BLOCKS) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Красим только ПОЛНЫЕ кубические блоки. Растения (трава, цветы) формально
     * имеют RenderShape.MODEL, но это крестовидные модели с пустой/малой collision
     * shape — вокруг них плёнка превращалась в жёлтый «аквариум». Сравнение
     * collision-формы с полным кубом отсекает и их, и плиты/ступени/факелы.
     */
    static boolean isFilmCandidate(BlockState state, Level level, BlockPos pos) {
        return !state.isAir()
                && state.getRenderShape() == RenderShape.MODEL
                && !state.hasBlockEntity()
                && state.getCollisionShape(level, pos).equals(Shapes.block());
    }

    /** Все 6 соседей — такие же полные кубы => блок не виден изнутри сферы. */
    static boolean isBuried(Level level, BlockPos pos) {
        return isFilmCandidate(level.getBlockState(pos.north()), level, pos.north())
                && isFilmCandidate(level.getBlockState(pos.south()), level, pos.south())
                && isFilmCandidate(level.getBlockState(pos.east()), level, pos.east())
                && isFilmCandidate(level.getBlockState(pos.west()), level, pos.west())
                && isFilmCandidate(level.getBlockState(pos.above()), level, pos.above())
                && isFilmCandidate(level.getBlockState(pos.below()), level, pos.below());
    }

    private static void filmCube(PoseStack.Pose pose, VertexConsumer consumer, int light,
                                 double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
        float x0 = (float) minX;
        float y0 = (float) minY;
        float z0 = (float) minZ;
        float x1 = (float) maxX;
        float y1 = (float) maxY;
        float z1 = (float) maxZ;
        // -Z, +Z, -X, +X, -Y, +Y
        filmQuad(pose, consumer, light, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        filmQuad(pose, consumer, light, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        filmQuad(pose, consumer, light, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        filmQuad(pose, consumer, light, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        filmQuad(pose, consumer, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        filmQuad(pose, consumer, light, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
    }

    private static void filmQuad(PoseStack.Pose pose, VertexConsumer consumer, int light,
                                 float ax, float ay, float az, float bx, float by, float bz,
                                 float cx, float cy, float cz, float dx, float dy, float dz) {
        filmVertex(pose, consumer, light, ax, ay, az);
        filmVertex(pose, consumer, light, bx, by, bz);
        filmVertex(pose, consumer, light, cx, cy, cz);
        filmVertex(pose, consumer, light, dx, dy, dz);
    }

    private static void filmVertex(PoseStack.Pose pose, VertexConsumer consumer, int light,
                                   float x, float y, float z) {
        consumer.addVertex(pose, x, y, z)
                .setColor(GOLD_RGB | (FILM_ALPHA << 24))
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    static RenderType filmType() {
        if (filmType == null) {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(WHITE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    // LESS: плёнку перекрывает рельеф перед полем — сквозь стены не светит.
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("chronomancy_less", 513))
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    // Глубина ПИШЕТСЯ (дефолтный write mask): иначе плёнки всех кубов
                    // вдоль луча накапливались и давали «светящуюся кашу» вместо tint'а.
                    // Первый попавшийся слой закрывает остальные, яркость стабильна.
                    .createCompositeState(false);
            filmType = RenderType.create(
                    "chronomancy_time_dilation_film",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256,
                    state
            );
        }
        return filmType;
    }

    // =========================================================
    // ПИКСЕЛЬНАЯ ПУНКТИРНАЯ ГРАНИЦА (textured quads, НЕ GL-lines)
    // =========================================================
    //
    // Граница — это НЕ сплошной wireframe и НЕ sci-fi силовое поле. Это магический
    // конструкт из дискретных пиксельных глифов (штрихи/точки/часовые руны),
    // собранных в 2 медленно и НЕРАВНОМЕРНО вращающихся кольца + редкие метки по
    // поверхности. Форма читается пунктиром «— · ▪ —», а не математическими
    // окружностями. Билборды берутся из камеры, поэтому каждый глиф — это
    // маленький прямоугольник с nearest-фильтрацией и жёстким альфа-краем
    // (16x16-пиксельный язык Minecraft), а не гладкая светящаяся линия.
    //
    // Центр/радиус/темп читаются из сущности; объём поля уже показывает sepia
    // shader, поэтому граница физически не обязана закрывать всю сферу.
    
    private static void renderBoundary(TimeDilationFieldEntity entity, PoseStack poseStack,
                                       MultiBufferSource bufferSource, Vec3 center,
                                       double radius, float partialTick) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Quaternionf rot = camera.rotation();
        Vector3f right = rot.transform(1.0F, 0.0F, 0.0F, new Vector3f());
        Vector3f up = rot.transform(0.0F, 1.0F, 0.0F, new Vector3f());
    
        VertexConsumer consumer = bufferSource.getBuffer(glyphType());
        PoseStack.Pose pose = poseStack.last();
    
        double rate = entity.getTemporalRate();                     // ниже => сильнее замедлен
        double phase = (entity.tickCount + partialTick) * 0.016;    // «секунды» кадра
        int seed = entity.getId();                                  // стабильный вид одного поля
        double size = clamp(radius * 0.09, 0.24, 0.95);
        float pulse = entity.getBoundaryPulse();                    // задел на пересечение (пока 0)
    
        // Кольцо 1 — экваториальное (плоскость XZ).
        renderRing(pose, consumer, right, up, radius, size, phase, rate, seed, 0,
                1.0, 0.0, 0.0, 0.0, 0.0, 1.0, pulse);
        // Кольцо 2 — наклонная большая окружность (другая плоскость, не даёт «вертикальной линии»). 
        double tilt = 1.15;
        renderRing(pose, consumer, right, up, radius, size, phase, rate, seed, 1,
                1.0, 0.0, 0.0, 0.0, Math.cos(tilt), Math.sin(tilt), pulse);
    
        renderSurfaceRunes(pose, consumer, right, up, radius, size, phase, rate, seed, pulse);
    }
    
    /** Одно разрывное кольцо из глифов. Часть слотов пропущена => пунктир, а не окружность. */
    private static void renderRing(PoseStack.Pose pose, VertexConsumer consumer,
                                   Vector3f right, Vector3f up, double radius, double size,
                                   double phase, double rate, int seed, int ringId,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz, float pulse) {
        int slots = (int) clamp(radius * 9.0, 48, 220);
        // Медленное НЕРАВНОМЕРНОЕ вращение: шаг-с-паузой, скорость ~ temporalRate.
        double orbit = stepWarp((phase + ringId * 4.3) * (0.35 + 0.5 * rate), 0.14) * 0.6;
        for (int i = 0; i < slots; i++) {
            if (hash(seed + ringId * 101, i) > 0.85) {
                continue; // разрыв -> пунктирная граница (плотный, но всё ещё прерывистый)
            }
            double ang = (2.0 * Math.PI * i / slots) + orbit + hash(seed, i + 7) * 0.05;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double dx = ax * ca + bx * sa;
            double dy = ay * ca + by * sa;
            double dz = az * ca + bz * sa;
            double life = frac(phase * (0.12 + 0.1 * rate) + hash(seed, i + 3));
            float alpha = (float) (fadeInOut(life) * (0.55 + 0.45 * hash(seed, i + 5)));
            if (pulse > 0.01F) {
                alpha = (float) Math.min(1.0, alpha + pulse * 0.6);
            }
            if (alpha < 0.03F) {
                continue;
            }
            int tile = pickRingTile(hash(seed, i + 13));
            int color = pickColor(hash(seed + ringId, i + 11));
            glyphQuad(pose, consumer, right, up, dx * radius, dy * radius, dz * radius,
                    size, tile, color, alpha);
        }
    }
    
    /** Редкие rune/sand метки по поверхности сферы (Фибоначчи-раскладка, разреженно). */
    private static void renderSurfaceRunes(PoseStack.Pose pose, VertexConsumer consumer,
                                           Vector3f right, Vector3f up, double radius, double size,
                                           double phase, double rate, int seed, float pulse) {
        int n = (int) clamp(radius * 7.0, 30, 110);
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < n; i++) {
            double y = 1.0 - (i / (double) Math.max(1, n - 1)) * 2.0; // 1..-1
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double th = golden * i + stepWarp(phase * (0.2 + 0.3 * rate) + i, 0.2) * 0.15;
            double dx = Math.cos(th) * r;
            double dz = Math.sin(th) * r;
            double life = frac(phase * (0.09 + 0.06 * rate) + hash(seed, i + 21));
            float alpha = (float) (fadeInOut(life) * 0.8);
            if (pulse > 0.01F) {
                alpha = (float) Math.min(1.0, alpha + pulse * 0.5);
            }
            if (alpha < 0.03F) {
                continue;
            }
            int tile = 3 + (int) (hash(seed, i + 31) * 5.0); // 3..7: ромб/часы/руна/скобка/песок
            int color = pickColor(hash(seed, i + 41));
            glyphQuad(pose, consumer, right, up, dx * radius, y * radius, dz * radius,
                    size * 1.15, tile, color, alpha);
        }
    }
    
    /** Билборд-квад одного глифа: позиция относительно центра, UV берёт тайл из атласа. */
    static void glyphQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                  Vector3f right, Vector3f up, double cx, double cy, double cz,
                                  double size, int tile, int rgb, float alpha) {
        float h = (float) size * 0.5F;
        float rx = right.x * h, ry = right.y * h, rz = right.z * h;
        float ux = up.x * h, uy = up.y * h, uz = up.z * h;
        float u0 = tile / 8.0F, u1 = (tile + 1) / 8.0F;
        int argb = rgb | ((int) (clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24);
        float x = (float) cx, y = (float) cy, z = (float) cz;
        glyphVertex(pose, consumer, argb, x - rx - ux, y - ry - uy, z - rz - uz, u0, 1.0F);
        glyphVertex(pose, consumer, argb, x + rx - ux, y + ry - uy, z + rz - uz, u1, 1.0F);
        glyphVertex(pose, consumer, argb, x + rx + ux, y + ry + uy, z + rz + uz, u1, 0.0F);
        glyphVertex(pose, consumer, argb, x - rx + ux, y - ry + uy, z - rz + uz, u0, 0.0F);
    }
    
    private static void glyphVertex(PoseStack.Pose pose, VertexConsumer consumer, int argb,
                                    float x, float y, float z, float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
    
    static RenderType glyphType() {
        if (glyphType == null) {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    // blur=false -> NEAREST (пиксельность), mipmap=false -> без размытия вдали.
                    .setTextureState(new RenderStateShard.TextureStateShard(GLYPHS, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    // LESS: глифы перекрываются рельефом перед полем (сквозь стены не светят).
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("chronomancy_glyph_less", 513))
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .createCompositeState(false);
            glyphType = RenderType.create(
                    "chronomancy_time_dilation_boundary",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    2048,
                    state
            );
        }
        return glyphType;
    }
    
    // ------------------------------ хелперы анимации/раскладки ------------------------------
    
    /** Детерминированный [0,1) из двух int (стабильный вид одного поля между кадрами). */
    static double hash(int a, int b) {
        long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 30)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
    
    private static double frac(double v) {
        return v - Math.floor(v);
    }
    
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    
    /** Независимая жизнь сегмента: плавно появился -> держится -> плавно исчез. */
    private static double fadeInOut(double life) {
        return smooth01(life / 0.18) * smooth01((0.94 - life) / 0.24);
    }
    
    private static double smooth01(double x) {
        x = clamp(x, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }
    
    /**
     * Неравномерный ход «сдвиг -> короткая пауза -> сдвиг»: доля каждого шага
     * стоит на месте, затем быстро перескакивает к следующему. Даёт ощущение
     * нарушенного течения времени на границе.
     */
    private static double stepWarp(double phase, double step) {
        double s = phase / step;
        double idx = Math.floor(s);
        double fr = s - idx;
        double eased = fr < 0.42 ? 0.0 : smooth01((fr - 0.42) / 0.58);
        return (idx + eased) * step;
    }
    
    /** В основном штрихи/точки/тики; редко — часовой/рунный глиф. */
    private static int pickRingTile(double r) {
        if (r < 0.46) return 0;   // dash
        if (r < 0.68) return 1;   // dot
        if (r < 0.86) return 2;   // tick
        if (r < 0.94) return 4;   // rare broken-clock
        return 5;                 // rare rune
    }
    
    /** Распределение палитры: чаще песок/золото, бронза как тень, хайлайт редко (яркие пиксели). */
    private static int pickColor(double r) {
        if (r < 0.20) return C_BRONZE;
        if (r < 0.42) return C_SAND;
        if (r < 0.64) return C_GOLD;
        if (r < 0.78) return C_HILITE;
        if (r < 0.92) return C_BLUE;
        return C_SKY;
    }
    

    // package-private static: переиспользуются рендерером снаряда-«клубка»
    static void circleXZ(PoseStack poseStack, VertexConsumer consumer, int alpha,
                                 double y, double radius, int segments) {
        for (int i = 0; i < segments; i++) {
            double a0 = 2.0 * Math.PI * i / segments;
            double a1 = 2.0 * Math.PI * (i + 1) / segments;
            lineVertex(poseStack, consumer, alpha,
                    (float) (Math.cos(a0) * radius), (float) y, (float) (Math.sin(a0) * radius));
            lineVertex(poseStack, consumer, alpha,
                    (float) (Math.cos(a1) * radius), (float) y, (float) (Math.sin(a1) * radius));
        }
    }

    static void circleXY(PoseStack poseStack, VertexConsumer consumer, int alpha,
                                 int segments) {
        for (int i = 0; i < segments; i++) {
            double a0 = 2.0 * Math.PI * i / segments;
            double a1 = 2.0 * Math.PI * (i + 1) / segments;
            lineVertex(poseStack, consumer, alpha,
                    (float) Math.cos(a0), (float) Math.sin(a0), 0.0F);
            lineVertex(poseStack, consumer, alpha,
                    (float) Math.cos(a1), (float) Math.sin(a1), 0.0F);
        }
    }

    static void circleZY(PoseStack poseStack, VertexConsumer consumer, int alpha,
                                 int segments) {
        for (int i = 0; i < segments; i++) {
            double a0 = 2.0 * Math.PI * i / segments;
            double a1 = 2.0 * Math.PI * (i + 1) / segments;
            lineVertex(poseStack, consumer, alpha,
                    0.0F, (float) Math.sin(a0), (float) Math.cos(a0));
            lineVertex(poseStack, consumer, alpha,
                    0.0F, (float) Math.sin(a1), (float) Math.cos(a1));
        }
    }

    private static void lineVertex(PoseStack poseStack, VertexConsumer consumer, int alpha,
                                   float x, float y, float z) {
        PoseStack.Pose pose = poseStack.last();
        consumer.addVertex(pose, x, y, z)
                .setColor(GOLD_RGB | (Math.min(alpha, 255) << 24))
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(TimeDilationFieldEntity entity) {
        return null;
    }
}
