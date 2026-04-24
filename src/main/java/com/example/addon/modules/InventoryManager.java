package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.utils.SortUtils;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Three sub-features in one module.
 * Pressing the module keybind (or clicking Enable) fires all active sub-features immediately.
 * Keep the module ON for auto-sort-on-open, continuous sort, and container handling.
 */
public class InventoryManager extends Module {

    public enum SlotType {
        AUTO, SWORD, AXE, BOW, CROSSBOW, POTION, FOOD, SHIELD, TOTEM, BLOCK, EMPTY
    }

    private static final SlotType[] DEFAULT_HOTBAR = {
        SlotType.SWORD, SlotType.AXE, SlotType.BOW,
        SlotType.POTION, SlotType.POTION, SlotType.FOOD,
        SlotType.SHIELD, SlotType.TOTEM, SlotType.BLOCK
    };

    // ── Inventory Sorter ─────────────────────────────────────────────
    private final SettingGroup sgInv = settings.createGroup("Inventory Sorter");

    private final Setting<Boolean> invSort = sgInv.add(new BoolSetting.Builder()
        .name("sort-inventory")
        .description("Sort main inventory when module is activated.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> invSortOnOpen = sgInv.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Also sort inventory every time you open it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> invContinuous = sgInv.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously sort inventory while module is enabled.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> invDelay = sgInv.add(new IntSetting.Builder()
        .name("continuous-delay")
        .description("Ticks between continuous sorts.")
        .defaultValue(40).min(5).sliderMax(200)
        .visible(() -> invContinuous.get())
        .build());

    private final Setting<Keybind> sortKey = sgInv.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Press to sort whatever inventory or container is currently open.")
        .defaultValue(Keybind.none())
        .action(this::doSort)
        .build());

    // ── PvP Loadout ──────────────────────────────────────────────────
    private final SettingGroup sgPvp = settings.createGroup("PvP Loadout");

