package com.chronomancy;

import com.chronomancy.client.ChronoClient;
import com.chronomancy.client.ChronoWeatherClock;
import com.chronomancy.client.ClientTemporalFieldManager;
import com.chronomancy.client.ClientWorldStopTint;
import com.chronomancy.client.TemporalVolumePostProcess;
import com.chronomancy.client.WorldStopPostProcess;
import com.chronomancy.client.particle.ChronoParticles;
import com.chronomancy.effect.TemporalStasisEvents;
import com.chronomancy.effect.TemporalStasisSyncEvents;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.registry.ChronoAttributes;
import com.chronomancy.registry.ChronoArmorMaterialRegistry;
import com.chronomancy.registry.ChronoCreativeTabRegistry;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.temporal.BacktrackDamageQueue;
import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import com.chronomancy.temporal.TemporalDilationHandler;
import com.chronomancy.temporal.TemporalPlayerActions;
import com.chronomancy.temporal.TemporalPlayerClock;
import com.chronomancy.temporal.TemporalDilationPlayerMovement;
import com.chronomancy.temporal.borrowed.BorrowedFutureManager;
import com.chronomancy.temporal.borrowed.BorrowedFutureMining;
import com.chronomancy.client.BorrowedFutureClientState;
import com.chronomancy.temporal.history.EntityPositionHistory;
import com.chronomancy.temporal.history.RewindPlaybackManager;
import com.chronomancy.temporal.history.TemporalHistoryManager;
import com.chronomancy.temporal.worldstop.WorldStopEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.bus.api.EventPriority;
import org.slf4j.Logger;
import com.chronomancy.effect.TemporalStasisCombatEvents;

