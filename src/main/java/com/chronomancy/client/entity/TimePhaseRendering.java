package com.chronomancy.client.entity;

import com.chronomancy.ChronomancyMod;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import software.bernie.geckolib.cache.object.GeoBone;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * «Фазирование сквозь время» (только клиент) — общее для Rift Maker, Chronomaly, копий Rift,
 * медных двойников игрока и Chrono Double:
 * <ol>
 *   <li><b>Оболочка</b> — модель рисуется второй раз, чуть раздутой, шейдером {@code rift_phase_shell}:
 *       переливающееся (синий → голубой → золотой) свечение по краям силуэта и бегущие полосы;</li>
 *   <li><b>Выпадения</b> — время от времени одна часть тела на ~1.4 с попиксельно растворяется шейдером
 *       {@code rift_phase_dissolve} со светящейся кромкой и слегка «глитчит» в сторону;</li>
 *   <li><b>Эхо-силуэты</b> — при телепорте 3 силуэта вдоль пути, при быстром движении — шлейф.</li>
 * </ol>
 * Время шейдеров идёт от системных часов, поэтому эффект живёт и во время The World Stop.
 * Если шейдеры не загрузились (например, из-за шейдерпака), эффекты просто выключаются.
 */
public final class TimePhaseRendering {

    private static ShaderInstance shellShader;
    private static ShaderInstance dissolveShader;
    private static ShaderInstance stasisShader;
    /** Остаточные копии заклинаний: плотнее и заметнее оболочки (см. rift_phase_echo.fsh). */
    private static ShaderInstance echoShader;
    private static final Map<RenderType, RenderType[]> VANILLA_ECHO = new IdentityHashMap<>();
    private static final Map<RenderType, RenderType> VANILLA_STASIS = new IdentityHashMap<>();
    private static final Map<ResourceLocation, RenderType> GEO_SHELL = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GEO_SHELL_IMMUNE = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GEO_DISSOLVE = new HashMap<>();
    private static final Map<RenderType, RenderType[]> VANILLA_SHELL = new IdentityHashMap<>();
    private static final Map<RenderType, RenderType[]> VANILLA_DISSOLVE = new IdentityHashMap<>();
    private static final int ALPHA_BUCKETS = 16;

    public static final int CYCLE_TICKS = 70;
    static final float PHASE_TICKS = 28.0F;
    public static final float SHELL_ALPHA = 0.55F;
    private static final int MAX_GHOSTS = 6;
    /** Сколько живёт любая остаточная копия: 1.5 секунды. */
    public static final int GHOST_LIFE = 30;

    private TimePhaseRendering() {
    }