    private final Setting<Boolean> pvpEquipArmor = sgPvp.add(new BoolSetting.Builder()
        .name("equip-armor")
        .description("Auto-equip best armor from inventory on activate.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pvpArrangeHotbar = sgPvp.add(new BoolSetting.Builder()
        .name("arrange-hotbar")
        .description("Move best PvP items to configured hotbar slots on activate.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pvpOnRespawn = sgPvp.add(new BoolSetting.Builder()
        .name("on-respawn")
        .description("Also apply PvP loadout automatically on respawn.")
        .defaultValue(false)
        .build());

    private final List<Setting<SlotType>> hotbarSlots = new ArrayList<>();

    // ── Container Sorter ─────────────────────────────────────────────
    private final SettingGroup sgContainer = settings.createGroup("Container Sorter");

    private final Setting<Boolean> ctSortOnOpen = sgContainer.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Sort container contents when you open a chest/barrel/etc.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ctRestock = sgContainer.add(new BoolSetting.Builder()
        .name("restock")
        .description("Pull matching items from container into your inventory.")
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

    // ── State ────────────────────────────────────────────────────────
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private int tickCounter = 0;
    private boolean wasDead = false;
    private boolean containerPending = false;

    public InventoryManager() {
        super(AddonTemplate.CATEGORY, "inventory-manager",
            "Enable to sort inventory + apply PvP loadout. Keep on for auto-sort and container features.");

        for (int i = 0; i < 9; i++) {
            hotbarSlots.add(sgPvp.add(new EnumSetting.Builder<SlotType>()
                .name("slot-" + (i + 1))
                .description("Item type for hotbar slot " + (i + 1) + ".")
                .defaultValue(DEFAULT_HOTBAR[i])
                .visible(() -> pvpArrangeHotbar.get())
                .build()));
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        if (invSort.get()) doSort();
        if (pvpEquipArmor.get() || pvpArrangeHotbar.get()) {
            applyPvpLoadout();
            info("PvP loadout applied.");
        }
    }

    // ── Sort dispatcher (keybind + onActivate) ───────────────────────

    private void doSort() {
        if (mc.player == null) return;
        tickCounter = 0;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler instanceof PlayerScreenHandler || handler == null) {
            sortPlayerInventory();
            info("Inventory sorted.");
        } else {
            int containerSize = handler.slots.size() - 36;
            if (containerSize > 0) {
                SortUtils.sortSlotRange(handler, 0, containerSize - 1);
                SortUtils.mergeStacks(handler, 0, containerSize - 1);
                // Sort the player main inventory portion visible in this screen
                SortUtils.sortSlotRange(handler, containerSize, containerSize + 26);
                SortUtils.mergeStacks(handler, containerSize, containerSize + 26);
            }
            info("Container & inventory sorted.");
        }
    }

    // ── Events ───────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Continuous inventory sort
        if (invSort.get() && invContinuous.get()) {
            if (++tickCounter >= invDelay.get()) {
                tickCounter = 0;
                sortPlayerInventory();
            }
        }

        // Respawn loadout trigger
        if (pvpOnRespawn.get() && (pvpEquipArmor.get() || pvpArrangeHotbar.get())) {
            boolean isDead = mc.player.isDead() || mc.player.getHealth() <= 0;
            if (wasDead && !isDead) applyPvpLoadout();
            wasDead = isDead;
        }

        // Container sort (deferred one tick from OpenScreenEvent)
        if (containerPending) {
            containerPending = false;
            handleContainerSort();
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;

        if (invSort.get() && invSortOnOpen.get()
                && event.screen instanceof InventoryScreen) {
            sortPlayerInventory();
        }

        if (event.screen instanceof HandledScreen<?>
                && !(event.screen instanceof InventoryScreen)
                && (ctSortOnOpen.get() || ctRestock.get())) {
            containerPending = true;
        }
    }

    // ── Inventory Sorter ─────────────────────────────────────────────

    private void sortPlayerInventory() {
        if (mc.player == null) return;
        PlayerScreenHandler h = mc.player.playerScreenHandler;
        SortUtils.sortSlotRange(h, 9, 35);
        SortUtils.mergeStacks(h, 9, 35);
    }

    // ── PvP Loadout ──────────────────────────────────────────────────

    private void applyPvpLoadout() {
        if (mc.player == null) return;
        if (pvpEquipArmor.get()) equipBestArmor();
        if (pvpArrangeHotbar.get()) arrangeHotbar();
    }

    private void equipBestArmor() {
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        // PlayerScreenHandler armor slots: 5=helmet 6=chestplate 7=leggings 8=boots
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[] screenSlots = {5, 6, 7, 8};

        for (int s = 0; s < 4; s++) {
            EquipmentSlot eqSlot = slots[s];
            int armorScreenSlot = screenSlots[s];
            ItemStack equipped = handler.getSlot(armorScreenSlot).getStack();

            int bestSlot = -1;
            int bestScore = SortUtils.scoreArmor(equipped);

            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (candidate.isEmpty()) continue;
                var equippable = candidate.get(DataComponentTypes.EQUIPPABLE);
                if (equippable == null || equippable.slot() != eqSlot) continue;
                int score = SortUtils.scoreArmor(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }

            if (bestSlot == -1) continue;
            if (!equipped.isEmpty()) SortUtils.shiftClick(syncId, armorScreenSlot);
            SortUtils.shiftClick(syncId, bestSlot);
        }
    }

    private void arrangeHotbar() {
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        for (int hotbarIdx = 0; hotbarIdx < 9; hotbarIdx++) {
            SlotType desired = hotbarSlots.get(hotbarIdx).get();
            if (desired == SlotType.EMPTY || desired == SlotType.AUTO) continue;

            // Screen slot 36-44 = hotbar slots 0-8
            ItemStack current = handler.getSlot(36 + hotbarIdx).getStack();
            if (!current.isEmpty() && matchesSlotType(current, desired)) continue;

            // Search main inventory (screen slots 9-35) for a matching item
            int foundSlot = -1;
            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (!candidate.isEmpty() && matchesSlotType(candidate, desired)) {
                    foundSlot = i;
                    break;
                }
            }
            if (foundSlot == -1) continue;

            // SWAP: moves item at foundSlot into hotbar slot hotbarIdx
            SortUtils.interact(syncId, foundSlot, hotbarIdx, SlotActionType.SWAP);
        }
    }

    private boolean matchesSlotType(ItemStack stack, SlotType type) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return switch (type) {
            case SWORD    -> id.endsWith("_sword");
            case AXE      -> id.endsWith("_axe");
            case BOW      -> id.equals("bow");
            case CROSSBOW -> id.equals("crossbow");
            case SHIELD   -> id.equals("shield") || id.endsWith("_shield");
            case TOTEM    -> stack.getItem() == Items.TOTEM_OF_UNDYING;
            case POTION   -> stack.getComponents().contains(DataComponentTypes.POTION_CONTENTS)
                             && !id.endsWith("_arrow");
            case FOOD     -> stack.getComponents().contains(DataComponentTypes.FOOD);
            case BLOCK    -> stack.getItem() instanceof BlockItem;
            default       -> false;
        };
    }

    // ── Container Sorter ─────────────────────────────────────────────

    private void handleContainerSort() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler instanceof PlayerScreenHandler) return;

        // Container slots = total slots minus the 36 player inventory slots
        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return;

        if (ctSortOnOpen.get()) {
            SortUtils.sortSlotRange(handler, 0, containerSize - 1);
        }

        if (ctRestock.get()) {
            restockFromContainer(handler, containerSize);
        }
    }

    private void restockFromContainer(ScreenHandler handler, int containerSize) {
        int syncId = handler.syncId;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            SortUtils.ItemCategory cat = SortUtils.getCategory(stack);
            boolean shouldRestock = switch (cat) {
                case POTION -> ctRestockPotions.get();
                case FOOD   -> ctRestockFood.get();
                case BLOCK  -> ctRestockBlocks.get();
                case MISC   -> ctRestockArrows.get() && id.endsWith("_arrow");
                default     -> false;
            };

            if (shouldRestock && !playerHasItem(stack.getItem())) {
                SortUtils.shiftClick(syncId, i);
            }
        }
    }

    private boolean playerHasItem(Item target) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isOf(target)) return true;
        }
        return false;
    }
}
