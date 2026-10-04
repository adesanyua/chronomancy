package com.chronomancy.client.particle;

import com.chronomancy.client.SpellCopperClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleGroup;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Обёртка над чужой частицей (огонь, снег, яд — что угодно из общего атласа частиц): частица живёт и
 * рисует себя сама, как раньше, но попадает в медный слой {@link SpellCopperClient#SHEET}, и его
 * шейдер перекрашивает её в медь. Так красятся эффекты заклинаний, сжатых Timeless Book.
 *
 * <p>Конструктор {@link Particle} сам вызывает {@code getBoundingBox()} (через {@code setSize}) —
 * ещё до того, как здесь присвоено поле {@code inner}. Поэтому каждый делегирующий метод сначала
 * проверяет, что обёрнутая частица уже есть, и иначе отвечает как обычная частица.
 */
public final class CopperSpellParticle extends Particle {
    private final Particle inner;

    public CopperSpellParticle(ClientLevel level, Particle inner) {
        super(level, 0.0D, 0.0D, 0.0D);
        this.inner = inner;
    }

    @Override
    public void tick() {
        inner.tick();
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        inner.render(buffer, camera, partialTicks);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return SpellCopperClient.SHEET;
    }

    @Override
    public boolean isAlive() {
        return inner != null ? inner.isAlive() : super.isAlive();
    }

    @Override
    public void remove() {
        if (inner != null) {
            inner.remove();
        } else {
            super.remove();
        }
    }

    @Override
    public int getLifetime() {
        return inner != null ? inner.getLifetime() : super.getLifetime();
    }

    @Override
    public Optional<ParticleGroup> getParticleGroup() {
        return inner != null ? inner.getParticleGroup() : super.getParticleGroup();
    }

    @Override
    public AABB getBoundingBox() {
        return inner != null ? inner.getBoundingBox() : super.getBoundingBox();
    }

    @Override
    public AABB getRenderBoundingBox(float partialTicks) {
        return inner != null ? inner.getRenderBoundingBox(partialTicks) : super.getRenderBoundingBox(partialTicks);
    }

    @Override
    public Vec3 getPos() {
        return inner != null ? inner.getPos() : super.getPos();
    }
}
