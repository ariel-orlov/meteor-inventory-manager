package com.example.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
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
        String id = Registries.ITEM.getId(item).getPath();

        // Armor and shield via equippable component (1.21.2+)
        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable != null) {
            EquipmentSlot slot = equippable.slot();
            if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                    || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
                return ItemCategory.ARMOR;
            }
        }

        // Weapons by ID suffix (robust across refactors)
        if (id.endsWith("_sword")) return ItemCategory.SWORD;
        if (id.endsWith("_axe"))   return ItemCategory.AXE;
        if (id.equals("bow"))      return ItemCategory.BOW;
        if (id.equals("crossbow")) return ItemCategory.CROSSBOW;
        if (item == Items.TOTEM_OF_UNDYING) return ItemCategory.TOTEM;
        if (id.equals("shield") || id.endsWith("_shield")) return ItemCategory.SHIELD;

        // Potions via component (excludes tipped arrows)
        if (stack.getComponents().contains(DataComponentTypes.POTION_CONTENTS)
                && !id.endsWith("_arrow")) return ItemCategory.POTION;

        // Food via component
        if (stack.getComponents().contains(DataComponentTypes.FOOD)) return ItemCategory.FOOD;

        // Tools by ID suffix
        if (id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe"))
            return ItemCategory.TOOL;

        // Blocks
        if (item instanceof BlockItem) return ItemCategory.BLOCK;

        return ItemCategory.MISC;
    }

    public static int scoreItem(ItemStack stack) {
        if (stack.isEmpty()) return Integer.MIN_VALUE;
        int base = getCategory(stack).priority * -1000;
        return base + stack.getEnchantments().getSize();
    }

    public static int scoreArmor(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) return 0;
        EquipmentSlot slot = equippable.slot();
        if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
                && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) return 0;

        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        int tier = 0;
        if      (id.startsWith("netherite_")) tier = 6;
        else if (id.startsWith("diamond_"))   tier = 5;
        else if (id.startsWith("iron_"))      tier = 4;
        else if (id.startsWith("chainmail_")) tier = 3;
        else if (id.startsWith("golden_"))    tier = 2;
        else if (id.startsWith("leather_"))   tier = 1;

        return tier * 100 + stack.getEnchantments().getSize() * 10;
    }

    public static int compareItems(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        if (a.isEmpty()) return 1;
        if (b.isEmpty()) return -1;
        int catCmp = getCategory(a).priority - getCategory(b).priority;
        if (catCmp != 0) return catCmp;
        // Group identical items together (so same-type stacks end up adjacent)
        String idA = Registries.ITEM.getId(a.getItem()).getPath();
        String idB = Registries.ITEM.getId(b.getItem()).getPath();
        int idCmp = idA.compareTo(idB);
        if (idCmp != 0) return idCmp;
        return b.getCount() - a.getCount(); // larger stacks first
    }

    public static void mergeStacks(ScreenHandler handler, int start, int end) {
        if (mc.player == null) return;
        int syncId = handler.syncId;
        int count = end - start + 1;
        ItemStack[] local = new ItemStack[count];
        for (int i = 0; i < count; i++)
            local[i] = handler.getSlot(start + i).getStack().copy();

        for (int i = 0; i < count; i++) {
            if (local[i].isEmpty() || local[i].getCount() >= local[i].getMaxCount()) continue;
            for (int j = i + 1; j < count; j++) {
                if (local[j].isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(local[i], local[j])) break;
                int space = local[i].getMaxCount() - local[i].getCount();
                int take = Math.min(space, local[j].getCount());
                interact(syncId, start + j, 0, SlotActionType.PICKUP);
                interact(syncId, start + i, 0, SlotActionType.PICKUP);
                int leftover = local[j].getCount() - take;
                if (leftover > 0) {
                    interact(syncId, start + j, 0, SlotActionType.PICKUP);
                    local[j] = local[j].copy();
                    local[j].setCount(leftover);
                } else {
                    local[j] = ItemStack.EMPTY;
                }
                local[i] = local[i].copy();
                local[i].setCount(local[i].getCount() + take);
                if (local[i].getCount() >= local[i].getMaxCount()) break;
            }
        }
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
