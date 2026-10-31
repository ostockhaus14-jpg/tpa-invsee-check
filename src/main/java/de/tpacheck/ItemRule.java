package de.tpacheck;

import java.util.ArrayList;
import java.util.List;

/** Eine Bedingung für ein wertvolles Item (Item-ID plus optionale Zusatzbedingungen). */
public class ItemRule {
    public String id = "minecraft:air";
    /** Gesamtanzahl über alle passenden Stacks. */
    public int minCount = 1;
    /** Verzauberungen (auch bei Büchern), die das Item haben muss. */
    public List<EnchantReq> enchants = new ArrayList<>();
    /** false = ALLE Verzauberungen nötig, true = EINE davon reicht. */
    public boolean anyEnchant = false;
    /** Feuerwerk: Mindest-Flugdauer, 0 = egal. */
    public int minFlight = 0;
    /** Tränke/Pfeile: benötigter Effekt (leer = egal) und Mindeststufe (1 = Stufe I). */
    public String effect = "";
    public int effectLevel = 1;

    public static class EnchantReq {
        public String id = "";
        public int level = 1;

        public EnchantReq() {}

        public EnchantReq(String id, int level) {
            this.id = id;
            this.level = level;
        }
    }

    public ItemRule() {}

    public ItemRule(String id) {
        this.id = id;
    }

    /** Keine Zusatzbedingungen (Standardregel). */
    public boolean isPlain() {
        return minCount <= 1 && enchants.isEmpty() && minFlight <= 0 && effect.isEmpty();
    }

    /** Bringt alle Felder in einen gültigen Zustand. false = Regel unbrauchbar (ungültige Item-ID). */
    public boolean normalize() {
        String n = TpaConfig.normalizeId(id);
        if (n == null) return false;
        id = n;
        minCount = clamp(minCount, 1, 2304);
        List<EnchantReq> out = new ArrayList<>();
        if (enchants != null) {
            for (EnchantReq q : enchants) {
                if (q == null) continue;
                String e = TpaConfig.normalizeId(q.id);
                if (e == null) continue;
                boolean dup = false;
                for (EnchantReq o : out) {
                    if (o.id.equals(e)) { dup = true; break; }
                }
                if (dup) continue;
                q.id = e;
                q.level = clamp(q.level, 1, 255);
                out.add(q);
            }
        }
        enchants = out;
        minFlight = clamp(minFlight, 0, 255);
        String eff = TpaConfig.normalizeId(effect);
        effect = eff == null ? "" : eff;
        effectLevel = clamp(effectLevel, 1, 255);
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
