# InventoryManager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Meteor Client module `InventoryManager` with three collapsible setting groups (Inventory Sorter, PvP Loadout, Container Sorter) and produce a working JAR.

**Architecture:** `SortUtils` holds all item scoring, category detection, and slot-click logic. `InventoryManager` is the single Meteor `Module` with three `SettingGroup`s that call into `SortUtils`. `AddonTemplate` is stripped to only register this one module.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric Loader 0.18.2, Meteor Client 1.21.11-SNAPSHOT, Gradle (Kotlin DSL)

---

## File Map

| Action | Path |
|--------|------|
| Delete | `src/main/java/com/example/addon/modules/ModuleExample.java` |
| Delete | `src/main/java/com/example/addon/commands/CommandExample.java` |
| Delete | `src/main/java/com/example/addon/hud/HudExample.java` |
| Modify | `src/main/java/com/example/addon/AddonTemplate.java` |
| Create | `src/main/java/com/example/addon/utils/SortUtils.java` |
| Create | `src/main/java/com/example/addon/modules/InventoryManager.java` |

---

## Slot Index Reference (PlayerScreenHandler)

| Slot range | Contents |
|-----------|----------|
| 5 | Helmet |
| 6 | Chestplate |
| 7 | Leggings |
| 8 | Boots |
| 9–35 | Main inventory |
| 36–44 | Hotbar (36=slot1 … 44=slot9) |
| 45 | Offhand |

`inv.main[i]` where i=0..8 → screen slot i+36 (hotbar)
`inv.main[i]` where i=9..35 → screen slot i (main inv)

---

### Task 1: Cleanup and update AddonTemplate

**Files:**
- Delete: `src/main/java/com/example/addon/modules/ModuleExample.java`
- Delete: `src/main/java/com/example/addon/commands/CommandExample.java`
- Delete: `src/main/java/com/example/addon/hud/HudExample.java`
- Modify: `src/main/java/com/example/addon/AddonTemplate.java`

- [ ] **Step 1: Delete example files**

```bash
rm src/main/java/com/example/addon/modules/ModuleExample.java
rm src/main/java/com/example/addon/commands/CommandExample.java
rm src/main/java/com/example/addon/hud/HudExample.java
```

- [ ] **Step 2: Overwrite AddonTemplate.java**

Full contents of `src/main/java/com/example/addon/AddonTemplate.java`:

```java
package com.example.addon;

import com.example.addon.modules.InventoryManager;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Inventory");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Inventory Manager Addon");
        Modules.get().add(new InventoryManager());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("ariel-orlov", "meteor-addon-template");
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove example files, update AddonTemplate for InventoryManager"
```

---

### Task 2: SortUtils — ItemCategory enum and getCategory()

**Files:**
- Create: `src/main/java/com/example/addon/utils/SortUtils.java`

- [ ] **Step 1: Create SortUtils.java with ItemCategory and getCategory**

```java
package com.example.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;

public class SortUtils {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    public enum ItemCategory {
        ARMOR(0), SWORD(1), AXE(2), BOW(3), CROSSBOW(4),
        TOTEM(5), SHIELD(6), POTION(7), FOOD(8), TOOL(9), BLOCK(10), MISC(11);

        public final int priority;
        ItemCategory(int priority) { this.priority = priority; }
    }

    public static ItemCategory getCategory(ItemStack stack) {
        if (stack.isEmpty()) return ItemCategory.MISC;
        Item item = stack.getItem();
        if (item instanceof ArmorItem) return ItemCategory.ARMOR;
        if (item instanceof SwordItem) return ItemCategory.SWORD;
        if (item instanceof AxeItem) return ItemCategory.AXE;
        if (item instanceof BowItem) return ItemCategory.BOW;
        if (item instanceof CrossbowItem) return ItemCategory.CROSSBOW;
        if (item == Items.TOTEM_OF_UNDYING) return ItemCategory.TOTEM;
        if (item instanceof ShieldItem) return ItemCategory.SHIELD;
        if (stack.getComponents().contains(DataComponentTypes.POTION_CONTENTS)
                && !(item instanceof ArrowItem)) return ItemCategory.POTION;
        if (stack.getComponents().contains(DataComponentTypes.FOOD)) return ItemCategory.FOOD;
        if (item instanceof MiningToolItem || item instanceof HoeItem) return ItemCategory.TOOL;
        if (item instanceof BlockItem) return ItemCategory.BLOCK;
        return ItemCategory.MISC;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/addon/utils/SortUtils.java
git commit -m "feat: add SortUtils with ItemCategory enum and getCategory()"
```

