# InventoryManager Addon — Design Spec
Date: 2026-04-21

## Overview

A Meteor Client addon with a single `InventoryManager` module containing three collapsible SettingGroups, each acting as an independent sub-feature. Final deliverable is a built JAR dropped into the Minecraft `mods/` folder alongside Meteor Client.

## Architecture

```
src/main/java/com/example/addon/
├── AddonTemplate.java               (entry point, registers InventoryManager)
├── modules/
│   └── InventoryManager.java        (one module, three SettingGroups)
└── utils/
    └── SortUtils.java               (item scoring, category ranking, slot-click simulation)
```

## Module: InventoryManager

### SettingGroup 1 — Inventory Sorter
- **enabled** `BoolSetting` — master toggle for this group's logic
- **trigger** `EnumSetting<Trigger>` — `OnKeybind | OnInventoryOpen | Continuous`
- **sortDelay** `IntSetting` — ticks between continuous sorts (default 20)

Sort order by category: Armor → Weapons → Tools → Potions → Food → Misc.
Within each category, items ranked by material tier + enchantment count/level.
Uses shift-click simulation to physically move items in Minecraft's slot protocol.

### SettingGroup 2 — PvP Loadout
- **enabled** `BoolSetting`
- **autoEquipArmor** `BoolSetting` — equips best helmet/chestplate/leggings/boots from inventory
- **trigger** `EnumSetting<LoadoutTrigger>` — `OnKeybind | OnRespawn`
- **slot1–slot9** `EnumSetting<SlotType>` — `Auto | Sword | Axe | Bow | Crossbow | Potion | Food | Shield | Totem | Block | Empty`

Armor scoring: material tier (leather < gold < chain < iron < diamond < netherite) × protection enchant level.
"Auto" hotbar slots: module picks best available item of most useful PvP type.
Pinned slots: user-defined type placed there if available, otherwise skipped.

### SettingGroup 3 — Container Sorter
- **enabled** `BoolSetting`
- **sortOnOpen** `BoolSetting` — reorganize container slots when screen opens
- **restock** `BoolSetting` — pull items from container into player inventory
- **restockPotions** `BoolSetting`
- **restockFood** `BoolSetting`
- **restockArrows** `BoolSetting`
- **restockBlocks** `BoolSetting`

Activates via `OpenScreenEvent`. Restock respects pinned hotbar slots from PvP Loadout group to avoid conflicts.

## SortUtils

Responsibilities:
- `getCategory(ItemStack)` → `ItemCategory` enum
- `scoreItem(ItemStack)` → `int` (higher = better)
- `scoreArmor(ItemStack)` → `int`
- `clickSlot(int slot, SlotActionType, ScreenHandler)` — wraps `mc.interactionManager.clickSlot`
- `shiftClick(int slot, ScreenHandler)` — shortcut for move-to-other-container

## Events Used
- `TickEvent.Post` — Continuous trigger, per-tick loadout check
- `OpenScreenEvent` — ContainerSorter activation
- `GameJoinedEvent` / player respawn — OnRespawn loadout trigger

## Build
- Minecraft 1.21.11, Fabric Loader 0.18.2, meteor-client 1.21.11-SNAPSHOT, Java 21
- `./gradlew build` → `build/libs/*.jar`
