package com.chronomancy.mixin;

import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фаза 5 — остановка МИРА (серверная) для The World Stop.
 *
 * <p>Сущности заморожены отменой {@code EntityTickEvent.Pre}; этот миксин
 * глушит оставшиеся мировые часы, которые идут НЕ через тик сущностей. Все
 * четыре цели — отдельные {@code ServerLevel.tick} под-фазы, отменяются на
 * HEAD, пока стоп активен (ВО ВСЕХ измерениях — мир один).
 *
 * <ul>
 *   <li>{@code tickTime}: единственное место, где advancing {@code gameTime}
 *       + вызов {@code getScheduledEvents().tick(server, gameTime)} + рост
 *       dayTime. Отмена ⇒ часы мира стоят, а ОТЛОЖЕННЫЕ блок-тики
 *       ({@code LevelTicks}) НЕ удаляются: очередь сверяется с замороженным
 *       {@code gameTime}, поэтому дошедшие до тика события просто не
 *       наступают и теряют ровно 6 секунд — пауза, а не сброс (plan §Phase 5).</li>
 *   <li>{@code advanceWeatherCycle}: таймеры дождя/грозы замирают.</li>
 *   <li>{@code tickChunk}: random ticks (рост культур/травы, поджог, руд),
 *       осадки и молнии — стоп.</li>
 *   <li>{@code tickCustomSpawners}: плавильники/респавнер-логика — стоп.</li>
 * </ul>
 *
 * <p>Соседние обновления блоков (redstone/observer) СОЗНАТЕЛЬНО не
 * замораживаются — они идут вне этих фаз и не являются «временем» в смысле
 * спама сущностей; это задокументированное ограничение.
 */
@Mixin(ServerLevel.class)
public abstract class WorldStopServerLevelMixin {

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void chronomancy$stopWorldTime(CallbackInfo ci) {
        if (GlobalTimeStopManager.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void chronomancy$stopWeather(CallbackInfo ci) {
        if (GlobalTimeStopManager.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void chronomancy$stopChunkTicks(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (GlobalTimeStopManager.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
    private void chronomancy$stopSpawners(boolean spawnEnemies, boolean spawnFriendlies, CallbackInfo ci) {
        if (GlobalTimeStopManager.isActive()) {
            ci.cancel();
        }
    }
}
