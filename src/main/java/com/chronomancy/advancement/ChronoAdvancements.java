package com.chronomancy.advancement;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * ДОСТИЖЕНИЯ ХРОНОМАНТИИ — цепочка от первого свитка до победы над Творцом разломов.
 *
 * <p>Сами достижения лежат в {@code data/chronomancy/advancement}. Те, что ванильные триггеры
 * выразить не могут (заклинание произнесено, случился парадокс, началось испытание), объявлены с
 * критерием {@code minecraft:impossible} и выдаются отсюда. Описание достижения видно ещё до того,
 * как оно получено, — так игрок узнаёт про парадокс и про испытание разлома.
 */
public final class ChronoAdvancements {
    public static final String ROOT = "root";
    public static final String FIRST_CAST = "first_cast";
    public static final String STASIS = "stasis";
    public static final String PARADOX = "paradox";
    public static final String WORLD_STOP = "world_stop";
    public static final String CLOCKSMITH = "clocksmith";
    public static final String TRIAL = "trial";
    public static final String RIFT_MAKER_ARRIVES = "rift_maker_arrives";
    public static final String RIFT_MAKER = "rift_maker";
    public static final String SECOND_CHANCE = "second_chance";

    private static final String PREFIX = ChronomancyMod.MODID + ":";
    private static final String WORLD_STOP_ID = PREFIX + "the_world_stop";
    /** Как часто инвентарь проверяется на первый свиток (пока корневое достижение не получено). */
    private static final int SCROLL_SCAN_INTERVAL = 40;

    private ChronoAdvancements() {
    }

    /** Выдать достижение {@code chronomancy:<name>}; повторный вызов ничего не делает. */
    public static void grant(ServerPlayer player, String name) {
        AdvancementHolder holder = holder(player, name);
        if (holder == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (progress.isDone()) {
            return;
        }
        java.util.List<String> remaining = new java.util.ArrayList<>();
        progress.getRemainingCriteria().forEach(remaining::add);
        for (String criterion : remaining) {
            player.getAdvancements().award(holder, criterion);
        }
    }

    /** То же, если сущность — игрок на сервере. */
    public static void grant(Entity entity, String name) {
        if (entity instanceof ServerPlayer player) {
            grant(player, name);
        }
    }

    public static boolean has(ServerPlayer player, String name) {
        AdvancementHolder holder = holder(player, name);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static AdvancementHolder holder(ServerPlayer player, String name) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        return player.getServer().getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, name));
    }

    /** Любое заклинание хрономантии — «первые секунды»; The World Stop — своё достижение. */
    public static void onSpellCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getSpellId() == null
                || !event.getSpellId().startsWith(PREFIX)) {
            return;
        }
        grant(player, ROOT);
        grant(player, FIRST_CAST);
        if (WORLD_STOP_ID.equals(event.getSpellId())) {
            grant(player, WORLD_STOP);
        }
    }

    /** Корень цепочки — свиток хрономантии в инвентаре (куплен, найден в сундуке, выдан командой). */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % SCROLL_SCAN_INTERVAL != 0
                || has(player, ROOT)) {
            return;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (holdsChronomancy(stack)) {
                grant(player, ROOT);
                return;
            }
        }
        if (holdsChronomancy(player.getOffhandItem())) {
            grant(player, ROOT);
        }
    }

    private static boolean holdsChronomancy(ItemStack stack) {
        if (stack.isEmpty() || !ISpellContainer.isSpellContainer(stack)) {
            return false;
        }
        for (var slot : ISpellContainer.get(stack).getActiveSpells()) {
            if (slot.getSpell().getSpellId().startsWith(PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
