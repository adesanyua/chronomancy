package com.chronomancy.temporal.worldstop;

import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.spell.TemporalStasisBeamSpell;
import com.chronomancy.spell.TheWorldStopSpell;
import io.redspace.ironsspellbooks.api.events.SpellCooldownAddedEvent;
import io.redspace.ironsspellbooks.api.events.ChangeManaEvent;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Серверная обвязка The World Stop: Точки интеграции NeoForge/ISS.
 *
 * <p>Ядро заморозки — ОТМЕНА {@link EntityTickEvent.Pre} (проверено по
 * исходникам 1.21.1: событие fires и на ServerLevel.tickNonPassenger, и на
 * ClientLevel; setOldPosAndRot()/tickCount++ выполняются ДО события, поэтому
 * позиция и рендер замороженной сущности зафиксированы без дёргания).
 * Ни setNoAi, ни ручных правок tickCount, ни per-mob фиксов — никаких
 * запрещённых приёмов.
 *
 * <p>Клиентская половина заморозки — глобальный миксин
 * {@code WorldStopClientLevelMixin} (отмена {@code ClientLevel.tickNonPassenger/
 * tickPassenger} на HEAD по ЕДИНСТВЕННОМУ глобальному состоянию из {@code
 * ClientWorldStopState}); сюда пакеты приходят отдельными глобальными payload'ами.
 */
public final class WorldStopEvents {

    private WorldStopEvents() {
    }

    // =========================================================
    // ТАЙМЕР (внешние серверные тики — НЕ gameTime, НЕ tickCount)
    // =========================================================

    public static void onServerTick(ServerTickEvent.Post event) {
        GlobalTimeStopManager.onServerTick(event);
    }

