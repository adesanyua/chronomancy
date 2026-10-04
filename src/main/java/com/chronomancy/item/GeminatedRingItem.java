package com.chronomancy.item;

import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Curio ring that carries a single spell in an ISS {@link ISpellContainer} slot, exactly like the
 * school's imbued gear: the spell is imprinted at the Arcane Anvil and, because the container is a
 * <em>spell wheel</em>, it shows up in the castable spell bar while worn.
 *
 * <p>Gimmick (see {@link GeminatedRingEvents}): the stored spell always resolves <b>twice</b> but
 * charges <b>triple</b> mana.
 */
public class GeminatedRingItem extends CurioBaseItem<GeminatedRingItem> {

    public GeminatedRingItem(Properties properties) {
        super(properties, "ring");
    }

    /** The spell currently imprinted in the ring, or {@code null} when the slot is empty. */
    public static SpellData storedSpell(ItemStack stack) {
        if (!ISpellContainer.isSpellContainer(stack)) return null;
        SpellData data = ISpellContainer.get(stack).getSpellAtIndex(0);
        return data == null || data.getSpell() == null ? null : data;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        SpellData stored = storedSpell(stack);
        if (stored != null) {
            tooltip.add(Component.translatable("item.chronomancy.geminated_ring.stored", stored.getDisplayName())
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("item.chronomancy.geminated_ring.empty").withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public List<Component> getAttributesTooltip(List<Component> tooltips, TooltipContext context, ItemStack stack) {
        if (tooltips.isEmpty()) {
            tooltips.add(Component.empty());
            tooltips.add(Component.translatable("curios.modifiers.ring").withStyle(ChatFormatting.GOLD));
        }
        return tooltips;
    }
}
