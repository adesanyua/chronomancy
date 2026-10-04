package com.chronomancy.item;

import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.effect.TemporalStasisEvents;
import com.chronomancy.temporal.history.TemporalHistoryManager;
import com.chronomancy.temporal.history.TemporalSnapshot;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import net.minecraft.world.damagesource.DamageTypes;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.*;

/** All Curios effects are authoritative on the server; the items alone have no inventory effects. */
public final class ChronoCurioEvents {
    private ChronoCurioEvents() {}
    private record Debt(long due, float damage) {}
    private static final Map<UUID, List<Debt>> DEBTS = new HashMap<>();
    private static final Map<UUID, Long> WATCH_COOLDOWN = new HashMap<>();
    private static final Map<UUID, Rest> REST = new HashMap<>();
    private static final Map<ServerLevel, Long> FROZEN_DAY = new WeakHashMap<>();
    private static final Set<UUID> PAYING = new HashSet<>();
    private static final int REST_TICKS = 100;
    /** Как часто серая «шторка» перезарядки на иконке часов сверяется с настоящей перезарядкой. */
    private static final int WATCH_SYNC_INTERVAL = 20;

    private static int watchCooldownTicks() {
        return com.chronomancy.ChronoConfig.watchCooldownSeconds() * 20;
    }

    private static boolean wearing(LivingEntity entity, Item item) {
        return item instanceof CurioBaseItem<?> curio && curio.isEquippedBy(entity);
    }

    /** Надето ли Ring of Rifts (с ним удары пробивают кадры неуязвимости). */
    public static boolean wearsRiftRing(LivingEntity entity) {
        return wearing(entity, ChronoItemRegistry.RING_OF_RIFTS.get());
    }

    /** Надето ли Rift Heart (с ним Остановка мира открывает разломы). */
    public static boolean wearsRiftHeart(LivingEntity entity) {
        return wearing(entity, ChronoItemRegistry.RIFT_HEART.get());
    }