---

### Task 3: SortUtils — scoring functions

**Files:**
- Modify: `src/main/java/com/example/addon/utils/SortUtils.java`

- [ ] **Step 1: Add imports and scoring methods at the bottom of SortUtils (before the closing `}`)**

Add these imports at the top of the file (after the existing ones):

```java
import net.minecraft.registry.Registries;
```

Add these methods inside the class body:

```java
    public static int scoreItem(ItemStack stack) {
        if (stack.isEmpty()) return Integer.MIN_VALUE;
        int base = getCategory(stack).priority * -1000;
        int enchants = 0;
        for (var entry : stack.getEnchantments()) {
            enchants += entry.getIntValue();
        }
        return base + enchants;
    }

    public static int scoreArmor(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return 0;
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        int tier = 0;
        if (id.startsWith("netherite_")) tier = 6;
        else if (id.startsWith("diamond_")) tier = 5;
        else if (id.startsWith("iron_")) tier = 4;
        else if (id.startsWith("chainmail_")) tier = 3;
        else if (id.startsWith("golden_")) tier = 2;
        else if (id.startsWith("leather_")) tier = 1;
        int enchants = 0;
        for (var entry : stack.getEnchantments()) {
            enchants += entry.getIntValue();
        }
        return tier * 100 + enchants * 10;
    }

    public static int compareItems(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        if (a.isEmpty()) return 1;
        if (b.isEmpty()) return -1;
        int catCmp = getCategory(a).priority - getCategory(b).priority;
        if (catCmp != 0) return catCmp;
        return scoreItem(b) - scoreItem(a);
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/addon/utils/SortUtils.java
git commit -m "feat: add item scoring and comparison to SortUtils"
```

---

### Task 4: SortUtils — slot interaction helpers and sortSlotRange()

**Files:**
- Modify: `src/main/java/com/example/addon/utils/SortUtils.java`

- [ ] **Step 1: Add these imports to SortUtils**

```java
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
```

- [ ] **Step 2: Add interaction + sort methods inside SortUtils class**

```java
    public static void interact(int syncId, int slotId, int button, SlotActionType type) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(syncId, slotId, button, type, mc.player);
    }

    public static void shiftClick(int syncId, int slotId) {
        interact(syncId, slotId, 0, SlotActionType.QUICK_MOVE);
    }

    public static void sortSlotRange(ScreenHandler handler, int screenSlotStart, int screenSlotEnd) {
        if (mc.player == null) return;
        int syncId = handler.syncId;
        int count = screenSlotEnd - screenSlotStart + 1;

        ItemStack[] local = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            local[i] = handler.getSlot(screenSlotStart + i).getStack().copy();
        }

        for (int i = 0; i < count - 1; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < count; j++) {
                if (compareItems(local[j], local[bestIdx]) < 0) bestIdx = j;
            }
            if (bestIdx != i) {
                swapScreenSlots(syncId,
                    screenSlotStart + i, screenSlotStart + bestIdx,
                    local, i, bestIdx);
            }
        }
    }

    private static void swapScreenSlots(int syncId, int slotA, int slotB,
                                        ItemStack[] local, int idxA, int idxB) {
        ItemStack a = local[idxA];
        ItemStack b = local[idxB];
        if (a.isEmpty() && b.isEmpty()) return;

        if (a.isEmpty()) {
            interact(syncId, slotB, 0, SlotActionType.PICKUP);
            interact(syncId, slotA, 0, SlotActionType.PICKUP);
        } else if (b.isEmpty()) {
            interact(syncId, slotA, 0, SlotActionType.PICKUP);
            interact(syncId, slotB, 0, SlotActionType.PICKUP);
        } else {
            interact(syncId, slotA, 0, SlotActionType.PICKUP);
            interact(syncId, slotB, 0, SlotActionType.PICKUP);
            interact(syncId, slotA, 0, SlotActionType.PICKUP);
        }

        local[idxA] = b;
        local[idxB] = a;
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/utils/SortUtils.java
git commit -m "feat: add slot interaction helpers and sortSlotRange() to SortUtils"
```

