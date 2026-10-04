package com.chronomancy;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Серверный конфиг Chronomancy ({@code serverconfig/chronomancy-server.toml} в папке мира).
 *
 * <p>Баланс Time Rift / испытания разлома — шанс, число разломов, число Chronomaly и лимиты
 * можно подкрутить (или поднять шанс до 1.0 для теста) без пересборки мода. Здесь же числа
 * механик школы: парадокс, Accelerated Zone, Sands of Time, усталость стазиса, предметы, цены
 * Часовщика.
 *
 * <p>Перезарядки, цена маны и множитель силы самих заклинаний здесь НЕ дублируются: их правит
 * собственный конфиг Iron's Spells — файл
 * {@code config/irons_spellbooks_spell_config/chronomancy/<заклинание>.json}
 * (ключи {@code irons_spellbooks:cooldown_in_seconds}, {@code mana_cost_multiplier},
 * {@code power_multiplier}; образец лежит рядом в {@code irons_spellbooks/example.txt}).
 */
public final class ChronoConfig {

    public static final ModConfigSpec SPEC;

    /** Выключатель Time Rift: 0 — испытание отключено; иначе разломы всегда приходят к носителю Rift Heart. */
    public static final ModConfigSpec.DoubleValue RIFT_CHANCE;
    /** Сколько волн в испытании: волна N открывает N разломов. */
    public static final ModConfigSpec.IntValue WAVES;
    /** Пауза между выпусками одного разлома (серверные тики). */
    public static final ModConfigSpec.IntValue RIFT_SPAWN_INTERVAL;
    /** Сколько мобов выпускает один разлом (случайно minMobs..maxMobs). */
    public static final ModConfigSpec.IntValue RIFT_MIN_MOBS;
    public static final ModConfigSpec.IntValue RIFT_MAX_MOBS;
    /** Доля фазирующих мобов (зомби/скелет/крипер) среди выпущенных; остальное — Chronomaly. */
    public static final ModConfigSpec.DoubleValue PHASING_SHARE;
    /** Сколько мобов испытания могут быть живы одновременно. */
    public static final ModConfigSpec.IntValue TRIAL_MAX_ALIVE;
    /** Страховка от вечной остановки мира (секунды, 0 = выключено). */
    public static final ModConfigSpec.IntValue TRIAL_TIMEOUT;
    /** После волны Chronomaly приходит босс Rift Maker. */
    public static final ModConfigSpec.BooleanValue SPAWN_BOSS;
    /** Островки времени в испытании: период смены (он же срок жизни одного островка) и радиус. */
    public static final ModConfigSpec.IntValue ISLAND_PERIOD;
    public static final ModConfigSpec.DoubleValue ISLAND_RADIUS;

