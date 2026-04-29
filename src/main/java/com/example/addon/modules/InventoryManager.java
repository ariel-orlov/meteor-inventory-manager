package com.example.addon.modules;

import com.example.addon.InventoryManagerAddon;
import com.example.addon.utils.SortUtils;
import com.example.addon.utils.VersionCompat;
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
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;

import java.util.*;

public class InventoryManager extends Module {

    public enum SlotType {
        AUTO, SWORD, AXE, BOW, CROSSBOW, POTION, FOOD, SHIELD, TOTEM, BLOCK, EMPTY
    }

    private static final SlotType[] DEFAULT_HOTBAR = {
        SlotType.SWORD, SlotType.AXE, SlotType.BOW,
        SlotType.POTION, SlotType.POTION, SlotType.FOOD,
        SlotType.SHIELD, SlotType.TOTEM, SlotType.BLOCK
    };

    // ── Inventory Sorter ─────────────────────────────────────────────────────
    private final SettingGroup sgInv = settings.createGroup("Inventory Sorter");

    private final Setting<Keybind> sortKey = sgInv.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Press to sort the open inventory or container on demand.")
        .defaultValue(Keybind.none())
        .action(this::doSort)
        .build());

    private final Setting<Boolean> invSortOnOpen = sgInv.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Automatically sort your inventory when you open it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sortHotbar = sgInv.add(new BoolSetting.Builder()
        .name("sort-hotbar")
        .description("Also sort hotbar slots (as a separate pass, items won't cross regions).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> invContinuous = sgInv.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously re-sort inventory while the module is on.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> invDelay = sgInv.add(new IntSetting.Builder()
        .name("continuous-delay")
        .description("Ticks between continuous sorts.")
        .defaultValue(40).min(5).sliderMax(200)
        .visible(() -> invContinuous.get())
        .build());

    private final Setting<Integer> actionsPerTick = sgInv.add(new IntSetting.Builder()
        .name("actions-per-tick")
        .description("Slot clicks per tick. Lower = safer on strict anti-cheat servers (1–4 recommended).")
        .defaultValue(4).min(1).sliderMax(20)
        .build());

    // ── PvP Loadout ──────────────────────────────────────────────────────────
    private final SettingGroup sgPvp = settings.createGroup("PvP Loadout");

    private final Setting<Boolean> pvpEquipArmor = sgPvp.add(new BoolSetting.Builder()
        .name("equip-armor")
        .description("Auto-equip best armor on activate and every second while active.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pvpArrangeHotbar = sgPvp.add(new BoolSetting.Builder()
        .name("arrange-hotbar")
        .description("Move best PvP items to configured hotbar slots on activate.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pvpOnRespawn = sgPvp.add(new BoolSetting.Builder()
        .name("on-respawn")
        .description("Re-apply PvP loadout automatically after you respawn.")
        .defaultValue(false)
        .build());

    private final List<Setting<SlotType>> hotbarSlots = new ArrayList<>();

    // ── Container Sorter ─────────────────────────────────────────────────────
    private final SettingGroup sgContainer = settings.createGroup("Container Sorter");

    private final Setting<Boolean> ctSortOnOpen = sgContainer.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Sort container contents when you open a chest, barrel, shulker box, etc.")
        .defaultValue(true)
        .build());

    private final Setting<Keybind> depositKey = sgContainer.add(new KeybindSetting.Builder()
        .name("deposit-key")
        .description("While a container is open, press to shift-click matching items from your inventory into it.")
        .defaultValue(Keybind.none())
        .action(this::doDeposit)
        .build());

    private final Setting<Boolean> ctRestock = sgContainer.add(new BoolSetting.Builder()
        .name("restock")
        .description("Pull matching items from containers into your inventory when you open them.")
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

    // ── Inventory Extras ─────────────────────────────────────────────────────
    private final SettingGroup sgExtras = settings.createGroup("Inventory Extras");

    private final Setting<Boolean> fullWarning = sgExtras.add(new BoolSetting.Builder()
        .name("full-warning")
        .description("Warn in chat when your inventory is almost full.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> warnThreshold = sgExtras.add(new IntSetting.Builder()
        .name("warn-threshold")
        .description("Free slots remaining that triggers the warning.")
        .defaultValue(3).min(1).sliderMax(10)
        .visible(() -> fullWarning.get())
        .build());

    private final Setting<Boolean> autoTrash = sgExtras.add(new BoolSetting.Builder()
        .name("auto-trash")
        .description("Automatically drop items whose IDs are in the trash list.")
        .defaultValue(false)
        .build());

    private final Setting<String> trashList = sgExtras.add(new StringSetting.Builder()
        .name("trash-list")
        .description("Comma-separated item IDs to auto-drop (e.g. rotten_flesh,gravel,flint).")
        .defaultValue("")
        .visible(() -> autoTrash.get())
        .build());

    // ── State ────────────────────────────────────────────────────────────────
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Queue<Runnable> actionQueue = new LinkedList<>();
    private int  tickCounter       = 0;
    private int  armorTimer        = 0;
    private int  warnCooldown      = 0;
    private int  trashCooldown     = 0;
    private boolean wasDead        = false;
    private boolean containerPending = false;

    public InventoryManager() {
        super(InventoryManagerAddon.CATEGORY, "inventory-manager",
            "Sort inventory/containers, manage PvP loadout, quick deposit, and auto-trash. " +
            "Bind sort-key for on-demand sorting.");

        for (int i = 0; i < 9; i++) {
            hotbarSlots.add(sgPvp.add(new EnumSetting.Builder<SlotType>()
                .name("slot-" + (i + 1))
                .description("Desired item type for hotbar slot " + (i + 1) + ".")
                .defaultValue(DEFAULT_HOTBAR[i])
                .visible(() -> pvpArrangeHotbar.get())
                .build()));
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        actionQueue.clear();
        if (pvpEquipArmor.get() || pvpArrangeHotbar.get()) {
            applyPvpLoadout();
            info("PvP loadout applied.");
        }
    }

    @Override
    public void onDeactivate() {
        actionQueue.clear();
        tickCounter   = 0;
        warnCooldown  = 0;
        trashCooldown = 0;
        armorTimer    = 0;
        containerPending = false;
    }

    // ── Events ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Drain action queue at configured rate
        for (int i = 0; i < actionsPerTick.get() && !actionQueue.isEmpty(); i++)
            actionQueue.poll().run();

        // Continuous sort — only when queue is idle to prevent overlap
        if (invContinuous.get() && actionQueue.isEmpty()) {
            if (++tickCounter >= invDelay.get()) {
                tickCounter = 0;
                enqueuePlayerSort();
            }
        }

        // Auto-equip armor every second, queue-idle to avoid interfering with sort
        if (pvpEquipArmor.get() && actionQueue.isEmpty()) {
            if (++armorTimer >= 20) {
                armorTimer = 0;
                equipBestArmor();
            }
        }

        // Respawn loadout re-apply
        if (pvpOnRespawn.get() && (pvpEquipArmor.get() || pvpArrangeHotbar.get())) {
            boolean isDead = mc.player.isDead() || mc.player.getHealth() <= 0;
            if (wasDead && !isDead) applyPvpLoadout();
            wasDead = isDead;
        }

        // Deferred container sort (must wait one tick after OpenScreenEvent)
        if (containerPending && actionQueue.isEmpty()) {
            containerPending = false;
            handleContainerSort();
        }

        // Inventory full warning (cooldown: 10 seconds)
        if (fullWarning.get()) {
            if (warnCooldown > 0) {
                warnCooldown--;
            } else {
                int free = 0;
                var inv = mc.player.getInventory();
                for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) free++;
                if (free <= warnThreshold.get()) {
                    warning("Inventory almost full! (" + free + " free slot" + (free == 1 ? "" : "s") + ")");
                    warnCooldown = 200;
                }
            }
        }

        // Auto-trash: scan every 10 ticks, only when queue is idle
        if (autoTrash.get() && actionQueue.isEmpty()) {
            if (--trashCooldown <= 0) {
                trashCooldown = 10;
                dropTrashItems();
            }
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;

        if (invSortOnOpen.get() && event.screen instanceof InventoryScreen) {
            actionQueue.clear();
            tickCounter = 0; // reset continuous timer so it doesn't fire immediately after
            enqueuePlayerSort();
        }

        if (event.screen instanceof HandledScreen<?>
                && !(event.screen instanceof InventoryScreen)
                && (ctSortOnOpen.get() || ctRestock.get())) {
            containerPending = true;
        }
    }

    // ── Sort ─────────────────────────────────────────────────────────────────

    private void doSort() {
        if (mc.player == null) return;
        actionQueue.clear();
        tickCounter = 0;

        ScreenHandler handler = mc.player.currentScreenHandler;

        if (handler instanceof PlayerScreenHandler) {
            enqueuePlayerSort();
            info("Sorting inventory...");
            return;
        }

        if (!isStorageContainer(handler)) {
            info("Not a sortable container.");
            return;
        }

        int csz = getContainerSize(handler);
        // Sort container slots
        SortUtils.enqueueSortAndMerge(actionQueue, handler, 0, csz - 1);
        // Sort player main inventory visible in the container screen
        SortUtils.enqueueSortAndMerge(actionQueue, handler, csz, csz + 26);
        info("Sorting container and inventory...");
    }

    private void enqueuePlayerSort() {
        if (mc.player == null) return;
        PlayerScreenHandler h = mc.player.playerScreenHandler;
        // Main inventory (slots 9–35 in PlayerScreenHandler)
        SortUtils.enqueueSortAndMerge(actionQueue, h, 9, 35);
        // Hotbar (slots 36–44) as a separate pass — items won't cross regions
        if (sortHotbar.get())
            SortUtils.enqueueSortAndMerge(actionQueue, h, 36, 44);
    }

    // ── PvP Loadout ──────────────────────────────────────────────────────────

    private void applyPvpLoadout() {
        if (mc.player == null) return;
        if (pvpEquipArmor.get()) equipBestArmor();
        if (pvpArrangeHotbar.get()) arrangeHotbar();
    }

    private void equipBestArmor() {
        if (mc.player == null) return;
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        // PlayerScreenHandler armor slot indices: HEAD=5, CHEST=6, LEGS=7, FEET=8
        EquipmentSlot[] slots       = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[]           screenSlots = {5, 6, 7, 8};

        for (int s = 0; s < 4; s++) {
            EquipmentSlot target  = slots[s];
            int           armSlot = screenSlots[s];
            ItemStack     current = handler.getSlot(armSlot).getStack();
            int           best    = SortUtils.scoreArmor(current);
            int           bestSrc = -1;

            for (int i = 9; i <= 44; i++) { // search both main inv and hotbar
                ItemStack cand = handler.getSlot(i).getStack();
                if (cand.isEmpty()) continue;
                EquipmentSlot candSlot = VersionCompat.getEquipSlot(cand);
                if (candSlot != target) continue;
                int score = SortUtils.scoreArmor(cand);
                if (score > best) { best = score; bestSrc = i; }
            }

            if (bestSrc == -1) continue;
            if (!current.isEmpty()) SortUtils.shiftClick(syncId, armSlot);
            SortUtils.shiftClick(syncId, bestSrc);
        }
    }

    private void arrangeHotbar() {
        if (mc.player == null) return;
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;

        for (int idx = 0; idx < 9; idx++) {
            SlotType desired = hotbarSlots.get(idx).get();
            if (desired == SlotType.EMPTY || desired == SlotType.AUTO) continue;

            ItemStack current = handler.getSlot(36 + idx).getStack();
            if (!current.isEmpty() && matchesSlotType(current, desired)) continue;

            int found = -1;
            for (int i = 9; i <= 35; i++) {
                ItemStack c = handler.getSlot(i).getStack();
                if (!c.isEmpty() && matchesSlotType(c, desired)) { found = i; break; }
            }
            if (found == -1) continue;
            // SWAP: moves the item at slot `found` to hotbar position `idx`
            SortUtils.interact(syncId, found, idx, SlotActionType.SWAP);
        }
    }

    private boolean matchesSlotType(ItemStack stack, SlotType type) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return switch (type) {
            case SWORD    -> id.endsWith("_sword") || id.equals("trident");
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

    // ── Container Sorter ─────────────────────────────────────────────────────

    private void handleContainerSort() {
        if (mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isStorageContainer(handler)) return;

        int csz = getContainerSize(handler);

        if (ctSortOnOpen.get())
            SortUtils.enqueueSortAndMerge(actionQueue, handler, 0, csz - 1);

        if (ctRestock.get())
            restockFromContainer(handler, csz);
    }

    private void restockFromContainer(ScreenHandler handler, int csz) {
        int syncId = handler.syncId;
        for (int i = 0; i < csz; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            SortUtils.ItemCategory cat = SortUtils.getCategory(stack);
            boolean take = switch (cat) {
                case POTION -> ctRestockPotions.get();
                case FOOD   -> ctRestockFood.get();
                case BLOCK  -> ctRestockBlocks.get();
                case MISC   -> ctRestockArrows.get() && id.endsWith("_arrow");
                default     -> false;
            };

            if (take && !playerHasItem(stack.getItem())) {
                final int slot = i;
                actionQueue.add(() -> SortUtils.shiftClick(syncId, slot));
            }
        }
    }

    private void doDeposit() {
        if (mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isStorageContainer(handler)) {
            info("Open a storage container to use deposit.");
            return;
        }
        int csz = getContainerSize(handler);
        if (csz <= 0) return;

        // Collect all item types already present in the container
        Set<Item> inContainer = new HashSet<>();
        for (int i = 0; i < csz; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty()) inContainer.add(s.getItem());
        }
        if (inContainer.isEmpty()) {
            info("Container is empty — nothing to deposit into.");
            return;
        }

        int syncId = handler.syncId;
        int count  = 0;
        // Player main inventory occupies slots csz..(csz+26) in the container screen
        for (int i = csz; i < csz + 27; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty() && inContainer.contains(s.getItem())) {
                final int slot = i;
                actionQueue.add(() -> SortUtils.shiftClick(syncId, slot));
                count++;
            }
        }
        if (count > 0) info("Depositing " + count + " stack(s)...");
        else info("No matching items to deposit.");
    }

    private boolean playerHasItem(Item target) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++)
            if (inv.getStack(i).isOf(target)) return true;
        return false;
    }

    // ── Inventory Extras ─────────────────────────────────────────────────────

    private void dropTrashItems() {
        if (mc.player == null) return;
        String raw = trashList.get().trim();
        if (raw.isEmpty()) return;

        Set<String> trash = new HashSet<>();
        for (String e : raw.split(",")) {
            String trimmed = e.trim().toLowerCase();
            if (!trimmed.isEmpty()) trash.add(trimmed);
        }
        if (trash.isEmpty()) return;

        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;
        // Scan main inventory only (slots 9–35); never touch hotbar or armor
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (trash.contains(id))
                SortUtils.interact(syncId, i, 1, SlotActionType.THROW); // button=1 throws full stack
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true for pure storage containers (chests, barrels, shulker boxes,
     * hoppers, droppers/dispensers). Excludes workbench screens where slot indices
     * aren't simple storage (anvils, furnaces, crafting tables, trades, etc.).
     */
    private boolean isStorageContainer(ScreenHandler handler) {
        if (handler == null || handler instanceof PlayerScreenHandler) return false;
        return handler instanceof GenericContainerScreenHandler   // 1–6 row chests, barrel, ender chest
            || handler instanceof ShulkerBoxScreenHandler
            || handler instanceof HopperScreenHandler
            || handler instanceof Generic3x3ContainerScreenHandler; // dropper / dispenser
    }

    /**
     * Returns the number of container-owned slots (total slots minus the 36 player slots
     * that are always appended at the end of a storage screen handler).
     */
    private int getContainerSize(ScreenHandler handler) {
        return handler.slots.size() - 36;
    }
}