---

### Task 5: InventoryManager — module skeleton with all settings

**Files:**
- Create: `src/main/java/com/example/addon/modules/InventoryManager.java`

- [ ] **Step 1: Create InventoryManager.java with all three setting groups and no logic yet**

```java
package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;

import java.util.ArrayList;
import java.util.List;

public class InventoryManager extends Module {

    public enum Trigger { ON_KEYBIND, ON_OPEN, CONTINUOUS }
    public enum LoadoutTrigger { ON_KEYBIND, ON_RESPAWN }
    public enum SlotType { AUTO, SWORD, AXE, BOW, CROSSBOW, POTION, FOOD, SHIELD, TOTEM, BLOCK, EMPTY }

    private static final SlotType[] DEFAULT_HOTBAR = {
        SlotType.SWORD, SlotType.AXE, SlotType.BOW,
        SlotType.POTION, SlotType.POTION, SlotType.FOOD,
        SlotType.SHIELD, SlotType.TOTEM, SlotType.BLOCK
    };

    // ── Inventory Sorter ────────────────────────────────────────────
    private final SettingGroup sgInv = settings.createGroup("Inventory Sorter");

    private final Setting<Boolean> invEnabled = sgInv.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable inventory sorting.")
        .defaultValue(true)
        .build());

    private final Setting<Trigger> invTrigger = sgInv.add(new EnumSetting.Builder<Trigger>()
        .name("trigger")
        .description("When to sort the player inventory.")
        .defaultValue(Trigger.ON_OPEN)
        .build());

    private final Setting<Integer> invDelay = sgInv.add(new IntSetting.Builder()
        .name("continuous-delay")
        .description("Ticks between sorts in Continuous mode.")
        .defaultValue(40).min(5).sliderMax(200)
        .visible(() -> invTrigger.get() == Trigger.CONTINUOUS)
        .build());

    private final Setting<Keybind> invKeybind = sgInv.add(new KeybindSetting.Builder()
        .name("sort-keybind")
        .description("Key to trigger inventory sort.")
        .defaultValue(Keybind.none())
        .visible(() -> invTrigger.get() == Trigger.ON_KEYBIND)
        .build());

    // ── PvP Loadout ─────────────────────────────────────────────────
    private final SettingGroup sgPvp = settings.createGroup("PvP Loadout");

    private final Setting<Boolean> pvpEnabled = sgPvp.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable PvP loadout management.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pvpAutoArmor = sgPvp.add(new BoolSetting.Builder()
        .name("auto-equip-armor")
        .description("Automatically equip the best armor from inventory.")
        .defaultValue(true)
        .build());

    private final Setting<LoadoutTrigger> pvpTrigger = sgPvp.add(new EnumSetting.Builder<LoadoutTrigger>()
        .name("trigger")
        .description("When to apply the PvP loadout.")
        .defaultValue(LoadoutTrigger.ON_KEYBIND)
        .build());

    private final Setting<Keybind> pvpKeybind = sgPvp.add(new KeybindSetting.Builder()
        .name("loadout-keybind")
        .description("Key to apply PvP loadout.")
        .defaultValue(Keybind.none())
        .visible(() -> pvpTrigger.get() == LoadoutTrigger.ON_KEYBIND)
        .build());

    private final List<Setting<SlotType>> hotbarSlots = new ArrayList<>();

    // ── Container Sorter ────────────────────────────────────────────
    private final SettingGroup sgContainer = settings.createGroup("Container Sorter");

    private final Setting<Boolean> ctEnabled = sgContainer.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable container sorting.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ctSortOnOpen = sgContainer.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Sort container contents when you open it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ctRestock = sgContainer.add(new BoolSetting.Builder()
        .name("restock")
        .description("Pull items from container into your inventory.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ctRestockPotions = sgContainer.add(new BoolSetting.Builder()
        .name("restock-potions").description("Restock potions.").defaultValue(true)
        .visible(() -> ctRestock.get()).build());

    private final Setting<Boolean> ctRestockFood = sgContainer.add(new BoolSetting.Builder()
        .name("restock-food").description("Restock food.").defaultValue(true)
        .visible(() -> ctRestock.get()).build());

    private final Setting<Boolean> ctRestockArrows = sgContainer.add(new BoolSetting.Builder()
        .name("restock-arrows").description("Restock arrows.").defaultValue(true)
        .visible(() -> ctRestock.get()).build());

    private final Setting<Boolean> ctRestockBlocks = sgContainer.add(new BoolSetting.Builder()
        .name("restock-blocks").description("Restock blocks.").defaultValue(false)
        .visible(() -> ctRestock.get()).build());

    public InventoryManager() {
        super(AddonTemplate.CATEGORY, "inventory-manager",
            "Sorts inventory, manages PvP loadout, and handles containers.");

        for (int i = 0; i < 9; i++) {
            hotbarSlots.add(sgPvp.add(new EnumSetting.Builder<SlotType>()
                .name("hotbar-" + (i + 1))
                .description("Preferred item for hotbar slot " + (i + 1) + ".")
                .defaultValue(DEFAULT_HOTBAR[i])
                .build()));
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/addon/modules/InventoryManager.java
git commit -m "feat: add InventoryManager module skeleton with all three setting groups"
```

