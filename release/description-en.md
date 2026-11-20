# Barricade

This mod adds post-apocalyptic urban defenses and scene lighting to Minecraft, suited to survival bases, abandoned city blocks, and custom maps.

## Features

The mod currently adds three main types of blocks, each with its own functionality.

### Barricades

When placed, a Barricade is filled with randomly selected models from the default `barricade:materials` tag, forming a roadblock one and a half blocks high. Vanilla models, GeckoLib, and Simple Bedrock Model are supported.

While holding a block, sneak-right-click a Barricade to embed it. The block remains interactive while preserving the pile's cluttered appearance. When the embedded block is removed, the displaced debris returns to its original position. Multi-block blocks such as doors and beds are not currently supported.

Rename a Barricade to a tag name at an anvil, such as `minecraft:logs`, and it will select models from that tag when placed. This lets players create barricade piles with different models. The `barricade:materials` tag includes furniture from MrCrayfish's Refurbished Furniture; when that mod is installed, its furniture is included in the default model pool.

### Window Seals

The mod adds wooden and iron window seals. Their models adapt to the size of the area the player places them in, avoiding a uniform appearance. Seals block passage but do not prevent projectiles from passing through; this includes compatibility with TaCZ and Superb Warfare. Certain melee weapons can also hit enemies on the other side through the seals. With Better Combat, spears, lances, and trident-style weapons are automatically supported.

Use the `barricade:window_boards_pass_through` tag to add projectiles that can pass through the seals, or the `barricade:window_boards_attack_through` item tag to specify which melee weapons can hit targets through them.

### String Lights

Mount a String-Light Power Box on a wall, floor, or ceiling. Hold the wire, right-click the box's outlet, then right-click another suitable attachment point. The wire generates naturally, can span up to 32 blocks, and can be extended or branched from an existing hanger.

String lights use FE for power. Right-click the box with an empty hand to switch them on or off. Wire can be removed with shears.

**Note:** The mod does not add FE equipment, so players need a separate mod that supplies FE to power the bulbs.

## Installation

Requires **Minecraft Java Edition 1.20.1, Forge 47.3.19 or later for 1.20.1, and Java 17**. Clients require [Accelerated Rendering (AR)](https://www.curseforge.com/minecraft/mc-mods/accelerated-rendering/files/8448250) 1.0.14 or later for Forge 1.20.1. Dedicated servers do not need AR. Client GPUs and drivers must support OpenGL 4.3 and 64-bit shader integers.

All other integrations mentioned above are optional.

Author: **Scarasol** · License: **GNU Affero General Public License v3.0**
