package com.chronomancy.temporal.worldstop;

import com.chronomancy.ChronoConfig;
import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.ChronomalyEntity;
import com.chronomancy.entity.RiftMakerEntity;
import com.chronomancy.entity.TimeRiftEntity;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Серверный координатор Time Rift и ИСПЫТАНИЯ разлома.
 *
 * <p>Правила:
 * <ul>
 *   <li>РОВНО один бросок шанса на каждый успешный старт The World Stop
 *       ({@link #onWorldStopStarted}) — не каждый тик, повторного броска нет;</li>
 *   <li>при успехе начинается испытание из {@code waves} волн (по умолчанию 3): волна N открывает
 *       N разломов (не ближе 3 блоков друг к другу, в свободном месте с безопасной опорой в прямой
 *       видимости кастера), каждый разлом выпускает {@code minMobsPerRift..maxMobsPerRift}
 *       (по умолчанию 3–7) мобов — Chronomaly и фазирующих зомби/скелетов/криперов;</li>
 *   <li>пока испытание идёт, таймер стопа стоит — время не возобновится, пока все волны не
 *       повержены или кастер не погиб (смерть/выход/смена измерения кастера завершают стоп);</li>
 *   <li>мобы выходят по очереди, не больше {@code maxAlive} живых одновременно. Засчитывается
 *       любая смерть моба испытания; пропавший без смерти (выгрузка чанка и т.п.) заменяется
 *       новым. Волна побеждена — её разломы схлопываются и открываются разломы следующей;</li>
 *   <li>страховочный лимит {@code trialTimeoutSeconds} (0 = выключен) — чтобы мир на сервере не
 *       остался остановленным навсегда, если бой невозможен (кастер замурован, в креативе и т.п.).</li>
 * </ul>
 *
 * <p>Состояние — только серверный поток, как и у {@link GlobalTimeStopManager}.
 */
public final class TimeRiftManager {

    private static final int PLACEMENT_ATTEMPTS = 40;
    private static final double MIN_DISTANCE = 3.0D;
    private static final double MAX_DISTANCE = 7.0D;
    private static final double MIN_RIFT_SEPARATION_SQR = 3.0D * 3.0D;

    /** Серийный номер текущего (или последнего) стопа; разлом живёт только в «своём». */
    private static long stopSerial;

    // --- испытание ---
    private static boolean trialActive;
    private static UUID trialCaster;
    private static ResourceKey<Level> trialLevel;
    /** Текущая волна (1..waves) и сколько всего волн. */
    private static int wave;
    private static int waves;
    /** Сколько мобов нужно победить в текущей волне / уже повержено / уже выпущено. */
    private static int required;
    private static int killed;
    private static int released;
    private static int totalKilled;
    private static int trialTicks;
    /** Вторая фаза испытания: после волны Chronomaly пришёл Rift Maker. */
    private static boolean bossPhase;
    private static UUID bossId;
    /** Врата босса раскрыты, сам босс ещё не выпал: через сколько тиков и куда (ноги, в воздухе). */
    private static int bossDropDelay;
    private static Vec3 bossDropSpot;
    private static UUID bossGate;
    /** Через сколько тиков после падения босса врата схлопнутся. */
    private static int bossGateClose;
    /** Высота падения босса из врат: сколько блоков свободного воздуха ищем над местом появления. */
    private static final int BOSS_DROP_MAX = 9;
    private static final int BOSS_DROP_MIN = 3;
    private static final float BOSS_GATE_SCALE = 4.0F;
    private static final Set<UUID> alive = new HashSet<>();
    private static final List<UUID> rifts = new ArrayList<>();
    /** Через сколько тиков откроется следующий островок времени. */
    private static int islandTimer;

    private TimeRiftManager() {
    }

    public static long currentStopSerial() {
        return stopSerial;
    }

    public static boolean isTrialActive() {
        return trialActive;
    }

    // =========================================================
    // Старт / конец стопа
    // =========================================================

    /** Вызывается из {@code GlobalTimeStopManager.tryStart} ПОСЛЕ успешного старта. */
    static void onWorldStopStarted(ServerPlayer caster) {
        stopSerial++;
        resetTrial();

        ServerLevel level = caster.serverLevel();
        RandomSource random = level.getRandom();
        // Разломы приходят только к носителю Rift Heart — и тогда всегда. Шанс из конфига теперь
        // работает как выключатель: 0 — испытание отключено совсем.
        if (ChronoConfig.riftChance() <= 0.0D || !com.chronomancy.item.ChronoCurioEvents.wearsRiftHeart(caster)) {
            return;
        }

        waves = Math.max(1, ChronoConfig.waves());
        if (!openWave(level, caster, 1)) {
            ChronomancyMod.LOGGER.info("[TimeRift] roll succeeded but no safe spot near {}",
                    caster.getName().getString());
            return;
        }
        trialActive = true;
        islandTimer = Math.max(1, ChronoConfig.islandPeriodSeconds()) * 20;
        com.chronomancy.advancement.ChronoAdvancements.grant(caster,
                com.chronomancy.advancement.ChronoAdvancements.TRIAL);
        trialCaster = caster.getUUID();
        trialLevel = level.dimension();
        ChronoNetwork.broadcastRiftTrial(true);
        announceWave(caster);
    }

    /**
     * Открывает разломы волны {@code number} (их столько же, сколько номер волны). Разломы прошлой
     * волны схлопываются, только если новые удалось разместить.
     *
     * @return {@code false}, если не удалось поставить ни одного разлома
     */
    private static boolean openWave(ServerLevel level, ServerPlayer caster, int number) {
        RandomSource random = level.getRandom();
        List<Vec3> placed = new ArrayList<>();
        List<UUID> opened = new ArrayList<>();
        int min = ChronoConfig.riftMinMobs();
        int max = Math.max(min, ChronoConfig.riftMaxMobs());
        int mobs = 0;
        for (int i = 0; i < number; i++) {
            Vec3 spot = findRiftSpot(level, caster, random, placed);
            if (spot == null) {
                break;
            }
            TimeRiftEntity rift = ChronoEntityTypeRegistry.TIME_RIFT.get().create(level);
            if (rift == null) {
                break;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(caster.getX() - spot.x, spot.z - caster.getZ()));
            rift.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
            rift.bind(stopSerial, caster);
            // Разломы открываются вразнобой — так их легче заметить по очереди.
            rift.delayOpening(10 + i * 12);
            if (level.addFreshEntity(rift)) {
                opened.add(rift.getUUID());
                placed.add(spot);
                mobs += min + random.nextInt(max - min + 1);
            }
        }
        if (opened.isEmpty()) {
            return false;
        }
        for (UUID id : rifts) {
            if (level.getEntity(id) instanceof TimeRiftEntity old) {
                old.beginClosing();
            }
        }
        rifts.clear();
        rifts.addAll(opened);
        wave = number;
        required = mobs;
        killed = 0;
        released = 0;
        trialTicks = 0;
        ChronomancyMod.LOGGER.info("[TimeRift] wave {}/{}: {} rift(s), {} mob(s) for {}",
                wave, waves, opened.size(), required, caster.getName().getString());
        return true;
    }

    private static void announceWave(ServerPlayer caster) {
        caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.wave",
                wave, waves, rifts.size(), required).withStyle(ChatFormatting.AQUA), false);
        showProgress(caster);
    }

    /** В каком радиусе от погибшего кастера испытания с земли пропадают осколки хрономалий. */
    private static final double FAILED_LOOT_RADIUS = 64.0D;

    /**
     * Кастер испытания погиб во время волн или боя с боссом: всё, что вышло из разломов, исчезает,
     * а осколки хрономалий, лежащие на земле, пропадают — проваленное испытание не кормит. Остальные
     * предметы не трогаем. Вызывается ДО завершения стопа и до того, как игрок выронит инвентарь:
     * мобы не успевают получить накопленный урон (и оставить добычу), а осколки из сумки самого
     * игрока падают позже и остаются лежать. Rift Maker, его двойники и разломы уходят следом —
     * в {@link #onWorldStopEnded}.
     */
    static void onCasterDied(ServerPlayer caster) {
        if (!trialActive || trialCaster == null || !trialCaster.equals(caster.getUUID())) {
            return;
        }
        ServerLevel level = trialLevel == null ? null : caster.server.getLevel(trialLevel);
        if (level == null) {
            return;
        }
        List<UUID> mobs = new ArrayList<>(alive);
        alive.clear(); // чтобы уход мобов не считался ни победой, ни поводом выпустить новых
        int gone = 0;
        for (UUID id : mobs) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                level.sendParticles(com.chronomancy.registry.ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                        entity.getX(), entity.getY(0.5D), entity.getZ(), 12,
                        entity.getBbWidth() * 0.4D, entity.getBbHeight() * 0.35D, entity.getBbWidth() * 0.4D, 0.0D);
                entity.discard();
                gone++;
            }
        }
        int shards = 0;
        for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, caster.getBoundingBox().inflate(FAILED_LOOT_RADIUS),
                drop -> drop.getItem().is(com.chronomancy.registry.ChronoItemRegistry.CHRONOMALY_SHARD.get()))) {
            shards += item.getItem().getCount();
            item.discard();
        }
        caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.failed")
                .withStyle(ChatFormatting.GRAY), false);
        ChronomancyMod.LOGGER.info("[TimeRift] trial caster died (wave {}/{}, boss={}): {} mob(s) vanished, {} shard(s) cleared",
                wave, waves, bossPhase, gone, shards);
    }

    /** Вызывается из {@code GlobalTimeStopManager.end} при ЛЮБОМ завершении стопа. */
    static void onWorldStopEnded(MinecraftServer server) {
        // Босс не повержен, а стоп кончился (смерть/выход/смена измерения кастера, таймаут):
        // Rift Maker исчезает — даже если на сервере остались другие игроки.
        UUID boss = bossPhase ? bossId : null;
        bossPhase = false;
        bossId = null;
        if (server != null && trialLevel != null) {
            ServerLevel level = server.getLevel(trialLevel);
            for (UUID id : rifts) {
                Entity entity = level == null ? null : level.getEntity(id);
                if (entity instanceof TimeRiftEntity rift) {
                    rift.beginClosing();
                }
            }
            if (boss != null && level != null && level.getEntity(boss) instanceof RiftMakerEntity maker
                    && maker.isAlive()) {
                ChronomancyMod.LOGGER.info("[TimeRift] World Stop ended before Rift Maker was defeated — boss vanishes");
                maker.vanish();
            }
        }
        if (trialActive) {
            ChronoNetwork.broadcastRiftTrial(false);
        }
        closeIslands(server);
        resetTrial();
    }

    private static void resetTrial() {
        trialActive = false;
        trialCaster = null;
        trialLevel = null;
        wave = 0;
        waves = 0;
        required = 0;
        killed = 0;
        released = 0;
        totalKilled = 0;
        trialTicks = 0;
        bossPhase = false;
        bossId = null;
        bossDropDelay = 0;
        bossDropSpot = null;
        bossGate = null;
        bossGateClose = 0;
        islandTimer = 0;
        alive.clear();
        rifts.clear();
    }

    /**
     * Каждый тик активного стопа. @return {@code true}, если идёт испытание и таймер стопа
     * должен стоять.
     */
    static boolean tickTrial(MinecraftServer server) {
        if (!trialActive) {
            return false;
        }
        trialTicks++;
        int timeout = ChronoConfig.trialTimeoutSeconds();
        if (timeout > 0 && trialTicks >= timeout * 20) {
            ServerPlayer caster = server.getPlayerList().getPlayer(trialCaster);
            if (caster != null) {
                caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.timeout")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            ChronomancyMod.LOGGER.info("[TimeRift] trial timed out after {} s (wave {}, {}/{})", timeout, wave, killed, required);
            finishTrial();
            return false;
        }
        if (bossPhase) {
            tickBossArrival(server);
        }
        tickIslands(server);
        return trialActive;
    }

    // =========================================================
    // Островки времени
    // =========================================================

    /**
     * Островки времени ({@link com.chronomancy.entity.TimeIslandEntity}) сменяют друг друга раз в
     * {@code rift_trial.islandPeriodSeconds}: первый открывается через период после начала испытания,
     * живёт ровно период, и в тот же тик, когда он закрывается, рядом с кастером открывается следующий —
     * за островком приходится переходить. Внутри снова идут обычная регенерация здоровья и маны. И в
     * волнах, и в бою с боссом. Не нашлось места — попытка повторяется через секунду.
     */
    private static void tickIslands(MinecraftServer server) {
        int period = ChronoConfig.islandPeriodSeconds();
        if (period <= 0 || --islandTimer > 0) {
            return;
        }
        ServerPlayer caster = findCaster(server);
        ServerLevel level = trialLevel == null ? null : server.getLevel(trialLevel);
        if (caster == null || level == null || caster.level() != level || !caster.isAlive()) {
            islandTimer = 20;
            return;
        }
        Vec3 spot = findIslandSpot(level, caster, level.getRandom());
        com.chronomancy.entity.TimeIslandEntity island = spot == null ? null
                : ChronoEntityTypeRegistry.TIME_ISLAND.get().create(level);
        if (island == null) {
            islandTimer = 20;
            return;
        }
        int life = period * 20;
        island.moveTo(spot.x, spot.y, spot.z, 0.0F, 0.0F);
        island.configure(ChronoConfig.islandRadius(), life);
        if (!level.addFreshEntity(island)) {
            islandTimer = 20;
            return;
        }
        islandTimer = life; // следующий откроется ровно тогда, когда этот закроется
        level.playSound(null, spot.x, spot.y + 0.5D, spot.z, com.chronomancy.registry.ChronoSounds.TEMPORAL_RELEASE.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F);
        caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.island")
                .withStyle(ChatFormatting.GREEN), true);
    }

    /** Место для островка: ровная безопасная опора в 3–7 блоках от кастера, в прямой видимости. */
    private static Vec3 findIslandSpot(ServerLevel level, ServerPlayer caster, RandomSource random) {
        BlockPos casterPos = caster.blockPosition();
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = Mth.floor(caster.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(caster.getZ() + Math.sin(angle) * distance);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = new BlockPos(x, casterPos.getY() + dy, z);
                if (!level.isLoaded(feet) || !level.getWorldBorder().isWithinBounds(feet) || !isSafeFloor(level, feet.below())) {
                    continue;
                }
                Vec3 spot = new Vec3(x + 0.5D, feet.getY(), z + 0.5D);
                if (hasRoom(level, EntityType.PLAYER.getSpawnAABB(spot.x, spot.y, spot.z))
                        && inLineOfSight(level, caster, spot.add(0.0D, 1.0D, 0.0D))) {
                    return spot;
                }
            }
        }
        return null;
    }

    /** Испытание кончилось — открытые островки гаснут вместе с ним. */
    private static void closeIslands(MinecraftServer server) {
        ServerLevel level = server == null || trialLevel == null ? null : server.getLevel(trialLevel);
        if (level == null) {
            return;
        }
        List<Entity> islands = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof com.chronomancy.entity.TimeIslandEntity) {
                islands.add(entity);
            }
        }
        islands.forEach(Entity::discard); // не во время обхода: discard меняет сам список сущностей
    }

    /** Врата босса: раскрылись — босс выпадает; чуть позже врата схлопываются. */
    private static void tickBossArrival(MinecraftServer server) {
        ServerLevel level = trialLevel == null ? null : server.getLevel(trialLevel);
        if (bossDropDelay > 0 && --bossDropDelay == 0) {
            Vec3 spot = bossDropSpot;
            bossDropSpot = null;
            if (level == null || spot == null || !spawnBoss(level, findCaster(server), spot, true)) {
                ChronomancyMod.LOGGER.info("[TimeRift] Rift Maker could not drop from the gate — trial ends");
                finishTrial();
                return;
            }
            bossGateClose = 30;
        }
        if (bossGateClose > 0 && --bossGateClose == 0 && level != null && bossGate != null
                && level.getEntity(bossGate) instanceof TimeRiftEntity gate) {
            gate.beginClosing();
        }
    }

    /** Испытание окончено: снимаем паузу — стоп завершится на ближайшем тике по таймеру. */
    private static void finishTrial() {
        trialActive = false;
        ChronoNetwork.broadcastRiftTrial(false);
        GlobalTimeStopManager.resumeSoon();
    }

    // =========================================================
    // Выпуск Chronomaly (зовут разломы)
    // =========================================================

    /** Можно ли сейчас выпустить ещё одного моба испытания. */
    public static boolean canRelease(long serial) {
        return trialActive
                && !bossPhase
                && serial == stopSerial
                && released < required
                && alive.size() < ChronoConfig.trialMaxAlive();
    }

    /** Разлом выпустил моба — он участвует в испытании. */
    public static void onReleased(net.minecraft.world.entity.LivingEntity mob) {
        released++;
        alive.add(mob.getUUID());
    }

    public static UUID trialCaster() {
        return trialCaster;
    }

    // =========================================================
    // Учёт побед
    // =========================================================

    /** LivingDeathEvent (LOWEST, после возможной отмены смерти тотемом и т.п.). */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !trialActive || event.getEntity().level().isClientSide) {
            return;
        }
        if (bossPhase && event.getEntity() instanceof RiftMakerEntity boss && boss.getUUID().equals(bossId)) {
            onBossDefeated(boss.getServer());
            return;
        }
        net.minecraft.world.entity.LivingEntity mob = event.getEntity();
        if (!alive.remove(mob.getUUID())) {
            return; // не из этого испытания (яйцо призыва, прошлый стоп)
        }
        killed++;
        totalKilled++;
        ServerPlayer caster = findCaster(mob.getServer());
        if (killed < required) {
            if (caster != null) {
                showProgress(caster);
            }
            return;
        }
        ChronomancyMod.LOGGER.info("[TimeRift] wave {}/{} cleared ({} mobs)", wave, waves, required);
        if (wave < waves && caster != null && mob.level() instanceof ServerLevel serverLevel
                && openWave(serverLevel, caster, wave + 1)) {
            caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.wave_cleared",
                    wave - 1).withStyle(ChatFormatting.GOLD), false);
            announceWave(caster);
            return;
        }
        if (ChronoConfig.spawnBoss() && startBossPhase(mob.level(), caster)) {
            return;
        }
        if (caster != null) {
            caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.cleared", totalKilled)
                    .withStyle(ChatFormatting.GOLD), false);
        }
        finishTrial();
    }

    // =========================================================
    // Босс: Rift Maker
    // =========================================================

    /**
     * Волны побеждены — над ареной раскрываются огромные врата, и из них выпадает Rift Maker
     * (в тесноте, где над головой нет места, он по-старому выходит из разлома на земле). Время
     * по-прежнему стоит, пока босс не повержен (или кастер не погиб); страховочный лимит
     * отсчитывается заново.
     */
    private static boolean startBossPhase(Level level, ServerPlayer caster) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        Vec3 spot = null;
        for (UUID id : rifts) {
            if (serverLevel.getEntity(id) instanceof TimeRiftEntity rift && rift.isAlive()) {
                spot = rift.position();
                break;
            }
        }
        EntityType<RiftMakerEntity> type = ChronoEntityTypeRegistry.RIFT_MAKER.get();
        Vec3 riftSpot = spot;
        if (spot == null || !serverLevel.noCollision(type.getSpawnAABB(spot.x, spot.y, spot.z))) {
            spot = caster == null ? null : findBossSpot(serverLevel, caster, type);
        }
        if (spot == null) {
            // Босс огромный (2.2 × 6.4): в тесноте выходим прямо из разлома — стены его не душат,
            // а застряв, он сам телепортируется к цели.
            spot = riftSpot;
        }
        if (spot == null) {
            return false;
        }
        // Сколько свободного воздуха над местом: оттуда босс и упадёт.
        int drop = 0;
        for (int h = BOSS_DROP_MAX; h >= BOSS_DROP_MIN; h--) {
            if (serverLevel.noCollision(type.getSpawnAABB(spot.x, spot.y + h, spot.z).expandTowards(0.0D, -h, 0.0D))) {
                drop = h;
                break;
            }
        }
        TimeRiftEntity gate = drop > 0 && caster != null ? ChronoEntityTypeRegistry.TIME_RIFT.get().create(serverLevel) : null;
        if (gate == null) {
            return spawnBoss(serverLevel, caster, spot, false);
        }
        // Разломы волн своё отслужили — остаются только врата.
        for (UUID id : rifts) {
            if (serverLevel.getEntity(id) instanceof TimeRiftEntity old) {
                old.beginClosing();
            }
        }
        rifts.clear();
        double gateY = spot.y + drop + type.getHeight() + 0.4D;
        gate.moveTo(spot.x, gateY, spot.z, 0.0F, 0.0F);
        gate.bind(stopSerial, caster);
        gate.makeBossGate(BOSS_GATE_SCALE);
        if (!serverLevel.addFreshEntity(gate)) {
            return spawnBoss(serverLevel, caster, spot, false);
        }
        rifts.add(gate.getUUID());
        bossGate = gate.getUUID();
        bossDropSpot = new Vec3(spot.x, spot.y + drop, spot.z);
        bossDropDelay = TimeRiftEntity.OPEN_TICKS + 8;
        bossPhase = true;
        bossId = null;
        trialTicks = 0;
        caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.boss")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        ChronomancyMod.LOGGER.info("[TimeRift] Rift Maker gate opened {} blocks above {}", drop, BlockPos.containing(spot));
        return true;
    }

    /** Сам босс: на земле ({@code falling == false}) или в воздухе под вратами — тогда он падает. */
    private static boolean spawnBoss(ServerLevel serverLevel, ServerPlayer caster, Vec3 spot, boolean falling) {
        EntityType<RiftMakerEntity> type = ChronoEntityTypeRegistry.RIFT_MAKER.get();
        RiftMakerEntity boss = type.create(serverLevel);
        if (boss == null) {
            return false;
        }
        boss.markTrialBoss();
        float yaw = caster == null ? serverLevel.getRandom().nextFloat() * 360.0F
                : (float) Math.toDegrees(Math.atan2(caster.getZ() - spot.z, caster.getX() - spot.x)) - 90.0F;
        boss.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        if (falling) {
            boss.beginArrival();
        }
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(boss, serverLevel,
                serverLevel.getCurrentDifficultyAt(BlockPos.containing(spot)), net.minecraft.world.entity.MobSpawnType.EVENT, null);
        if (caster != null && caster.isAlive() && !caster.isCreative() && !caster.isSpectator()) {
            boss.setTarget(caster);
        }
        if (!serverLevel.addFreshEntity(boss)) {
            return false;
        }
        bossPhase = true;
        com.chronomancy.advancement.ChronoAdvancements.grant(caster,
                com.chronomancy.advancement.ChronoAdvancements.RIFT_MAKER_ARRIVES);
        bossId = boss.getUUID();
        trialTicks = 0;
        serverLevel.sendParticles(com.chronomancy.registry.ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                spot.x, spot.y + 1.5, spot.z, 8, 0.6, 1.0, 0.6, 0.05);
        serverLevel.playSound(null, spot.x, spot.y + 1, spot.z, com.chronomancy.registry.ChronoSounds.RIFT_MAKER_SUMMON.get(),
                net.minecraft.sounds.SoundSource.HOSTILE, 2.0F, 0.6F);
        if (caster != null && !falling) {
            caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.boss")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        }
        ChronomancyMod.LOGGER.info("[TimeRift] Rift Maker summoned at {}", boss.blockPosition());
        return true;
    }

    private static Vec3 findBossSpot(ServerLevel level, ServerPlayer caster, EntityType<?> type) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = 4.0D + random.nextDouble() * 4.0D;
            int x = Mth.floor(caster.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(caster.getZ() + Math.sin(angle) * distance);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = new BlockPos(x, caster.blockPosition().getY() + dy, z);
                if (!level.isLoaded(feet) || !isSafeFloor(level, feet.below())) {
                    continue;
                }
                Vec3 spot = new Vec3(x + 0.5D, feet.getY(), z + 0.5D);
                if (hasRoom(level, type.getSpawnAABB(spot.x, spot.y, spot.z))) {
                    return spot;
                }
            }
        }
        return null;
    }

    private static void onBossDefeated(MinecraftServer server) {
        ServerPlayer caster = server == null ? null : server.getPlayerList().getPlayer(trialCaster);
        if (caster != null) {
            caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.boss_defeated")
                    .withStyle(ChatFormatting.GOLD), false);
        }
        ChronomancyMod.LOGGER.info("[TimeRift] Rift Maker defeated — trial cleared");
        finishTrial();
    }

    /** Chronomaly испытания пропала без смерти (выгрузка, discard) — её место займёт новая. */
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!trialActive || event.getLevel().isClientSide()) {
            return;
        }
        // Босс исчез без смерти (выгрузка чанка, /kill не считается — там смерть) — не держим мир.
        if (bossPhase && event.getEntity() instanceof RiftMakerEntity boss && boss.getUUID().equals(bossId)
                && boss.getRemovalReason() != null && !boss.getRemovalReason().equals(Entity.RemovalReason.KILLED)) {
            ChronomancyMod.LOGGER.info("[TimeRift] Rift Maker left the level ({}) — trial ends", boss.getRemovalReason());
            finishTrial();
            return;
        }
        if (alive.remove(event.getEntity().getUUID())) {
            released = Math.max(0, released - 1);
        }
    }

    /** Кастер испытания: из списка игроков сервера или (для служебных игроков) из уровней. */
    private static ServerPlayer findCaster(MinecraftServer server) {
        if (server == null || trialCaster == null) {
            return null;
        }
        ServerPlayer caster = server.getPlayerList().getPlayer(trialCaster);
        if (caster == null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getPlayerByUUID(trialCaster) instanceof ServerPlayer player) {
                    return player;
                }
            }
        }
        return caster;
    }

    private static void showProgress(ServerPlayer caster) {
        caster.displayClientMessage(Component.translatable("message.chronomancy.time_rift.progress", killed, required, wave, waves)
                .withStyle(ChatFormatting.AQUA), true);
    }

    // =========================================================
    // Размещение
    // =========================================================

    private static Vec3 findRiftSpot(ServerLevel level, ServerPlayer caster, RandomSource random, List<Vec3> taken) {
        EntityType<TimeRiftEntity> riftType = ChronoEntityTypeRegistry.TIME_RIFT.get();
        EntityType<?> spawnType = ChronoEntityTypeRegistry.CHRONOMALY.get();
        BlockPos casterPos = caster.blockPosition();

        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = Mth.floor(caster.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(caster.getZ() + Math.sin(angle) * distance);

            // Сверху вниз: ищем пол в пределах [-3; +2] блоков от ног кастера.
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = new BlockPos(x, casterPos.getY() + dy, z);
                BlockPos floor = feet.below();
                if (!level.isLoaded(feet) || !level.getWorldBorder().isWithinBounds(feet)) {
                    continue;
                }
                if (!isSafeFloor(level, floor)) {
                    continue;
                }
                Vec3 spot = new Vec3(x + 0.5D, feet.getY() + 0.05D, z + 0.5D);
                if (taken.stream().anyMatch(other -> other.distanceToSqr(spot) < MIN_RIFT_SEPARATION_SQR)) {
                    continue;
                }
                if (!hasRoom(level, riftType.getSpawnAABB(spot.x, spot.y, spot.z))
                        || !hasRoom(level, spawnType.getSpawnAABB(spot.x, spot.y + 0.2D, spot.z))) {
                    continue;
                }
                if (!inLineOfSight(level, caster, spot.add(0, 1.0D, 0))) {
                    continue;
                }
                return spot;
            }
        }
        return null;
    }

    /** Твёрдый верх и ничего опасного под ногами. */
    private static boolean isSafeFloor(ServerLevel level, BlockPos floor) {
        BlockState state = level.getBlockState(floor);
        return state.isFaceSturdy(level, floor, Direction.UP) && !isHazard(state);
    }

    /** Свободный объём: без коллизий, жидкостей и опасных блоков внутри. */
    private static boolean hasRoom(ServerLevel level, AABB box) {
        return level.noCollision(box)
                && !level.containsAnyLiquid(box)
                && level.getBlockStates(box).noneMatch(TimeRiftManager::isHazard);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL);
    }

    private static boolean inLineOfSight(ServerLevel level, ServerPlayer caster, Vec3 target) {
        HitResult hit = level.clip(new ClipContext(caster.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        return hit.getType() == HitResult.Type.MISS;
    }
}
