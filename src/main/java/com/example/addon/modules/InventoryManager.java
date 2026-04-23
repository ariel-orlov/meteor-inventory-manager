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

public class InventoryManager extends Module {

    public enum Trigger { ON_KEYBIND, ON_OPEN, CONTINUOUS }
    public enum LoadoutTrigger { ON_KEYBIND, ON_RESPAWN }
    public enum SlotType { AUTO, SWORD, AXE, BOW, CROSSBOW, POTION, FOOD, SHIELD, TOTEM, BLOCK, EMPTY }

    private static final SlotType[] DEFAULT_HOTBAR = {
        SlotType.SWORD, SlotType.AXE, SlotType.BOW,
        SlotType.POTION, SlotType.POTION, SlotType.FOOD,
        SlotType.SHIELD, SlotType.TOTEM, SlotType.BLOCK
    };

    // ── Inventory Sorter ─────────────────────────────────────────────
    private final SettingGroup sgInv = settings.createGroup("Inventory Sorter");

    private final Setting<Boolean> invEnabled = sgInv.add(new BoolSetting.Builder()
        .name("enabled").description("Enable inventory sorting.").defaultValue(true).build());

    private final Setting<Trigger> invTrigger = sgInv.add(new EnumSetting.Builder<Trigger>()
        .name("trigger").description("When to sort the player inventory.")
        .defaultValue(Trigger.ON_OPEN).build());

    private final Setting<Integer> invDelay = sgInv.add(new IntSetting.Builder()
        .name("continuous-delay").description("Ticks between sorts in Continuous mode.")
        .defaultValue(40).min(5).sliderMax(200)
        .visible(() -> invTrigger.get() == Trigger.CONTINUOUS).build());

    private final Setting<Keybind> invKeybind = sgInv.add(new KeybindSetting.Builder()
        .name("sort-keybind").description("Key to trigger inventory sort.")
        .defaultValue(Keybind.none())
        .visible(() -> invTrigger.get() == Trigger.ON_KEYBIND).build());

    // ── PvP Loadout ──────────────────────────────────────────────────
    private final SettingGroup sgPvp = settings.createGroup("PvP Loadout");

    private final Setting<Boolean> pvpEnabled = sgPvp.add(new BoolSetting.Builder()
        .name("enabled").description("Enable PvP loadout management.").defaultValue(true).build());

    private final Setting<Boolean> pvpAutoArmor = sgPvp.add(new BoolSetting.Builder()
        .name("auto-equip-armor").description("Automatically equip the best armor from inventory.")
        .defaultValue(true).build());

    private final Setting<LoadoutTrigger> pvpTrigger = sgPvp.add(new EnumSetting.Builder<LoadoutTrigger>()
        .name("trigger").description("When to apply the PvP loadout.")
        .defaultValue(LoadoutTrigger.ON_KEYBIND).build());

    private final Setting<Keybind> pvpKeybind = sgPvp.add(new KeybindSetting.Builder()
        .name("loadout-keybind").description("Key to apply PvP loadout.")
        .defaultValue(Keybind.none())
        .visible(() -> pvpTrigger.get() == LoadoutTrigger.ON_KEYBIND).build());

    private final List<Setting<SlotType>> hotbarSlots = new ArrayList<>();

    // ── Container Sorter ─────────────────────────────────────────────
    private final SettingGroup sgContainer = settings.createGroup("Container Sorter");

    private final Setting<Boolean> ctEnabled = sgContainer.add(new BoolSetting.Builder()
        .name("enabled").description("Enable container sorting.").defaultValue(true).build());

    private final Setting<Boolean> ctSortOnOpen = sgContainer.add(new BoolSetting.Builder()
        .name("sort-on-open").description("Sort container contents when you open it.")
        .defaultValue(true).build());

    private final Setting<Boolean> ctRestock = sgContainer.add(new BoolSetting.Builder()
        .name("restock").description("Pull items from container into your inventory.")
        .defaultValue(true).build());

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
    private int invTickCounter = 0;
    private boolean invKeyLastPressed = false;
    private boolean pvpKeyLastPressed = false;
    private boolean wasDead = false;
    private boolean containerSortPending = false;

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

    // ── Events ───────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        handleInvSorterTick();
        handlePvpLoadoutTick();
        handleContainerTick();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;

        if (invEnabled.get() && invTrigger.get() == Trigger.ON_OPEN
                && event.screen instanceof InventoryScreen) {
            sortPlayerInventory();
        }

        if (ctEnabled.get()
                && event.screen instanceof HandledScreen<?>
                && !(event.screen instanceof InventoryScreen)) {
            containerSortPending = true;
        }
    }

    // ── Inventory Sorter ─────────────────────────────────────────────

    private void handleInvSorterTick() {
        if (!invEnabled.get()) return;
        if (invTrigger.get() == Trigger.CONTINUOUS) {
            if (++invTickCounter >= invDelay.get()) {
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
        SortUtils.sortSlotRange(mc.player.playerScreenHandler, 9, 35);
    }

    // ── PvP Loadout ──────────────────────────────────────────────────

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
        int[] armorScreenSlots = {5, 6, 7, 8};

        for (int s = 0; s < 4; s++) {
            EquipmentSlot eqSlot = armorSlots[s];
            int armorScreenSlot = armorScreenSlots[s];
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

            ItemStack current = handler.getSlot(36 + hotbarIdx).getStack();
            if (!current.isEmpty() && matchesSlotType(current, desired)) continue;

            int foundSlot = -1;
            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (!candidate.isEmpty() && matchesSlotType(candidate, desired)) {
                    foundSlot = i;
                    break;
                }
            }
            if (foundSlot == -1) continue;
            SortUtils.interact(syncId, foundSlot, hotbarIdx, SlotActionType.SWAP);
        }
    }

    private boolean matchesSlotType(ItemStack stack, SlotType type) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return switch (type) {
            case SWORD -> id.endsWith("_sword");
            case AXE -> id.endsWith("_axe");
            case BOW -> id.equals("bow");
            case CROSSBOW -> id.equals("crossbow");
            case SHIELD -> id.equals("shield") || id.endsWith("_shield");
            case TOTEM -> stack.getItem() == Items.TOTEM_OF_UNDYING;
            case POTION -> stack.getComponents().contains(DataComponentTypes.POTION_CONTENTS)
                           && !id.endsWith("_arrow");
            case FOOD -> stack.getComponents().contains(DataComponentTypes.FOOD);
            case BLOCK -> stack.getItem() instanceof BlockItem;
            default -> false;
        };
    }

    // ── Container Sorter ─────────────────────────────────────────────

    private void handleContainerTick() {
        if (!containerSortPending) return;
        containerSortPending = false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler instanceof PlayerScreenHandler) return;

        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return;

        if (ctSortOnOpen.get()) SortUtils.sortSlotRange(handler, 0, containerSize - 1);
        if (ctRestock.get()) restockFromContainer(handler, containerSize);
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

            if (shouldRestock && !playerAlreadyHas(stack)) SortUtils.shiftClick(syncId, i);
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
}
