package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.item.ChronoUtilityItem;
import com.chronomancy.item.ClocksmithArmorItem;
import com.chronomancy.item.ClockHandItem;
import com.chronomancy.item.TimeManagementSpellBook;
import com.chronomancy.item.GeminatedRingItem;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.item.UpgradeOrbItem;
import io.redspace.ironsspellbooks.item.armor.UpgradeOrbType;
import io.redspace.ironsspellbooks.registries.ComponentRegistry;
import io.redspace.ironsspellbooks.registries.UpgradeOrbTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoItemRegistry {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ChronomancyMod.MODID);

    /**
     * Geminated Ring — a Curios ring with a single ISS spell slot (imprinted at the Arcane Anvil).
     * Its container is a <em>spell wheel</em> (create(maxSpells, spellWheel, mustEquip)) so the stored
     * spell surfaces in the castable spell bar while worn; {@code GeminatedRingEvents} then casts it
     * twice for triple mana.
     */
    public static final DeferredHolder<Item, GeminatedRingItem> GEMINATED_RING = ITEMS.registerItem(
            "geminated_ring",
            (properties) -> new GeminatedRingItem(
                    properties.stacksTo(1).rarity(Rarity.RARE)
                            .component(ComponentRegistry.SPELL_CONTAINER, ISpellContainer.create(1, true, true))));

    public static final DeferredHolder<Item, ChronoUtilityItem> DEFERRED_PENDULUM = curio("deferred_pendulum", "necklace");
    public static final DeferredHolder<Item, ChronoUtilityItem> SECOND_CHANCE_WATCH = curio("second_chance_watch", "charm");
    public static final DeferredHolder<Item, ChronoUtilityItem> CRACKED_DIAL = curio("cracked_dial", "ring");
    public static final DeferredHolder<Item, ChronoUtilityItem> HOURGLASS_OF_FOCUS = curio("hourglass_of_focus", "charm");
    public static final DeferredHolder<Item, ChronoUtilityItem> TEMPORAL_ANCHOR = curio("temporal_anchor", "necklace");
    public static final DeferredHolder<Item, ChronoUtilityItem> CLOCKWORK_KEY = curio("clockwork_key", "belt");

    /**
     * Ring of Rifts — кольцо с синим кристаллом хрономали. +5% к восстановлению заклинаний и к силе
     * хрономантии, удары владельца игнорируют кадры неуязвимости. Изредка продаёт Часовщик;
     * гарантированно падает с него. Разломы оно больше не открывает — это делает {@link #RIFT_HEART},
     * которое из кольца и собирают.
     */
    public static final DeferredHolder<Item, ChronoUtilityItem> RING_OF_RIFTS = ITEMS.registerItem("ring_of_rifts",
            props -> new ChronoUtilityItem(props.stacksTo(1).rarity(Rarity.EPIC).fireResistant(), "ring")
                    .withAttributes("ring",
                            new com.chronomancy.item.CurioBaseItem.ChronoAttribute(
                                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.COOLDOWN_REDUCTION, 0.05,
                                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                            new com.chronomancy.item.CurioBaseItem.ChronoAttribute(
                                    ChronoAttributes.CHRONOMANCY_SPELL_POWER, 0.05,
                                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));

    /**
     * Rift Heart — кристалл Кольца разломов в пириевой оправе (рецепт: кольцо и четыре пириевых
     * слитка Iron's Spells). Амулет: пока он надет, Остановка мира всегда открывает разломы; без него
     * волны не приходят вовсе.
     */
    public static final DeferredHolder<Item, ChronoUtilityItem> RIFT_HEART = ITEMS.registerItem("rift_heart",
            props -> new ChronoUtilityItem(props.stacksTo(1).rarity(Rarity.EPIC).fireResistant(), "charm"));

    /**
     * Timeless Seal — амулет с +50% сопротивления магии времени: вдвое слабее урон заклинаний школы,
     * стазис, замедление в поле и откат Backtrack. От The World Stop не защищает.
     */
    public static final DeferredHolder<Item, ChronoUtilityItem> TIMELESS_SEAL = ITEMS.registerItem("timeless_seal",
            props -> new ChronoUtilityItem(props.stacksTo(1).rarity(Rarity.RARE), "charm")
                    .withAttributes("charm",
                            new com.chronomancy.item.CurioBaseItem.ChronoAttribute(
                                    ChronoAttributes.CHRONOMANCY_MAGIC_RESIST, 0.5,
                                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));

    private static DeferredHolder<Item, ChronoUtilityItem> curio(String name, String slot) {
        return ITEMS.registerItem(name, props -> new ChronoUtilityItem(props.stacksTo(1).rarity(Rarity.RARE), slot));
    }

    public static final DeferredHolder<Item, ClocksmithArmorItem> CLOCKSMITH_HELMET = armor("clocksmith_helmet", ArmorItem.Type.HELMET);
    public static final DeferredHolder<Item, ClocksmithArmorItem> CLOCKSMITH_CHESTPLATE = armor("clocksmith_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final DeferredHolder<Item, ClocksmithArmorItem> CLOCKSMITH_LEGGINGS = armor("clocksmith_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredHolder<Item, ClocksmithArmorItem> CLOCKSMITH_BOOTS = armor("clocksmith_boots", ArmorItem.Type.BOOTS);

    private static DeferredHolder<Item, ClocksmithArmorItem> armor(String id, ArmorItem.Type type) {
        return ITEMS.registerItem(id, properties -> {
            properties.stacksTo(1).rarity(Rarity.RARE).fireResistant()
                    .durability(type.getDurability(37));
            if (type == ArmorItem.Type.CHESTPLATE) {
                properties.component(ComponentRegistry.SPELL_CONTAINER, ISpellContainer.create(1, true, true));
            }
            return new ClocksmithArmorItem(ChronoArmorMaterialRegistry.CLOCKSMITH, type, properties);
        });
    }

    /** Netherite-sword-equivalent melee clock hand; blade glints in eight animated frames. */
    public static final DeferredHolder<Item, ClockHandItem> CLOCK_HAND = ITEMS.registerItem("clock_hand",
            props -> new ClockHandItem(props.stacksTo(1).rarity(Rarity.RARE).fireResistant()
                    .attributes(ClockHandItem.createClockHandAttributes())));

    public static final DeferredHolder<Item, TimeManagementSpellBook> BOOK_OF_TIME_MANAGMENT = ITEMS.registerItem(
            "book_of_time_managment", properties -> new TimeManagementSpellBook(properties.stacksTo(1)
                    .rarity(Rarity.EPIC).fireResistant().component(ComponentRegistry.SPELL_CONTAINER,
                            ISpellContainer.create(TimeManagementSpellBook.SPELL_SLOTS, true, true))));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }

    // ===== Chronomancy crafting materials, school focus and upgrade orb =====

    /** Песня пластинки Rift Maker: {@code data/chronomancy/jukebox_song/rift_maker.json}. */
    public static final ResourceKey<net.minecraft.world.item.JukeboxSong> RIFT_MAKER_SONG = ResourceKey.create(
            net.minecraft.core.registries.Registries.JUKEBOX_SONG,
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift_maker"));

    /** Пластинка с темой боя — редкий трофей Rift Maker (шанс задан в его таблице добычи). */
    public static final DeferredHolder<Item, Item> MUSIC_DISC_RIFT_MAKER = ITEMS.registerItem(
            "music_disc_rift_maker",
            (properties) -> new Item(properties.stacksTo(1).rarity(Rarity.RARE).jukeboxPlayable(RIFT_MAKER_SONG)));

    /** Time Rune — plain school material like the ISS elemental runes; the centre of the rune recipe. */
    public static final DeferredHolder<Item, Item> TIME_RUNE = ITEMS.registerItem("time_rune", Item::new);

    /** Grain of Time — the Chronomancy school focus (tagged into {@code chronomancy:chronomancy_focus}); crushed out of clocks by a falling anvil. */
    public static final DeferredHolder<Item, Item> GRAIN_OF_TIME = ITEMS.registerItem(
            "grain_of_time", (properties) -> new Item(properties.fireResistant()));

    /** Datapack upgrade-orb-type id granted by the Chronomancy Upgrade Orb (see data/chronomancy/.../upgrade_orb_type/chronomancy_power.json). */
    public static final ResourceKey<UpgradeOrbType> CHRONOMANCY_POWER = ResourceKey.create(
            UpgradeOrbTypeRegistry.UPGRADE_ORB_REGISTRY_KEY,
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chronomancy_power"));

    /**
     * Chronomancy Upgrade Orb — a real ISS {@link UpgradeOrbItem}: it carries the {@code upgrade_orb_type} data
     * component, so it only grants its +5% Chronomancy spell power once socketed into gear at the Arcane Anvil,
     * never from inventory.
     */
    public static final DeferredHolder<Item, UpgradeOrbItem> CHRONOMANCY_UPGRADE_ORB = ITEMS.registerItem(
            "chronomancy_upgrade_orb",
            (properties) -> new UpgradeOrbItem(properties.stacksTo(1).rarity(Rarity.UNCOMMON).fireResistant()
                    .component(ComponentRegistry.UPGRADE_ORB_TYPE, CHRONOMANCY_POWER)));

    // ===== Time Rift / Chronomaly =====

    /**
     * Chronomaly Shard — осколок временной аномалии, добыча Chronomaly. Материал для рецептов
     * Clocksmith, Clock Hand, Timeless Book и временных артефактов.
     */
    public static final DeferredHolder<Item, com.chronomancy.item.ChronomalyShardItem> CHRONOMALY_SHARD = ITEMS.registerItem(
            "chronomaly_shard", (properties) -> new com.chronomancy.item.ChronomalyShardItem(properties.rarity(Rarity.UNCOMMON).fireResistant()));

    /** Rift Creator — легендарный клинок с заклинанием Rift, трофей Rift Maker. */
    public static final DeferredHolder<Item, com.chronomancy.item.RiftCreatorItem> RIFT_CREATOR = ITEMS.registerItem(
            "rift_creator", com.chronomancy.item.RiftCreatorItem::new);

    /** Яйцо призыва босса Rift Maker — для creative/тестов. */
    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> RIFT_MAKER_SPAWN_EGG =
            ITEMS.registerItem("rift_maker_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.RIFT_MAKER, 0x2A1A10, 0x2992EF, properties));

    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> PHASING_ZOMBIE_SPAWN_EGG =
            ITEMS.registerItem("phasing_zombie_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.PHASING_ZOMBIE, 0x1E5F8C, 0xE3B45C, properties));
    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> PHASING_SKELETON_SPAWN_EGG =
            ITEMS.registerItem("phasing_skeleton_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.PHASING_SKELETON, 0xBFD8E8, 0x2992EF, properties));
    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> PHASING_CREEPER_SPAWN_EGG =
            ITEMS.registerItem("phasing_creeper_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.PHASING_CREEPER, 0x2E8C7A, 0x6EC8FF, properties));

    /** Яйцо призыва Часовщика — для creative/тестов. */
    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> CLOCKSMITH_SPAWN_EGG =
            ITEMS.registerItem("clocksmith_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.CLOCKSMITH, 0x6A4024, 0xEDB054, properties));

    /** Яйцо призыва Chronomaly — для creative/тестов. */
    public static final DeferredHolder<Item, net.neoforged.neoforge.common.DeferredSpawnEggItem> CHRONOMALY_SPAWN_EGG =
            ITEMS.registerItem("chronomaly_spawn_egg", (properties) -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                    ChronoEntityTypeRegistry.CHRONOMALY, 0x04205C, 0xE3B45C, properties));
}
