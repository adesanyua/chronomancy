package com.chronomancy.client;

import com.chronomancy.entity.RiftMakerEntity;
import com.chronomancy.registry.ChronoSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/**
 * Музыка боя с Rift Maker. Тема начинается, как только над ареной раскрываются врата босса, и
 * звучит, пока живой босс рядом с игроком: обычная фоновая музыка заменяется его
 * темой — тем же ванильным механизмом, каким бой с драконом заменяет музыку Края: трек начинается
 * сразу, идёт по кругу и подчиняется ползунку «Музыка». Босс погиб или остался далеко — тема
 * обрывается, и игра возвращается к обычной музыке.
 */
public final class BossMusicClient {
    /** На каком расстоянии от босса играет его тема. */
    private static final double RANGE = 64.0D;
    private static final int SCAN_INTERVAL = 10;

    private static Music music;
    private static boolean bossNear;
    private static int scanIn;

    private BossMusicClient() {
    }

    private static Music music() {
        if (music == null) {
            // без пауз между повторами и с немедленной заменой текущей мелодии
            music = new Music(ChronoSounds.MUSIC_RIFT_MAKER, 0, 0, true);
        }
        return music;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            bossNear = false;
            return;
        }
        if (--scanIn > 0) {
            return;
        }
        scanIn = SCAN_INTERVAL;
        var area = mc.player.getBoundingBox().inflate(RANGE);
        // врата над ареной уже раскрылись, а сам босс ещё не выпал — музыка начинается с его появления
        boolean near = !mc.level.getEntitiesOfClass(RiftMakerEntity.class, area, RiftMakerEntity::isAlive).isEmpty()
                || !mc.level.getEntitiesOfClass(com.chronomancy.entity.TimeRiftEntity.class, area,
                        gate -> gate.isAlive() && gate.isOverhead()).isEmpty();
        if (bossNear && !near) {
            mc.getMusicManager().stopPlaying(music());
        }
        bossNear = near;
    }

    public static void onSelectMusic(SelectMusicEvent event) {
        if (bossNear) {
            event.overrideMusic(music());
        }
    }
}
