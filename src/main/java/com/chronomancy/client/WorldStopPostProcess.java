package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.IOException;

/**
 * THE WORLD STOP — собственный client-side post-processing (Phase B).
 *
 * <p>Реализовано ИСКЛЮЧИТЕЛЬНО на ванильном {@link PostChain} (тот же стек,
 * что у creeper/spider/enderman экрана) — БЕЗ Iris/Oculus/OptiFine. Единственная
 * точка входа в рендер-пайплайн — миксин {@code WorldStopPostEffectMixin},
 * вставляющий {@link #onRender} сразу после {@code LevelRenderer#doEntityOutline}
 * внутри {@code GameRenderer#render}: ровно там, где ваниль прогоняет собственный
 * postEffect, — весь 3D-мир (включая руку и аутлайны) уже отрисован в
 * {@code minecraft:main}, а HUD/GUI ещё не начинался. Поэтому интерфейс цвету
 * не подвергается (критерии §4/§25), и сразу после нас {@code GameRenderer}
 * сам перевязывает main-таргет и выставляет GUI-орто projection.
 *
 * <p>Эффект масштабируется uniform'ом {@code EffectStrength}: 0 -> побайтово
 * vanilla, 1 -> полный медно-сепиевый вид. Прогресс перехода считается по
 * РЕАЛЬНОМУ времени ({@link Util#getNanos()}), НИКОГДА не по замороженному
 * {@code level.getGameTime()/tickCount} (§12). Цепочка создаётся лениво при
 * первом {@code strength>0} и полностью освобождается ({@link PostChain#close()})
 * при возврате к 0 — в простое GPU-память не держим (§14).
 *
 * <p>Resize окна/fulscreen переживается сверкой размеров main-таргета каждый
 * кадр (§15). F3+T: {@link ReloadListener} на render-потоке закрывает цепочку и
 * сбрасывает флаг аварии — при следующем активном кадре она пересоздаётся из
 * уже перевыпущенных ванильных shared-шейдеров (§16). Сбой загрузки shader только
 * логируется и откатывается к простому тинту ({@link ClientWorldStopTint}), игру
 * не роняем; gameplay World Stop от шейдера не зависит вовсе (§18).
 */
public final class WorldStopPostProcess {

