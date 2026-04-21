package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keybind-triggered one-shot inventory sorter.
 * Press the module keybind → sorts → auto-disables.
 *
 * Supported containers: chests, barrels, ender chests, shulker boxes,
 * dispensers, droppers, hoppers (server-safe via configurable delay).
 */
public class InvSort extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between inventory actions. Raise this on servers with anti-cheat (4–6 is safe).")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> sortContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-containers")
        .description("Sort the open container when one is available.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-player")
        .description("Sort your main inventory (hotbar excluded).")
        .defaultValue(true)
        .build()
    );

    private final Deque<int[]> actionQueue = new ArrayDeque<>();
    private int timer = 0;

    public InvSort() {
        super(AddonTemplate.CATEGORY, "inv-sort",
            "Sorts your inventory and open containers. Press the keybind to trigger.");
    }

    @Override
    public void onActivate() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open your inventory or a container first.");
            toggle();
            return;
        }

        clearCursor();

        if (sortContainers.get()) {
            actionQueue.addAll(buildSortPlan(getContainerSlots(screen)));
        }

        if (sortPlayer.get()) {
            actionQueue.addAll(buildSortPlan(getPlayerMainSlots(screen)));
        }

        // Execute first action on the very first tick
        timer = delay.get();

        if (actionQueue.isEmpty()) {
            info("Nothing to sort.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        actionQueue.clear();
        timer = 0;
        if (mc.player != null && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            InvUtils.dropHand();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Cancel if the screen was closed mid-sort
        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            toggle();
            return;
        }

        if (actionQueue.isEmpty()) {
            toggle();
            return;
        }

        if (timer < delay.get()) {
            timer++;
            return;
        }
        timer = 0;

        int[] action = actionQueue.poll();
        InvUtils.move().fromId(action[0]).toId(action[1]);
    }

    // ── Slot collection ────────────────────────────────────────────────────

    /**
     * Returns sortable slots for the open container.
     * Supported: generic chests/barrels/ender chests, shulker boxes,
     * dispensers/droppers (3×3), and hoppers.
     */
    private List<SlotEntry> getContainerSlots(HandledScreen<?> screen) {
        if (!(screen instanceof GenericContainerScreen)
            && !(screen instanceof ShulkerBoxScreen)
            && !(screen instanceof Generic3x3ContainerScreen)
            && !(screen instanceof HopperScreen)) {
            return List.of();
        }

        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            // All non-player slots in these screens are sortable item storage
            if (!(slot.inventory instanceof PlayerInventory)) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    /** Returns player main inventory slots (indices 9–35, hotbar excluded). */
    private List<SlotEntry> getPlayerMainSlots(HandledScreen<?> screen) {
        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) {
                int index = ((ISlot) slot).meteor$getIndex();
                if (SlotUtils.isMain(index)) {
                    result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
                }
            }
        }
        return result;
    }

    // ── Sort plan ──────────────────────────────────────────────────────────

    private List<int[]> buildSortPlan(List<SlotEntry> slots) {
        if (slots.isEmpty()) return List.of();

        // Work on copies so simulation doesn't touch real slot objects
        List<SlotEntry> working = new ArrayList<>(slots.size());
        for (SlotEntry s : slots) working.add(new SlotEntry(s.id, s.stack.copy()));

        List<int[]> actions = new ArrayList<>();
        stackPhase(working, actions);
        sortPhase(working, actions);
        return actions;
    }

    /**
     * Phase 1 — Stacking: merge partial stacks of identical items.
     * Each action moves 'source' into 'target'; Minecraft merges them automatically.
     */
    private void stackPhase(List<SlotEntry> slots, List<int[]> actions) {
        for (int i = 0; i < slots.size(); i++) {
            SlotEntry target = slots.get(i);
            if (target.stack.isEmpty()
                || !target.stack.isStackable()
                || target.stack.getCount() >= target.stack.getMaxCount()) continue;

            for (int j = i + 1; j < slots.size(); j++) {
                SlotEntry source = slots.get(j);
                if (source.stack.isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(target.stack, source.stack)) continue;

                actions.add(new int[]{source.id, target.id});

                int combined = target.stack.getCount() + source.stack.getCount();
                int max = target.stack.getMaxCount();
                if (combined <= max) {
                    target.stack = target.stack.copyWithCount(combined);
                    source.stack = ItemStack.EMPTY;
                } else {
                    source.stack = source.stack.copyWithCount(combined - max);
                    target.stack = target.stack.copyWithCount(max);
                    break; // target full — advance to next target
                }
                if (target.stack.getCount() >= target.stack.getMaxCount()) break;
            }
        }
    }

    /**
     * Phase 2 — Sorting: selection-sort by registry ID → stack count → damage.
     * Each action is a swap; InvUtils.move() handles the displaced item automatically.
     */
    private void sortPhase(List<SlotEntry> slots, List<int[]> actions) {
        for (int i = 0; i < slots.size(); i++) {
            int bestIdx = i;
            for (int j = i + 1; j < slots.size(); j++) {
                if (isBetter(slots.get(j), slots.get(bestIdx))) bestIdx = j;
            }
            if (bestIdx != i && !slots.get(bestIdx).stack.isEmpty()) {
                actions.add(new int[]{slots.get(bestIdx).id, slots.get(i).id});
                ItemStack tmp = slots.get(i).stack;
                slots.get(i).stack = slots.get(bestIdx).stack;
                slots.get(bestIdx).stack = tmp;
            }
        }
    }

    private boolean isBetter(SlotEntry candidate, SlotEntry current) {
        ItemStack c = candidate.stack;
        ItemStack b = current.stack;
        if (b.isEmpty() && !c.isEmpty()) return true;
        if (!b.isEmpty() && c.isEmpty()) return false;
        int cmp = Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
        if (cmp == 0) {
            if (c.getCount() != b.getCount()) return c.getCount() > b.getCount();
            return c.getDamage() > b.getDamage();
        }
        return cmp > 0;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Places any cursor-held item back into an empty slot before sorting.
     * Drops to ground as a last resort to avoid corrupting the action plan.
     */
    private void clearCursor() {
        if (mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        FindItemResult empty = InvUtils.findEmpty();
        if (empty.found()) InvUtils.click().slot(empty.slot());
        else InvUtils.click().slotId(-999);
    }

    private static class SlotEntry {
        final int id;
        ItemStack stack;

        SlotEntry(int id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
        }
    }
}
