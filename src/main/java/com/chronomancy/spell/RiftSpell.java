package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.rift.RiftEchoManager;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.AnimationHolder;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.List;

/**
 * RIFT — уникальное заклинание легендарного оружия Rift Creator (не выпадает свитками, не крафтится).
 *
 * <p>Нужна цель (наведение как у ISS-заклинаний на цель, до {@value #RANGE} блоков): рядом с ней
 * раскрывается разрыв, и через секунду из него выходит медная копия цели, нападающая на оригинал
 * (см. {@link RiftEchoManager}). Без подходящей цели каст не начинается — мана и перезарядка не
 * тратятся. Копия живёт {@link RiftEchoManager#ECHO_LIFETIME} тиков (10 с) + 1 с за каждые 10% силы заклинаний. Перезарядка 25 секунд.
 */
public class RiftSpell extends AbstractSpell {

    public static final int RANGE = 32;
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift");

    private final DefaultConfig config = new DefaultConfig()
            .setMinRarity(SpellRarity.LEGENDARY)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
            .setMaxLevel(1)
            .setCooldownSeconds(25)
            .setAllowCrafting(false)
            .build();

    public RiftSpell() {
        this.baseManaCost = 80;
        this.manaCostPerLevel = 0;
        this.castTime = 0;
    }

    @Override public ResourceLocation getSpellResource() { return ID; }
    @Override public DefaultConfig getDefaultConfig() { return config; }
    @Override public CastType getCastType() { return CastType.INSTANT; }
    @Override public boolean allowLooting() { return false; }
    @Override public boolean allowCrafting() { return false; }
    @Override public AnimationHolder getCastStartAnimation() { return SpellAnimations.ONE_HANDED_VERTICAL_UPSWING_ANIMATION; } // разрывает пространство снизу вверх
    @Override public Vector3f getTargetingColor() { return new Vector3f(0.35F, 0.65F, 1.0F); }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.rift.copy"),
                Component.translatable("spell.chronomancy.rift.duration", RiftEchoManager.lifetimeFor(caster) / 20 + "s"),
                Component.translatable("spell.chronomancy.rift.vanish"),
                Component.translatable("spell.chronomancy.rift.no_bosses"),
                Component.translatable("ui.irons_spellbooks.distance", RANGE));
    }

    /** Цель обязательна и должна быть копируемой (не босс). */
    @Override
    public boolean checkPreCastConditions(Level level, int spellLevel, LivingEntity entity, MagicData playerMagicData) {
        return Utils.preCastTargetHelper(level, entity, playerMagicData, this, RANGE, 0.35F, true,
                RiftEchoManager::canCopy);
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster, CastSource source, MagicData data) {
        if (level instanceof ServerLevel serverLevel
                && data.getAdditionalCastData() instanceof TargetEntityCastData targetData) {
            LivingEntity target = targetData.getTarget(serverLevel);
            if (target != null) {
                RiftEchoManager.openRift(serverLevel, caster, target);
            }
        }
        super.onCast(level, spellLevel, caster, source, data);
    }
}
