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

    // ── Inventory Sorter ─────────────────────────────────────────────
    private final SettingGroup sgInv = settings.createGroup("Inventory Sorter");

    private final Setting<Keybind> sortKey = sgInv.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Press to sort the open inventory or container on demand.")
        .defaultValue(Keybind.none())
        .action(this::doSort)
        .build());

    private final Setting<Boolean> invSortOnOpen = sgInv.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Automatically sort your inventory whenever you open it.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sortHotbar = sgInv.add(new BoolSetting.Builder()
        .name("sort-hotbar")
        .description("Also sort hotbar slots when sorting inventory.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> invContinuous = sgInv.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously re-sort your inventory while the module is on.")
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
        .description("Slot clicks sent per tick. Lower = safer on strict anti-cheat servers.")
        .defaultValue(4).min(1).sliderMax(20)
        .build());

    // ── PvP Loadout ──────────────────────────────────────────────────
    private final SettingGroup sgPvp = settings.createGroup("PvP Loadout");

    private final Setting<Boolean> pvpEquipArmor = sgPvp.add(new BoolSetting.Builder()
        .name("equip-armor")
        .description("Auto-equip best armor on activate and whenever better armor is picked up.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pvpArrangeHotbar = sgPvp.add(new BoolSetting.Builder()
        .name("arrange-hotbar")
        .description("Move best PvP items to configured hotbar slots on activate.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pvpOnRespawn = sgPvp.add(new BoolSetting.Builder()
        .name("on-respawn")
        .description("Re-apply PvP loadout automatically on respawn.")
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

    private final Setting<Keybind> depositKey = sgContainer.add(new KeybindSetting.Builder()
        .name("deposit-key")
        .description("Press while a container is open to deposit matching items from your inventory.")
        .defaultValue(Keybind.none())
        .action(this::doDeposit)
        .build());

    private final Setting<Boolean> ctRestock = sgContainer.add(new BoolSetting.Builder()
        .name("restock")
        .description("Pull matching items from container into your inventory when you open it.")
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

    // ── Inventory Extras ─────────────────────────────────────────────
    private final SettingGroup sgExtras = settings.createGroup("Inventory Extras");

    private final Setting<Boolean> fullWarning = sgExtras.add(new BoolSetting.Builder()
        .name("full-warning")
        .description("Warn in chat when your inventory is almost full.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> warnThreshold = sgExtras.add(new IntSetting.Builder()
        .name("warn-threshold")
        .description("Free slots remaining before warning fires.")
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

    // ── State ────────────────────────────────────────────────────────
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Queue<Runnable> actionQueue = new LinkedList<>();
    private int tickCounter    = 0;
    private int armorTimer     = 0;
    private int warnCooldown   = 0;
    private int trashCooldown  = 0;
    private boolean wasDead    = false;
    private boolean containerPending = false;

    public InventoryManager() {
        super(AddonTemplate.CATEGORY, "inventory-manager",
            "Sort inventory/containers, manage PvP loadout, deposit & trash. Bind sort-key to sort on demand.");

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
        actionQueue.clear();
        if (pvpEquipArmor.get() || pvpArrangeHotbar.get()) {
            applyPvpLoadout();
            info("PvP loadout applied.");
        }
    }

    @Override
    public void onDeactivate() {
        actionQueue.clear();
    }

    // ── Events ───────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Drain action queue at configured rate (server-safe pacing)
        for (int i = 0; i < actionsPerTick.get() && !actionQueue.isEmpty(); i++)
            actionQueue.poll().run();

        // Continuous sort — only fires when queue is idle to avoid overlap
        if (invContinuous.get() && actionQueue.isEmpty()) {
            if (++tickCounter >= invDelay.get()) {
                tickCounter = 0;
                enqueuePlayerSort();
            }
        }

        // Auto-equip armor: check every second, only when queue is idle
        if (pvpEquipArmor.get() && actionQueue.isEmpty()) {
            if (++armorTimer >= 20) {
                armorTimer = 0;
                equipBestArmor();
            }
        }

        // Respawn loadout
        if (pvpOnRespawn.get() && (pvpEquipArmor.get() || pvpArrangeHotbar.get())) {
            boolean isDead = mc.player.isDead() || mc.player.getHealth() <= 0;
            if (wasDead && !isDead) applyPvpLoadout();
            wasDead = isDead;
        }

        // Deferred container sort (set one tick after OpenScreenEvent)
        if (containerPending && actionQueue.isEmpty()) {
            containerPending = false;
            handleContainerSort();
        }

        // Inventory full warning (max one warning per 10 seconds)
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

        // Auto-trash: scan every 10 ticks
        if (autoTrash.get()) {
            if (trashCooldown-- <= 0) {
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
            enqueuePlayerSort();
        }

        if (event.screen instanceof HandledScreen<?>
                && !(event.screen instanceof InventoryScreen)
                && (ctSortOnOpen.get() || ctRestock.get())) {
            containerPending = true;
        }
    }

    // ── Sort ─────────────────────────────────────────────────────────

    private void doSort() {
        if (mc.player == null) return;
        actionQueue.clear();
        tickCounter = 0;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler instanceof PlayerScreenHandler || handler == null) {
            enqueuePlayerSort();
            info("Sorting inventory...");
        } else {
            int csz = handler.slots.size() - 36;
            if (csz > 0) {
                // Container slots
                SortUtils.enqueueSortRange(actionQueue, handler, 0, csz - 1);
                SortUtils.enqueueMergeStacks(actionQueue, handler, 0, csz - 1);
                // Player main inventory as seen in container screen
                SortUtils.enqueueSortRange(actionQueue, handler, csz, csz + 26);
                SortUtils.enqueueMergeStacks(actionQueue, handler, csz, csz + 26);
            }
            info("Sorting container & inventory...");
        }
    }

    private void enqueuePlayerSort() {
        if (mc.player == null) return;
        PlayerScreenHandler h = mc.player.playerScreenHandler;
        // Always sort main inventory (9-35)
        SortUtils.enqueueSortRange(actionQueue, h, 9, 35);
        SortUtils.enqueueMergeStacks(actionQueue, h, 9, 35);
        // Optionally also sort hotbar (36-44) as a separate pass so items don't cross regions
        if (sortHotbar.get()) {
            SortUtils.enqueueSortRange(actionQueue, h, 36, 44);
            SortUtils.enqueueMergeStacks(actionQueue, h, 36, 44);
        }
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

        EquipmentSlot[] slots      = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[]           screenSlots = {5, 6, 7, 8};

        for (int s = 0; s < 4; s++) {
            EquipmentSlot eqSlot       = slots[s];
            int           armorScreen  = screenSlots[s];
            ItemStack     equipped     = handler.getSlot(armorScreen).getStack();
            int           bestSlot     = -1;
            int           bestScore    = SortUtils.scoreArmor(equipped);

            for (int i = 9; i <= 35; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();
                if (candidate.isEmpty()) continue;
                var eq = candidate.get(DataComponentTypes.EQUIPPABLE);
                if (eq == null || eq.slot() != eqSlot) continue;
                int score = SortUtils.scoreArmor(candidate);
                if (score > bestScore) { bestScore = score; bestSlot = i; }
            }

            if (bestSlot == -1) continue;
            if (!equipped.isEmpty()) SortUtils.shiftClick(syncId, armorScreen);
            SortUtils.shiftClick(syncId, bestSlot);
        }
    }

    private void arrangeHotbar() {
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
            SortUtils.interact(syncId, found, idx, SlotActionType.SWAP);
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
        int csz = handler.slots.size() - 36;
        if (csz <= 0) return;

        if (ctSortOnOpen.get())
            SortUtils.enqueueSortRange(actionQueue, handler, 0, csz - 1);

        if (ctRestock.get())
            restockFromContainer(handler, csz);
    }

    private void restockFromContainer(ScreenHandler handler, int csz) {
        int syncId = handler.syncId;
        for (int i = 0; i < csz; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            String id  = Registries.ITEM.getId(stack.getItem()).getPath();
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

    // Deposit: shift-click player inventory items that match existing container stacks
    private void doDeposit() {
        if (mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler instanceof PlayerScreenHandler || handler == null) {
            info("Open a container to use deposit.");
            return;
        }
        int csz = handler.slots.size() - 36;
        if (csz <= 0) return;

        Set<Item> inContainer = new HashSet<>();
        for (int i = 0; i < csz; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty()) inContainer.add(s.getItem());
        }
        if (inContainer.isEmpty()) return;

        int syncId = handler.syncId;
        int count  = 0;
        // Player main inventory sits at slots csz..csz+26 in the container screen
        for (int i = csz; i < csz + 27; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty() && inContainer.contains(s.getItem())) {
                final int slot = i;
                actionQueue.add(() -> SortUtils.shiftClick(syncId, slot));
                count++;
            }
        }
        if (count > 0) info("Depositing " + count + " stack(s)...");
    }

    private boolean playerHasItem(Item target) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++)
            if (inv.getStack(i).isOf(target)) return true;
        return false;
    }

    // ── Inventory Extras ─────────────────────────────────────────────

    private void dropTrashItems() {
        if (mc.player == null) return;
        String raw = trashList.get().trim();
        if (raw.isEmpty()) return;

        Set<String> trash = new HashSet<>();
        for (String e : raw.split(",")) trash.add(e.trim().toLowerCase());

        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        int syncId = handler.syncId;
        // Only scan main inventory (9-35), never hotbar or armor
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (trash.contains(Registries.ITEM.getId(stack.getItem()).getPath()))
                SortUtils.interact(syncId, i, 1, SlotActionType.THROW); // button=1 drops whole stack
        }
    }
}
