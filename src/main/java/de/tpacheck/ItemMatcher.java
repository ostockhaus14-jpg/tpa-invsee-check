package de.tpacheck;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Prüft, ob ein ItemStack zu einer Regel passt. */
public final class ItemMatcher {
    private ItemMatcher() {}

    public static boolean matches(ItemRule r, ItemStack st) {
        if (st.isEmpty()) return false;
        if (!r.id.equals(Registries.ITEM.getId(st.getItem()).toString())) return false;

        if (!r.enchants.isEmpty()) {
            Map<String, Integer> have = enchantLevels(st);
            boolean any = false;
            boolean all = true;
            for (ItemRule.EnchantReq q : r.enchants) {
                boolean ok = have.getOrDefault(q.id, 0) >= q.level;
                any |= ok;
                all &= ok;
            }
            if (r.anyEnchant ? !any : !all) return false;
        }

        if (r.minFlight > 0) {
            FireworksComponent fw = st.get(DataComponentTypes.FIREWORKS);
            if (fw == null || fw.flightDuration() < r.minFlight) return false;
        }

        if (!r.effect.isEmpty()) {
            PotionContentsComponent pc = st.get(DataComponentTypes.POTION_CONTENTS);
            if (pc == null) return false;
            boolean ok = false;
            for (StatusEffectInstance ei : pc.getEffects()) {
                String id = idOf(ei.getEffectType());
                if (r.effect.equals(id) && ei.getAmplifier() + 1 >= r.effectLevel) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    /** Name des Items plus ggf. Verzauberungen, für die Chatmeldung. */
    public static String describe(ItemStack st) {
        String name = st.getName().getString();
        List<String> ench = new ArrayList<>();
        addEnchants(ench, st.get(DataComponentTypes.ENCHANTMENTS));
        addEnchants(ench, st.get(DataComponentTypes.STORED_ENCHANTMENTS));
        return ench.isEmpty() ? name : name + " [" + String.join(", ", ench) + "]";
    }

    private static Map<String, Integer> enchantLevels(ItemStack st) {
        Map<String, Integer> m = new HashMap<>();
        collect(m, st.get(DataComponentTypes.ENCHANTMENTS));
        collect(m, st.get(DataComponentTypes.STORED_ENCHANTMENTS)); // Verzauberte Bücher
        return m;
    }

    private static void collect(Map<String, Integer> m, ItemEnchantmentsComponent c) {
        if (c == null) return;
        for (RegistryEntry<Enchantment> e : c.getEnchantments()) {
            String id = idOf(e);
            if (id != null) m.merge(id, c.getLevel(e), Math::max);
        }
    }

    private static void addEnchants(List<String> out, ItemEnchantmentsComponent c) {
        if (c == null) return;
        for (RegistryEntry<Enchantment> e : c.getEnchantments()) {
            String id = idOf(e);
            if (id != null) out.add(RuleText.enchantName(id) + " " + c.getLevel(e));
        }
    }

    private static <T> String idOf(RegistryEntry<T> e) {
        return e.getKey().map(k -> k.getValue().toString()).orElse(null);
    }
}
