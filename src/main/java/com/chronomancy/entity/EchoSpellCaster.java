package com.chronomancy.entity;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Каст чужого заклинания мобом, который не является магом Iron's Spells: медный двойник повторяет
 * последнее заклинание игрока. Ведёт заклинание теми же шагами, что и маги ISS — проверка условий,
 * время каста, {@code onCast} (у непрерывных — импульс раз в полсекунды), завершение, — но на
 * собственном {@link MagicData}: ни маны, ни перезарядок игрока это не касается.
 *
 * <p>Чужие заклинания написаны под игроков и магов ISS; если какое-то из них не переносит такого
 * кастера, ошибка ловится, каст бросается, и двойник дальше дерётся врукопашную ({@link #broken}).
 */
final class EchoSpellCaster {
    /** Мгновенные заклинания двойник всё же «замахивает» — у игрока есть время среагировать. */
    private static final int INSTANT_WINDUP = 10;
    private static final int MAX_CAST_TICKS = 100;
    private static final int PULSE_INTERVAL = 10;
    private static final double CAST_RANGE = 24.0D;

    private final Mob mob;
    private final AbstractSpell spell;
    private final int spellLevel;
    private final MagicData magic = new MagicData(true);
    private int cooldown = 30;
    private int remaining = -1;
    private int elapsed;
    private boolean broken;

    EchoSpellCaster(Mob mob, AbstractSpell spell, int spellLevel) {
        this.mob = mob;
        this.spell = spell;
        this.spellLevel = spellLevel;
        // как у магов ISS: без этих данных initiateCast падает; для не-мага синхронизация — пустышка
        this.magic.setSyncedData(new io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData(mob));
    }

    boolean broken() {
        return broken;
    }

    boolean casting() {
        return remaining >= 0;
    }

    /** Желаемая дистанция до цели: двойник-маг держится поодаль. */
    double preferredRange() {
        return 9.0D;
    }

    /** @return {@code true}, если в этом тике двойник занят кастом (стоит и целится). */
    boolean tick(LivingEntity target, boolean canSee) {
        if (broken) {
            return false;
        }
        try {
            if (remaining < 0) {
                if (cooldown > 0) {
                    cooldown--;
                }
                if (cooldown > 0 || !canSee || mob.distanceToSqr(target) > CAST_RANGE * CAST_RANGE) {
                    return false;
                }
                return begin(target);
            }
            aim(target);
            Level level = mob.level();
            magic.handleCastDuration();
            if (spell.getCastType() == CastType.CONTINUOUS && elapsed % PULSE_INTERVAL == 0) {
                spell.onCast(level, spellLevel, mob, CastSource.MOB, magic);
            }
            spell.onServerCastTick(level, spellLevel, mob, magic);
            elapsed++;
            if (--remaining <= 0) {
                if (spell.getCastType() != CastType.CONTINUOUS) {
                    mob.swing(InteractionHand.MAIN_HAND);
                    spell.onCast(level, spellLevel, mob, CastSource.MOB, magic);
                }
                finish(false);
            }
            return true;
        } catch (Throwable t) {
            ChronomancyMod.LOGGER.warn("Chronomancy: двойник не смог повторить заклинание {} — дальше дерётся врукопашную: {}",
                    spell.getSpellId(), t.toString());
            broken = true;
            abort();
            return false;
        }
    }

    private boolean begin(LivingEntity target) {
        aim(target);
        Level level = mob.level();
        if (!spell.checkPreCastConditions(level, spellLevel, mob, magic)) {
            cooldown = 20;
            return false;
        }
        int castTime = spell.getCastType() == CastType.INSTANT ? INSTANT_WINDUP
                : Mth.clamp(spell.getEffectiveCastTime(spellLevel, mob), INSTANT_WINDUP, MAX_CAST_TICKS);
        magic.initiateCast(spell, spellLevel, castTime, CastSource.MOB, "mainhand");
        spell.onServerPreCast(level, spellLevel, mob, magic);
        remaining = castTime;
        elapsed = 0;
        mob.getNavigation().stop();
        level.playSound(null, mob.getX(), mob.getY() + 1, mob.getZ(),
                com.chronomancy.registry.ChronoSounds.CLOCK_TICK.get(), SoundSource.HOSTILE, 1.0F, 1.4F);
        return true;
    }

    private void finish(boolean cancelled) {
        Level level = mob.level();
        spell.onServerCastComplete(level, spellLevel, mob, magic, cancelled);
        magic.resetCastingState();
        remaining = -1;
        // пауза между кастами: не чаще раза в 3 секунды и не реже раза в 8
        cooldown = Mth.clamp(spell.getSpellCooldown() / 2, 60, 160);
    }

    /** Двойник рассыпался или сломался посреди каста — убираем за заклинанием (струю, цель и т.п.). */
    void abort() {
        if (remaining >= 0) {
            try {
                finish(true);
            } catch (Throwable ignored) {
                magic.resetCastingState();
                remaining = -1;
            }
        }
    }

    /** Взгляд точно на цель: заклинания летят туда, куда смотрит кастер. */
    private void aim(LivingEntity target) {
        Vec3 from = mob.getEyePosition();
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.6D, 0);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        mob.setYRot(yaw);
        mob.setXRot(pitch);
        mob.yHeadRot = yaw;
        mob.yBodyRot = yaw;
        mob.getLookControl().setLookAt(target, 60.0F, 60.0F);
    }
}
