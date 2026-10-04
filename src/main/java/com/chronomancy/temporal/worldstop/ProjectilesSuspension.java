package com.chronomancy.temporal.worldstop;

import com.chronomancy.ChronomancyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Фаза 3 — приостановка СНАРЯДОВ The World Stop.
 *
 * <p>Во время мирового стопа тикает только кастер, значит единственные
 * новые снаряды, рождающиеся в остановленном времени — снаряды кастера.
 * Обычные (не-кастерские) летящие снаряды уже заморожены отменой
 * {@code EntityTickEvent.Pre} и не требуют работы с скоростью.
 *
 * <p>Снаряд кастера, созданный во время стопа, подвешивается: исходная
 * скорость/поворот/гравитация сохраняются, {@code setDeltaMovement(ZERO)} +
 * {@code setNoGravity(true)} — снаряд «висит» на вылете. Никаких голых
 * {@code velocity *= 0}: оригинал хранится и честно восстанавливается.
 *
 * <p>{@link #resumeAll(MinecraftServer)} вызывается из {@code end()} ПЕРВЫМ
 * (до выброса урона) и обходит ВСЕ измерения ({@code server.getAllLevels()})
 * — снаряд мог улететь в другое измерение. Восстановление: скорость,
 * пересчёт yaw/pitch из вектора ({@link Mth#atan2}), флаг импульса и два
 * глобальных пакета (teleport + motion) всем трекающим игрокам.
 */
public final class ProjectilesSuspension {

    private ProjectilesSuspension() {
    }

    /** Сохранёное состояние одного подвешенного снаряда. */
    private static final class Suspended {
        private final Vec3 motion;
        private final boolean noGravity;

        private Suspended(Vec3 motion, boolean noGravity) {
            this.motion = motion;
            this.noGravity = noGravity;
        }
    }

    // Ключ — UUID снаряда (устойчив к смене измерения; объект мог быть
    // удалён — тогда запись просто выбрасывается при resumeAll).
    private static final Map<UUID, Suspended> SUSPENDED = new HashMap<>();

    /**
     * Точка входа NeoForge. Регистрируется как обработчик EntityJoinLevelEvent.
     * Ловит только снаряды, чей владелец — кастер мирового стопа.
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !GlobalTimeStopManager.isActive()) {
            return;
        }
        if (!(event.getEntity() instanceof Projectile projectile)) {
            return;
        }
        Entity owner = projectile.getOwner();
        if (owner == null || !GlobalTimeStopManager.isCaster(owner)) {
            return;
        }
        if (projectile instanceof com.chronomancy.entity.SandsOfTimeProjectile) {
            return; // струя песка не «снаряд на вылете»: она течёт и в остановленном времени
        }
        suspend(projectile);
    }

    /** Идемпотентная подвеска: повторный вызов для того же снаряда — no-op. */
    private static void suspend(Projectile projectile) {
        UUID id = projectile.getUUID();
        if (SUSPENDED.containsKey(id)) {
            return;
        }
        SUSPENDED.put(id, new Suspended(projectile.getDeltaMovement(), projectile.isNoGravity()));
        projectile.setDeltaMovement(Vec3.ZERO);
        projectile.setNoGravity(true);
    }

    /**
     * Разморозка всех снарядов во всех измерениях. Вызывается из
     * {@code GlobalTimeStopManager.end()} (уже после снятия флага active).
     */
    public static void resumeAll(MinecraftServer server) {
        if (SUSPENDED.isEmpty()) {
            return;
        }
        // Снималок-копия ключей + атомарная очистка карты — снаряд не может
        // быть восстановлен дважды, даже если end() зовут конкурентно.
        Map<UUID, Suspended> snapshot = new HashMap<>(SUSPENDED);
        SUSPENDED.clear();

        for (Map.Entry<UUID, Suspended> entry : snapshot.entrySet()) {
            Projectile projectile = findProjectile(server, entry.getKey());
            if (projectile == null) {
                continue;
            }
            resume(projectile, entry.getValue());
        }
        if (!snapshot.isEmpty()) {
            ChronomancyMod.LOGGER.info("[WorldStop] resumed {} suspended projectile(s)", snapshot.size());
        }
    }

    private static Projectile findProjectile(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof Projectile projectile) {
                return projectile;
            }
        }
        return null;
    }

    private static void resume(Projectile projectile, Suspended state) {
        projectile.setNoGravity(state.noGravity);
        Vec3 motion = state.motion;
        projectile.setDeltaMovement(motion);

        if (!motion.equals(Vec3.ZERO)) {
            double horizontal = motion.horizontalDistance();
            projectile.setYRot((float) (Mth.atan2(motion.x, motion.z) * Mth.RAD_TO_DEG));
            projectile.setXRot((float) (Mth.atan2(motion.y, horizontal) * Mth.RAD_TO_DEG));
            projectile.yRotO = projectile.getYRot();
            projectile.xRotO = projectile.getXRot();
            // Публичное поле Entity: клиент на первом тике не применит гравитацию
            // раньше полученного импульса.
            projectile.hasImpulse = true;
        }

        // Один снаряд → два глобальных пакета всем, кто его трекает. Без
        // per-player циклa: чанк-провайдер сам рассылает трекающим игрокам.
        ServerChunkCache chunkSource = ((ServerLevel) projectile.level()).getChunkSource();
        chunkSource.broadcast(projectile, new ClientboundTeleportEntityPacket(projectile));
        chunkSource.broadcast(projectile, new ClientboundSetEntityMotionPacket(projectile));
    }

    /** Отладочный счётчик (используется тестами Phase 3). */
    public static int suspendedCount() {
        return new ArrayList<>(SUSPENDED.keySet()).size();
    }
}