    // --- парадокс ---
    public static final ModConfigSpec.DoubleValue PARADOX_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue PARADOX_SECONDS_PER_DAMAGE;
    // --- Accelerated Zone ---
    public static final ModConfigSpec.DoubleValue ZONE_BASE_HASTE;
    public static final ModConfigSpec.DoubleValue ZONE_HASTE_PER_LEVEL;
    public static final ModConfigSpec.DoubleValue ZONE_DAMAGE_TAKEN_SHARE;
    public static final ModConfigSpec.DoubleValue ZONE_CROSSING_DAMAGE_SHARE;
    public static final ModConfigSpec.IntValue ZONE_CROSSING_COOLDOWN;
    // --- Sands of Time ---
    public static final ModConfigSpec.DoubleValue SANDS_BASE_DAMAGE;
    public static final ModConfigSpec.DoubleValue SANDS_DAMAGE_PER_LEVEL;
    public static final ModConfigSpec.IntValue SANDS_STASIS_TICKS;
    // --- стазис ---
    public static final ModConfigSpec.DoubleValue STASIS_FATIGUE;
    public static final ModConfigSpec.IntValue STASIS_FATIGUE_RESET;
    public static final ModConfigSpec.DoubleValue STASIS_FATIGUE_RIFT_MAKER;
    // --- иглы ---
    public static final ModConfigSpec.IntValue NEEDLE_STACKS_WHEN_UNSTABLE;
    public static final ModConfigSpec.DoubleValue NEEDLE_BASE_DAMAGE;
    public static final ModConfigSpec.DoubleValue NEEDLE_GROWTH;
    // --- Borrowed Future ---
    public static final ModConfigSpec.DoubleValue BORROWED_VULNERABILITY;
    public static final ModConfigSpec.DoubleValue BORROWED_WARD_SHARE;
    // --- предметы ---
    public static final ModConfigSpec.DoubleValue PENDULUM_SHARE;
    public static final ModConfigSpec.IntValue PENDULUM_DELAY;
    public static final ModConfigSpec.DoubleValue PENDULUM_FORGIVEN;
    public static final ModConfigSpec.IntValue WATCH_COOLDOWN;
    public static final ModConfigSpec.IntValue WATCH_REWIND;
    public static final ModConfigSpec.DoubleValue CRACKED_DIAL_CHANCE;
    // --- Часовщик ---
    public static final ModConfigSpec.DoubleValue CLOCKSMITH_PRICE_MULTIPLIER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Time Rift: tears in stopped time that can open when The World Stop is cast").push("time_rift");
        RIFT_CHANCE = builder
                .comment("Time Rift switch: 0 disables the trial entirely; any value above 0 enables it.",
                        "Rifts open on every World Stop cast by a player wearing the Rift Heart, and never without it.")
                .defineInRange("chance", 0.10D, 0.0D, 1.0D);
        WAVES = builder
                .comment("Trial waves: wave N opens N rifts (wave 1 - one rift, wave 2 - two, ...)")
                .defineInRange("waves", 3, 1, 6);
        RIFT_SPAWN_INTERVAL = builder
                .comment("Ticks between two Chronomaly releases of one rift")
                .defineInRange("spawnIntervalTicks", 40, 5, 400);
        builder.pop();
        builder.comment("Rift trial: time stays stopped until the caster defeats the Chronomalies or dies").push("rift_trial");
        RIFT_MIN_MOBS = builder
                .comment("Minimum number of mobs one rift releases")
                .defineInRange("minMobsPerRift", 3, 1, 32);
        RIFT_MAX_MOBS = builder
                .comment("Maximum number of mobs one rift releases")
                .defineInRange("maxMobsPerRift", 7, 1, 32);
        PHASING_SHARE = builder
                .comment("Share of phasing zombies/skeletons/creepers among released mobs (the rest are Chronomalies)")
                .defineInRange("phasingMobShare", 0.6D, 0.0D, 1.0D);
        TRIAL_MAX_ALIVE = builder
                .comment("How many trial mobs may be alive at the same time")
                .defineInRange("maxAlive", 8, 1, 32);
        TRIAL_TIMEOUT = builder
                .comment("Safety limit in seconds: the trial ends and time resumes after this long (0 = never)")
                .defineInRange("timeoutSeconds", 300, 0, 3600);
        SPAWN_BOSS = builder
                .comment("After the Chronomaly wave the Rift Maker boss appears; time stays stopped until it falls")
                .define("spawnBoss", true);
        // Прежние ключи islandIntervalSeconds / islandDurationSeconds заменены одним: островок живёт
        // ровно один период, и в момент его закрытия открывается следующий.
        ISLAND_PERIOD = builder
                .comment("Islands of time: every this many seconds a new island opens and the previous one closes",
                        "(the first one opens this long after the trial began; 0 = no islands).",
                        "Inside an island the caster's own time flows again: natural health regeneration, hunger and mana regeneration")
                .defineInRange("islandPeriodSeconds", 30, 0, 3600);
        ISLAND_RADIUS = builder
                .comment("Islands of time: radius in blocks")
                .defineInRange("islandRadius", 3.0D, 1.0D, 16.0D);
        builder.pop();

        builder.comment("Time Paradox: an Accelerated Zone and a Time Dilation Field meeting at one point").push("paradox");
        PARADOX_DAMAGE_MULTIPLIER = builder
                .comment("Blast damage = (field slow % + zone haste %) x this multiplier. 40% + 40% = 80 damage at 1.0")
                .defineInRange("damageMultiplier", 1.0D, 0.0D, 10.0D);
        PARADOX_SECONDS_PER_DAMAGE = builder
                .comment("Paradox effect duration in seconds per point of blast damage (0.25 = damage / 4: 80 damage -> 20 s)")
                .defineInRange("effectSecondsPerDamage", 0.25D, 0.0D, 5.0D);
        builder.pop();

