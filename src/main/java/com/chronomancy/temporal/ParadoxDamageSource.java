package com.chronomancy.temporal;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Урон временного парадокса. Обычный {@link SpellDamageSource} зоны (школа, сопротивления, сила
 * заклинаний считаются как прежде), но со своим сообщением о смерти: у самой Accelerated Zone теперь
 * есть собственный урон — за пересечение границы, и два этих случая в чате различаются.
 */
public final class ParadoxDamageSource extends SpellDamageSource {
    private static final String DEATH_KEY = "death.attack.chronomancy.time_paradox";
    private final Entity named;

    private ParadoxDamageSource(Entity direct, Entity causing, AbstractSpell spell) {
        super(direct, causing, null, spell);
        this.named = causing != null ? causing : direct;
    }

    /** Урон «от имени» владельца парадокса (в зачёт убийства идёт он). */
    public static ParadoxDamageSource of(Entity blast, Entity owner, AbstractSpell spell) {
        return new ParadoxDamageSource(blast, owner, spell);
    }

    /** Урон самой вспышки: по владельцу и его союзникам, которых ISS иначе счёл бы «своими». */
    public static ParadoxDamageSource of(Entity blast, AbstractSpell spell) {
        return new ParadoxDamageSource(blast, blast, spell);
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity victim) {
        return Component.translatable(DEATH_KEY, victim.getDisplayName(), named.getDisplayName());
    }
}
