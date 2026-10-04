package com.chronomancy.temporal;

import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.entity.TimeParadoxEntity;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.registry.ChronoSpellRegistry;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * ВРЕМЕННОЙ ПАРАДОКС — Accelerated Zone и Time Dilation Field сошлись в одной точке.
 *
 * <p>Условие: центр одной области лежит внутри другой (брошены «в одну точку»; простое касание
 * краями парадокса не даёт — там темпы просто перемножаются). Обе области исчезают, а между их
 * центрами происходит взрыв времени:
 * <ul>
 *   <li>радиус — сумма радиусов поля и зоны;</li>
 *   <li>урон — сумма процентов замедления и ускорения (поле 40% + зона 40% = 80 урона), магический.
 *       Его получают ВСЕ в радиусе — и тот, кто устроил парадокс, и его союзники. Убийства
 *       засчитываются тому, кто завершил парадокс (чья область появилась позже; в паре «игрок и моб» —
 *       игроку);</li>
 *   <li>выжившие получают эффект парадокса — их время то несётся, то вязнет ({@link TimeParadox}).
 *       Длится он тем дольше, чем сильнее был взрыв: четверть урона в секундах (80 урона — 20 с,
 *       160 — 40 с). Хрономальных мобов (Chronomaly, фазирующие мобы разлома, Rift Maker и его
 *       двойники) эффект не берёт, но урон они получают.</li>
 * </ul>
 * Блоки не разрушаются: это не взрыв вещества.
 */
public final class TimeParadoxBlast {
    /** Тиков эффекта парадокса на единицу урона взрыва: урон / 4 секунд = урон × 5 тиков. */
    public static final int PARADOX_TICKS_PER_DAMAGE = 5;

    /** Сколько длится эффект парадокса после взрыва с таким уроном (не меньше секунды; доля — в конфиге). */
    public static int paradoxTicks(float damage) {
        return Math.max(20, (int) Math.round(damage * com.chronomancy.ChronoConfig.paradoxSecondsPerDamage() * 20.0D));
    }
    private static final double SEARCH = 24.0D;

    private TimeParadoxBlast() {
    }

    /**
     * Область ищет свою противоположность, с которой соприкоснулась — хватает и краёв. Вызывается из тика и зоны,
     * и поля: во время остановки мира тикает только область существа вне времени (поле Rift Maker),
     * и парадокс с застывшей зоной игрока должна найти она. Первая же находка убирает обе области,
     * так что пара срабатывает один раз.
     */
    public static void check(ServerLevel level, TimeDilationFieldEntity self) {
        if (self.isRemoved()) {
            return;
        }
        boolean wantZone = !self.isAccelerating();
        for (TimeDilationFieldEntity other : level.getEntitiesOfClass(TimeDilationFieldEntity.class,
                self.getBoundingBox().inflate(SEARCH),
                f -> f != self && !f.isRemoved() && f.isAccelerating() == wantZone)) {
            if (self.touches(other)) {
                if (wantZone) {
                    detonate(level, other, self);
                } else {
                    detonate(level, self, other);
                }
                return;
            }
        }
    }

    private static LivingEntity owner(ServerLevel level, TimeDilationFieldEntity area) {
        UUID id = area.getChronoOwnerUUID().orElse(null);
        Entity entity = id == null ? null : level.getEntity(id);
        return entity instanceof LivingEntity living ? living : null;
    }

    /** Урон парадокса: проценты замедления поля плюс проценты ускорения зоны. */
    public static float damage(TimeDilationFieldEntity zone, TimeDilationFieldEntity field) {
        double slowPercent = (TemporalRate.NORMAL - field.getTemporalRate()) * 100.0D;
        double fastPercent = (zone.getTemporalRate() - TemporalRate.NORMAL) * 100.0D;
        return (float) (Math.max(0.0D, slowPercent + fastPercent) * com.chronomancy.ChronoConfig.paradoxDamageMultiplier());
    }

