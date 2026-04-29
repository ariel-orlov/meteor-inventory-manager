package com.example.addon.utils;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Handles Minecraft API differences across versions.
 *
 * DataComponentTypes.EQUIPPABLE was added in 1.21.2. Before that, armor
 * items were detected via ArmorItem.getSlotType(). Both ArmorItem and the
 * EQUIPPABLE field are resolved via reflection so this class compiles against
 * any recent MC version without hard-coding removed/added symbols.
 */
public final class VersionCompat {

    // MC 1.21.2+ — ComponentType<?> for the EQUIPPABLE component.
    @SuppressWarnings("rawtypes")
    private static final net.minecraft.component.ComponentType<?> EQUIPPABLE_TYPE;

    // Pre-1.21.2 — ArmorItem class and its getSlotType() method.
    private static final Class<?>  ARMOR_ITEM_CLASS;
    private static final Method    LEGACY_GET_SLOT;

    static {
        // ── 1.21.2+ path: resolve DataComponentTypes.EQUIPPABLE ──
        net.minecraft.component.ComponentType<?> eqType = null;
        try {
            Field f = net.minecraft.component.DataComponentTypes.class.getDeclaredField("EQUIPPABLE");
            f.setAccessible(true);
            //noinspection unchecked
            eqType = (net.minecraft.component.ComponentType<?>) f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {}
        EQUIPPABLE_TYPE = eqType;

        // ── Pre-1.21.2 path: resolve ArmorItem and its slot accessor ──
        Class<?> armorClass = null;
        Method   slotMethod = null;
        if (EQUIPPABLE_TYPE == null) {
            try {
                armorClass = Class.forName("net.minecraft.item.ArmorItem");
                for (String name : new String[]{"getSlotType", "getEquipmentSlot"}) {
                    try { slotMethod = armorClass.getMethod(name); break; }
                    catch (NoSuchMethodException ignored) {}
                }
            } catch (ClassNotFoundException ignored) {}
        }
        ARMOR_ITEM_CLASS = armorClass;
        LEGACY_GET_SLOT  = slotMethod;
    }

    /**
     * Returns the EquipmentSlot for wearable items across MC versions,
     * or null if the item is not equippable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static EquipmentSlot getEquipSlot(ItemStack stack) {
        // ── 1.21.2+ ──
        if (EQUIPPABLE_TYPE != null) {
            Object comp = stack.get((net.minecraft.component.ComponentType) EQUIPPABLE_TYPE);
            if (comp == null) return null;
            try {
                // EquippableComponent is a Java record; slot() is the accessor
                return (EquipmentSlot) comp.getClass().getMethod("slot").invoke(comp);
            } catch (Exception e) {
                return null;
            }
        }
        // ── Pre-1.21.2 ──
        if (ARMOR_ITEM_CLASS != null && LEGACY_GET_SLOT != null) {
            Item item = stack.getItem();
            if (ARMOR_ITEM_CLASS.isInstance(item)) {
                try {
                    return (EquipmentSlot) LEGACY_GET_SLOT.invoke(item);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** True for the four body-armor slots (HEAD/CHEST/LEGS/FEET). */
    public static boolean isBodyArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD  || slot == EquipmentSlot.CHEST
            || slot == EquipmentSlot.LEGS  || slot == EquipmentSlot.FEET;
    }
}
