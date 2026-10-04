package com.chronomancy.client;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Клиентская инициализация. Регистрируется только на клиенте
 * (см. {@code ChronomancyMod}), поэтому безопасно обращается к клиентским событиям.
 */
public final class ChronoClient {

    private ChronoClient() {
    }

    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> top.theillusivec4.curios.api.client.CuriosRendererRegistry.register(
                com.chronomancy.registry.ChronoItemRegistry.BOOK_OF_TIME_MANAGMENT.get(),
                io.redspace.ironsspellbooks.render.SpellBookCurioRenderer::new));
    }

    /** При выходе из мира сбрасываем клиентский реестр замороженных id, rewind-трейлы и глобальный World Stop. */
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TemporalStasisClientState.clear();
        RewindTrailClientState.clear();
        ClientWorldStopState.clear();
        ClientTemporalFieldManager.clear();
        BorrowedFutureClientState.clear();
        TemporalNeedleClient.clear();
        RiftEchoClient.clear();
        VanillaPhase.clear();
        SpellCopperClient.clear();
        SandStacksClient.clear();
        ParadoxClient.clear();
    }

    /**
     * Регистрация клиентских рендереров. Каждому типу сущности ОБЯЗАТЕЛЬНО
     * сопоставляем рендерер: в 1.21.1 отсутствие рендерера у трекащейся сущности
     * приводит к NPE в {@code EntityRenderDispatcher.shouldRender} (см.
     * {@link TimeDilationFieldRenderer}).
     */
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ChronoEntityTypeRegistry.CHRONO_DOUBLE_ARROW.get(), net.minecraft.client.renderer.entity.TippableArrowRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TEMPORAL_NEEDLE.get(), TemporalNeedleRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TIME_DILATION_FIELD.get(),
                TimeDilationFieldRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TIME_DILATION_ORB.get(),
                TimeDilationOrbRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TIME_PARADOX.get(), TimeParadoxRenderer::new);
        // Конус песка невидим — он только сыплет частицы.
        event.registerEntityRenderer(ChronoEntityTypeRegistry.SANDS_OF_TIME_CONE.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.REWIND_AFTERIMAGE.get(),
                RewindAfterimageRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.BACKTRACK_BOLT.get(),
                BacktrackBoltRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.CHRONO_DOUBLE.get(),
                ChronoDoubleRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.CHRONOMALY.get(),
                com.chronomancy.client.entity.ChronomalyRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.CLOCKSMITH.get(),
                com.chronomancy.client.entity.ClocksmithRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TIME_RIFT.get(),
                com.chronomancy.client.entity.TimeRiftRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.TIME_ISLAND.get(),
                com.chronomancy.client.entity.TimeIslandRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.PLAYER_ECHO.get(),
                com.chronomancy.client.entity.PlayerEchoRenderer::new);
        event.registerEntityRenderer(ChronoEntityTypeRegistry.RIFT_MAKER.get(),
                com.chronomancy.client.entity.RiftMakerRenderer::new);
        // Фазирующие мобы — ванильные модели; фазирование рисует VanillaPhase.
        registerRaw(event, ChronoEntityTypeRegistry.PHASING_ZOMBIE.get(),
                net.minecraft.client.renderer.entity.ZombieRenderer::new);
        registerRaw(event, ChronoEntityTypeRegistry.PHASING_SKELETON.get(),
                net.minecraft.client.renderer.entity.SkeletonRenderer::new);
        registerRaw(event, ChronoEntityTypeRegistry.PHASING_CREEPER.get(),
                net.minecraft.client.renderer.entity.CreeperRenderer::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerRaw(EntityRenderersEvent.RegisterRenderers event,
                                    net.minecraft.world.entity.EntityType type,
                                    net.minecraft.client.renderer.entity.EntityRendererProvider provider) {
        event.registerEntityRenderer(type, provider);
    }
}
