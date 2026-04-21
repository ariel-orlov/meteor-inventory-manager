package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
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
 * Persistent inventory sorter. Enable the module, then press the sort
 * keybind while any inventory or container is open to trigger a sort.
 * Mode and direction can be changed with hotkeys at any time.
 *
 * Supported containers: chests, barrels, ender chests (generic),
 * shulker boxes, dispensers/droppers (3x3), hoppers, horses with chests.
 */
public class InvSort extends Module {

    // ── Sort mode ──────────────────────────────────────────────────────────

    public enum SortMode {
        REGISTRY("Registry ID"),   // deterministic order by internal item path
        NAME("Display Name"),      // alphabetical by translated item name
        COUNT("Stack Count");      // largest stacks first

        private final String label;
        SortMode(String label) { this.label = label; }

        @Override
        public String toString() { return label; }

        public SortMode next() {
            SortMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSort    = settings.createGroup("Sort Behaviour");
    private final SettingGroup sgHotkeys = settings.createGroup("Hotkeys");

    // --- General ---

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between inventory actions. Raise on servers with anti-cheat (4-6 is safe).")
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

    private final Setting<Boolean> autoSort = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sort")
        .description("Automatically sort supported containers when you open them.")
        .defaultValue(false)
        .build()
    );

    // --- Sort Behaviour ---
    // stackOnly declared first so sortMode/reverseSort can safely reference it in visible().

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
        .description("Reverse the sort order (Z->A, or fewest items first for Count mode).")
        .defaultValue(false)
        .visible(() -> !stackOnly.get())
        .build()
    );

    // --- Hotkeys ---
    // These fire via @EventHandler while the module is enabled, so all keybinds
    // remain responsive as long as InvSort is toggled on.

