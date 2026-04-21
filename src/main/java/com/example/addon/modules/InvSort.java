package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.InventorySorter;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayDeque;
import java.util.Deque;

public class InvSort extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between each inventory action.")
        .defaultValue(2)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> sortContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-containers")
        .description("Sort the open container (chest, shulker box) when one is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-player")
        .description("Sort your main inventory (excludes hotbar).")
        .defaultValue(true)
        .build()
    );

    private final Deque<InventorySorter> sorterQueue = new ArrayDeque<>();

    public InvSort() {
        super(AddonTemplate.CATEGORY, "inv-sort", "Sorts your inventory and/or open container. Toggle with keybind to trigger.");
    }

    @Override
    public void onActivate() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open your inventory or a container first.");
            toggle();
            return;
        }

        if (sortContainers.get()) {
            Slot containerSlot = findContainerSlot(screen);
            if (containerSlot != null) sorterQueue.add(new InventorySorter(screen, containerSlot));
        }

        if (sortPlayer.get()) {
            Slot playerSlot = findPlayerMainSlot(screen);
            if (playerSlot != null) sorterQueue.add(new InventorySorter(screen, playerSlot));
        }

        if (sorterQueue.isEmpty()) {
            info("Nothing to sort.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        sorterQueue.clear();
        // Drop any item stuck on the cursor from mid-sort
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

        InventorySorter current = sorterQueue.peek();
        if (current == null) {
            toggle();
            return;
        }

        if (current.tick(delay.get())) sorterQueue.poll();
    }

    private Slot findPlayerMainSlot(HandledScreen<?> screen) {
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) {
                int index = ((ISlot) slot).meteor$getIndex();
                if (SlotUtils.isMain(index)) return slot;
            }
        }
        return null;
    }

    private Slot findContainerSlot(HandledScreen<?> screen) {
        if (!(screen instanceof GenericContainerScreen) && !(screen instanceof ShulkerBoxScreen)) return null;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof SimpleInventory) return slot;
        }
        return null;
    }
}
