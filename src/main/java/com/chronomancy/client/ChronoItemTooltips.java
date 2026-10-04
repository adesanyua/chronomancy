package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import net.minecraft.locale.Language;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Описания предметов мода — в одном стиле и в одном месте. Под названием предмета идут:
 * <ul>
 *   <li>легенда ({@code item.chronomancy.<id>.lore}) — короткая литературная строка курсивом, цвет
 *       зависит от редкости предмета;</li>
 *   <li>эффекты ({@code item.chronomancy.<id>.effect}) — по строке на эффект, с медным ромбом в
 *       начале; числа и ключевые слова, взятые в {@code |черту|}, подсвечиваются золотом.</li>
 * </ul>
 * Несколько строк разделяются {@code \n}. Нет ключа — нет строки, так что предметам без описания
 * (яйца призыва) ничего не добавляется.
 */
public final class ChronoItemTooltips {
    /** Цвет легенды по редкости: легендарное — золото, редкое — голубой, необычное — медь, обычное — песок. */
    private static final int LORE_EPIC = 0xF2C14E, LORE_RARE = 0x8EC5F0, LORE_UNCOMMON = 0xDFA468, LORE_COMMON = 0xCDBB8C;
    /** Строка эффекта: ромб, обычный текст, подсветка. */
    private static final int MARK = 0xE3944A, TEXT = 0xD3DCE6, ACCENT = 0xFFD36B;
    private static final String HIGHLIGHT = "|";

    private ChronoItemTooltips() {}

    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!ChronomancyMod.MODID.equals(id.getNamespace())) {
            return;
        }
        String key = "item." + ChronomancyMod.MODID + "." + id.getPath();
        // Строки берём из языка как есть: I18n.get прогоняет текст через String.format, и любой
        // знак «%» в описании («50%») превращал строку в «Format error: …».
        Language language = Language.getInstance();
        List<Component> lines = new ArrayList<>();
        if (language.has(key + ".lore")) {
            Style lore = Style.EMPTY.withColor(loreColor(stack)).withItalic(true);
            for (String line : language.getOrDefault(key + ".lore").split("\n")) {
                lines.add(Component.literal(line).withStyle(lore));
            }
        }
        if (language.has(key + ".effect")) {
            for (String line : language.getOrDefault(key + ".effect").split("\n")) {
                lines.add(effect(line));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        List<Component> tooltip = event.getToolTip();
        tooltip.addAll(Math.min(1, tooltip.size()), lines); // сразу под названием
    }

    private static int loreColor(ItemStack stack) {
        // Базовая редкость предмета: зачарование её не «повышает», цвет легенды остаётся прежним.
        return switch (stack.getOrDefault(net.minecraft.core.component.DataComponents.RARITY,
                net.minecraft.world.item.Rarity.COMMON)) {
            case EPIC -> LORE_EPIC;
            case RARE -> LORE_RARE;
            case UNCOMMON -> LORE_UNCOMMON;
            default -> LORE_COMMON;
        };
    }

    /** «♦ текст |подсветка| текст»: нечётные куски между чертами красятся в золото. */
    private static Component effect(String line) {
        MutableComponent out = Component.literal("♦ ").withStyle(Style.EMPTY.withColor(MARK).withItalic(false));
        String[] parts = line.split(java.util.regex.Pattern.quote(HIGHLIGHT), -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            out.append(Component.literal(parts[i])
                    .withStyle(Style.EMPTY.withColor(i % 2 == 1 ? ACCENT : TEXT).withItalic(false)));
        }
        return out;
    }
}