    private final Setting<Keybind> sortKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Press to trigger a sort of the current screen.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> cycleModeKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("cycle-mode")
        .description("Cycle sort mode: Registry ID -> Display Name -> Stack Count -> repeat.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> toggleReverseKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("toggle-reverse")
        .description("Toggle between normal and reversed sort direction.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> toggleStackOnlyKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("toggle-stack-only")
        .description("Toggle stack-only mode on or off.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────

    private final Deque<int[]> actionQueue = new ArrayDeque<>();
    private int timer = 0;
    private int autoSortCountdown = -1; // -1 = not pending; counts down to 0 then triggers

    public InvSort() {
        super(AddonTemplate.CATEGORY, "inv-sort",
            "Persistent inventory sorter. Keep enabled and press the sort keybind.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onDeactivate() {
        cancelSort();
        autoSortCountdown = -1;
    }

    // ── Input handling ─────────────────────────────────────────────────────

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        if (sortKey.get().matches(event.input)) { triggerSort(); event.cancel(); }
        else if (cycleModeKey.get().matches(event.input)) cycleMode();
        else if (toggleReverseKey.get().matches(event.input)) toggleReverse();
        else if (toggleStackOnlyKey.get().matches(event.input)) toggleStackOnly();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        // Cancel mouse event for sort trigger to prevent pick-block or other default actions
        if (sortKey.get().matches(event.input)) { triggerSort(); event.cancel(); }
        else if (cycleModeKey.get().matches(event.input)) cycleMode();
        else if (toggleReverseKey.get().matches(event.input)) toggleReverse();
        else if (toggleStackOnlyKey.get().matches(event.input)) toggleStackOnly();
    }

    // ── Screen open — auto-sort trigger ───────────────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!autoSort.get()) return;
        if (event.screen instanceof CreativeInventoryScreen) return;

        boolean supported = event.screen instanceof GenericContainerScreen
            || event.screen instanceof ShulkerBoxScreen
            || event.screen instanceof Generic3x3ContainerScreen
            || event.screen instanceof HopperScreen
            || event.screen instanceof HorseScreen;

        if (supported) {
            cancelSort(); // clear any previous sort state
            autoSortCountdown = 3; // wait 3 ticks for the screen handler to initialise
        }
    }

    // ── Inventory packet — desync cancel ──────────────────────────────────

    @EventHandler
    private void onInventoryUpdate(InventoryEvent event) {
        if (actionQueue.isEmpty()) return;
        // If the server pushes a full inventory sync while we're sorting, our
        // action plan is stale. Cancel and let the user re-trigger.
        if (event.packet.getSyncId() == mc.player.currentScreenHandler.syncId) {
            cancelSort();
            info("Sort cancelled: inventory updated by server. Re-trigger to sort again.");
        }
    }

    // ── Tick — auto-sort countdown and action execution ───────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Auto-sort countdown: fires triggerSort() once the screen is ready
        if (autoSortCountdown > 0) {
            autoSortCountdown--;
            if (autoSortCountdown == 0) {
                autoSortCountdown = -1;
                triggerSort();
            }
        }

        if (actionQueue.isEmpty()) return;

        // Cancel if screen closed mid-sort
        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            cancelSort();
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

    // ── Sort trigger ───────────────────────────────────────────────────────

    private void triggerSort() {
        if (mc.player == null) return;

        // Creative inventory has a completely different slot layout
        if (mc.currentScreen instanceof CreativeInventoryScreen) {
            info("Sorting is not supported in the creative inventory.");
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open your inventory or a container first.");
            return;
        }

        // Cancel any in-progress sort before starting a new one
        cancelSort();
        clearCursor();

        if (sortContainers.get()) actionQueue.addAll(buildSortPlan(getContainerSlots(screen)));
        if (sortPlayer.get())     actionQueue.addAll(buildSortPlan(getPlayerSlots(screen)));

        timer = delay.get(); // fire first action on the very next tick

        if (actionQueue.isEmpty()) info("Nothing to sort.");
    }

    private void cancelSort() {
        actionQueue.clear();
        timer = 0;
        if (mc.player != null && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            InvUtils.dropHand();
        }
    }

    // ── Hotkey actions ─────────────────────────────────────────────────────

    private void cycleMode() {
        if (stackOnly.get()) { info("Disable stack-only first to change sort mode."); return; }
        SortMode next = sortMode.get().next();
        sortMode.set(next);
        info("Sort mode: %s.", next);
    }

    private void toggleReverse() {
        if (stackOnly.get()) { info("Disable stack-only first to change sort direction."); return; }
        boolean next = !reverseSort.get();
        reverseSort.set(next);
        info("Sort direction: %s.", next ? "reversed" : "normal");
    }

    private void toggleStackOnly() {
        boolean next = !stackOnly.get();
        stackOnly.set(next);
        info("Stack-only: %s.", next ? "on" : "off");
    }

    // ── Slot collection ────────────────────────────────────────────────────

    /**
     * Returns sortable container slots for supported screen types.
     * Supported: chests/barrels/ender chests (generic), shulker boxes,
     * dispensers/droppers (3x3), hoppers, horses with chests.
     * Returns an empty list for unsupported screens (furnace, crafting table, etc.).
     */
    private List<SlotEntry> getContainerSlots(HandledScreen<?> screen) {
        if (screen instanceof GenericContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof Generic3x3ContainerScreen
            || screen instanceof HopperScreen) {
            return collectNonPlayerSlots(screen);
        }

        if (screen instanceof HorseScreen horseScreen) {
            return getHorseChestSlots(horseScreen);
        }

        return List.of();
    }

    /** Collects all non-PlayerInventory slots from the screen's handler. */
    private List<SlotEntry> collectNonPlayerSlots(HandledScreen<?> screen) {
        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    /**
     * Returns horse chest slots, skipping slot 0 (saddle) and slot 1 (armor).
     * Only collects if the horse has a chest (inventory size > 2).
     */
    private List<SlotEntry> getHorseChestSlots(HorseScreen screen) {
        List<SlotEntry> result = new ArrayList<>();
        boolean pastEquipment = false;
        int equipmentSeen = 0;

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;

            // First two non-player slots are saddle and armor — skip them
            if (equipmentSeen < 2) {
                equipmentSeen++;
                continue;
            }

            pastEquipment = true;
            result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
        }

        // If no chest slots were found beyond equipment, return empty
        return pastEquipment ? result : List.of();
    }

    /**
     * Returns player inventory slots. Always includes main (indices 9-35).
     * Optionally includes hotbar (0-8) when sort-hotbar is enabled.
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
     * Returns true if candidate should be placed before current.
     * Primary comparison uses sort mode and respects reverseSort.
     * Tiebreakers are always ascending (registry -> count -> damage) for stability.
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

        // Stable tiebreaker — unaffected by reverseSort
        cmp = Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
        if (cmp != 0) return cmp > 0;
        if (c.getCount() != b.getCount()) return c.getCount() > b.getCount();
        return c.getDamage() > b.getDamage();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Moves any cursor-held item to an empty slot before sorting.
     * Falls back to dropping it on the ground if the inventory is full.
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