    // =========================================================
    // ЗАМОРОЗКА СУЩНОСТЕЙ (серверная сторона)
    // =========================================================

    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        // Клиентские сущности морозит WorldStopClientLevelMixin (своё состояние
        // из глобального пакета); здесь — только серверный мир.
        if (entity.level().isClientSide) {
            return;
        }
        if (!GlobalTimeStopManager.isActive()) {
            return;
        }
        // Кастер и сущности вне времени (Chronomaly, Time Rift) продолжают тикать.
        if (GlobalTimeStopManager.isExempt(entity)) {
            return;
        }
        event.setCanceled(true);
    }

    // =========================================================
    // IRON'S SPELLS: singleton-гейт и семантика кулдауна
    // =========================================================

    /**
     * SpellPreCastEvent fire ДО списания маны (mana тратится только в
     * castSpell) — правильная точка отказа: второй The World Stop отклоняется
     * с сохранением маны, существующий стоп не перезапускается и кастер не
     * меняется; замороженные игроки не кастуют вообще.
     */
    public static void onSpellPreCast(SpellPreCastEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !GlobalTimeStopManager.isActive()) {
            return;
        }
        // Temporal Stasis Beam запрещён во время стопа даже для кастера: цель и так
        // заморожена мировым стопом, а сам луч — мгновенный hitscan в onCast, он не
        // «подвешивается» как снаряд и по-прежнему долетал бы до цели. Отклоняем
        // ДО списания маны (SpellPreCastEvent precede mana spend).
        if (TemporalStasisBeamSpell.SPELL_ID.equals(event.getSpellId())) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(
                        Component.translatable("spell.chronomancy.temporal_stasis_beam.stopped"), true);
            }
            return;
        }
        boolean isWorldStop = TheWorldStopSpell.SPELL_ID.equals(event.getSpellId());

        if (isWorldStop) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(
                        Component.translatable("spell.chronomancy.the_world_stop.already_stopped"), true);
            }
            return;
        }
        if (!GlobalTimeStopManager.isCaster(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * §20: кулдаун The World Stop НЕ вешается на касте — полный 120 s
     * выставляется менеджером при возобновлении времени (armResumeCooldown).
     */
    public static void onSpellCooldownAdded(SpellCooldownAddedEvent.Pre event) {
        if (TheWorldStopSpell.SPELL_ID.equals(event.getSpell().getSpellId())) {
            event.setCanceled(true);
        }
    }

    /**
     * Фаза 4: заморозка пассивной РЕГЕНЕРАЦИИ маны кастера (его биологическое
     * время стоит). Отсекаем только рост (newMana &gt; oldMana) — списание
     * маны на касты во время стопа остаётся разрешённым. Только для кастера:
     * остальные игроки и так не кастуют (заморожены).
     */
    /**
     * Пока время стоит, броня не изнашивается: вещи кастера так же вне времени, как его здоровье и
     * голод. Удары мобов разлома по-прежнему ранят — не тратится только прочность.
     */
    public static void onArmorHurt(net.neoforged.neoforge.event.entity.living.ArmorHurtEvent event) {
        if (GlobalTimeStopManager.isActive() && !event.getEntity().level().isClientSide) {
            event.setCanceled(true);
        }
    }

    public static void onChangeMana(ChangeManaEvent event) {
        if (!GlobalTimeStopManager.isActive()) {
            return;
        }
        Player player = event.getEntity();
        if (player.level().isClientSide || !GlobalTimeStopManager.isCaster(player)) {
            return;
        }
        // зелье мгновенной маны — не регенерация: оно работает и в остановленном времени;
        // в островке времени личное время идёт, и мана копится как обычно
        if (event.getNewMana() > event.getOldMana() && !InstantRestoration.applying()
                && !com.chronomancy.entity.TimeIslandEntity.anyCovers(player)) {
            event.setCanceled(true);
        }
    }

    // =========================================================
    // ДЕЙСТВИЯ ЗАМОРОЖЕННЫХ ИГРОКОВ (packet-вход вне тика)
    // =========================================================
    //
    // Движение уже заблокировано клиентской заморозкой + якорями сервера;
    // атаки/предметы/ломание блоков приходят пакетами минуя entity tick —
    // режем на PlayerInteractEvent (серверная ветка). Атака по сущности в
    // 1.21.1 НЕ имеет отдельного события (player.attack вызывается из
    // packet handler напрямую) — блокируется на damage-гейте ниже.

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (shouldRejectFrozenPlayerAction(event.getEntity(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (shouldRejectFrozenPlayerAction(event.getEntity(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (shouldRejectFrozenPlayerAction(event.getEntity(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (shouldRejectFrozenPlayerAction(event.getEntity(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (shouldRejectFrozenPlayerAction(event.getEntity(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    // База PlayerInteractEvent НЕ cancellable (отменяются только подклассы),
    // поэтому общее правило — предикат, а setCanceled в каждом хендлере.
    private static boolean shouldRejectFrozenPlayerAction(Player player, Level level) {
        if (level.isClientSide || !GlobalTimeStopManager.isActive()) {
            return false;
        }
        return !GlobalTimeStopManager.isCaster(player);
    }

    /**
     * Фаза 3: пока время остановлено, ни один удар не применяется мгновенно
     * (HP мира замирает). Каждый входящий урон буферизуется и будет выброшен
     * разом на возобновлении. Цель, уже стоящая в индивидуальном стазисе,
     * ПРОПУСКАЕТСЯ — у неё свой накопитель урона (один владелец на жертву).
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide || !GlobalTimeStopManager.isActive()) {
            return;
        }
        if (event.getEntity().hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
            return;
        }
        // Chronomaly живёт вне остановленного времени: её удары и удары по ней —
        // мгновенные, в общий буфер не попадают.
        if (WorldStopExempt.bypassesDamageBuffer(event.getSource(), event.getEntity())) {
            return;
        }
        TemporalDamageBuffer.capture(event);
        event.setCanceled(true);
    }

    /**
     * Мгновенный удар Chronomaly по кастеру: фиксируем здоровье в слепке биологического
     * времени (иначе {@code CasterPersonalTime} откатил бы урон на следующем тике).
     */
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (!GlobalTimeStopManager.isActive() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        GlobalTimeStopManager.acceptCasterHealth(player);
    }

    /**
     * Фаза 3: снаряд кастера, родившийся во время стопа, подвешивается
     * (сохранение скорости + zero + noGravity). Разморозка — в end().
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // Во время испытания Time Rift снаряды кастера не подвешиваются — они участвуют в бою.
        if (TimeRiftManager.isTrialActive()) {
            return;
        }
        ProjectilesSuspension.onEntityJoinLevel(event);
    }

    // =========================================================
    // LIFECYCLE / EDGE CASES (§21) — мир никогда не остаётся frozen
    // =========================================================

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!GlobalTimeStopManager.isActive() || !GlobalTimeStopManager.isCaster(event.getEntity())) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            // Игрок уже выпадает из playerList — кулдаун вешаем заранее.
            GlobalTimeStopManager.armCooldownForLoggingOutCaster(serverPlayer);
        }
        GlobalTimeStopManager.end("caster logged out");
    }

    public static void onCasterDied(LivingDeathEvent event) {
        if (GlobalTimeStopManager.isActive() && GlobalTimeStopManager.isCaster(event.getEntity())) {
            if (event.getEntity() instanceof ServerPlayer caster) {
                TimeRiftManager.onCasterDied(caster); // проваленное испытание: мобы и осколки исчезают
            }
            GlobalTimeStopManager.end("caster died");
        }
    }

    public static void onCasterChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (GlobalTimeStopManager.isActive() && GlobalTimeStopManager.isCaster(event.getEntity())) {
            GlobalTimeStopManager.end("caster changed dimension");
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        GlobalTimeStopManager.end("server stopping");
    }

    /**
     * Вошёл новый игрок (или догоняет после resync) — получает то же самое
     * глобальное состояние одним пакетом; его заморозка начнётся с первым
     * же тиком (и якорь поставлен в enforceAnchors).
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!GlobalTimeStopManager.isActive() || !(event.getEntity() instanceof ServerPlayer joiner)) {
            return;
        }
        if (joiner.server == null) {
            return;
        }
        ServerPlayer caster = joiner.server.getPlayerList().getPlayer(GlobalTimeStopManager.getCasterUUID());
        int casterEntityId = caster != null ? caster.getId() : -1;
        ChronoNetwork.sendWorldStopStartToPlayer(joiner, casterEntityId,
                GlobalTimeStopManager.getRemainingTicks());
        if (TimeRiftManager.isTrialActive()) {
            ChronoNetwork.sendRiftTrialToPlayer(joiner, true);
        }
    }
}