---

### Task 6: InventoryManager — InvSorter logic

**Files:**
- Modify: `src/main/java/com/example/addon/modules/InventoryManager.java`

- [ ] **Step 1: Add imports to InventoryManager**

```java
import com.example.addon.utils.SortUtils;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.PlayerScreenHandler;
```

- [ ] **Step 2: Add fields and InvSorter event handlers inside the class**

Add these fields after the `hotbarSlots` list:

```java
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private int invTickCounter = 0;
    private boolean invKeyLastPressed = false;
```

Add these methods inside the class:

```java
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        handleInvSorterTick();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;
        if (invEnabled.get() && invTrigger.get() == Trigger.ON_OPEN
                && event.screen instanceof InventoryScreen) {
            sortPlayerInventory();
        }
    }

    private void handleInvSorterTick() {
        if (!invEnabled.get()) return;

        if (invTrigger.get() == Trigger.CONTINUOUS) {
            invTickCounter++;
            if (invTickCounter >= invDelay.get()) {
                invTickCounter = 0;
                sortPlayerInventory();
            }
        } else if (invTrigger.get() == Trigger.ON_KEYBIND) {
            boolean pressed = invKeybind.get().isPressed();
            if (pressed && !invKeyLastPressed) sortPlayerInventory();
            invKeyLastPressed = pressed;
        }
    }

    private void sortPlayerInventory() {
        if (mc.player == null) return;
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        // Sort main inventory slots (screen slots 9–35)
        SortUtils.sortSlotRange(handler, 9, 35);
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/InventoryManager.java
git commit -m "feat: add InvSorter logic with ON_OPEN, CONTINUOUS, and ON_KEYBIND triggers"
```

---

### Task 7: InventoryManager — PvP Loadout (armor equip + hotbar)

**Files:**
- Modify: `src/main/java/com/example/addon/modules/InventoryManager.java`

- [ ] **Step 1: Add imports**

```java
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
```

- [ ] **Step 2: Add PvP loadout fields after `invKeyLastPressed`**

```java
    private boolean pvpKeyLastPressed = false;
    private boolean wasDead = false;
```

- [ ] **Step 3: Extend onTick to handle PvP triggers**

Replace `handleInvSorterTick()` call in `onTick` with:

```java
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        handleInvSorterTick();
        handlePvpLoadoutTick();
    }
```

- [ ] **Step 4: Add PvP loadout methods**

