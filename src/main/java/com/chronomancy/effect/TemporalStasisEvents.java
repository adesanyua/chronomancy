package com.chronomancy.effect;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.sound.ChronoStasisSounds;
import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class TemporalStasisEvents {

    private TemporalStasisEvents() {
    }

    // =========================================================
    // NBT KEYS
    // =========================================================

    public static final String STASIS_DATA_KEY =
            "ChronoStasisData";

    private static final String INITIALIZED_KEY =
            "Initialized";

    private static final String ACCUMULATED_DAMAGE_KEY =
            "AccumulatedDamage";

    private static final String DAMAGE_CAP_KEY =
            "DamageCap";

    private static final String SPELL_LEVEL_KEY =
            "SpellLevel";

    /**
     * АБСОЛЮТНЫЙ дедлайн стазиса в игровом времени мира (ServerLevel.getGameTime()).
     * Замороженная сущность НЕ тикает (TemporalStasisServerLevelMixin отменяет
     * tickNonPassenger), поэтому таймер ведут внешние мировые часы, а не сама сущность.
     */
    private static final String STASIS_END_GAME_TIME_KEY =
            "StasisEndGameTime";

    /**
     * Тот же дедлайн по часам {@link com.chronomancy.temporal.ChronoClock}, которые НЕ стоят во время
     * The World Stop. Игровое время в остановке стоит, и стазис на том, кто в ней действует (кастер:
     * удар Rift Maker, пески времени), по нему не кончился бы до конца остановки — а в испытании
     * разлома это «пока босс не добьёт». Для таких сущностей срок идёт по этим часам.
     */
    private static final String STASIS_END_CHRONO_KEY =
            "StasisEndChrono";

    // Position
    private static final String X_KEY =
            "FrozenX";

    private static final String Y_KEY =
            "FrozenY";

    private static final String Z_KEY =
            "FrozenZ";

    // Rotation
    private static final String Y_ROT_KEY =
            "FrozenYRot";

    private static final String X_ROT_KEY =
            "FrozenXRot";

    private static final String BODY_ROT_KEY =
            "FrozenBodyRot";

    private static final String HEAD_ROT_KEY =
            "FrozenHeadRot";

    // Previous states
    private static final String HAD_NO_GRAVITY_KEY =
            "HadNoGravity";

    private static final String WAS_SILENT_KEY =
            "WasSilent";

    private static final String FIRE_TICKS_KEY =
            "FireTicks";


    // =========================================================
    // FALLBACK
    // =========================================================

    private static final float DEFAULT_DAMAGE_CAP =
            12.0F;


    // =========================================================
    // MAX DURATION (внешние мировые часы)
    // =========================================================

    /**
     * Длительность стазиса по умолчанию (в тиках мирового времени) при отсутствии
     * длительности эффекта. Для внешних источников берём MobEffectInstance.getDuration(),
     * для заклинания передаём точный срок в {@link #initializeStasis}.
     */
    public static final long STASIS_DURATION_TICKS =
            20L * 3L;


    // =========================================================
    // DATA
    // =========================================================

    private static CompoundTag getOrCreateData(
            LivingEntity entity
    ) {
        CompoundTag persistent =
                entity.getPersistentData();

        if (!persistent.contains(STASIS_DATA_KEY)) {
            persistent.put(
                    STASIS_DATA_KEY,
                    new CompoundTag()
            );
        }

        return persistent.getCompound(
                STASIS_DATA_KEY
        );
    }


    private static CompoundTag getData(
            LivingEntity entity
    ) {
        return entity
                .getPersistentData()
                .getCompound(STASIS_DATA_KEY);
    }


    private static boolean hasData(
            LivingEntity entity
    ) {
        return entity
                .getPersistentData()
                .contains(STASIS_DATA_KEY);
    }


    // =========================================================
    // EFFECT
    // =========================================================

    public static boolean hasStasisEffect(
            LivingEntity entity
    ) {
        return entity.hasEffect(
                ChronoMobEffectRegistry.TEMPORAL_STASIS
        );
    }


    /**
     * Проверяет дедлайн стазиса по внешним мировым часам. Вызывается из
     * {@code TemporalStasisServerLevelMixin} ДО отмены тика замороженной сущности,
     * поскольку сама сущность во время стазиса не тикает.
     *
     * @param gameTime Текущее ServerLevel.getGameTime().
     * @return true, если время вышло и эффект был снят прямо сейчас — в этом
     *         случае вызывающий НЕ должен отменять тик: сущность оттикается
     *         штатно, и единый release-флоу отработает через EntityTickEvent.Post
     *         (тот же путь, что и разрушение стазиса по Damage Capacity).
     */
    public static boolean expireStasisIfNeeded(
            Entity entity,
            long gameTime
    ) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }

        if (!hasStasisEffect(living)) {
            return false;
        }

        // A frozen entity never reaches EntityTickEvent.Post, where the old fallback
        // used to initialize externally applied Stasis. Initialize it here, BEFORE
        // cancelling the entity tick, so /effect and other mods cannot freeze forever.
        CompoundTag data = getOrCreateData(living);
        if (!data.getBoolean(INITIALIZED_KEY) || !data.contains(STASIS_END_GAME_TIME_KEY)) {
            initializeFallback(living, data);
        }

        boolean due = gameTime >= data.getLong(STASIS_END_GAME_TIME_KEY);
        if (!due && data.contains(STASIS_END_CHRONO_KEY)
                && com.chronomancy.temporal.worldstop.GlobalTimeStopManager.isActive()
                && com.chronomancy.temporal.worldstop.GlobalTimeStopManager.isExempt(living)) {
            // остановка мира: игровое время стоит, а эта сущность в ней живёт — срок по внешним часам
            due = com.chronomancy.temporal.ChronoClock.now() >= data.getLong(STASIS_END_CHRONO_KEY);
        }
        if (!due) {
            return false;
        }

        living.removeEffect(
                ChronoMobEffectRegistry.TEMPORAL_STASIS
        );

        /*
         * Стазис закончился сам по себе (по таймеру мирового времени):
         * звук тот же, что и при разрушении игроком по Damage Capacity.
         */
        ChronoStasisSounds.playCapacityBreak(
                living.level(),
                living
        );

        return true;
    }


    // =========================================================
    // INITIALIZE FROM SPELL
    // =========================================================

    /**
     * Вызывается заклинанием сразу после наложения
     * Temporal Stasis.
     *
     * @param durationTicks длительность стазиса в тиках мирового времени,
     *                      рассчитанная спеллом по уровню свитка и spell power.
     */
    /**
     * Урон выпущенного стазиса: по всем свойствам это обычная «магия» (тот же тип урона — броня,
     * сопротивления и буфер Остановки мира видят его как раньше), но со своим сообщением о смерти.
     */
    private static net.minecraft.world.damagesource.DamageSource stasisReleaseSource(LivingEntity entity) {
        return new net.minecraft.world.damagesource.DamageSource(entity.damageSources().magic().typeHolder()) {
            @Override
            public net.minecraft.network.chat.Component getLocalizedDeathMessage(LivingEntity victim) {
                return net.minecraft.network.chat.Component.translatable(
                        "death.attack.chronomancy.temporal_stasis", victim.getDisplayName());
            }
        };
    }

    /**
     * @return сколько тиков стазис продлится на самом деле (после сопротивления и усталости);
     *         0 — цель к стазису сейчас невосприимчива, эффект снят.
     */
    public static long initializeStasis(
            LivingEntity target,
            int spellLevel,
            float damageCap,
            long durationTicks
    ) {
        // Сопротивление магии времени укорачивает стазис (луч, Cracked Dial, удар Rift Maker — все
        // источники проходят через этот метод).
        durationTicks = com.chronomancy.temporal.TimeMagicResist.scaleTicks(target, durationTicks);
        // Игрок под Borrowed Future стоит в стазисе вдвое меньше.
        durationTicks = com.chronomancy.temporal.borrowed.BorrowedFutureWard.scaleStasis(target, durationTicks);
        // Усталость: каждый следующий стазис на той же цели короче; на шестой раз подряд не ляжет.
        durationTicks = com.chronomancy.temporal.StasisFatigue.apply(target, durationTicks);
        if (durationTicks <= 0L) {
            // Цель уже стояла в стазисе — он идёт как шёл; новый просто не лёг.
            if (!(hasData(target) && getData(target).getBoolean(INITIALIZED_KEY))) {
                target.removeEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
                target.getPersistentData().remove(STASIS_DATA_KEY);
            }
            return 0L;
        }
        // песок времени на застывшей цели не осыпается
        com.chronomancy.temporal.SandsOfTime.onStasis(target, durationTicks);

        CompoundTag data =
                getOrCreateData(target);


        /*
         * Запоминаем точку, в которой время остановилось.
         */
        data.putDouble(
                X_KEY,
                target.getX()
        );

        data.putDouble(
                Y_KEY,
                target.getY()
        );

        data.putDouble(
                Z_KEY,
                target.getZ()
        );


        // Rotation
        data.putFloat(
                Y_ROT_KEY,
                target.getYRot()
        );

        data.putFloat(
                X_ROT_KEY,
                target.getXRot()
        );

        data.putFloat(
                BODY_ROT_KEY,
                target.yBodyRot
        );

        data.putFloat(
                HEAD_ROT_KEY,
                target.yHeadRot
        );


        // Previous states
        data.putBoolean(
                HAD_NO_GRAVITY_KEY,
                target.isNoGravity()
        );

        data.putBoolean(
                WAS_SILENT_KEY,
                target.isSilent()
        );

        data.putInt(
                FIRE_TICKS_KEY,
                target.getRemainingFireTicks()
        );


        // Spell
        data.putInt(
                SPELL_LEVEL_KEY,
                spellLevel
        );

        data.putFloat(
                DAMAGE_CAP_KEY,
                Math.max(
                        1.0F,
                        damageCap
                )
        );


        // Damage starts from zero
        data.putFloat(
                ACCUMULATED_DAMAGE_KEY,
                0.0F
        );


        /*
         * Дедлайн стазиса считается по мировому времени в момент наложения:
         * замороженная сущность не тикает, поэтому часы — внешний ServerLevel.
         * Длительность передаёт спелл (растёт с уровнем свитка и spell power).
         */
        long endGameTime =
                target.level().getGameTime() + Math.max(1L, durationTicks);

        data.putLong(
                STASIS_END_GAME_TIME_KEY,
                endGameTime
        );
        data.putLong(
                STASIS_END_CHRONO_KEY,
                com.chronomancy.temporal.ChronoClock.now() + Math.max(1L, durationTicks)
        );


        data.putBoolean(
                INITIALIZED_KEY,
                true
        );
        return durationTicks;
    }


    // =========================================================
    // DAMAGE
    // =========================================================

    public static void onLivingIncomingDamage(
            LivingIncomingDamageEvent event
    ) {
        LivingEntity entity =
                event.getEntity();


        if (!hasStasisEffect(entity)) {
            return;
        }


        /*
         * Во время стазиса HP вообще не меняется.
         */
        event.setCanceled(true);


        float incoming =
                event.getAmount();


        if (incoming <= 0.0F) {
            return;
        }


        CompoundTag data =
                getOrCreateData(entity);


        /*
         * /effect или другая система могла наложить
         * Temporal Stasis напрямую.
         */
        if (!data.contains(
                DAMAGE_CAP_KEY
        )) {
            data.putFloat(
                    DAMAGE_CAP_KEY,
                    DEFAULT_DAMAGE_CAP
            );
        }


        float accumulated =
                data.getFloat(
                        ACCUMULATED_DAMAGE_KEY
                );


        accumulated += incoming;


        data.putFloat(
                ACCUMULATED_DAMAGE_KEY,
                accumulated
        );


        float damageCap =
                data.getFloat(
                        DAMAGE_CAP_KEY
                );


        // =====================================================
        // STASIS HOLDS
        // =====================================================

        if (accumulated < damageCap) {
            return;
        }


        // =====================================================
        // BREAK STASIS
        // =====================================================

        /*
         * Порог достигнут.
         *
         * Убираем эффект.
         *
         * Накопленный урон здесь НЕ наносим.
         * Это сделает release() после снятия эффекта.
         */
        entity.removeEffect(
                ChronoMobEffectRegistry.TEMPORAL_STASIS
        );

        /*
         * Stasis Capacity разрушена: стеклянный CRACK +
         * рассыпание песка + низкий удар.
         */
        ChronoStasisSounds.playCapacityBreak(
                entity.level(),
                entity
        );
    }


    // =========================================================
    // ENTITY TICK
    // =========================================================

    public static void onEntityTick(
            EntityTickEvent.Post event
    ) {
        if (!(event.getEntity()
                instanceof LivingEntity entity)) {
            return;
        }


        // =====================================================
        // STASIS ACTIVE
        // =====================================================

        if (hasStasisEffect(entity)) {

            CompoundTag data =
                    getOrCreateData(entity);


            /*
             * Fallback для стазиса, наложенного не
             * нашим заклинанием.
             */
            if (!data.getBoolean(
                    INITIALIZED_KEY
            )) {
                initializeFallback(
                        entity,
                        data
                );
            }


            freeze(
                    entity,
                    data
            );

            /*
             * Пока стазис активен — очень тихий периодический
             * часовой tick (раз в секунду, со сдвигом по id,
             * чтобы толпа не тикала синхронно).
             */
            if (!entity.level().isClientSide
                    && (entity.level().getGameTime() + entity.getId()) % 20L == 0L) {
                ChronoStasisSounds.playActiveTick(
                        entity.level(),
                        entity
                );
            }

            return;
        }


        // =====================================================
        // STASIS ENDED
        // =====================================================

        if (!hasData(entity)) {
            return;
        }


        CompoundTag data =
                getData(entity);


        if (data.getBoolean(
                INITIALIZED_KEY
        )) {
            release(entity);
        }
    }


    // =========================================================
    // FALLBACK INITIALIZATION
    // =========================================================

    private static void initializeFallback(
            LivingEntity entity,
            CompoundTag data
    ) {
        data.putDouble(
                X_KEY,
                entity.getX()
        );

        data.putDouble(
                Y_KEY,
                entity.getY()
        );

        data.putDouble(
                Z_KEY,
                entity.getZ()
        );


        data.putFloat(
                Y_ROT_KEY,
                entity.getYRot()
        );

        data.putFloat(
                X_ROT_KEY,
                entity.getXRot()
        );

        data.putFloat(
                BODY_ROT_KEY,
                entity.yBodyRot
        );

        data.putFloat(
                HEAD_ROT_KEY,
                entity.yHeadRot
        );


        data.putBoolean(
                HAD_NO_GRAVITY_KEY,
                entity.isNoGravity()
        );

        data.putBoolean(
                WAS_SILENT_KEY,
                entity.isSilent()
        );

        data.putInt(
                FIRE_TICKS_KEY,
                entity.getRemainingFireTicks()
        );


        if (!data.contains(
                DAMAGE_CAP_KEY
        )) {
            data.putFloat(
                    DAMAGE_CAP_KEY,
                    DEFAULT_DAMAGE_CAP
            );
        }


        if (!data.contains(
                ACCUMULATED_DAMAGE_KEY
        )) {
            data.putFloat(
                    ACCUMULATED_DAMAGE_KEY,
                    0.0F
            );
        }


        if (!data.contains(
                STASIS_END_GAME_TIME_KEY
        )) {
            int fallbackTicks = Math.max(1,
                    entity.getEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS) != null
                            ? entity.getEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS).getDuration()
                            : (int) STASIS_DURATION_TICKS);
            // стазис из стороннего источника (команда, другой мод) тоже устаёт; «0» истечёт сразу
            fallbackTicks = (int) com.chronomancy.temporal.StasisFatigue.apply(entity, fallbackTicks);
            com.chronomancy.temporal.SandsOfTime.onStasis(entity, fallbackTicks);
            data.putLong(
                    STASIS_END_GAME_TIME_KEY,
                    entity.level().getGameTime() + fallbackTicks
            );
            data.putLong(
                    STASIS_END_CHRONO_KEY,
                    com.chronomancy.temporal.ChronoClock.now() + fallbackTicks
            );
        }


        data.putBoolean(
                INITIALIZED_KEY,
                true
        );
    }


    // =========================================================
    // FREEZE
    // =========================================================

    /** Re-pin packet-driven players, whose movement can arrive outside their entity tick. */
    public static void pinFrozenPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (!hasStasisEffect(player)) return;
        if (expireStasisIfNeeded(player, player.level().getGameTime())) return;
        CompoundTag data = getOrCreateData(player);
        if (!data.getBoolean(INITIALIZED_KEY)) initializeFallback(player, data);
        freeze(player, data);
    }

    private static void freeze(
            LivingEntity entity,
            CompoundTag data
    ) {

        // =====================================================
        // POSITION
        // =====================================================

        double x =
                data.getDouble(X_KEY);

        double y =
                data.getDouble(Y_KEY);

        double z =
                data.getDouble(Z_KEY);


        /*
         * Жёстко возвращаем сущность в точку
         * начала стазиса каждый tick.
         *
         * Даже если внутренняя логика моба попробовала
         * его передвинуть, визуально и физически он
         * останется здесь.
         */
        entity.setPos(
                x,
                y,
                z
        );


        entity.setDeltaMovement(
                Vec3.ZERO
        );


        entity.hurtMarked =
                true;


        // =====================================================
        // GRAVITY
        // =====================================================

        entity.setNoGravity(
                true
        );


        // =====================================================
        // ROTATION
        // =====================================================

        float yRot =
                data.getFloat(
                        Y_ROT_KEY
                );

        float xRot =
                data.getFloat(
                        X_ROT_KEY
                );

        float bodyRot =
                data.getFloat(
                        BODY_ROT_KEY
                );

        float headRot =
                data.getFloat(
                        HEAD_ROT_KEY
                );


        entity.setYRot(
                yRot
        );

        entity.setXRot(
                xRot
        );


        entity.yRotO =
                yRot;

        entity.xRotO =
                xRot;


        entity.yBodyRot =
                bodyRot;

        entity.yBodyRotO =
                bodyRot;


        entity.yHeadRot =
                headRot;

        entity.yHeadRotO =
                headRot;


        // =====================================================
        // SOUND
        // =====================================================

        entity.setSilent(
                true
        );


        // =====================================================
        // FIRE
        // =====================================================

        entity.setRemainingFireTicks(
                data.getInt(
                        FIRE_TICKS_KEY
                )
        );
    }


    // =========================================================
    // RELEASE
    // =========================================================

    private static void release(
            LivingEntity entity
    ) {
        if (!hasData(entity)) {
            return;
        }


        CompoundTag data =
                getData(entity);


        float accumulatedDamage =
                data.getFloat(
                        ACCUMULATED_DAMAGE_KEY
                );


        // =====================================================
        // GRAVITY
        // =====================================================

        if (data.contains(
                HAD_NO_GRAVITY_KEY
        )) {
            entity.setNoGravity(
                    data.getBoolean(
                            HAD_NO_GRAVITY_KEY
                    )
            );
        }


        // =====================================================
        // SOUND
        // =====================================================

        if (data.contains(
                WAS_SILENT_KEY
        )) {
            entity.setSilent(
                    data.getBoolean(
                            WAS_SILENT_KEY
                    )
            );
        }


        // =====================================================
        // FIRE
        // =====================================================

        if (data.contains(
                FIRE_TICKS_KEY
        )) {
            entity.setRemainingFireTicks(
                    data.getInt(
                            FIRE_TICKS_KEY
                    )
            );
        }


        entity.setDeltaMovement(
                Vec3.ZERO
        );


        // =====================================================
        // REMOVE DATA
        // =====================================================

        /*
         * ОБЯЗАТЕЛЬНО удаляем данные до hurt().
         */
        entity.getPersistentData()
                .remove(
                        STASIS_DATA_KEY
                );


        // =====================================================
        // RELEASE DAMAGE
        // =====================================================

        if (accumulatedDamage > 0.0F) {

            /*
             * Выброс накопленного урона — «временная рана», а не физический
             * импульс: источник схлопнут в скаляр, направление у vanilla
             * knockback было бы случайным мусором (нулевой ratio), а сам
             * импульс обнулил бы позу замороженной цели. Поэтому no-knockback
             * здесь — для всего релиза (включая будущие deferred-раны вроде
             * выпущенного Backtrack-удара).
             */
            BacktrackKnockbackSuppression.runWithoutKnockback(
                    entity,
                    () -> entity.hurt(
                            stasisReleaseSource(entity),
                            accumulatedDamage
                    )
            );
        }
    }


    // =========================================================
    // GETTERS
    // =========================================================

    public static float getAccumulatedDamage(
            LivingEntity entity
    ) {
        if (!hasData(entity)) {
            return 0.0F;
        }

        return getData(entity)
                .getFloat(
                        ACCUMULATED_DAMAGE_KEY
                );
    }


    public static float getDamageCap(
            LivingEntity entity
    ) {
        if (!hasData(entity)) {
            return DEFAULT_DAMAGE_CAP;
        }

        CompoundTag data =
                getData(entity);

        if (!data.contains(
                DAMAGE_CAP_KEY
        )) {
            return DEFAULT_DAMAGE_CAP;
        }

        return data.getFloat(
                DAMAGE_CAP_KEY
        );
    }
}
