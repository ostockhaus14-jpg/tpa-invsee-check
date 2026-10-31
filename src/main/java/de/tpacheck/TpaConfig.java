package de.tpacheck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TpaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Mod an/aus. */
    public boolean enabled = true;
    /** Rüstung (Slots 37-40) und Offhand (Slot 46) im Invsee mitprüfen. */
    public boolean scanEquipment = true;
    /** So lange (Ticks, 20 = 1 s) wird auf den Inhalt des Invsee gewartet, bevor "nichts gefunden" gilt. */
    public int scanDelayTicks = 10;
    /** Optional: nur auf Servern aktiv, deren Adresse diesen Text enthält. Leer = überall. */
    public String serverFilter = "";
    /** Wertvolle Items inkl. Zusatzbedingungen (Verzauberungen, Anzahl, Feuerwerk, Effekte). */
    public List<ItemRule> rules = defaultRules();
    /** Altes Format (nur Item-IDs). Wird beim Laden in {@link #rules} umgewandelt. */
    public List<String> valuableItems = null;

    private static List<ItemRule> defaultRules() {
        List<ItemRule> l = new ArrayList<>();
        for (String id : List.of("minecraft:elytra", "minecraft:netherite_ingot", "minecraft:netherite_block",
                "minecraft:enchanted_golden_apple", "minecraft:diamond_block")) {
            l.add(new ItemRule(id));
        }
        return l;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("tpa-invsee-check.json");
    }

    public static TpaConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                TpaConfig c = GSON.fromJson(r, TpaConfig.class);
                if (c != null) {
                    c.normalize();
                    return c;
                }
            } catch (Exception e) {
                TpaInvseeCheck.LOG.warn("Config ist ungültig, sichere sie als .broken und nutze Standardwerte", e);
                try {
                    Files.move(p, p.resolveSibling("tpa-invsee-check.json.broken"), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                    // egal, wird gleich überschrieben
                }
            }
        }
        TpaConfig c = new TpaConfig();
        c.normalize();
        c.save();
        return c;
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("tpa-invsee-check.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            TpaInvseeCheck.LOG.warn("Config konnte nicht gespeichert werden", e);
        }
    }

    public void normalize() {
        if (scanDelayTicks < 2) scanDelayTicks = 2;
        if (scanDelayTicks > 200) scanDelayTicks = 200;
        if (serverFilter == null) serverFilter = "";

        if (valuableItems != null) { // Migration vom alten Format
            rules = new ArrayList<>();
            for (String s : valuableItems) {
                String n = normalizeId(s);
                if (n != null) rules.add(new ItemRule(n));
            }
            valuableItems = null;
        }
        List<ItemRule> out = new ArrayList<>();
        if (rules != null) {
            for (ItemRule r : rules) {
                if (r == null || !r.normalize()) continue;
                boolean dup = false;
                if (r.isPlain()) {
                    for (ItemRule o : out) {
                        if (o.isPlain() && o.id.equals(r.id)) { dup = true; break; }
                    }
                }
                if (!dup) out.add(r);
            }
        }
        rules = out;
    }

    /** "Elytra" / "minecraft:elytra" -> "minecraft:elytra"; null bei ungültiger Eingabe. */
    public static String normalizeId(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        Identifier id = Identifier.tryParse(s);
        return id == null ? null : id.toString();
    }
}
