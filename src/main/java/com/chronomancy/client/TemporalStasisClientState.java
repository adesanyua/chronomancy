package com.chronomancy.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Клиентский реестр id сущностей, которые сейчас заморожены Temporal Stasis.
 *
 * <p>Mob-эффекты не синхронизируются наблюдающим клиентам, поэтому клиент не
 * может определить стазис через {@code hasEffect}. Сервер явно сообщает
 * («кто/когда»), какой entity-id заморожен — см. {@code ChronoNetwork} и
 * {@code StasisSyncPayload}. Этот класс хранит результат, который читают
 * клиентские render/tick-миксины.
 */
public final class TemporalStasisClientState {

    private static final Set<Integer> FROZEN =
            Collections.synchronizedSet(new HashSet<>());

    private TemporalStasisClientState() {
    }

    public static void setFrozen(int entityId, boolean frozen) {
        if (frozen) {
            FROZEN.add(entityId);
        } else {
            FROZEN.remove(entityId);
        }
    }

    public static boolean isFrozen(int entityId) {
        return FROZEN.contains(entityId);
    }

    /**
     * Снапшот id для клиентского тикера частиц ({@code ChronoParticles}).
     * ArrayList(Collection) использует toArray — безопасно для synchronizedSet.
     */
    public static java.util.List<Integer> snapshotIds() {
        return new java.util.ArrayList<>(FROZEN);
    }

    /** Очистка при выходе из мира, чтобы id не «протекли» в следующую сессию. */
    public static void clear() {
        FROZEN.clear();
    }
}