```java
    private void handlePvpLoadoutTick() {
        if (!pvpEnabled.get()) return;

        boolean isDead = mc.player.isDead() || mc.player.getHealth() <= 0;
        if (pvpTrigger.get() == LoadoutTrigger.ON_RESPAWN) {
            if (wasDead && !isDead) applyPvpLoadout();
        } else {
            boolean pressed = pvpKeybind.get().isPressed();
            if (pressed && !pvpKeyLastPressed) applyPvpLoadout();
            pvpKeyLastPressed = pressed;
        }
        wasDead = isDead;
    }

    private void applyPvpLoadout() {
        if (pvpAutoArmor.get()) equipBestArmor();
        arrangeHotbar();
    }

    private void equipBestArmor() {
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[] screenSlots = {5, 6, 7, 8}; // helmet, chestplate, leggings, boots in PlayerScreenHandler

        for (int s = 0; s < 4; s++) {
            EquipmentSlot eqSlot = armorSlots[s];
            int armorScreenSlot = screenSlots[s];
            ItemStack equipped = handler.getSlot(armorScreenSlot).getStack();

            // Find best armor piece for this slot in main inventory (screen slots 9–35)
            int bestSlot = -1;
            int bestScore = SortUtils.scoreArmor(equipped);
            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (candidate.isEmpty()) continue;
                if (!(candidate.getItem() instanceof ArmorItem armorItem)) continue;
                if (armorItem.getSlotType() != eqSlot) continue;
                int score = SortUtils.scoreArmor(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }

            if (bestSlot == -1) continue;

            // Unequip current armor to inventory first (if slot is occupied)
            if (!equipped.isEmpty()) {
                SortUtils.shiftClick(syncId, armorScreenSlot);
            }
            // Equip the better piece via shift-click
            SortUtils.shiftClick(syncId, bestSlot);
        }
    }

    private void arrangeHotbar() {
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        for (int hotbarIdx = 0; hotbarIdx < 9; hotbarIdx++) {
            SlotType desired = hotbarSlots.get(hotbarIdx).get();
            if (desired == SlotType.EMPTY || desired == SlotType.AUTO) continue;

            int currentScreenSlot = 36 + hotbarIdx;
            ItemStack current = handler.getSlot(currentScreenSlot).getStack();

            // Check if the current item already matches the desired type
            if (!current.isEmpty() && matchesSlotType(current, desired)) continue;

            // Find matching item in main inventory (screen slots 9–35)
            int foundSlot = -1;
            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (!candidate.isEmpty() && matchesSlotType(candidate, desired)) {
                    foundSlot = i;
                    break;
                }
            }
            if (foundSlot == -1) continue;

            // Swap found item with target hotbar slot using SWAP action
            // SWAP button = hotbar slot index 0–8; slotId = source screen slot
            SortUtils.interact(syncId, foundSlot, hotbarIdx, SlotActionType.SWAP);
        }
    }

    private boolean matchesSlotType(ItemStack stack, SlotType type) {
        return switch (type) {
            case SWORD -> stack.getItem() instanceof net.minecraft.item.SwordItem;
            case AXE -> stack.getItem() instanceof net.minecraft.item.AxeItem;
            case BOW -> stack.getItem() instanceof net.minecraft.item.BowItem;
            case CROSSBOW -> stack.getItem() instanceof net.minecraft.item.CrossbowItem;
            case SHIELD -> stack.getItem() instanceof net.minecraft.item.ShieldItem;
            case TOTEM -> stack.getItem() == net.minecraft.item.Items.TOTEM_OF_UNDYING;
            case POTION -> stack.getComponents().contains(net.minecraft.component.DataComponentTypes.POTION_CONTENTS)
                           && !(stack.getItem() instanceof net.minecraft.item.ArrowItem);
            case FOOD -> stack.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD);
            case BLOCK -> stack.getItem() instanceof net.minecraft.item.BlockItem;
            default -> false;
        };
    }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/addon/modules/InventoryManager.java
git commit -m "feat: add PvP loadout — auto-equip armor and arrange hotbar"
```

---

