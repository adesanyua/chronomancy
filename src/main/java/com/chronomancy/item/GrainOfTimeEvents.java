package com.chronomancy.item;

import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Добыча Зерна времени без магии (её ещё нечем колдовать — фокус школы как раз Зерно):
 * <ul>
 *   <li><b>Наковальня.</b> Падающая наковальня (хотя бы с {@value #MIN_FALL} блоков), накрывшая
 *       брошенные часы ({@code minecraft:clock}), раздавливает одни часы — даже если лежит целая
 *       стопка, наковальня считает её за одни часы. Шанс 1 к {@value #ANVIL_CHANCE} на зерно, иначе 1–3
 *       золотых самородка;</li>
 *   <li><b>Молния.</b> Молния, ударившая в брошенные часы (гроза или громоотвод), переплавляет одни
 *       часы: шанс 1 к {@value #LIGHTNING_CHANCE} на зерно, иначе 1–3 самородка. Остальные часы стопки
 *       не сгорают;</li>
 *   <li><b>Археология</b> — подозрительный песок пустынных храмов, колодцев и древних руин
 *       (глобальный модификатор добычи, см. {@code ArchaeologyGrainModifier}).</li>
 * </ul>
 */
public final class GrainOfTimeEvents {
    /** Наковальня: одно Зерно времени в среднем на столько раздавленных часов. */
    public static final int ANVIL_CHANCE = 64;
    /** Молния: одно Зерно времени в среднем на столько переплавленных часов. */
    public static final int LIGHTNING_CHANCE = 8;
    /** Минимальная высота падения наковальни, блоков. */
    public static final float MIN_FALL = 2.0F;

    /** Какие стопки часов эта молния уже переплавила (молния бьёт несколько тиков подряд). */
    private static final java.util.Map<net.minecraft.world.entity.LightningBolt, java.util.Set<java.util.UUID>> STRUCK =
            new java.util.WeakHashMap<>();

    private GrainOfTimeEvents() {}

    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof FallingBlockEntity anvil)
                || !(anvil.level() instanceof ServerLevel level)
                || !anvil.getBlockState().is(BlockTags.ANVIL)) {
            return;
        }
        Vec3 motion = anvil.getDeltaMovement();
        if (motion.y >= -0.05 || anvil.fallDistance < MIN_FALL
                || anvil.getPersistentData().getBoolean("chronomancy.crushed")) {
            return;
        }
        // текущее положение + куда наковальня опустится за этот тик: часы на полу ловятся до приземления
        AABB crush = anvil.getBoundingBox().expandTowards(0, motion.y - 0.1, 0).inflate(0.05, 0, 0.05);
        // одна наковальня — одни часы, сколько бы их ни лежало под ней
        level.getEntitiesOfClass(ItemEntity.class, crush, e -> e.isAlive() && e.getItem().is(Items.CLOCK))
                .stream().findFirst()
                .ifPresent(item -> {
                    breakOneClock(level, item, ANVIL_CHANCE, false);
                    anvil.getPersistentData().putBoolean("chronomancy.crushed", true);
                });
    }

    /** Молния по брошенным часам: переплавляет одни часы из стопки, остальные не горят. */
    public static void onLightning(net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item) || !item.getItem().is(Items.CLOCK)
                || !(item.level() instanceof ServerLevel level)) {
            return;
        }
        event.setCanceled(true); // часы не сгорают от самой молнии
        if (STRUCK.computeIfAbsent(event.getLightning(), b -> new java.util.HashSet<>()).add(item.getUUID())) {
            breakOneClock(level, item, LIGHTNING_CHANCE, true);
        }
    }

    /** Ломает одни часы из стопки: зерно с шансом 1 к {@code chance}, иначе 1–3 самородка. */
    private static void breakOneClock(ServerLevel level, ItemEntity item, int chance, boolean lightning) {
        Vec3 pos = item.position();
        ItemStack stack = item.getItem().copy();
        stack.shrink(1);
        if (stack.isEmpty()) {
            item.discard();
        } else {
            item.setItem(stack);
            if (lightning) {
                // остаток стопки «застыл во времени»: огонь от молнии его не сжигает
                item.setInvulnerable(true);
                item.clearFire();
            }
        }
        boolean grain = level.getRandom().nextInt(chance) == 0;
        if (grain) {
            spawn(level, pos, new ItemStack(ChronoItemRegistry.GRAIN_OF_TIME.get()), lightning);
            level.sendParticles(ChronoParticleRegistry.TEMPORAL_RUNE.get(), pos.x, pos.y + 0.6, pos.z, 1, 0, 0, 0, 0);
            level.sendParticles(ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(), pos.x, pos.y + 0.4, pos.z,
                    16, 0.3, 0.2, 0.3, 0.02);
            level.playSound(null, pos.x, pos.y, pos.z, ChronoSounds.TEMPORAL_RELEASE.get(), SoundSource.BLOCKS, 1.0F, 1.2F);
        } else {
            spawn(level, pos, new ItemStack(Items.GOLD_NUGGET, 1 + level.getRandom().nextInt(3)), lightning);
        }
        level.playSound(null, pos.x, pos.y, pos.z, lightning ? SoundEvents.FIRE_EXTINGUISH : SoundEvents.ITEM_BREAK,
                SoundSource.BLOCKS, 0.8F, lightning ? 1.2F : 0.8F);
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), pos.x, pos.y + 0.3, pos.z, 5, 0.2, 0.1, 0.2, 0.05);
    }

    /** Выбросить добычу вверх и в сторону (обычными стопками, не больше максимума). */
    private static void spawn(ServerLevel level, Vec3 pos, ItemStack stack, boolean fireproof) {
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(stack.getMaxStackSize());
            ItemEntity drop = new ItemEntity(level, pos.x, pos.y + 0.3, pos.z, part);
            double a = level.getRandom().nextDouble() * Math.PI * 2.0;
            drop.setDeltaMovement(Math.cos(a) * 0.18, 0.3, Math.sin(a) * 0.18);
            drop.setDefaultPickUpDelay();
            if (fireproof) {
                drop.setInvulnerable(true); // добыча не сгорает в огне, который оставила молния
            }
            level.addFreshEntity(drop);
        }
    }
}
