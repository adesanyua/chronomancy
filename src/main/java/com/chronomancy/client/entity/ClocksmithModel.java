package com.chronomancy.client.entity;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMob;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMobModel;
import net.minecraft.resources.ResourceLocation;

/** Часовщик: гуманоидная модель и анимации магов Iron's Spells, своя только текстура. */
public class ClocksmithModel extends AbstractSpellCastingMobModel {
    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "textures/entity/clocksmith.png");

    @Override
    public ResourceLocation getTextureResource(AbstractSpellCastingMob mob) {
        return TEXTURE;
    }
}