@Mod(ChronomancyMod.MODID)
public class ChronomancyMod {
    public static final String MODID = "chronomancy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ChronomancyMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Initializing Iron's Spells 'n Spellbooks: Chronomancy");

        // Серверный конфиг (баланс Time Rift / Chronomaly), per-world serverconfig.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ChronoConfig.SPEC);

        ChronoAttributes.register(modEventBus);
        ChronoArmorMaterialRegistry.register(modEventBus);
        ChronoSounds.register(modEventBus);
        ChronoMobEffectRegistry.register(modEventBus);
        ChronoEntityTypeRegistry.register(modEventBus);
        ChronoParticleRegistry.register(modEventBus);
        ChronoSchools.register(modEventBus);
        ChronoItemRegistry.register(modEventBus);
        ChronoCreativeTabRegistry.register(modEventBus);
        ChronoSpellRegistry.register(modEventBus);
        com.chronomancy.registry.ChronoLootModifiers.register(modEventBus);

        // Сетевой слой: синхронизация состояния стазиса на клиент
        // (mob-эффекты не видны наблюдающим клиентам, hasEffect == false).
        modEventBus.addListener(ChronoNetwork::register);

        // Weapon bonuses must be calculated before either temporal damage buffer captures the hit.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                com.chronomancy.item.ClockHandCombatEvents::onIncomingDamage);
        // Регистрация обработчиков событий для стазиса на NeoForge.EVENT_BUS
        NeoForge.EVENT_BUS.addListener(TemporalStasisEvents::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(TemporalStasisEvents::onEntityTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.TimeMagicImmunity::onEffectApplicable);
        // Шаг сквозь время: уклонение хрономальных мобов и фазирование (Time Walk) от снарядов.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.PhaseDodge::onProjectileImpact);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.PhaseDodge::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(TemporalStasisCombatEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onSpellPreCast);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerActions::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onTracking);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onDeath);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onLeave);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onUnload);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.needle.TemporalNeedleManager::onStop);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TemporalPlayerClock::onServerTick);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerClock::onLogout);
        NeoForge.EVENT_BUS.addListener(TemporalPlayerClock::onServerStopping);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onPreCast);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, BorrowedFutureManager::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onLogout);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onLogin);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onClone);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onStartTracking);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onDimensionChange);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(BorrowedFutureMining::onBreakSpeed);

        // Equippable Curios: delayed damage, last-chance rewind, melee stasis, rest, day anchor, machinery.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onDamage);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onDamageApplied);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onRingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onBookIncomingDamage);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.TimelessBookCasting::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.TimelessBookCasting::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.TimelessBookCasting::onSpellCast);
        // Личный темп от эффектов: слои песка Sands of Time и парадокс.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.SandsOfTime::onLeave);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.SandsOfTime::onStop);
        // Accelerated Zone: ускоренные получают больше урона
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH,
                com.chronomancy.temporal.TemporalDilationHandler::onIncomingDamage);
        // Последнее заклинание игрока — его повторяет двойник-маг Rift Maker.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.LastSpellTracker::onSpellCast);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.LastSpellTracker::onLogout);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.LastSpellTracker::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onAttack);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onSpellCast);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.DayFreezeSync::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.DayFreezeSync::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ChronoCurioEvents::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.ParadoxSync::onAdded);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.ParadoxSync::onRemove);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.ParadoxSync::onExpired);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.ParadoxSync::onDeath);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.ParadoxSync::onStartTracking);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.ParadoxSync::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.ParadoxSync::onRespawn);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.ParadoxSync::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.StasisFatigue::onLeave);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.StasisFatigue::onStop);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.advancement.ChronoAdvancements::onSpellCast);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.advancement.ChronoAdvancements::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ClockworkKeyAcceleration::onLevelPre);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.ClockworkKeyAcceleration::onStop);

        // Grain of Time: feeding a golden clock to lava yields the Chronomancy school focus, 1 in 64.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.GrainOfTimeEvents::onEntityTickPre);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.GrainOfTimeEvents::onLightning);

        // Geminated Ring: the spell imprinted in the worn ring casts twice but charges triple mana.
        // The echo strike fires 0.5s after the paid cast, so it drains on the server tick loop.
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.GeminatedRingEvents::onSpellOnCast);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.GeminatedRingEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.item.GeminatedRingEvents::onServerStopping);

        // === CHRONO DOUBLE: зеркальное медное эхо кастера ===
        // Держит якорь двойника на «другой» стороне игрока, повторяет его melee- и
        // собственные урон-спеллы школы reduced-ударом; визуал — клиентский.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.chronodouble.ChronoDoubleProjectiles::onJoin);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onOwnerIncomingDamage);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onDeath);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onLeave);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onUnload);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleManager::onStop);
        // Generic ISS spell echo: re-run a successful INSTANT cast (non-Chronomancy) from the
        // double via a FakePlayer; only its spell damage is scaled down (heals/summons stay full).
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleSpellEcho::onSpellOnCast);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.chronodouble.ChronoDoubleSpellEcho::reduceDamage);

        // Серверные хуки синхронизации стазиса (не трогают рабочую механику)
        NeoForge.EVENT_BUS.addListener(TemporalStasisSyncEvents::onEffectAdded);
        NeoForge.EVENT_BUS.addListener(TemporalStasisSyncEvents::onEffectRemove);
        NeoForge.EVENT_BUS.addListener(TemporalStasisSyncEvents::onStartTracking);
        NeoForge.EVENT_BUS.addListener(TemporalStasisSyncEvents::onPlayerLogin);

        // Time Dilation: живой счётчик активных полей для быстрого пути в resolveRate
        // (один и тот же обработчик покрывает обе стороны — фильтр по типу сущности).
        NeoForge.EVENT_BUS.addListener(TemporalDilationHandler::onEntityJoined);
        NeoForge.EVENT_BUS.addListener(TemporalDilationHandler::onEntityLeft);
        NeoForge.EVENT_BUS.addListener(TemporalDilationPlayerMovement::onServerTick);

        // Temporal History: постоянная серверная запись снимков игроков для
        // Rewind и будущих history-based заклинаний (Echo, Time Anchor).
        NeoForge.EVENT_BUS.addListener(TemporalHistoryManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(TemporalHistoryManager::onLoggedOut);
        NeoForge.EVENT_BUS.addListener(TemporalHistoryManager::onDeath);
        // Rewind: серверная перемотка тела назад по траектории (вместо мгновенного tp).
        NeoForge.EVENT_BUS.addListener(RewindPlaybackManager::onServerTick);

        // === BACKTRACK: лёгкая история позиций мобов + отложенный урон ===
        // Запись мобов — on-demand (только после первого попадания), игрокам
        // history не нужна (читают общий TemporalHistoryManager).
        NeoForge.EVENT_BUS.addListener(EntityPositionHistory::onServerTick);
        NeoForge.EVENT_BUS.addListener(EntityPositionHistory::onEntityLeave);
        // Дедлайны ударов — по внешнему серверному времени (не tickCount цели);
        // очистка pending при смерти цели/кастера и на выключении сервера.
        NeoForge.EVENT_BUS.addListener(BacktrackDamageQueue::onServerTick);
        NeoForge.EVENT_BUS.addListener(BacktrackDamageQueue::onDeath);
        NeoForge.EVENT_BUS.addListener(BacktrackDamageQueue::onServerStopping);
        // Отложенный удар Backtrack — временная рана без физического импульса:
        // knockback отменяется точечно, только внутри синхронной обёртки
        // hurt (см. BacktrackKnockbackSuppression); все остальные удары — нет.
        NeoForge.EVENT_BUS.addListener(BacktrackKnockbackSuppression::onKnockback);

        // === THE WORLD STOP: глобальная остановка времени (серверная часть) ===
        // Таймер — внешние серверные тики; заморозка — отмена EntityTickEvent.Pre;
        // singleton/кулдаун-после-resume — интеграция с ISS через pre-cast события.
        // Внешние часы Chronomancy (идут и при World Stop): Rewind/Backtrack история и дедлайны.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, com.chronomancy.temporal.ChronoClock::onServerTick);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onServerTick);
        // Испытание Time Rift: счёт побеждённых Chronomaly (после возможной отмены смерти).
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, com.chronomancy.temporal.worldstop.TimeRiftManager::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.worldstop.TimeRiftManager::onEntityLeave);
        // Заклинание Rift: медные копии целей (тик, удержание цели, без лута/взаимодействий).
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onJoin);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onDrops);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onExperience);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onInteract);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onInteractSpecific);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onStartTracking);
        NeoForge.EVENT_BUS.addListener(com.chronomancy.temporal.rift.RiftEchoManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onEntityTickPre);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onSpellPreCast);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onSpellCooldownAdded);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onChangeMana);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onArmorHurt);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onIncomingDamage);
        // Chronomaly бьёт кастера мгновенно — фиксируем здоровье в его биологическом слепке.
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onDamagePost);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onCasterDied);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onCasterChangedDimension);
        NeoForge.EVENT_BUS.addListener(WorldStopEvents::onServerStopping);

        // Клиентский сброс реестра замороженных id при выходе из мира
        // (ClientPlayerNetworkEvent — событие игровой шины NeoForge.EVENT_BUS)

        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.TemporalNeedleClient::tick);
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.TemporalNeedleClient::render);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BorrowedFutureClientState::onPlayerTick);
            NeoForge.EVENT_BUS.addListener(BorrowedFutureClientState::onClientTick);
            NeoForge.EVENT_BUS.addListener(BorrowedFutureClientState::onBreakSpeed);
            NeoForge.EVENT_BUS.addListener(ChronoClient::onClientLogout);
            // Описания предметов мода: строка легенды и короткие эффекты (см. ChronoItemTooltips).
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.ChronoItemTooltips::onTooltip);
            // Timeless Book: полоса каста непрерывных заклинаний бежит в 5 раз быстрее, как и сам каст.
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.TimelessBookClient::onClientTick);
            // ...а эффекты таких заклинаний (частицы, модели, молнии) красятся в медь.
            com.chronomancy.client.SpellCopperClient.register();
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.SandStacksClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.ParadoxClient::onClientTick);
            // Замедление и ускорение личного времени не сужают и не расширяют поле зрения.
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.PersonalTimeFovClient::onComputeFov);
            // Тема боя с Rift Maker вместо фоновой музыки, пока босс рядом.
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.BossMusicClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.BossMusicClient::onSelectMusic);
            // The World Stop: клиентская заморозка сущностей — глобальным
            // миксином ClientLevel.tickNonPassenger/tickPassenger (см.
            // WorldStopClientLevelMixin), по ЕДИНСТВЕННОМУ глобальному состоянию;
            // per-entity пакетов не существует.
            // The World Stop: client-side cinematic copper/sepia post-processing
            // (vanilla PostChain, no Iris). Application point — via
            // WorldStopPostEffectMixin inside GameRenderer#render after doEntityOutline;
            // here on the game bus only a fallback tint and shader reload are registered.
            // The sepia tint is drawn ONLY when the shader is unavailable (see isTintFallbackActive).
            NeoForge.EVENT_BUS.addListener(ClientWorldStopTint::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(com.chronomancy.client.ClientWorldStopState::onClientTick);
            // F3+T: freeing/rebuilding the post-process chain on the render thread (mod bus, client).
            modEventBus.addListener(WorldStopPostProcess::registerReloadListener);
            // TIME DILATION FIELD: spherical volume post-process (vanilla PostChain, no
            // Iris). Applied via TimeDilationPostEffectMixin at the same doEntityOutline
            // slot as World Stop (World Stop wins priority inside the pass). Here we only
            // capture the exact per-frame level matrices (AFTER_LEVEL) and register the
            // chain's F3+T reload listener. No gameplay coupling: fields are read straight
            // from the entity-synced TimeDilationFieldEntity by ClientTemporalFieldManager.
            NeoForge.EVENT_BUS.addListener(ClientTemporalFieldManager::onRenderLevelStage);
            modEventBus.addListener(TemporalVolumePostProcess::registerReloadListener);
            modEventBus.addListener(com.chronomancy.client.AccelZonePostProcess::registerReloadListener);
            // Разреженные часы погоды: замедление дождя/снега под куполом.
            NeoForge.EVENT_BUS.addListener(ChronoWeatherClock::onClientTick);
            // Rewind-трейлы обрабатываются ВНУТРИ ChronoParticles.onClientTick
            // (после сброса бюджета спавна, до стазис-тикера) — отдельный
            // ClientTickEvent-хук не нужен, иначе два слушателя дерутся за счётчик.
            // Chronomancy particle system: свои типы частиц + провайдеры
            // (mod bus, client) и клиентский тикер песчинок стазиса
            // (игровая шина). Сервер particle-пакетов не рассылает.
            modEventBus.addListener(ChronoParticles::registerProviders);
            NeoForge.EVENT_BUS.addListener(ChronoParticles::onClientTick);
            NeoForge.EVENT_BUS.addListener(ChronoParticles::onRenderStage);
            // Клиентская шина мода: каждому типу сущности нужен рендерер,
            // иначе NPE в EntityRenderDispatcher.shouldRender (1.21.1).
            modEventBus.addListener(ChronoClient::registerRenderers);
            modEventBus.addListener(com.chronomancy.client.CopperEchoRendering::registerShaders);
            modEventBus.addListener(com.chronomancy.client.entity.TimePhaseRendering::registerShaders);
            modEventBus.addListener(ChronoClient::onClientSetup);
        }
    }
}
