package com.chronomancy.client;

import com.chronomancy.network.ParadoxSyncPayload;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.TimeParadox;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Клиентская сторона эффекта Парадокса: кто им поражён и в какой фазе качелей времени он сейчас.
 *
 * <p>Сам визуал на модели рисует {@link VanillaPhase}: в быструю фазу — яркая оболочка и шлейф
 * силуэтов, в медленную — тусклая оболочка и частые «рывки» тела; части модели выпадают в обеих.
 * Здесь — учёт поражённых и частицы: в быструю фазу вверх срываются искры, в медленную вокруг висят
 * пылинки, а в момент переключения фазы тело трескается.
 */
public final class ParadoxClient {
    /** id сущности -> до какого клиентского тика эффект считается висящим. */
    private static final Int2LongMap UNTIL = new Int2LongOpenHashMap();
    /**
     * id -> сам объект сущности, на котором висит эффект. Игрок после возрождения получает тот же id,
     * но это уже другой объект: запись о прежнем к нему не относится (иначе визуал парадокса
     * переезжал на возродившегося).
     */
    private static final java.util.Map<Integer, java.lang.ref.WeakReference<Entity>> BOUND = new java.util.HashMap<>();
    /**
     * Сколько тиков клиент верит одному пакету. Сервер, пока эффект висит, повторяет пакет каждые две
     * секунды ({@code ParadoxSync}); если повторы прекратились, а пакет о снятии потерялся, визуал
     * гаснет сам — навсегда он остаться не может.
     */
    private static final int HOLD_TICKS = 100;
    /** Свои тики клиента (не игровое время мира: оно стоит в остановленном мире). */
    private static long ticks;
    /** Разница между серверными часами качелей и {@link #ticks}. */
    private static long offset;

    private ParadoxClient() {
    }

    public static void apply(ParadoxSyncPayload payload) {
        if (payload.ticks() <= 0) {
            UNTIL.remove(payload.entityId());
            BOUND.remove(payload.entityId());
            return;
        }
        // Эффект тикает тиками самой жертвы, а её время то спешит, то вязнет — точного срока клиент не
        // знает и не считает: держит запись недолго, а сервер её подновляет, пока эффект висит.
        UNTIL.put(payload.entityId(), ticks + Math.min(payload.ticks() + 20L, HOLD_TICKS));
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level == null ? null : mc.level.getEntity(payload.entityId());
        if (entity != null) {
            BOUND.put(payload.entityId(), new java.lang.ref.WeakReference<>(entity));
        } else {
            BOUND.remove(payload.entityId()); // привяжется, когда сущность появится
        }
        offset = payload.clock() - ticks;
    }

    /** Висит ли на сущности Парадокс (свой игрок знает это и без пакета — по самому эффекту). */
    public static boolean afflicted(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!UNTIL.isEmpty() && UNTIL.containsKey(entity.getId()) && bound(entity)) {
            return true;
        }
        return entity == Minecraft.getInstance().player && entity instanceof LivingEntity living
                && living.hasEffect(ChronoMobEffectRegistry.PARADOX);
    }

    /** Относится ли запись с этим id к ЭТОМУ объекту сущности; чужая запись сразу убирается. */
    private static boolean bound(Entity entity) {
        java.lang.ref.WeakReference<Entity> ref = BOUND.get(entity.getId());
        if (ref == null) {
            BOUND.put(entity.getId(), new java.lang.ref.WeakReference<>(entity));
            return true;
        }
        if (ref.get() != entity) {
            UNTIL.remove(entity.getId());
            BOUND.remove(entity.getId());
            return false;
        }
        return true;
    }

    /** Быстрая фаза качелей (иначе медленная) — та же формула, что на сервере в {@link TimeParadox#rate}. */
    public static boolean fast(Entity entity) {
        return ((phaseClock(entity) / TimeParadox.SWING_TICKS) & 1L) == 0L;
    }

    private static long phaseClock(Entity entity) {
        return ticks + offset + Math.floorMod(entity.getId() * 7, TimeParadox.SWING_TICKS * 2);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            UNTIL.clear();
            BOUND.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        if (!UNTIL.isEmpty()) {
            var it = UNTIL.int2LongEntrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity entity = mc.level.getEntity(entry.getIntKey());
                java.lang.ref.WeakReference<Entity> ref = BOUND.get(entry.getIntKey());
                if (entity == null || entry.getLongValue() <= ticks || !entity.isAlive()
                        || (ref != null && ref.get() != entity)) {
                    it.remove();
                    BOUND.remove(entry.getIntKey());
                } else {
                    particles(mc, entity);
                }
            }
        }
        // свой игрок, вошедший в мир уже с эффектом: пакета не было, но эффект у него синхронизирован
        if (mc.player != null && !UNTIL.containsKey(mc.player.getId()) && afflicted(mc.player)) {
            particles(mc, mc.player);
        }
    }

    private static void particles(Minecraft mc, Entity entity) {
        if (!entity.isAlive() || entity.isInvisible()
                || (entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson())) {
            return; // от первого лица частицы сыпались бы прямо в камеру
        }
        RandomSource random = entity.getRandom();
        long clock = phaseClock(entity);
        boolean fast = ((clock / TimeParadox.SWING_TICKS) & 1L) == 0L;
        double w = entity.getBbWidth() * 0.6D;
        double h = entity.getBbHeight();
        if (clock % TimeParadox.SWING_TICKS == 0) {
            // время переломилось: по телу бегут трещины
            for (int i = 0; i < 4; i++) {
                mc.level.addParticle(ChronoParticleRegistry.TEMPORAL_CRACK.get(),
                        entity.getX() + (random.nextDouble() - 0.5D) * w * 2.0D,
                        entity.getY() + random.nextDouble() * h,
                        entity.getZ() + (random.nextDouble() - 0.5D) * w * 2.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        if (fast) {
            // время спешит: искры срываются вверх
            mc.level.addParticle(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    entity.getX() + (random.nextDouble() - 0.5D) * w * 2.0D,
                    entity.getY() + random.nextDouble() * h * 0.8D,
                    entity.getZ() + (random.nextDouble() - 0.5D) * w * 2.0D,
                    0.0D, 0.10D + random.nextDouble() * 0.12D, 0.0D);
        } else if ((clock & 1L) == 0L) {
            // время вязнет: пылинки почти стоят
            mc.level.addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    entity.getX() + (random.nextDouble() - 0.5D) * w * 2.4D,
                    entity.getY() + random.nextDouble() * h,
                    entity.getZ() + (random.nextDouble() - 0.5D) * w * 2.4D,
                    (random.nextDouble() - 0.5D) * 0.01D, (random.nextDouble() - 0.5D) * 0.01D,
                    (random.nextDouble() - 0.5D) * 0.01D);
        }
    }

    public static void clear() {
        UNTIL.clear();
        BOUND.clear();
    }
}