        builder.comment("Accelerated Zone").push("accelerated_zone");
        ZONE_BASE_HASTE = builder
                .comment("Haste at level 1 (0.40 = enemies are 40% faster)")
                .defineInRange("baseHaste", 0.40D, 0.0D, 0.85D);
        ZONE_HASTE_PER_LEVEL = builder
                .comment("Extra haste per spell level")
                .defineInRange("hastePerLevel", 0.05D, 0.0D, 0.5D);
        ZONE_DAMAGE_TAKEN_SHARE = builder
                .comment("How much of the haste becomes extra damage taken (1.0 = +40% damage at +40% haste)")
                .defineInRange("damageTakenShare", 1.0D, 0.0D, 5.0D);
        ZONE_CROSSING_DAMAGE_SHARE = builder
                .comment("Damage an enemy takes when it enters or leaves the zone, as a share of the haste percentage",
                        "(0.15 at 40% haste = 6 damage; on the way out the zone's extra-damage debuff still applies)")
                .defineInRange("crossingDamageShare", 0.15D, 0.0D, 5.0D);
        ZONE_CROSSING_COOLDOWN = builder
                .comment("Ticks before the same enemy can be hurt by crossing a zone border again")
                .defineInRange("crossingCooldownTicks", 20, 0, 1200);
        builder.pop();

        builder.comment("Sands of Time").push("sands_of_time");
        SANDS_BASE_DAMAGE = builder
                .comment("Starting damage per second at level 1 (before spell power)")
                .defineInRange("baseDamage", 8.0D, 0.0D, 1000.0D);
        SANDS_DAMAGE_PER_LEVEL = builder
                .comment("Extra starting damage per second for every level above 1")
                .defineInRange("damagePerLevel", 2.0D, 0.0D, 1000.0D);
        SANDS_STASIS_TICKS = builder
                .comment("Stasis at the end of a full channel, in ticks")
                .defineInRange("stasisTicks", 40, 0, 1200);
        builder.pop();