    private static final ResourceLocation CHAIN =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "shaders/post/world_stop.json");

    private static final String UNIFORM_STRENGTH = "EffectStrength";
    private static final String UNIFORM_VIGNETTE = "VignetteAmount";
    private static final String UNIFORM_CHROMATIC = "ChromaticAmount";
    private static final String UNIFORM_GRAIN = "GrainAmount";

    // Интенсивности слоёв (Phase C). Кормятся в шейдер КАЖДЫЙ кадр именно
    // отсюда: будущий клиентский конфиг (§20 ТЗ) = банальная замена этих трёх
    // чисел на значения из опций — ни архитектуры, ни шейдера трогать не нужно.
    private static final float VIGNETTE_AMOUNT = 1.0F;
    private static final float CHROMATIC_AMOUNT = 1.0F;
    private static final float GRAIN_AMOUNT = 1.0F;

    // Время плавных переходов (сек) — вне зависимостей от игрового тика.
    private static final float RAMP_UP_SECONDS = 0.22F;
    private static final float RAMP_DOWN_SECONDS = 0.37F;

    private static PostChain chain;
    private static boolean broken;
    private static float strength;
    private static long lastNanos = Long.MIN_VALUE;
    private static int lastWidth;
    private static int lastHeight;

    private WorldStopPostProcess() {
    }

    /**
     * Вызывается из {@code GameRenderer#render} на render-потоке, когда весь мир
     * уже отрисован в main-таргет, а GUI ещё не рисуется.
     */
    public static void onRender(DeltaTracker deltaTracker) {
        float dt = realTimeDeltaSeconds();

        // В островке времени остановка мира не действует: у того, кто стоит внутри цилиндра, экран
        // снова цветной. Переход — той же плавной кривой, что и начало/конец самой остановки.
        float target = ClientWorldStopState.isActive() && !insideTimeIsland() ? 1.0F : 0.0F;
        float step = (target > strength ? RAMP_UP_SECONDS : RAMP_DOWN_SECONDS);
        float rate = step > 0.0F ? 1.0F / step : 100.0F;
        strength = Mth.clamp(strength + Math.signum(target - strength) * rate * dt, 0.0F, 1.0F);

        // Шейдер не загрузился — молча выходим: тинт-фолбэк сам дорисует сепию.
        if (broken) {
            return;
        }

        // Полностью выключили: дальше — чистый vanilla-кадр. Цепочку освобождаем, только когда остановка
        // кончилась: в островке времени она ещё понадобится, а пересоздавать её на каждом выходе из
        // цилиндра — заметная пауза.
        if (strength <= 0.0F) {
            if (!ClientWorldStopState.isActive()) {
                closeChain();
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (chain == null && !tryLoad(mc)) {
            return;
        }

        RenderTarget screen = mc.getMainRenderTarget();
        if (screen.width != lastWidth || screen.height != lastHeight) {
            chain.resize(screen.width, screen.height);
            lastWidth = screen.width;
            lastHeight = screen.height;
        }

        chain.setUniform(UNIFORM_STRENGTH, strength);
        chain.setUniform(UNIFORM_VIGNETTE, VIGNETTE_AMOUNT);
        chain.setUniform(UNIFORM_CHROMATIC, CHROMATIC_AMOUNT);
        chain.setUniform(UNIFORM_GRAIN, GRAIN_AMOUNT);

        // Тот же GL-стейт, что выставляет ваниль перед process() своего postEffect.
        // После нас GameRenderer перевяжет main-таргет и поставит GUI-projection,
        // поэтому ничего не «протекает» в HUD.
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(deltaTracker.getGameTimeDeltaTicks());
    }

    /** Стоит ли тот, чьими глазами смотрит игрок, в островке времени. */
    private static boolean insideTimeIsland() {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.Entity viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        return viewer != null && com.chronomancy.entity.TimeIslandEntity.anyCovers(viewer);
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
            ChronomancyMod.LOGGER.debug("[WorldStop] post-process shader enabled ({})", CHAIN);
            return true;
        } catch (IOException | RuntimeException e) {
            // Не крешим игру и не спамим: фиксируем аварию до ближайшего F3+T и
            // полагаемся на простой тинт как fallback. (JsonSyntaxException —
            // подкласс RuntimeException, поэтому отдельным пунктом не нужен.)
            ChronomancyMod.LOGGER.warn(
                    "[WorldStop] post-process shader failed to load; using simple tint fallback ({})", CHAIN, e);
            closeChain();
            broken = true;
            return false;
        }
    }

    /** Реальный (wall-clock) delta кадра в секундах; не зависит от world time. */
    private static float realTimeDeltaSeconds() {
        long now = Util.getNanos();
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = now;
            return 0.0F;
        }
        float dt = (now - lastNanos) / 1_000_000_000.0F;
        lastNanos = now;
        // Страховка от лагов/сворачивания окна: не даём переходу «перепрыгнуть».
        return Mth.clamp(dt, 0.0F, 0.1F);
    }

    /**
     * @return {@code true}, если шейдер недоступен, а стоп активен — тогда
     * {@link ClientWorldStopTint} рисует старый простой тинт как единственный
     * визуальный фолбэк (иначе тинт выключен, чтобы не было двойной цветокоррекции).
     */
    public static boolean isTintFallbackActive() {
        return broken && ClientWorldStopState.isActive() && !insideTimeIsland();
    }

    /**
     * @return {@code true}, если шейдер World Stop реально перекрашивает кадр в этот
     * момент (strength>0). Time Dilation volume сверяется с этим и уступает экран
     * стопу (приоритет WORLD STOP > TEMPORAL VOLUME, два grade не складываем).
     */
    public static boolean isShaderActive() {
        return !broken && strength > 0.0F;
    }

    private static void closeChain() {
        if (chain != null) {
            try {
                chain.close();
            } catch (RuntimeException e) {
                ChronomancyMod.LOGGER.warn("[WorldStop] failed to close post-process chain cleanly", e);
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
     * Переживает F3+T: {@code apply()} вызывается на render-потоке ПОСЛЕ того,
     * как ванильный {@code GameRenderer} освобождает/пересобирает shared-шейдеры
     * (слушатели применяются в порядке регистрации, а vanilla — раньше модов).
     * Закрываем свою цепочку (она держит ссылки на старые shared-программы) и
     * сбрасываем аварию — пересоздание произойдёт лениво на следующем кадре.
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
            return "chronomancy:world_stop_post";
        }
    }
}
