# Inventory Manager — Meteor Client Addon

A smart inventory automation addon for [Meteor Client](https://meteorclient.com).  
Auto-sort, PvP loadout, quick deposit, inventory-full warnings, and auto-trash — all configurable and server-safe.

---

## Features

### Inventory Sorter
Sorts your inventory by item category (armor → sword → axe → bow → shields → potions → food → tools → blocks → misc), then alphabetically within each category, with larger stacks first.

- **Sort Key** — bind a key to sort on demand; fires even with a container screen open
- **Sort on Inventory Open** — automatically sorts when you open your player inventory
- **Sort Hotbar** — optionally sort the hotbar (9 slots) as a separate pass
- **Continuous Sort** — re-sort every N ticks (configurable)
- **Actions Per Tick** — throttle how many slot clicks are sent per tick (1–20, default 4) for server compatibility

### PvP Loadout
- **Auto-Equip Best Armor** — automatically equips your best available armor set every second
- **Arrange Hotbar** — assigns specific item types (sword, axe, bow, crossbow, totem, shield, food, potion, pickaxe) to chosen hotbar slots
- **Apply on Respawn** — re-applies PvP loadout automatically when you respawn

### Container Sorter
When a chest, barrel, or other container is open:
- **Sort on Container Open** — automatically sorts the container contents when opened
- **Deposit Key** — bind a key to instantly shift-click all matching items from your inventory into the open container
- **Auto-Restock** — refills your inventory from open containers (food, blocks, tools, potions)

### Inventory Extras
- **Inventory Full Warning** — sends a chat warning when your inventory reaches a configurable fullness threshold (with cooldown to avoid spam)
- **Auto Trash** — automatically drops items whose IDs are in a comma-separated list (e.g. `rotten_flesh,gravel,flint`)

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version
2. Install [Meteor Client](https://meteorclient.com/download) (matching MC version)
3. Download the latest `inventory-manager-*.jar` from [**Releases**](https://github.com/ariel-orlov/meteor-inventory-manager/releases/latest)
4. Place the JAR in your `.minecraft/mods/` folder
5. Launch Minecraft — the **Inventory** category will appear in Meteor's module list

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.8 |
| Meteor Client | 1.21.8-SNAPSHOT or newer |
| Fabric Loader | 0.19.2+ |
| Java | 21+ |

---

## Building from Source

```bash
git clone https://github.com/ariel-orlov/meteor-inventory-manager
cd meteor-inventory-manager
./gradlew build
```

The output JAR will be in `build/libs/`.

---

## Configuration

All settings are in Meteor's GUI under **Modules → Inventory → Inventory Manager**.  
Settings are grouped into four sections:

| Group | Key settings |
|---|---|
| Inventory Sorter | Sort Key, Sort on Open, Sort Hotbar, Continuous Sort, Actions Per Tick |
| PvP Loadout | Auto-Equip Armor, Arrange Hotbar, Apply on Respawn, hotbar slot assignments |
| Container Sorter | Sort on Open, Deposit Key, Auto-Restock options |
| Inventory Extras | Full Warning threshold, Auto Trash item list |

### Server Safety
The **Actions Per Tick** slider (default 4) limits how many slot interactions are sent each tick.  
Lower values (1–2) are safer on strict anti-cheat servers; higher values (10–20) are faster on permissive servers.

---

## License

[MIT](LICENSE)