        builder.comment("Temporal Stasis").push("stasis");
        STASIS_FATIGUE = builder
                .comment("Every new stasis on the same target is this much shorter than the previous one",
                        "(0.2: 100%, 80%, 60%, 40%, 20%, then immune until the counter resets). 0 disables it")
                .defineInRange("fatiguePerStasis", 0.20D, 0.0D, 1.0D);
        STASIS_FATIGUE_RESET = builder
                .comment("Seconds without a new stasis after which the counter resets")
                .defineInRange("fatigueResetSeconds", 60, 1, 3600);
        STASIS_FATIGUE_RIFT_MAKER = builder
                .comment("The same step for a stasis caused by the Rift Maker itself (its slam and its Sands of Time):",
                        "0.1 = its stasis on one player goes 100%, 90%, 80% ... instead of 100%, 80%, 60%")
                .defineInRange("riftMakerFatiguePerStasis", 0.10D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("Time-Piercing Needle").push("needles");
        NEEDLE_STACKS_WHEN_UNSTABLE = builder
                .comment("Stacks one needle adds to a target under Paradox or inside an Accelerated Zone (1 = no bonus)")
                .defineInRange("stacksPerHitWhenUnstable", 2, 1, 12);
        // Ключ назывался baseDamage (3.0): переименован, чтобы новое значение по умолчанию заменило
        // старое в уже созданных мирах — сохранённый конфиг перекрывает значения по умолчанию.
        NEEDLE_BASE_DAMAGE = builder
                .comment("Collapse damage of a single needle (before spell power). The same for players and mobs")
                .defineInRange("firstNeedleDamage", 7.5D, 0.0D, 1000.0D);
        NEEDLE_GROWTH = builder
                .comment("Every further needle multiplies the collapse damage by this much",
                        "(7.5 and 1.45: 1 needle = 7.5, 4 = 22.9, 8 = 101, 12 = 447; originally 0.4, 3.2, 51 and 819)")
                .defineInRange("growthPerStack", 1.45D, 1.0D, 4.0D);
        builder.pop();

        builder.comment("Borrowed Future").push("borrowed_future");
        BORROWED_VULNERABILITY = builder
                .comment("Extra damage taken while hastened, as a share of the haste (0.1: x2.75 haste -> +27.5% damage)")
                .defineInRange("damageTakenPerHaste", 0.10D, 0.0D, 1.0D);
        BORROWED_WARD_SHARE = builder
                .comment("Share of a foreign Temporal Stasis duration and of any time slow that still reaches a player",
                        "under Borrowed Future, in both phases (0.5 = twice weaker, 1.0 = no protection)")
                .defineInRange("stasisAndSlowShare", 0.50D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("Trinkets").push("items");
        PENDULUM_SHARE = builder
                .comment("Deferred Pendulum: share of incoming damage that is postponed")
                .defineInRange("pendulumDeferredShare", 0.50D, 0.0D, 1.0D);
        PENDULUM_DELAY = builder
                .comment("Deferred Pendulum: how long the postponed damage waits, in ticks")
                .defineInRange("pendulumDelayTicks", 100, 1, 1200);
        PENDULUM_FORGIVEN = builder
                .comment("Deferred Pendulum: share of the postponed damage that never arrives (0.2 = it lands a fifth weaker)")
                .defineInRange("pendulumLostShare", 0.2D, 0.0D, 1.0D);
        WATCH_COOLDOWN = builder
                .comment("Second Chance Watch: cooldown in seconds")
                .defineInRange("watchCooldownSeconds", 180, 1, 36000);
        WATCH_REWIND = builder
                .comment("Second Chance Watch: how far back it rewinds, in ticks (history keeps about 6 seconds)")
                .defineInRange("watchRewindTicks", 100, 1, 115);
        CRACKED_DIAL_CHANCE = builder
                .comment("Cracked Dial: chance that a melee hit puts the target into stasis")
                .defineInRange("crackedDialChance", 0.10D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("Clocksmith").push("clocksmith");
        CLOCKSMITH_PRICE_MULTIPLIER = builder
                .comment("Multiplier for every Clocksmith price except the Ring of Rifts. Applies to offers generated after the change")
                .defineInRange("priceMultiplier", 2.0D, 0.1D, 20.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private ChronoConfig() {
    }

    /** Безопасное чтение: до загрузки конфига (или вне мира) — значение по умолчанию. */
    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static double riftChance() { return read(RIFT_CHANCE); }
    public static int waves() { return read(WAVES); }
    public static int riftSpawnInterval() { return read(RIFT_SPAWN_INTERVAL); }
    public static int riftMinMobs() { return read(RIFT_MIN_MOBS); }
    public static int riftMaxMobs() { return read(RIFT_MAX_MOBS); }
    public static double phasingShare() { return read(PHASING_SHARE); }
    public static int trialMaxAlive() { return read(TRIAL_MAX_ALIVE); }
    public static int trialTimeoutSeconds() { return read(TRIAL_TIMEOUT); }
    public static boolean spawnBoss() { return read(SPAWN_BOSS); }
    public static int islandPeriodSeconds() { return read(ISLAND_PERIOD); }
    public static double islandRadius() { return read(ISLAND_RADIUS); }

    public static double paradoxDamageMultiplier() { return read(PARADOX_DAMAGE_MULTIPLIER); }
    public static double paradoxSecondsPerDamage() { return read(PARADOX_SECONDS_PER_DAMAGE); }
    public static double zoneBaseHaste() { return read(ZONE_BASE_HASTE); }
    public static double zoneHastePerLevel() { return read(ZONE_HASTE_PER_LEVEL); }
    public static double zoneDamageTakenShare() { return read(ZONE_DAMAGE_TAKEN_SHARE); }
    public static double zoneCrossingDamageShare() { return read(ZONE_CROSSING_DAMAGE_SHARE); }
    public static int zoneCrossingCooldown() { return read(ZONE_CROSSING_COOLDOWN); }
    public static double sandsBaseDamage() { return read(SANDS_BASE_DAMAGE); }
    public static double sandsDamagePerLevel() { return read(SANDS_DAMAGE_PER_LEVEL); }
    public static int sandsStasisTicks() { return read(SANDS_STASIS_TICKS); }
    public static double stasisFatigue() { return read(STASIS_FATIGUE); }
    public static int stasisFatigueResetSeconds() { return read(STASIS_FATIGUE_RESET); }
    public static double stasisFatigueRiftMaker() { return read(STASIS_FATIGUE_RIFT_MAKER); }
    public static int needleStacksWhenUnstable() { return read(NEEDLE_STACKS_WHEN_UNSTABLE); }
    public static double needleBaseDamage() { return read(NEEDLE_BASE_DAMAGE); }
    public static double needleGrowth() { return read(NEEDLE_GROWTH); }
    public static double borrowedVulnerability() { return read(BORROWED_VULNERABILITY); }
    public static double borrowedWardShare() { return read(BORROWED_WARD_SHARE); }
    public static double pendulumShare() { return read(PENDULUM_SHARE); }
    public static int pendulumDelay() { return read(PENDULUM_DELAY); }
    public static double pendulumForgiven() { return read(PENDULUM_FORGIVEN); }
    public static int watchCooldownSeconds() { return read(WATCH_COOLDOWN); }
    public static int watchRewindTicks() { return read(WATCH_REWIND); }
    public static double crackedDialChance() { return read(CRACKED_DIAL_CHANCE); }
    public static double clocksmithPriceMultiplier() { return read(CLOCKSMITH_PRICE_MULTIPLIER); }
}
