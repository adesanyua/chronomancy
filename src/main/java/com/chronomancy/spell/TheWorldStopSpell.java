package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * THE WORLD STOP — легендарное заклинание Chronomancy: абсолютная
 * глобальная остановка времени на 6 секунд (120 внешних серверных тиков).
 *
 * <p>Мир замирает целиком (сущности, BlockEntity, мир/погода/время — фазы
 * 2..5), единственное исключение — кастер: он может действовать, но его
 * биологическое время тоже заморожено (Фаза 4).
 *
 * <p>Весь геймплей живёт в {@link GlobalTimeStopManager}; здесь только
 * интеграция с Iron's Spells:
 * <ul>
 *   <li>singleton-отклонение повторного каста — на {@code SpellPreCastEvent}
 *       (WorldStopEvents), ДО списания маны;</li>
 *   <li>мана 1200 списывается ISS штатно на касте;</li>
 *   <li>кулдаун 180 s — НЕ на касте (отклоняется SpellCooldownAddedEvent.Pre),
 *       а полностью вешается менеджером при возобновлении времени (§20);</li>
 *   <li>duration не масштабируется: legendary, maxLevel 1, всегда 6 секунд,
 *       spell power ни на что не влияет.</li>
 * </ul>
 */
public class TheWorldStopSpell extends AbstractSpell {

    /** Пол строка id: {@code AbstractSpell#getSpellId()} == resource.toString(). */
    public static final String SPELL_ID = "chronomancy:the_world_stop";

    private static final ResourceLocation SPELL_RESOURCE =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "the_world_stop");

    // =========================================================
    // DURATION SCALING (от spell power: +0.2 s за каждые 10% сверх 100%)
    // =========================================================

    /** Базовая длительность при 100% spell power — 6 секунд (120 серверных тиков). */
    public static final double BASE_DURATION_SECONDS = 6.0;

    /** +0.2 секунды за каждые 10% spell power сверх базовых 100%. */
    public static final double SECONDS_PER_TEN_PCT_SPELL_POWER = 0.2;

    /**
     * Итоговая длительность стопа в тиках внешнего серверного времени.
     *
     * <p>Формула (по образцу {@code TemporalStasisBeamSpell#calculateStasisDurationSeconds}):
     * {@code 6 s + (max(0, spellPower - 1.0) / 0.1) * 0.2 s}. При 100% power = 120 тиков;
     * напр. 150% power → +5 * 0.2 = +1 s → 7 s = 140 тиков. Null-caster → база.
     */
    public static int computeDurationTicks(LivingEntity caster) {
        double seconds = BASE_DURATION_SECONDS;
        if (caster != null) {
            double spellPower = ChronoSchools.totalSpellPower(caster);
            double bonus = Math.max(0.0, spellPower - 1.0);
            seconds += (bonus / 0.1) * SECONDS_PER_TEN_PCT_SPELL_POWER;
        }
        return Math.max(1, (int) Math.round(seconds * 20.0));
    }

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    .setMinRarity(SpellRarity.LEGENDARY)
                    .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
                    .setMaxLevel(1)
                    .setCooldownSeconds(180)
                    .build();

    public TheWorldStopSpell() {
        this.baseManaCost = 1200;
        this.manaCostPerLevel = 0;
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;
        this.castTime = 40; // 2 с замаха: руки скрещены — и время замирает
    }

    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** Анимация каста: скрещённые руки во время замаха, затем руки в стороны. */
    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastStartAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.PREPARE_CROSS_ARMS;
    }

    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastFinishAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.CAST_T_POSE;
    }

    @Override
    public DefaultConfig getDefaultConfig() {
        return defaultConfig;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return SPELL_RESOURCE;
    }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.the_world_stop.duration",
                        (computeDurationTicks(caster) / 20) + "s"),
                Component.translatable("spell.chronomancy.the_world_stop.mana_cost", getManaCost(spellLevel)),
                Component.translatable("spell.chronomancy.the_world_stop.global"),
                Component.translatable("spell.chronomancy.the_world_stop.potions")
        );
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {

        if (!level.isClientSide && caster instanceof ServerPlayer player && player.isAlive()) {
            // Мана уже списана ISS в castSpell ДО onCast — синглтон-гейт стоит
            // на SpellPreCastEvent (до маны), поэтому сюда может прийти только
            // единственный валидный каст. false здесь практически недостижим;
            // если всё же — мир просто не останавливаем, кулдаун не вешаем
            // (_spell_ всё ещё без кулдауна — Pre-событие отменило add).
            int durationTicks = computeDurationTicks(caster);
            boolean started = GlobalTimeStopManager.tryStart(player, this, castSource, durationTicks);
            if (!started) {
                player.displayClientMessage(
                        Component.translatable("spell.chronomancy.the_world_stop.already_stopped"), true);
            }
        }
        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }
}
