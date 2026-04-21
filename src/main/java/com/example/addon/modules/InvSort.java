package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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

    // ── Sort mode enum ─────────────────────────────────────────────────────

    public enum SortMode {
        REGISTRY("Registry ID"),   // deterministic order based on item registry path
        NAME("Display Name"),      // alphabetical by translated item name
        COUNT("Stack Count");      // largest stacks placed first

        private final String label;
        SortMode(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    // ── Settings ───────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSort    = settings.createGroup("Sort Behaviour");

    // General
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between inventory actions. Raise this on servers with anti-cheat (4-6 is safe).")
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
        .description("Sort your main inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-hotbar")
        .description("Include hotbar slots in the player inventory sort.")
        .defaultValue(false)
        .visible(sortPlayer::get)
        .build()
    );

    // Sort behaviour — stackOnly must be declared before sortMode/reverseSort
    // so their visible() lambdas can reference it safely.
    private final Setting<Boolean> stackOnly = sgSort.add(new BoolSetting.Builder()
        .name("stack-only")
        .description("Only merge partial stacks; skip reordering entirely.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SortMode> sortMode = sgSort.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Primary criterion used to order items.")
        .defaultValue(SortMode.REGISTRY)
        .visible(() -> !stackOnly.get())
        .build()
    );

    private final Setting<Boolean> reverseSort = sgSort.add(new BoolSetting.Builder()
        .name("reverse")
        .description("Reverse the sort order (e.g. Z->A, or fewest items first).")
        .defaultValue(false)
        .visible(() -> !stackOnly.get())
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────

    private final Deque<int[]> actionQueue = new ArrayDeque<>();
    private int timer = 0;

    public InvSort() {
        super(AddonTemplate.CATEGORY, "inv-sort",
            "Sorts your inventory and open containers. Press the keybind to trigger.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

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
            actionQueue.addAll(buildSortPlan(getPlayerSlots(screen)));
        }

        timer = delay.get(); // fire first action on the very first tick

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
     * Returns sortable container slots for supported screen types.
     * Supported: chests/barrels/ender chests (generic), shulker boxes,
     * dispensers/droppers (3x3), hoppers.
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
            if (!(slot.inventory instanceof PlayerInventory)) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    /**
     * Returns player inventory slots. Always includes main (indices 9-35).
     * Optionally includes hotbar (indices 0-8) when sort-hotbar is enabled.
     */
    private List<SlotEntry> getPlayerSlots(HandledScreen<?> screen) {
        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            int index = ((ISlot) slot).meteor$getIndex();
            if (SlotUtils.isMain(index) || (sortHotbar.get() && SlotUtils.isHotbar(index))) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    // ── Sort plan ──────────────────────────────────────────────────────────

    private List<int[]> buildSortPlan(List<SlotEntry> slots) {
        if (slots.isEmpty()) return List.of();

        List<SlotEntry> working = new ArrayList<>(slots.size());
        for (SlotEntry s : slots) working.add(new SlotEntry(s.id, s.stack.copy()));

        List<int[]> actions = new ArrayList<>();
        stackPhase(working, actions);
        if (!stackOnly.get()) sortPhase(working, actions);
        return actions;
    }

    /** Phase 1: merge partial stacks of identical items. */
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
                    break;
                }
                if (target.stack.getCount() >= target.stack.getMaxCount()) break;
            }
        }
    }

    /** Phase 2: selection-sort using the configured sort mode and direction. */
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

    /**
     * Returns true if {@code candidate} should be placed before {@code current}.
     * Primary comparison uses the configured sort mode and direction.
     * Ties are broken by registry ID → count → damage (always ascending, for stability).
     */
    private boolean isBetter(SlotEntry candidate, SlotEntry current) {
        ItemStack c = candidate.stack;
        ItemStack b = current.stack;
        if (b.isEmpty() && !c.isEmpty()) return true;
        if (!b.isEmpty() && c.isEmpty()) return false;

        int cmp = switch (sortMode.get()) {
            case REGISTRY -> Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
            case NAME     -> b.getName().getString().compareToIgnoreCase(c.getName().getString());
            case COUNT    -> Integer.compare(c.getCount(), b.getCount()); // natural = most first
        };

        if (cmp != 0) return reverseSort.get() ? cmp < 0 : cmp > 0;

        // Stable tiebreaker — not affected by reverseSort
        cmp = Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
        if (cmp != 0) return cmp > 0;
        if (c.getCount() != b.getCount()) return c.getCount() > b.getCount();
        return c.getDamage() > b.getDamage();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Moves any cursor-held item to an empty slot before sorting starts.
     * Falls back to dropping it on the ground if the inventory is completely full.
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
