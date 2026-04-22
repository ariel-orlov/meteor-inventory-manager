package com.example.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

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
}