    // =========================================================
    // Шейдеры
    // =========================================================

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift_phase_shell"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                shellShader = s;
                clearCaches();
            });
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift_phase_dissolve"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                dissolveShader = s;
                clearCaches();
            });
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift_phase_echo"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                echoShader = s;
                clearCaches();
            });
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "stasis_echo"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                stasisShader = s;
                clearCaches();
            });
        } catch (IOException e) {
            shellShader = null;
            dissolveShader = null;
            stasisShader = null;
            ChronomancyMod.LOGGER.warn("Time phase shaders failed to load, effect disabled: {}", e.toString());
        }
        // Шейдеры Accelerated Zone и вспышки парадокса — отдельно: их сбой не должен гасить остальные
        // (тогда грани зоны и оболочки вспышки рисуются обычной оболочкой фазы).
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "accel_zone"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                zoneShader = s;
                clearCaches();
            });
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "paradox_blast"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                paradoxShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            zoneShader = null;
            paradoxShader = null;
            ChronomancyMod.LOGGER.warn("Accelerated Zone shaders failed to load, falling back to the phase shell: {}",
                    e.toString());
        }
        // След песков босса — тоже отдельно: без шейдера пятно рисуется ровной заливкой.
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "sands_footprint"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                sandsShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            sandsShader = null;
            ChronomancyMod.LOGGER.warn("Sands footprint shader failed to load, falling back to a plain fill: {}", e.toString());
        }
        // Круги ударов босса — каждый сам по себе: без шейдера рисуется ровный круг.
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "heavy_shockwave"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                heavyShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            heavyShader = null;
            ChronomancyMod.LOGGER.warn("Heavy shockwave shader failed to load, falling back to a plain circle: {}", e.toString());
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "stasis_dial"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                stasisDialShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            stasisDialShader = null;
            ChronomancyMod.LOGGER.warn("Stasis dial shader failed to load, falling back to a plain circle: {}", e.toString());
        }
        // Островок времени — тоже сам по себе: без шейдера рисуется ровный круг.
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_island"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                timeIslandShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            timeIslandShader = null;
            ChronomancyMod.LOGGER.warn("Time island shader failed to load, falling back to a plain circle: {}", e.toString());
        }
        // Стена островка времени: без шейдера цилиндр просто не рисуется, пятно на земле остаётся.
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_island_wall"),
                    DefaultVertexFormat.NEW_ENTITY), s -> {
                timeIslandWallShader = s;
                clearCaches();
            });
        } catch (IOException | RuntimeException e) {
            timeIslandWallShader = null;
            ChronomancyMod.LOGGER.warn("Time island wall shader failed to load, the cylinder will not be drawn: {}", e.toString());
        }
    }

    private static ShaderInstance timeIslandShader;
    private static RenderType timeIsland;
    private static ShaderInstance timeIslandWallShader;
    private static RenderType timeIslandWall;

    public static boolean timeIslandWallShaderActive() {
        return timeIslandWallShader != null;
    }

    /** Стена цилиндра островка времени: аддитивно, видна с обеих сторон, глубину не пишет (как грани зоны). */
    public static RenderType timeIslandWall(ResourceLocation whiteTexture) {
        if (timeIslandWall == null) {
            timeIslandWall = RenderType.create("chronomancy_time_island_wall",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 2048, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> timed(timeIslandWallShader)))
                            .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return timeIslandWall;
    }

    public static boolean timeIslandShaderActive() {
        return timeIslandShader != null;
    }

    /** Пятно островка времени (см. {@link TimeIslandRenderer}). */
    public static RenderType timeIsland(ResourceLocation whiteTexture) {
        if (timeIsland == null) {
            timeIsland = groundDecal("chronomancy_time_island", whiteTexture, () -> timed(timeIslandShader));
        }
        return timeIsland;
    }

    private static ShaderInstance zoneShader;
    private static ShaderInstance paradoxShader;
    private static ShaderInstance sandsShader;
    private static RenderType zoneFace;
    private static RenderType paradoxShell;
    private static RenderType sandsFootprint;

    private static ShaderInstance heavyShader;
    private static ShaderInstance stasisDialShader;
    private static RenderType heavyShockwave;
    private static RenderType stasisDial;

    public static boolean heavyShaderActive() {
        return heavyShader != null;
    }

    public static boolean stasisDialShaderActive() {
        return stasisDialShader != null;
    }

    private static ShaderInstance timed(ShaderInstance shader) {
        shader.safeGetUniform("PhaseTime").set(time());
        return shader;
    }

    /** Круг усиленного удара Rift Maker (см. {@link BossGroundFxRendering}). */
    public static RenderType heavyShockwave(ResourceLocation whiteTexture) {
        if (heavyShockwave == null) {
            heavyShockwave = groundDecal("chronomancy_heavy_shockwave", whiteTexture, () -> timed(heavyShader));
        }
        return heavyShockwave;
    }

    /** Циферблат удара стазиса Rift Maker (см. {@link BossGroundFxRendering}). */
    public static RenderType stasisDial(ResourceLocation whiteTexture) {
        if (stasisDial == null) {
            stasisDial = groundDecal("chronomancy_stasis_dial", whiteTexture, () -> timed(stasisDialShader));
        }
        return stasisDial;
    }

    /** Рисунок на земле: обычное альфа-смешивание, виден с обеих сторон, глубину не пишет. */
    private static RenderType groundDecal(String name, ResourceLocation whiteTexture,
                                          java.util.function.Supplier<ShaderInstance> shader) {
        return RenderType.create(name,
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 2048, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                        .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    /** Загружен ли шейдер следа Sands of Time (иначе {@link SandsConeRendering} рисует запасной вид). */
    public static boolean sandsShaderActive() {
        return sandsShader != null;
    }

    private static ShaderInstance sands() {
        sandsShader.safeGetUniform("PhaseTime").set(time());
        return sandsShader;
    }

    /** След струи песков на земле: обычное альфа-смешивание, виден с обеих сторон, глубину не пишет. */
    public static RenderType sandsFootprint(ResourceLocation whiteTexture) {
        if (sandsFootprint == null) {
            sandsFootprint = RenderType.create("chronomancy_sands_footprint",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::sands))
                            .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return sandsFootprint;
    }

    private static ShaderInstance zone() {
        if (zoneShader == null) {
            return shell();
        }
        zoneShader.safeGetUniform("PhaseTime").set(time());
        return zoneShader;
    }

    private static ShaderInstance paradox() {
        if (paradoxShader == null) {
            return shell();
        }
        paradoxShader.safeGetUniform("PhaseTime").set(time());
        return paradoxShader;
    }

    /** Есть собственный шейдер граней зоны (иначе {@link #zoneFace} рисует обычной оболочкой фазы). */
    public static boolean zoneShaderActive() {
        return zoneShader != null;
    }

    public static boolean paradoxShaderActive() {
        return paradoxShader != null;
    }

    /** Грани куба Accelerated Zone: аддитивно, видны с обеих сторон, глубину не пишут. */
    public static RenderType zoneFace(ResourceLocation whiteTexture) {
        if (zoneFace == null) {
            zoneFace = RenderType.create("chronomancy_zone_face",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::zone))
                            .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return zoneFace;
    }

    /** Оболочки вспышки парадокса: то же смешивание, свой шейдер с «рывками» времени. */
    public static RenderType paradoxShell(ResourceLocation whiteTexture) {
        if (paradoxShell == null) {
            paradoxShell = RenderType.create("chronomancy_paradox_shell",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::paradox))
                            .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return paradoxShell;
    }

    private static void clearCaches() {
        fieldBubble = null;
        zoneFace = null;
        paradoxShell = null;
        sandsFootprint = null;
        heavyShockwave = null;
        stasisDial = null;
        timeIsland = null;
        timeIslandWall = null;
        GEO_SHELL.clear();
        GEO_SHELL_IMMUNE.clear();
        GEO_DISSOLVE.clear();
        VANILLA_SHELL.clear();
        VANILLA_ECHO.clear();
        VANILLA_DISSOLVE.clear();
        VANILLA_STASIS.clear();
    }

    public static boolean available() {
        return shellShader != null && dissolveShader != null && stasisShader != null;
    }

    private static float time() {
        return (Util.getMillis() % 3_600_000L) / 1000.0F;
    }

    private static ShaderInstance shell() {
        shellShader.safeGetUniform("PhaseTime").set(time());
        shellShader.safeGetUniform("Immune").set(0.0F);
        return shellShader;
    }

    /** Сила «неуязвимой» окраски для {@link #geoShellImmune}: читается в момент отрисовки партии. */
    private static float immuneLevel;

    private static ShaderInstance shellImmune() {
        shellShader.safeGetUniform("PhaseTime").set(time());
        shellShader.safeGetUniform("Immune").set(immuneLevel);
        return shellShader;
    }

    private static ShaderInstance echo() {
        echoShader.safeGetUniform("PhaseTime").set(time());
        return echoShader;
    }

    private static ShaderInstance dissolve(boolean copper) {
        dissolveShader.safeGetUniform("PhaseTime").set(time());
        dissolveShader.safeGetUniform("Copper").set(copper ? 1.0F : 0.0F);
        return dissolveShader;
    }

    // =========================================================
    // RenderType для GeckoLib (по текстуре сущности)
    // =========================================================

    static RenderType geoShell(ResourceLocation texture) {
        return GEO_SHELL.computeIfAbsent(texture, tex -> RenderType.create("chronomancy_phase_shell",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::shell))
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .createCompositeState(false)));
    }

    /**
     * Оболочка сущности, сквозь которую сейчас проходят снаряды: тот же шейдер, но палитра уходит
     * из сине-золотой в фиолетово-розовую и тело целиком подсвечивается ({@code level} 0..1).
     */
    static RenderType geoShellImmune(ResourceLocation texture, float level) {
        immuneLevel = level;
        return GEO_SHELL_IMMUNE.computeIfAbsent(texture, tex -> RenderType.create("chronomancy_phase_shell_immune",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::shellImmune))
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .createCompositeState(false)));
    }

    private static RenderType fieldBubble;

    /** Переливающийся «пузырь времени» вокруг поля: та же оболочка, видна и изнутри (без culling). */
    public static RenderType fieldBubble(ResourceLocation whiteTexture) {
        if (fieldBubble == null) {
            fieldBubble = RenderType.create("chronomancy_field_bubble",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(TimePhaseRendering::shell))
                            .setTextureState(new RenderStateShard.TextureStateShard(whiteTexture, false, false))
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return fieldBubble;
    }

    static RenderType geoDissolve(ResourceLocation texture) {
        return GEO_DISSOLVE.computeIfAbsent(texture, tex -> RenderType.create("chronomancy_phase_dissolve",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> dissolve(false)))
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .createCompositeState(true)));
    }

    // =========================================================
    // RenderType для обычных (ванильных/модовых) рендереров: оборачиваем их собственный тип
    // =========================================================

    /** Тот же тип (текстура, culling…), но шейдер оболочки, аддитивно, без записи глубины. */
    public static RenderType vanillaShell(RenderType original, float alpha) {
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return null;
        }
        int bucket = Mth.clamp(Math.round(alpha * (ALPHA_BUCKETS - 1)), 1, ALPHA_BUCKETS - 1);
        RenderType[] row = VANILLA_SHELL.computeIfAbsent(original, k -> new RenderType[ALPHA_BUCKETS]);
        if (row[bucket] == null) {
            float a = bucket / (float) (ALPHA_BUCKETS - 1);
            row[bucket] = new RenderType("chronomancy_phase_shell/" + original, original.format(), original.mode(),
                    original.bufferSize(), false, true,
                    () -> {
                        original.setupRenderState();
                        RenderSystem.setShader(TimePhaseRendering::shell);
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                        RenderSystem.depthMask(false);
                        RenderSystem.enableCull();
                        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, a);
                    },
                    () -> {
                        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                        RenderSystem.depthMask(true);
                        RenderSystem.defaultBlendFunc();
                        RenderSystem.disableBlend();
                        original.clearRenderState();
                    }) {
            };
        }
        return row[bucket];
    }

    /**
     * Остаточная копия: тот же тип, но шейдер {@code rift_phase_echo}, обычное альфа-смешивание и
     * запись глубины — плотный узнаваемый силуэт, видимый и днём (оболочка аддитивна и почти
     * пропадает на светлом фоне). Без шейдера — откат к оболочке.
     */
    public static RenderType vanillaEcho(RenderType original, float alpha) {
        if (echoShader == null) {
            return vanillaShell(original, alpha);
        }
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return null;
        }
        int bucket = Mth.clamp(Math.round(alpha * (ALPHA_BUCKETS - 1)), 1, ALPHA_BUCKETS - 1);
        RenderType[] row = VANILLA_ECHO.computeIfAbsent(original, k -> new RenderType[ALPHA_BUCKETS]);
        if (row[bucket] == null) {
            float a = bucket / (float) (ALPHA_BUCKETS - 1);
            row[bucket] = new RenderType("chronomancy_phase_echo/" + original, original.format(), original.mode(),
                    original.bufferSize(), false, true,
                    () -> {
                        original.setupRenderState();
                        RenderSystem.setShader(TimePhaseRendering::echo);
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        RenderSystem.depthMask(true);
                        RenderSystem.enableCull();
                        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, a);
                    },
                    () -> {
                        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                        RenderSystem.disableBlend();
                        original.clearRenderState();
                    }) {
            };
        }
        return row[bucket];
    }

    /** Тот же тип, но шейдер растворения ({@code copper} — красить в медь, как копии Rift). */
    public static RenderType vanillaDissolve(RenderType original, boolean copper) {
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return original;
        }
        RenderType[] pair = VANILLA_DISSOLVE.computeIfAbsent(original, k -> new RenderType[2]);
        int i = copper ? 1 : 0;
        if (pair[i] == null) {
            pair[i] = new RenderType("chronomancy_phase_dissolve/" + copper + "/" + original, original.format(),
                    original.mode(), original.bufferSize(), original.affectsCrumbling(), false,
                    () -> {
                        original.setupRenderState();
                        RenderSystem.setShader(() -> dissolve(copper));
                    },
                    original::clearRenderState) {
            };
        }
        return pair[i];
    }

    /** Стазис: тот же тип, но шейдер «застывшего золота» ({@code stasis_echo}). */
    public static RenderType vanillaStasis(RenderType original) {
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return original;
        }
        return VANILLA_STASIS.computeIfAbsent(original, rt -> new RenderType("chronomancy_stasis/" + rt,
                rt.format(), rt.mode(), rt.bufferSize(), rt.affectsCrumbling(), rt.sortOnUpload(),
                () -> {
                    rt.setupRenderState();
                    stasisShader.safeGetUniform("PhaseTime").set(time());
                    RenderSystem.setShader(() -> stasisShader);
                },
                rt::clearRenderState) {
        });
    }

    public static int whiteWithAlpha(float alpha) {
        return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | 0xFFFFFF;
    }

    // =========================================================
    // «Выпадения» частей тела (детерминированно по id и времени сущности)
    // =========================================================

    /**
     * Текущее выпадение: {@code selector} — случайное число для выбора части (кость/ModelPart),
     * прогресс 0..~0.9 и направление глитча.
     */
    public record Phase(int selector, float progress, int glitchSign, boolean glitchFrame) {
    }

    public static Phase phaseOf(Entity entity, float partialTick) {
        return phaseOf(entity, partialTick, CYCLE_TICKS);
    }

    /** @param cycleTicks как часто случаются выпадения (меньше — чаще; не меньше 32). */
    public static Phase phaseOf(Entity entity, float partialTick, int cycleTicks) {
        return phaseOf(entity, partialTick, cycleTicks, 0.0F);
    }

    /**
     * @param unrest 0..1 — «беспокойство» (заряд игл): убирает спокойные циклы, чаще и сильнее
     *               сбоит поза выпадающей части.
     */
    public static Phase phaseOf(Entity entity, float partialTick, int cycleTicks, float unrest) {
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
            return null;
        }
        final int CYCLE_TICKS = Math.max(32, cycleTicks);
        float t = entity.tickCount + partialTick;
        int cycle = Mth.floor(t / CYCLE_TICKS);
        int h = mix(entity.getId() * 0x9E3779B1 + cycle * 0x85EBCA6B);
        if ((h & 0xFF) > 200 + (int) (55 * unrest)) {
            return null; // часть циклов «спокойная»
        }
        int start = (h >>> 8 & 0xFFFF) % (int) (CYCLE_TICKS - PHASE_TICKS);
        float local = t - cycle * CYCLE_TICKS - start;
        if (local < 0 || local > PHASE_TICKS) {
            return null;
        }
        float p = Math.min(0.92F, Mth.sin((float) Math.PI * local / PHASE_TICKS) * 1.15F);
        if (p < 0.03F) {
            return null;
        }
        boolean glitch = p > 0.45F - 0.3F * unrest && (entity.tickCount / 2) % (unrest > 0.5F ? 2 : 3) == 0;
        return new Phase(h >>> 16 & 0x7FFF, p, (h >>> 30 & 1) == 0 ? 1 : -1, glitch);
    }

    static boolean inSubtree(GeoBone bone, String root) {
        for (GeoBone b = bone; b != null; b = b.getParent()) {
            if (root.equals(b.getName())) {
                return true;
            }
        }
        return false;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    // =========================================================
    // Эхо-силуэты
    // =========================================================

    public static final class Ghost {
        public final double x, y, z;
        public final float yaw;
        public final int born;
        public final int life;
        /** Поза сущности в момент появления копии: копия застывает в ней, а не повторяет движения. */
        public final FrozenPose pose;

        Ghost(double x, double y, double z, float yaw, int born, int life, FrozenPose pose) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.born = born;
            this.life = life;
            this.pose = pose;
        }

        /** Яркость силуэта в момент {@code now} (0 — не рисовать): первые 45% жизни — в полную силу. */
        public float alpha(float now) {
            float age = now - born;
            if (age < 0 || age > life) {
                return 0.0F;
            }
            float f = age / life;
            return f < 0.45F ? 0.95F : 0.95F * (1.0F - (f - 0.45F) / 0.55F);
        }
    }

    private static final class Track {
        double px, py, pz;
        int lastTick = -1;
        int cooldown;
        final ArrayDeque<Ghost> ghosts = new ArrayDeque<>();
    }

    private static final Map<Entity, Track> TRACKS = new WeakHashMap<>();

    /** Раз в клиентский тик: ловим телепорт (скачок позиции) и рывки (быстрое движение). */
    public static Iterable<Ghost> track(Entity entity) {
        return track(entity, 0.3D, 4, GHOST_LIFE, true);
    }

    /** Есть ли у сущности ещё не погасшие силуэты (их рисуют и после окончания самого эффекта). */
    public static boolean hasGhosts(Entity entity) {
        Track tr = TRACKS.get(entity);
        if (tr == null || tr.ghosts.isEmpty()) {
            return false;
        }
        int now = entity.tickCount;
        for (Ghost g : tr.ghosts) {
            if (now - g.born <= g.life) {
                return true;
            }
        }
        tr.ghosts.clear();
        return false;
    }

    /** Явный силуэт (например, точка, откуда Backtrack вырвал цель), появится через {@code delay} тиков. */
    public static void addGhost(Entity entity, double x, double y, double z, int delay, int life) {
        Track tr = TRACKS.computeIfAbsent(entity, e -> new Track());
        float yaw = entity instanceof LivingEntity l ? l.yBodyRot : entity.getYRot();
        tr.ghosts.addLast(new Ghost(x, y, z, yaw, entity.tickCount + delay, life, FrozenPose.of(entity)));
        while (tr.ghosts.size() > MAX_GHOSTS + 2) {
            tr.ghosts.removeFirst();
        }
    }

    /**
     * @param trailSpeed с какой скорости (блоков/тик) за сущностью тянется шлейф; {@code < 0} — без шлейфа
     * @param trailEvery раз в сколько тиков оставлять силуэт шлейфа
     * @param trailLife  сколько тиков живёт силуэт шлейфа
     * @param teleports  оставлять силуэты при телепорте (скачке позиции)
     */
    public static Iterable<Ghost> track(Entity entity, double trailSpeed, int trailEvery, int trailLife,
                                        boolean teleports) {
        Track tr = TRACKS.computeIfAbsent(entity, e -> new Track());
        int now = entity.tickCount;
        if (now != tr.lastTick) {
            if (tr.lastTick >= 0) {
                double dx = entity.getX() - tr.px, dy = entity.getY() - tr.py, dz = entity.getZ() - tr.pz;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double horiz = Math.sqrt(dx * dx + dz * dz);
                float yaw = entity instanceof LivingEntity l ? l.yBodyRot : entity.getYRot();
                FrozenPose pose = FrozenPose.of(entity);
                if (tr.cooldown > 0) {
                    tr.cooldown--;
                }
                if (teleports && dist > 1.5D && dist < 64.0D && tr.cooldown == 0) {
                    // Телепорт: силуэт в точке ухода и два — по пути к точке прибытия.
                    double tx = entity.lerpTargetX(), ty = entity.lerpTargetY(), tz = entity.lerpTargetZ();
                    for (int k = 0; k < 3; k++) {
                        double f = k / 3.0D;
                        tr.ghosts.addLast(new Ghost(tr.px + (tx - tr.px) * f, tr.py + (ty - tr.py) * f,
                                tr.pz + (tz - tr.pz) * f, yaw, now + k * 2, GHOST_LIFE, pose));
                    }
                    tr.cooldown = 8;
                } else if (trailSpeed >= 0 && horiz > trailSpeed && dist <= 1.5D && now % Math.max(1, trailEvery) == 0) {
                    tr.ghosts.addLast(new Ghost(tr.px, tr.py, tr.pz, yaw, now, trailLife, pose));
                }
                while (tr.ghosts.size() > MAX_GHOSTS + 2) {
                    tr.ghosts.removeFirst();
                }
                for (Iterator<Ghost> it = tr.ghosts.iterator(); it.hasNext(); ) {
                    Ghost g = it.next();
                    if (now - g.born > g.life) {
                        it.remove();
                    }
                }
            }
            tr.px = entity.getX();
            tr.py = entity.getY();
            tr.pz = entity.getZ();
            tr.lastTick = now;
        }
        return tr.ghosts;
    }
}
