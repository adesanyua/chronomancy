package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.client.particle.CopperSpellParticle;
import com.chronomancy.item.ChronoCurioEvents;
import com.chronomancy.item.TimelessBookCasting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * TIMELESS BOOK — медь на эффектах сжатых заклинаний.
 *
 * <p>Сущности непрерывного заклинания (струя дыхания, огненные шары Blaze Storm, кометы Starfall…),
 * выпущенные владельцем книги во время сжатого каста, помечаются «медными»:
 * <ul>
 *   <li>частицы, которые такая сущность порождает в свой тик, заворачиваются в
 *       {@link CopperSpellParticle} и рисуются шейдером {@code chronomancy:copper_spell_particle};</li>
 *   <li>сама сущность (если у неё есть модель) рисуется медным шейдером копий
 *       ({@link CopperEchoRendering}); геометрия без текстуры (молнии, лучи) перекрашивается в медь
 *       по цвету вершин;</li>
 *   <li>частицы заклинаний, которые присылает сервер (кольца ударов комет, туман Starfall, вспышки
 *       попаданий), приходят отдельным пакетом и ни к какой сущности не привязаны — их медными делает
 *       {@link #beginPacket}: по близости к игроку, который только что колдовал сжатый каст, или к
 *       медной сущности.</li>
 * </ul>
 * Всё это только картинка: на сервер ничего не уходит.
 */
public final class SpellCopperClient {
    private static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "copper_spell_particle");
    /** Медный tint — когда шейдер недоступен, и для геометрии без текстуры. */
    private static final float TINT_R = 0.86F, TINT_G = 0.53F, TINT_B = 0.28F;
    /** Сколько тиков жизни сущности ещё ждём, что кастер «проявится» как сжимающий каст. */
    private static final int DECIDE_TICKS = 10;

    /** Частицы Iron's Spells, присланные сервером, красятся в меди в этом радиусе от колдующего... */
    private static final double PACKET_CASTER_RANGE = 64.0D;
    /** ...и в этом — от уже медной сущности (комета, огненный шар). */
    private static final double PACKET_ENTITY_RANGE = 12.0D;
    /** Сколько тиков после сжатого каста его серверные частицы ещё считаются медными (кометы долетают). */
    private static final long BURST_MEMORY = 60L;
    private static final String SPELL_PARTICLES_NAMESPACE = "irons_spellbooks";

    /** id игрока -> игровое время, когда его в последний раз видели за сжатым кастом. */
    private static final Map<Integer, Long> BURST_SEEN = new HashMap<>();
    private static boolean packetScope;

    /** id сущности -> медная ли она (решение принимается один раз, см. {@link #isCopper}). */
    private static final Map<Integer, Boolean> DECIDED = new HashMap<>();
    @Nullable
    private static Entity scope;
    @Nullable
    private static ShaderInstance shader;
    private static boolean shaderTried;

    /** Слой частиц: общий атлас частиц, обычное смешивание, медный шейдер. */
    public static final ParticleRenderType SHEET = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            ShaderInstance copper = currentShader();
            RenderSystem.depthMask(true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (copper != null) {
                copper.safeGetUniform("PhaseTime").set((Util.getMillis() % 3_600_000L) / 1000.0F);
                RenderSystem.setShader(() -> copper);
            } else {
                RenderSystem.setShader(GameRenderer::getParticleShader);
            }
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "CHRONO_COPPER_SPELL";
        }
    };

    private SpellCopperClient() {
    }

    /** Подписка на клиентские события (игровая шина). */
    public static void register() {
        // LOWEST и без отменённых событий: если тик сущности отменили (стазис, остановка мира), Pre сюда
        // не дойдёт — а значит, не останется открытой «области», которую некому закрыть в Post.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, SpellCopperClient::onEntityTickPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SpellCopperClient::onEntityTickPost);
        NeoForge.EVENT_BUS.addListener(SpellCopperClient::onClientTick);
    }

    @Nullable
    private static ShaderInstance currentShader() {
        if (!shaderTried) {
            shaderTried = true;
            try {
                shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), SHADER,
                        DefaultVertexFormat.PARTICLE);
            } catch (Throwable t) {
                shader = null;
                ChronomancyMod.LOGGER.warn("Chronomancy copper_spell_particle shader не загрузился, частицы красятся tint'ом: {}",
                        t.getMessage());
            }
        }
        return shader;
    }

    // =========================================================
    // Какие сущности медные
    // =========================================================

    /**
     * Сущность заклинания, выпущенная владельцем Timeless Book во время сжатого непрерывного каста.
     * Решение принимается в первые тики жизни сущности и запоминается: огненный шар остаётся медным
     * и после того, как сам каст закончился.
     */
    public static boolean isCopper(@Nullable Entity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }
        Boolean known = DECIDED.get(entity.getId());
        if (known != null) {
            return known;
        }
        // только снаряды и области заклинаний (у Iron's Spells это всё Projectile), не выброшенные предметы
        boolean copper = entity instanceof Projectile projectile
                && projectile.getOwner() instanceof Player owner && burstCasting(owner);
        if (copper || entity.tickCount > DECIDE_TICKS) {
            DECIDED.put(entity.getId(), copper);
        }
        return copper;
    }

    /** Игрок носит книгу и прямо сейчас колдует заклинание, которое она сжимает. */
    private static boolean burstCasting(Player player) {
        String spellId;
        if (player == Minecraft.getInstance().player) {
            if (!ClientMagicData.isCasting()) {
                return false;
            }
            spellId = ClientMagicData.getCastingSpellId();
        } else {
            SyncedSpellData data = ClientMagicData.getSyncedSpellData(player);
            if (data == null || !data.isCasting()) {
                return false;
            }
            spellId = data.getCastingSpellId();
        }
        return spellId != null && !spellId.isEmpty()
                && TimelessBookCasting.accelerates(SpellRegistry.getSpell(spellId))
                && ChronoCurioEvents.wearsTimelessBook(player);
    }

    // =========================================================
    // Частицы: «область» тика медной сущности
    // =========================================================

    private static void onEntityTickPre(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide && isCopper(entity)) {
            scope = entity;
        }
    }

    private static void onEntityTickPost(EntityTickEvent.Post event) {
        if (scope == event.getEntity()) {
            scope = null;
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        scope = null; // страховка: область никогда не переживает клиентский тик
        packetScope = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            DECIDED.clear();
            BURST_SEEN.clear();
            return;
        }
        long now = mc.level.getGameTime();
        for (Player player : mc.level.players()) {
            if (burstCasting(player)) {
                BURST_SEEN.put(player.getId(), now);
            }
        }
        if (!BURST_SEEN.isEmpty()) {
            BURST_SEEN.values().removeIf(seen -> now - seen > BURST_MEMORY || seen > now);
        }
        if (!DECIDED.isEmpty() && now % 100L == 0L) {
            DECIDED.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        }
    }

    public static void clear() {
        DECIDED.clear();
        BURST_SEEN.clear();
        scope = null;
        packetScope = false;
    }

    // =========================================================
    // Частицы, присланные сервером
    // =========================================================

    /**
     * Сервер прислал пакет частиц. Если это частицы Iron's Spells рядом с игроком, который колдует
     * (или только что колдовал) сжатый каст, либо рядом с медной сущностью — все частицы, созданные
     * при обработке этого пакета, станут медными. Закрывается в {@link #endPacket}.
     */
    public static void beginPacket(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z) {
        packetScope = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || options == null || (BURST_SEEN.isEmpty() && DECIDED.isEmpty())) {
            return;
        }
        ResourceLocation type = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        if (type == null || !SPELL_PARTICLES_NAMESPACE.equals(type.getNamespace())) {
            return;
        }
        for (int playerId : BURST_SEEN.keySet()) {
            Entity caster = mc.level.getEntity(playerId);
            if (caster != null && caster.distanceToSqr(x, y, z) <= PACKET_CASTER_RANGE * PACKET_CASTER_RANGE) {
                packetScope = true;
                return;
            }
        }
        for (Map.Entry<Integer, Boolean> entry : DECIDED.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity != null && entity.distanceToSqr(x, y, z) <= PACKET_ENTITY_RANGE * PACKET_ENTITY_RANGE) {
                packetScope = true;
                return;
            }
        }
    }

    public static void endPacket() {
        packetScope = false;
    }

    /**
     * Частица, только что созданная в мире. Если её породил тик медной сущности или «медный» пакет
     * частиц с сервера — заворачиваем в медь.
     * Трогаем только частицы обычных текстурных слоёв: у особых слоёв свой формат вершин.
     */
    public static Particle wrapParticle(Particle particle) {
        if ((scope == null && !packetScope) || particle == null || particle instanceof CopperSpellParticle) {
            return particle;
        }
        // свои частицы школы (песок Sands of Time и т.п.) умеют быть медными сами — их слой уже медный
        if (particle instanceof com.chronomancy.client.particle.PhaseParticle phase) {
            phase.makeCopper();
            return particle;
        }
        ParticleRenderType type = particle.getRenderType();
        if (type != ParticleRenderType.PARTICLE_SHEET_OPAQUE && type != ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                && type != ParticleRenderType.PARTICLE_SHEET_LIT) {
            return particle;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return particle;
        }
        if (currentShader() == null) {
            particle.setColor(TINT_R, TINT_G, TINT_B);
            return particle;
        }
        return new CopperSpellParticle(mc.level, particle);
    }

    // =========================================================
    // Модель сущности
    // =========================================================

    /**
     * Буфер для отрисовки медной сущности заклинания: текстурные entity-слои — медным шейдером копий,
     * остальное с цветом вершин (молнии, лучи, свечение) — медью по яркости этого цвета.
     */
    public static MultiBufferSource wrap(MultiBufferSource delegate) {
        if (delegate instanceof CopperSpellSource) {
            return delegate;
        }
        return new CopperSpellSource(delegate, CopperEchoRendering.wrap(delegate));
    }

    private record CopperSpellSource(MultiBufferSource delegate, MultiBufferSource entityCopper) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (type.format() == DefaultVertexFormat.NEW_ENTITY && CopperEchoRendering.available()) {
                return entityCopper.getBuffer(type);
            }
            if (!type.format().contains(VertexFormatElement.COLOR)) {
                return delegate.getBuffer(type);
            }
            return new CopperTint(delegate, type);
        }
    }

    /**
     * Перекрашивает цвет вершин в медь по его яркости. Буфер перезапрашивается на каждой вершине —
     * так же, как в {@code VanillaPhase.DematConsumer}: обёртка не должна держать у себя строитель,
     * который источник успел завершить.
     */
    private static final class CopperTint implements VertexConsumer {
        private final MultiBufferSource source;
        private final RenderType type;
        private VertexConsumer delegate;

        CopperTint(MultiBufferSource source, RenderType type) {
            this.source = source;
            this.type = type;
            this.delegate = source.getBuffer(type);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate = source.getBuffer(type);
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            float lum = (0.299F * r + 0.587F * g + 0.114F * b) / 255.0F;
            float k = Math.min(1.0F, 0.35F + 0.9F * lum); // тёмное остаётся тёмной медью, яркое — светлой
            float shine = Math.max(0.0F, lum - 0.75F) * 1.6F; // самые яркие вершины уходят в светлый блик
            delegate.setColor(
                    Math.min(255, Math.round(255.0F * (TINT_R * k + (1.0F - TINT_R) * shine))),
                    Math.min(255, Math.round(255.0F * (TINT_G * k + (1.0F - TINT_G) * shine))),
                    Math.min(255, Math.round(255.0F * (TINT_B * k + (1.0F - TINT_B) * shine))),
                    a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