    public static void detonate(ServerLevel level, TimeDilationFieldEntity zone, TimeDilationFieldEntity field) {
        Vec3 center = zone.position().add(field.position()).scale(0.5D);
        double radius = zone.getRadius() + field.getRadius();
        float damage = damage(zone, field);

        // Урон получают все, но убийства надо кому-то засчитать: парадокс «принадлежит» тому, кто его
        // завершил (чья область моложе); в паре «игрок и моб» — игроку, чтобы добыча и опыт не пропали.
        TimeDilationFieldEntity later = zone.tickCount <= field.tickCount ? zone : field;
        TimeDilationFieldEntity earlier = later == zone ? field : zone;
        LivingEntity laterOwner = owner(level, later);
        LivingEntity earlierOwner = owner(level, earlier);
        LivingEntity owner = laterOwner == null ? earlierOwner
                : (!(laterOwner instanceof Player) && earlierOwner instanceof Player) ? earlierOwner : laterOwner;

        zone.discard();
        field.discard();

        TimeParadoxEntity blast = ChronoEntityTypeRegistry.TIME_PARADOX.get().create(level);
        if (blast != null) {
            blast.setPos(center.x, center.y, center.z);
            blast.configure((float) radius);
            if (owner != null) {
                // имя виновника — для сообщения о смерти тех, кого ударило «своим» парадоксом
                blast.setCustomName(owner.getName());
            }
            level.addFreshEntity(blast);
        }
        level.playSound(null, center.x, center.y, center.z, ChronoSounds.NEEDLE_COLLAPSE.get(),
                SoundSource.PLAYERS, 3.0F, 0.55F);
        level.playSound(null, center.x, center.y, center.z, ChronoSounds.TEMPORAL_CRACK.get(),
                SoundSource.PLAYERS, 3.0F, 0.7F);
        level.playSound(null, center.x, center.y, center.z, ChronoSounds.TEMPORAL_RELEASE.get(),
                SoundSource.PLAYERS, 2.5F, 1.5F);

        com.chronomancy.advancement.ChronoAdvancements.grant(owner,
                com.chronomancy.advancement.ChronoAdvancements.PARADOX);
        DamageSource source = owner != null
                ? ParadoxDamageSource.of(blast != null ? blast : owner, owner,
                        ChronoSpellRegistry.ACCELERATED_ZONE_SPELL)
                : level.damageSources().magic();
        // Парадокс не щадит никого. Но урон «от имени владельца» по нему самому и по его союзникам
        // Iron's Spells отбросил бы как огонь по своим — им наносит урон сама вспышка.
        DamageSource ownSideSource = owner != null && blast != null
                ? ParadoxDamageSource.of(blast, ChronoSpellRegistry.ACCELERATED_ZONE_SPELL)
                : level.damageSources().magic();
        AABB box = new AABB(center, center).inflate(radius + 2.0D);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (victim.isSpectator() || (victim instanceof Player player && player.isCreative())) {
                continue;
            }
            boolean ownSide = victim == owner
                    || (owner != null && DamageSources.isFriendlyFireBetween(owner, victim));
            // расстояние до ближайшей точки тела: крупного моба задевает и краем
            AABB body = victim.getBoundingBox();
            double dx = Math.max(0.0D, Math.max(body.minX - center.x, center.x - body.maxX));
            double dy = Math.max(0.0D, Math.max(body.minY - center.y, center.y - body.maxY));
            double dz = Math.max(0.0D, Math.max(body.minZ - center.z, center.z - body.maxZ));
            if (dx * dx + dy * dy + dz * dz > radius * radius) {
                continue;
            }
            if (damage > 0.0F) {
                victim.invulnerableTime = 0;
                DamageSources.applyDamage(victim, damage, ownSide ? ownSideSource : source);
            }
            if (!victim.isAlive()) {
                continue;
            }
            // лёгкий толчок от центра — волна времени, а не взрывная
            Vec3 away = victim.position().subtract(center);
            double flat = Math.sqrt(away.x * away.x + away.z * away.z);
            if (flat > 1.0E-3) {
                double resist = victim.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
                double push = 0.55D * Math.max(0.0D, 1.0D - resist);
                victim.push(away.x / flat * push, 0.22D * (push / 0.55D), away.z / flat * push);
                victim.hurtMarked = true;
            }
            if (TimeMagicImmunity.isImmune(victim) || com.chronomancy.entity.ChronomalMob.is(victim)) {
                continue; // хрономальные мобы вне времени: парадокс ранит, но темп не расшатывает
            }
            TimeParadox.afflict(victim, (int) TimeMagicResist.scaleTicks(victim, paradoxTicks(damage)));
        }
    }
}