    /**
     * Ring of Rifts: удары владельца игнорируют кадры неуязвимости цели. Событие приходит ДО ванильной
     * проверки {@code invulnerableTime}, поэтому достаточно обнулить счётчик — удар пройдёт целиком,
     * а после него цель получит обычные кадры неуязвимости (до следующего удара владельца).
     */
    public static void onRingIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide || event.getEntity().invulnerableTime <= 0) return;
        if (event.getSource().getEntity() instanceof Player attacker && attacker != event.getEntity()
                && wearsRiftRing(attacker)) {
            event.getEntity().invulnerableTime = 0;
        }
    }

    /** Надета ли Timeless Book (в слоте книги заклинаний Curios). */
    public static boolean wearsTimelessBook(LivingEntity entity) {
        Item book = ChronoItemRegistry.BOOK_OF_TIME_MANAGMENT.get();
        return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity)
                .map(inventory -> inventory.findFirstCurio(book).isPresent()).orElse(false);
    }

    /**
     * Timeless Book: сжатый каст непрерывного заклинания (см. {@link TimelessBookCasting}) бьёт чаще,
     * чем длятся кадры неуязвимости, поэтому урон именно этого заклинания проходит сквозь них.
     * Событие приходит ДО ванильной проверки {@code invulnerableTime} — достаточно обнулить счётчик.
     * На остальные заклинания владельца книга не влияет.
     */
    public static void onBookIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide || target.invulnerableTime <= 0) return;
        if (!(event.getSource() instanceof io.redspace.ironsspellbooks.damage.SpellDamageSource spellSource)) return;
        if (!(event.getSource().getEntity() instanceof Player caster) || caster == target) return;
        if (TimelessBookCasting.isBurst(caster, spellSource.spell())) {
            target.invulnerableTime = 0;
        }
    }

    /**
     * After armor reductions: Deferred Pendulum postpones a share of the health damage
     * ({@code items.pendulumDeferredShare}, half by default) for {@code items.pendulumDelayTicks}
     * (five seconds), and part of the postponed damage never arrives at all
     * ({@code items.pendulumLostShare}, a fifth).
     */
    public static void onDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        boolean paying = PAYING.contains(player.getUUID()); // смертельный «долг» маятника часы тоже спасают
        float amount = event.getNewDamage();
        if (amount <= 0) return;
        UUID id = player.getUUID();
        long now = player.serverLevel().getGameTime();
        Rest rest = REST.get(id);
        if (rest != null) rest.reset(player.position(), now);

        // История игроков пишется по ChronoClock (серверные тики), а не по gameTime мира — раньше
        // поиск шёл по gameTime, снимок не находился никогда, и часы не срабатывали.
        long clock = com.chronomancy.temporal.ChronoClock.now();
        if (wearing(player, ChronoItemRegistry.SECOND_CHANCE_WATCH.get())
                && amount >= player.getHealth() + player.getAbsorptionAmount()
                && clock >= WATCH_COOLDOWN.getOrDefault(id, 0L)) {
            saveByWatch(player, clock);
            event.setNewDamage(0);
            return;
        }
        if (!paying && wearing(player, ChronoItemRegistry.DEFERRED_PENDULUM.get())) {
            // Absorption has not been applied at Pre: only postpone the part that would
            // have reached health, otherwise a shielded hit would create free debt.
            float delayed = Math.max(0, amount - player.getAbsorptionAmount())
                    * (float) com.chronomancy.ChronoConfig.pendulumShare();
            if (delayed > 0) {
                event.setNewDamage(amount - delayed);
                // Часть отложенного урона «теряется во времени» и не приходит никогда.
                float owed = delayed * (float) (1.0D - com.chronomancy.ChronoConfig.pendulumForgiven());
                if (owed > 0) {
                    // Срок — по ChronoClock: в остановленном мире gameTime стоит, и долг не пришёл бы вовсе.
                    DEBTS.computeIfAbsent(id, key -> new ArrayList<>())
                            .add(new Debt(clock + com.chronomancy.ChronoConfig.pendulumDelay(), owed));
                }
            }
        }
    }

    /**
     * Second Chance Watch: смертельный удар отменяется, игрок откатывается на 5 секунд назад
     * (позиция, здоровье, поглощение; глубина — {@code items.watchRewindTicks}). Если безопасной точки
     * нет — остаётся на месте, но выживает. Перезарядка ({@code items.watchCooldownSeconds}) видна на
     * иконке часов так же, как у ванильных предметов: серая шторка, которая постепенно спадает.
     */
    private static void saveByWatch(ServerPlayer player, long clock) {
        UUID id = player.getUUID();
        ServerLevel level = player.serverLevel();
        WATCH_COOLDOWN.put(id, clock + watchCooldownTicks());
        player.getCooldowns().addCooldown(ChronoItemRegistry.SECOND_CHANCE_WATCH.get(), watchCooldownTicks());
        com.chronomancy.advancement.ChronoAdvancements.grant(player,
                com.chronomancy.advancement.ChronoAdvancements.SECOND_CHANCE);
        DEBTS.remove(id);
        Vec3 from = player.position();
        TemporalSnapshot snapshot = TemporalHistoryManager.findNearest(id,
                clock - com.chronomancy.ChronoConfig.watchRewindTicks());
        if (snapshot != null && snapshot.dimension().equals(level.dimension())) {
            Vec3 safe = com.chronomancy.util.ChronoSafeTeleport.findSafePosition(level, snapshot.pos());
            if (safe != null && !player.isPassenger()) {
                player.teleportTo(level, safe.x, safe.y, safe.z, snapshot.yaw(), snapshot.pitch());
                player.setDeltaMovement(snapshot.velocity());
            }
            player.setHealth(Math.min(player.getMaxHealth(), Math.max(4.0F, snapshot.health())));
            player.setAbsorptionAmount(snapshot.absorption());
        } else {
            player.setHealth(Math.max(4.0F, player.getMaxHealth() * 0.3F));
        }
        player.fallDistance = 0;
        player.clearFire();
        player.invulnerableTime = 20; // остаток залпа в этот же тик не добивает
        player.hurtMarked = true;
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                com.chronomancy.registry.ChronoSounds.TEMPORAL_REWIND.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.2F);
        level.sendParticles(com.chronomancy.registry.ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                from.x, from.y + 1, from.z, 10, 0.3, 0.5, 0.3, 0.05);
        level.sendParticles(com.chronomancy.registry.ChronoParticleRegistry.TEMPORAL_RUNE.get(),
                player.getX(), player.getY() + 1, player.getZ(), 3, 0.3, 0.5, 0.3, 0.0);
    }

    /** Only successful direct player melee hits (not arrows or spells) roll for stasis. */
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim.level().isClientSide
                || event.getNewDamage() <= 0) return;
        if (event.getSource().is(DamageTypes.PLAYER_ATTACK)
                && event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getSource().getDirectEntity() == attacker
                && wearing(attacker, ChronoItemRegistry.CRACKED_DIAL.get())
                && !victim.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)
                && attacker.getRandom().nextFloat() < com.chronomancy.ChronoConfig.crackedDialChance()) {
            if (victim.addEffect(new MobEffectInstance(ChronoMobEffectRegistry.TEMPORAL_STASIS, 40, 0))) {
                // Frozen entities do not tick their own MobEffectInstance. The world-clock
                // deadline is mandatory, otherwise this two-second stasis never expires.
                TemporalStasisEvents.initializeStasis(victim, 1, 12.0f, 40);
            }
        }
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            Rest rest = REST.get(attacker.getUUID());
            if (rest != null) rest.reset(attacker.position(), attacker.serverLevel().getGameTime());
        }
    }

    public static void onSwing(ServerPlayer player) {
        Rest rest = REST.get(player.getUUID());
        if (rest != null) rest.reset(player.position(), player.serverLevel().getGameTime());
    }

    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Rest rest = REST.get(player.getUUID());
            if (rest != null) rest.reset(player.position(), player.serverLevel().getGameTime());
        }
    }

    public static void onSpellCast(SpellOnCastEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Rest rest = REST.get(player.getUUID());
            if (rest != null) rest.reset(player.position(), player.serverLevel().getGameTime());
        }
    }

    private static final class Rest {
        Vec3 position;
        long since;
        boolean granted;
        Rest(Vec3 position, long since) { reset(position, since); }
        void reset(Vec3 position, long since) { this.position = position; this.since = since; granted = false; }
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        long now = player.serverLevel().getGameTime();
        long clock = com.chronomancy.temporal.ChronoClock.now();
        syncWatchCooldown(player, clock);
        List<Debt> debts = DEBTS.get(id);
        if (debts != null) {
            Iterator<Debt> iterator = debts.iterator();
            while (iterator.hasNext()) {
                Debt debt = iterator.next();
                if (clock < debt.due()) continue;
                iterator.remove();
                if (!player.isAlive()) continue;
                PAYING.add(id);
                try {
                    // Debt is final post-armor damage; bypass re-applying armor or the pendulum.
                    if (debt.damage() >= player.getHealth()) {
                        // Kill through the normal death pipeline, not setHealth(0) (which skips die()).
                        player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
                    } else {
                        player.setHealth(player.getHealth() - debt.damage());
                        // хозяин остановленного мира: иначе слепок личного времени вернул бы здоровье
                        com.chronomancy.temporal.worldstop.GlobalTimeStopManager.acceptCasterHealth(player);
                    }
                } finally { PAYING.remove(id); }
                if (DEBTS.get(id) != debts) break; // часы второго шанса списали долг — дальше не платим
            }
            if (debts.isEmpty()) DEBTS.remove(id);
        }
        if (!wearing(player, ChronoItemRegistry.HOURGLASS_OF_FOCUS.get())) {
            REST.remove(id);
            return;
        }
        Rest rest = REST.computeIfAbsent(id, key -> new Rest(player.position(), now));
        if (player.position().distanceToSqr(rest.position) > .0001 || !player.onGround()) {
            rest.reset(player.position(), now);
        } else if (!rest.granted && now - rest.since >= REST_TICKS) {
            rest.granted = true;
            MagicData data = MagicData.getPlayerMagicData(player);
            data.setMana((float) player.getAttributeValue(AttributeRegistry.MAX_MANA));
            PacketDistributor.sendToPlayer(player, new SyncManaPacket(data));
        }
    }

    /**
     * Ванильная перезарядка предмета живёт в объекте игрока и не переживает ни смерть, ни перезаход, а
     * тикает вместе с игроком (в чужом остановленном мире — стоит). Настоящая перезарядка часов идёт по
     * {@link com.chronomancy.temporal.ChronoClock}; раз в секунду шторка на иконке подгоняется под неё.
     */
    private static void syncWatchCooldown(ServerPlayer player, long clock) {
        if (player.tickCount % WATCH_SYNC_INTERVAL != 0) return;
        Item watch = ChronoItemRegistry.SECOND_CHANCE_WATCH.get();
        long left = WATCH_COOLDOWN.getOrDefault(player.getUUID(), 0L) - clock;
        boolean shown = player.getCooldowns().isOnCooldown(watch);
        if (left > 0 && !shown) {
            player.getCooldowns().addCooldown(watch, (int) Math.min(Integer.MAX_VALUE, left));
        } else if (left <= 0 && shown) {
            player.getCooldowns().removeCooldown(watch);
        }
    }

    /** Per-dimension, global effect: the first equipped anchor pins the day; time resumes when none remain. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        boolean active = level.players().stream().anyMatch(p -> wearing(p, ChronoItemRegistry.TEMPORAL_ANCHOR.get()));
        if (active) level.setDayTime(FROZEN_DAY.computeIfAbsent(level, l -> l.getDayTime()));
        else FROZEN_DAY.remove(level);
        // Клиенты сами двигают солнце между синхронизациями — без этого небо мелко дёргалось и под
        // якорем, и в остановленном мире.
        com.chronomancy.temporal.DayFreezeSync.update(level, active
                || com.chronomancy.temporal.worldstop.GlobalTimeStopManager.isActive());
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DEBTS.remove(player.getUUID());
            REST.remove(player.getUUID());
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        REST.remove(id);
        // Debts and the watch cooldown remain for this server session if the player reconnects.
    }
    public static void onStop(ServerStoppingEvent event) {
        DEBTS.clear(); WATCH_COOLDOWN.clear(); REST.clear(); FROZEN_DAY.clear(); PAYING.clear();
    }
}
