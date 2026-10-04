package com.chronomancy.client;

import com.chronomancy.item.ChronoCurioEvents;
import com.chronomancy.item.TimelessBookCasting;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Клиентская половина сжатого каста Timeless Book: полоса каста и анимация своего игрока бегут в
 * {@value TimelessBookCasting#SPEED} раз быстрее — так же, как сервер проживает сам каст. Последний
 * шаг клиент не делает: завершение каста приходит с сервера.
 */
public final class TimelessBookClient {
    private TimelessBookClient() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused() || !ClientMagicData.isCasting()
                || ClientMagicData.getCastType() != CastType.CONTINUOUS) {
            return;
        }
        if (!TimelessBookCasting.accelerates(SpellRegistry.getSpell(ClientMagicData.getCastingSpellId()))
                || !ChronoCurioEvents.wearsTimelessBook(mc.player)) {
            return;
        }
        for (int i = 1; i < TimelessBookCasting.SPEED && ClientMagicData.getCastDurationRemaining() > 1; i++) {
            ClientMagicData.handleCastDuration();
        }
    }
}
