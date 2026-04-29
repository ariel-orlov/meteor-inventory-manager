package com.example.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Queue;

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

        // Elytra has an EQUIPPABLE component (CHEST slot) but is not combat armor
        if (id.equals("elytra")) return ItemCategory.MISC;

        EquipmentSlot slot = VersionCompat.getEquipSlot(stack);
        if (slot != null && VersionCompat.isBodyArmorSlot(slot)) return ItemCategory.ARMOR;

        if (id.endsWith("_sword") || id.equals("trident")) return ItemCategory.SWORD;
        if (id.endsWith("_axe"))   return ItemCategory.AXE;
        if (id.equals("bow"))      return ItemCategory.BOW;
        if (id.equals("crossbow")) return ItemCategory.CROSSBOW;
        if (item == Items.TOTEM_OF_UNDYING) return ItemCategory.TOTEM;
        if (id.equals("shield") || id.endsWith("_shield")) return ItemCategory.SHIELD;

        if (stack.getComponents().contains(DataComponentTypes.POTION_CONTENTS)
                && !id.endsWith("_arrow")) return ItemCategory.POTION;
        if (stack.getComponents().contains(DataComponentTypes.FOOD)) return ItemCategory.FOOD;

        if (id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe")
                || id.equals("fishing_rod") || id.equals("shears")
                || id.equals("flint_and_steel") || id.equals("carrot_on_a_stick")
                || id.equals("warped_fungus_on_a_stick"))
            return ItemCategory.TOOL;

        if (item instanceof BlockItem) return ItemCategory.BLOCK;

        return ItemCategory.MISC;
    }

    /** Material tier for sortable weapon/tool/armor items (higher = better). */
    private static int getMaterialTier(String id) {
        if (id.startsWith("netherite_")) return 5;
        if (id.startsWith("diamond_"))   return 4;
        if (id.startsWith("iron_"))      return 3;
        if (id.startsWith("stone_"))     return 2;
        if (id.startsWith("golden_") || id.startsWith("gold_")) return 1;
        if (id.startsWith("wooden_") || id.startsWith("leather_")) return 0;
        return -1;
    }

    /** Sum of all enchantment levels on a stack. */
    public static int enchantLevelSum(ItemStack stack) {
        var comp = stack.getEnchantments();
        int total = 0;
        for (var entry : comp.getEnchantmentEntries())
            total += entry.getIntValue();
        return total;
    }

    public static int scoreItem(ItemStack stack) {
        if (stack.isEmpty()) return Integer.MIN_VALUE;
        return getCategory(stack).priority * -1000 + enchantLevelSum(stack);
    }

    public static int scoreArmor(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        EquipmentSlot slot = VersionCompat.getEquipSlot(stack);
        if (slot == null || !VersionCompat.isBodyArmorSlot(slot)) return 0;

        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        int tier = getMaterialTier(id);
        if (tier < 0) tier = 0;
        return tier * 100 + enchantLevelSum(stack) * 10;
    }

    public static int compareItems(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        if (a.isEmpty()) return 1;
        if (b.isEmpty()) return -1;

        ItemCategory catA = getCategory(a), catB = getCategory(b);
        int catCmp = catA.priority - catB.priority;
        if (catCmp != 0) return catCmp;

        String idA = Registries.ITEM.getId(a.getItem()).getPath();
        String idB = Registries.ITEM.getId(b.getItem()).getPath();

        // Within the same category, prefer higher material tier first
        int tierCmp = getMaterialTier(idB) - getMaterialTier(idA);
        if (tierCmp != 0) return tierCmp;

        // Then prefer more enchants
        int enchCmp = enchantLevelSum(b) - enchantLevelSum(a);
        if (enchCmp != 0) return enchCmp;

        // Then group identical items alphabetically
        int idCmp = idA.compareTo(idB);
        if (idCmp != 0) return idCmp;

        return b.getCount() - a.getCount(); // larger stacks first
    }

    public static void interact(int syncId, int slotId, int button, SlotActionType type) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(syncId, slotId, button, type, mc.player);
    }

    public static void shiftClick(int syncId, int slotId) {
        interact(syncId, slotId, 0, SlotActionType.QUICK_MOVE);
    }

    // ── Combined sort + merge ─────────────────────────────────────────────────
    //
    // Both passes share the same local[] array so merge is planned against the
    // post-sort item positions, not the pre-sort snapshot. This is the correct
    // version to call — the separate enqueueSortRange / enqueueMergeStacks are
    // kept below for standalone use cases.

    public static void enqueueSortAndMerge(Queue<Runnable> queue, ScreenHandler handler, int start, int end) {
        if (mc.player == null) return;
        int syncId = handler.syncId;
        int count  = end - start + 1;
        if (count <= 1) return;

        ItemStack[] local = new ItemStack[count];
        for (int i = 0; i < count; i++)
            local[i] = handler.getSlot(start + i).getStack().copy();

        // Selection sort — updates local[] in place alongside queued swap actions
        for (int i = 0; i < count - 1; i++) {
            int best = i;
            for (int j = i + 1; j < count; j++)
                if (compareItems(local[j], local[best]) < 0) best = j;
            if (best != i)
                enqueueSwap(queue, syncId, start + i, start + best, local, i, best);
        }

        // Merge pass on the now-sorted local[] state.
        // Same-type items are adjacent after sort, so break-on-mismatch is correct.
        for (int i = 0; i < count; i++) {
            if (local[i].isEmpty() || local[i].getCount() >= local[i].getMaxCount()) continue;
            for (int j = i + 1; j < count; j++) {
                if (local[j].isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(local[i], local[j])) break;
                int space = local[i].getMaxCount() - local[i].getCount();
                int take  = Math.min(space, local[j].getCount());
                final int si = start + i, sj = start + j;
                queue.add(() -> interact(syncId, sj, 0, SlotActionType.PICKUP));
                queue.add(() -> interact(syncId, si, 0, SlotActionType.PICKUP));
                int leftover = local[j].getCount() - take;
                if (leftover > 0) {
                    queue.add(() -> interact(syncId, sj, 0, SlotActionType.PICKUP));
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

    // ── Standalone sort / merge (for cases where one pass is needed alone) ───

    public static void enqueueSortRange(Queue<Runnable> queue, ScreenHandler handler, int start, int end) {
        if (mc.player == null) return;
        int syncId = handler.syncId;
        int count  = end - start + 1;
        if (count <= 1) return;

        ItemStack[] local = new ItemStack[count];
        for (int i = 0; i < count; i++)
            local[i] = handler.getSlot(start + i).getStack().copy();

        for (int i = 0; i < count - 1; i++) {
            int best = i;
            for (int j = i + 1; j < count; j++)
                if (compareItems(local[j], local[best]) < 0) best = j;
            if (best != i)
                enqueueSwap(queue, syncId, start + i, start + best, local, i, best);
        }
    }

    public static void enqueueMergeStacks(Queue<Runnable> queue, ScreenHandler handler, int start, int end) {
        if (mc.player == null) return;
        int syncId = handler.syncId;
        int count  = end - start + 1;
        if (count <= 1) return;

        ItemStack[] local = new ItemStack[count];
        for (int i = 0; i < count; i++)
            local[i] = handler.getSlot(start + i).getStack().copy();

        for (int i = 0; i < count; i++) {
            if (local[i].isEmpty() || local[i].getCount() >= local[i].getMaxCount()) continue;
            for (int j = i + 1; j < count; j++) {
                if (local[j].isEmpty()) continue;
                // Use continue (not break) — the range may not be sorted
                if (!ItemStack.areItemsAndComponentsEqual(local[i], local[j])) continue;
                int space = local[i].getMaxCount() - local[i].getCount();
                int take  = Math.min(space, local[j].getCount());
                final int si = start + i, sj = start + j;
                queue.add(() -> interact(syncId, sj, 0, SlotActionType.PICKUP));
                queue.add(() -> interact(syncId, si, 0, SlotActionType.PICKUP));
                int leftover = local[j].getCount() - take;
                if (leftover > 0) {
                    queue.add(() -> interact(syncId, sj, 0, SlotActionType.PICKUP));
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

    private static void enqueueSwap(Queue<Runnable> queue, int syncId,
                                     int slotA, int slotB, ItemStack[] local, int idxA, int idxB) {
        ItemStack a = local[idxA], b = local[idxB];
        if (a.isEmpty() && b.isEmpty()) return;
        if (a.isEmpty()) {
            queue.add(() -> interact(syncId, slotB, 0, SlotActionType.PICKUP));
            queue.add(() -> interact(syncId, slotA, 0, SlotActionType.PICKUP));
        } else if (b.isEmpty()) {
            queue.add(() -> interact(syncId, slotA, 0, SlotActionType.PICKUP));
            queue.add(() -> interact(syncId, slotB, 0, SlotActionType.PICKUP));
        } else {
            queue.add(() -> interact(syncId, slotA, 0, SlotActionType.PICKUP));
            queue.add(() -> interact(syncId, slotB, 0, SlotActionType.PICKUP));
            queue.add(() -> interact(syncId, slotA, 0, SlotActionType.PICKUP));
        }
        local[idxA] = b;
        local[idxB] = a;
    }
}
