package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.temporal.SandsOfTime;
import io.redspace.ironsspellbooks.entity.spells.AbstractConeProjectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Струя песка заклинания Sands of Time — невидимый конус перед кастером, как конусы дыханий
 * Iron's Spells: сам он только сыплет частицы и раз в импульс задевает всех, кто внутри.
 * Что происходит с задетым, решает {@link SandsOfTime#pulse}.
 */
public class SandsOfTimeProjectile extends AbstractConeProjectile {

    /** Плотность струи: песчинок за тик (у конусов Iron's Spells — 10–15). */
    private static final int PARTICLES_PER_TICK = 30;

    public SandsOfTimeProjectile(EntityType<? extends AbstractConeProjectile> type, Level level) {
        super(type, level);
    }

    public SandsOfTimeProjectile(Level level, LivingEntity caster) {
        super(ChronoEntityTypeRegistry.SANDS_OF_TIME_CONE.get(), level, caster);
    }

    /** Песок летит от лица кастера расходящимся конусом: в основном песчинки, изредка золотая искра. */
    @Override
    public void spawnParticles() {
        Entity owner = getOwner();
        if (!level().isClientSide || owner == null) {
            return;
        }
        Vec3 look = owner.getLookAngle().normalize();
        Vec3 mouth = owner.position().add(look.scale(1.5D));
        double x = mouth.x;
        double y = mouth.y + owner.getEyeHeight() * 0.9F;
        double z = mouth.z;
        for (int i = 0; i < PARTICLES_PER_TICK; i++) {
            double speed = random.nextDouble() * 0.6D + 0.15D;
            double jitter = 0.2D;
            Vec3 spread = new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1)
                    .normalize().scale(0.8D);
            Vec3 motion = look.scale(3.0D).add(spread).normalize().scale(speed);
            level().addParticle(
                    random.nextFloat() < 0.8F ? ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get()
                            : ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    x + (random.nextDouble() * 2 - 1) * jitter,
                    y + (random.nextDouble() * 2 - 1) * jitter,
                    z + (random.nextDouble() * 2 - 1) * jitter,
                    motion.x, motion.y, motion.z);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (hit.getEntity() instanceof LivingEntity target && getOwner() instanceof LivingEntity caster) {
            SandsOfTime.pulse(caster, target, damage,
                    ChronoSpellRegistry.SANDS_OF_TIME_SPELL.getDamageSource(this, caster));
        }
    }
}
