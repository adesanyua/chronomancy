# Chronomancy

A school of time magic for Iron's Spells 'n Spellbooks on NeoForge 1.21.1.

Freeze an enemy mid-swing, rewind your own wounds, step out of the timeline and, at the top of the school, stop the whole world.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.238 or newer
- Iron's Spells 'n Spellbooks 3.16.3 or newer, with its own dependencies
- Curios API
- Optional: JEI (info pages for every spell and key item)

## What it adds

**Twelve spells.** Temporal Stasis Beam, Backtrack, Time Dilation Field, Accelerated Zone, Sands of Time, Time-Piercing Needle, Rewind, Time Walk, Chrono Double, Rift, Borrowed Future and The World Stop.

**The Rift Trial.** Cast The World Stop while wearing the Rift Heart and the stopped world tears open. Time does not move on until every wave is cleared and the Rift Maker boss is defeated. Health and mana do not recover in stopped time, except inside the Islands of Time that open nearby.

**Creatures.** The Rift Maker, Chronomalies, phasing zombies, skeletons and creepers, and the Clocksmith, who lives in clock towers and trades for Grains of Time.

**Gear.** The Timeless Book, the Clock Hand and Rift Creator swords, the Clocksmith armor set, and Curios trinkets such as the Second Chance Watch, the Deferred Pendulum and the Temporal Anchor.

## Configuration

Balance lives in `config/chronomancy-server.toml`: trial waves, mobs per rift, the boss switch, Islands of Time, needle damage, stasis fatigue and more. Cooldowns, mana costs and spell power follow the per-spell config of Iron's Spells 'n Spellbooks.

## Building

```
./gradlew build
```

The jar appears in `build/libs`. Java 21 is required.

## License

The mod is released under the [MIT License](LICENSE). The project skeleton comes from the NeoForge MDK; its license is kept in [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).
