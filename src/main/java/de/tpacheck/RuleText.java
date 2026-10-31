package de.tpacheck;

import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Namen und Kurzbeschreibungen für Regeln (übersetzt in die aktuelle Spielsprache). */
public final class RuleText {
    private RuleText() {}

    public record Ench(String id, int max) {}

    /**
     * Verzauberungen sind seit 1.21 ein Datenregister, das der Client erst nach dem Betreten
     * einer Welt kennt. Damit der Settings-Screen auch im Hauptmenü funktioniert, ist die Liste fest.
     */
    public static final List<Ench> ENCHANTS = List.of(
            new Ench("minecraft:protection", 4), new Ench("minecraft:fire_protection", 4),
            new Ench("minecraft:feather_falling", 4), new Ench("minecraft:blast_protection", 4),
            new Ench("minecraft:projectile_protection", 4), new Ench("minecraft:respiration", 3),
            new Ench("minecraft:aqua_affinity", 1), new Ench("minecraft:thorns", 3),
            new Ench("minecraft:depth_strider", 3), new Ench("minecraft:frost_walker", 2),
            new Ench("minecraft:binding_curse", 1), new Ench("minecraft:soul_speed", 3),
            new Ench("minecraft:swift_sneak", 3), new Ench("minecraft:sharpness", 5),
            new Ench("minecraft:smite", 5), new Ench("minecraft:bane_of_arthropods", 5),
            new Ench("minecraft:knockback", 2), new Ench("minecraft:fire_aspect", 2),
            new Ench("minecraft:looting", 3), new Ench("minecraft:sweeping_edge", 3),
            new Ench("minecraft:efficiency", 5), new Ench("minecraft:silk_touch", 1),
            new Ench("minecraft:unbreaking", 3), new Ench("minecraft:fortune", 3),
            new Ench("minecraft:power", 5), new Ench("minecraft:punch", 2),
            new Ench("minecraft:flame", 1), new Ench("minecraft:infinity", 1),
            new Ench("minecraft:luck_of_the_sea", 3), new Ench("minecraft:lure", 3),
            new Ench("minecraft:loyalty", 3), new Ench("minecraft:impaling", 5),
            new Ench("minecraft:riptide", 3), new Ench("minecraft:channeling", 1),
            new Ench("minecraft:multishot", 1), new Ench("minecraft:quick_charge", 3),
            new Ench("minecraft:piercing", 4), new Ench("minecraft:density", 5),
            new Ench("minecraft:breach", 4), new Ench("minecraft:wind_burst", 3),
            new Ench("minecraft:lunge", 3), new Ench("minecraft:mending", 1),
            new Ench("minecraft:vanishing_curse", 1)
    );

    public static String enchantName(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return id;
        return Text.translatable("enchantment." + ident.getNamespace() + "." + ident.getPath()).getString();
    }

    public static int enchantMax(String id) {
        for (Ench e : ENCHANTS) {
            if (e.id().equals(id)) return e.max();
        }
        return 255;
    }

    public static String effectName(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident != null && Registries.STATUS_EFFECT.containsId(ident)) {
            return Registries.STATUS_EFFECT.get(ident).getName().getString();
        }
        return id;
    }

    /** Kurztext für die Liste, z.B. "4x | Schärfe 5, Plünderung 3 | Flug 2+". Leer bei Standardregeln. */
    public static String summary(ItemRule r) {
        List<String> parts = new ArrayList<>();
        if (r.minCount > 1) parts.add(r.minCount + "x");
        if (!r.enchants.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (ItemRule.EnchantReq q : r.enchants) e.add(enchantName(q.id) + " " + q.level);
            parts.add(String.join(r.anyEnchant ? " / " : ", ", e));
        }
        if (r.minFlight > 0) parts.add("Flug " + r.minFlight + "+");
        if (!r.effect.isEmpty()) parts.add(effectName(r.effect) + " " + r.effectLevel + "+");
        return String.join(" | ", parts);
    }
}
