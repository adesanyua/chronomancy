package com.chronomancy.temporal;

import com.chronomancy.entity.ChronomalyEntity;
import com.chronomancy.entity.PlayerEchoEntity;
import com.chronomancy.entity.RiftMakerEntity;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * Иммунитет к магии времени: Rift Maker, его медные двойники и Chronomaly сами «сделаны» из
 * разорванного времени, поэтому на них не действуют:
 * <ul>
 *   <li>Time Dilation Field — ни чужие поля, ни поле самого босса;</li>
 *   <li>Temporal Stasis (луч, Cracked Dial и любые другие источники эффекта);</li>
 *   <li>откат позиции от Backtrack (отложенный урон при этом приходит как обычно);</li>
 *   <li>эффект Парадокса после временного взрыва (сам взрыв их ранит).</li>
 * </ul>
 * The World Stop на них и так не действует ({@code WorldStopExempt}). Урон от заклинаний
 * хрономантии они получают — иначе их нельзя было бы победить школьной магией.
 */
public final class TimeMagicImmunity {

    private TimeMagicImmunity() {
    }

    public static boolean isImmune(Entity entity) {
        return entity instanceof RiftMakerEntity
                || entity instanceof ChronomalyEntity
                || entity instanceof com.chronomancy.entity.ChronomalMob
                || (entity instanceof PlayerEchoEntity echo && echo.isFromBoss());
    }

    /** Стазис и парадокс на иммунных не накладываются вообще — из какого бы источника ни пришли. */
    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        if ((event.getEffectInstance().is(ChronoMobEffectRegistry.TEMPORAL_STASIS)
                || event.getEffectInstance().is(ChronoMobEffectRegistry.PARADOX))
                && (isImmune(event.getEntity()) || com.chronomancy.entity.ChronomalMob.is(event.getEntity()))) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }
}