### Task 8: InventoryManager — ContainerSorter logic

**Files:**
- Modify: `src/main/java/com/example/addon/modules/InventoryManager.java`

- [ ] **Step 1: Add imports**

```java
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.PlayerScreenHandler;
```

- [ ] **Step 2: Add container handler fields after `wasDead`**

```java
    private boolean containerSortPending = false;
```

- [ ] **Step 3: Add container screen detection in onOpenScreen**

Replace the existing `onOpenScreen` method with:

```java
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;

        // InvSorter: player opens their own inventory
        if (invEnabled.get() && invTrigger.get() == Trigger.ON_OPEN
                && event.screen instanceof InventoryScreen) {
            sortPlayerInventory();
        }

        // ContainerSorter: player opens an external container
        if (ctEnabled.get()
                && event.screen instanceof HandledScreen<?>
                && !(event.screen instanceof InventoryScreen)) {
            containerSortPending = true;
        }
    }
```

- [ ] **Step 4: Extend onTick to handle container sort after screen opens**

Replace the `onTick` body:

```java
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        handleInvSorterTick();
        handlePvpLoadoutTick();
        handleContainerTick();
    }
```

- [ ] **Step 5: Add handleContainerTick and helper methods**

```java
    private void handleContainerTick() {
        if (!containerSortPending) return;
        containerSortPending = false;

        var handler = mc.player.currentScreenHandler;
        if (handler == null || handler instanceof PlayerScreenHandler) return;

        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return;

        if (ctSortOnOpen.get()) {
            SortUtils.sortSlotRange(handler, 0, containerSize - 1);
        }

        if (ctRestock.get()) {
            restockFromContainer(handler, containerSize);
        }
    }

    private void restockFromContainer(net.minecraft.screen.ScreenHandler handler, int containerSize) {
        int syncId = handler.syncId;

        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            SortUtils.ItemCategory cat = SortUtils.getCategory(stack);
            boolean shouldRestock = switch (cat) {
                case POTION -> ctRestockPotions.get();
                case FOOD -> ctRestockFood.get();
                case BLOCK -> ctRestockBlocks.get();
                case MISC -> {
                    // Arrows fall under MISC since ArrowItem is not in a named category
                    yield ctRestockArrows.get() && stack.getItem() instanceof net.minecraft.item.ArrowItem;
                }
                default -> false;
            };

            if (shouldRestock && !playerAlreadyHas(stack)) {
                SortUtils.shiftClick(syncId, i);
            }
        }
    }

    private boolean playerAlreadyHas(ItemStack target) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && ItemStack.areItemsAndComponentsEqual(s, target)) return true;
        }
        return false;
    }
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/addon/modules/InventoryManager.java
git commit -m "feat: add ContainerSorter — sort on open and restock from container"
```

---

### Task 9: Build and produce JAR

**Files:**
- Read: `gradle/libs.versions.toml` (verify versions before building)

- [ ] **Step 1: Verify dependency versions look correct**

```bash
cat gradle/libs.versions.toml
```

Expected output should show `minecraft = "1.21.11"`, `meteor = "1.21.11-SNAPSHOT"`.

- [ ] **Step 2: Run build**

```bash
cd /Users/arielorlov/meteor-addon && ./gradlew build 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` with a JAR at `build/libs/`.

- [ ] **Step 3: If build fails, check the error and fix**

Common issues:
- Import not found → verify `net.minecraft.*` paths against Yarn 1.21.11 mappings
- `ArmorItem.getSlotType()` → in some 1.21.x builds this is `getSlotType()` or check via `EquipmentSlot` methods
- Enchantment iteration → if `stack.getEnchantments()` doesn't compile, use `EnchantmentHelper.getEnchantments(stack)`
- `ItemStack.areItemsAndComponentsEqual` → if missing, use `ItemStack.areEqual(a, b)` instead

- [ ] **Step 4: Confirm JAR exists**

```bash
ls -lh build/libs/*.jar
```

Expected: one JAR file, e.g. `addon-template-0.1.0.jar` (size ~10-50 KB).

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "build: final working InventoryManager addon JAR"
```
