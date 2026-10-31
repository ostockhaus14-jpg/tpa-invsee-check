package de.tpacheck;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Settings-Screen für Mod Menu: Schalter, Item-Suche mit Autocomplete (mit Item-Icons) und Liste der wertvollen Items. */
public class ConfigScreen extends Screen {
    private static final int SUGGESTIONS = 6;
    private static final int ROWS = 7;
    private static final int[] DELAYS = {5, 10, 20, 40};

    private record Entry(String id, String path, String name, String nameN, String compact,
                         String[] words, ItemStack stack) {}

    private final Screen parent;
    private final TpaConfig cfg = TpaInvseeCheck.CONFIG;

    private List<Entry> entries;
    private Map<String, Entry> byId;

    private TextFieldWidget search;
    private ButtonWidget delayBtn;
    private final ButtonWidget[] suggestionBtns = new ButtonWidget[SUGGESTIONS];
    private final Entry[] suggestionEntries = new Entry[SUGGESTIONS];
    private final ButtonWidget[] rowBtns = new ButtonWidget[ROWS];
    private final String[] rowIds = new String[ROWS];
    private ButtonWidget prevBtn, nextBtn;
    private int page = 0;
    private int leftX, rightX, colW, pageY;

    public ConfigScreen(Screen parent) {
        super(Text.literal("TPA Invsee Check"));
        this.parent = parent;
    }

    // ---------------------------------------------------------------- Aufbau

    @Override
    protected void init() {
        if (entries == null) buildEntries();

        colW = Math.min(190, (width - 30) / 2);
        leftX = width / 2 - colW - 5;
        rightX = width / 2 + 5;
        int fullW = colW * 2 + 10;
        int bw = (fullW - 8) / 3;

        // Obere Reihe: Schalter
        toggle(leftX, 20, bw, "Mod aktiv", () -> cfg.enabled, v -> cfg.enabled = v);
        toggle(leftX + bw + 4, 20, bw, "Rüstung/Offhand", () -> cfg.scanEquipment, v -> cfg.scanEquipment = v);
        delayBtn = addDrawableChild(ButtonWidget.builder(delayText(), b -> {
            int next = DELAYS[0];
            for (int d : DELAYS) {
                if (d > cfg.scanDelayTicks) { next = d; break; }
            }
            cfg.scanDelayTicks = next;
            b.setMessage(delayText());
            cfg.save();
        }).dimensions(leftX + 2 * (bw + 4), 20, bw, 20).build());

        // Suche (links)
        int searchY = 58;
        String old = search == null ? "" : search.getText();
        search = new TextFieldWidget(textRenderer, leftX, searchY, colW - 54, 20, Text.literal("Suche"));
        search.setMaxLength(64);
        search.setPlaceholder(Text.literal("Item suchen (DE/EN)..."));
        search.setText(old);
        search.setChangedListener(s -> updateSuggestions());
        addDrawableChild(search);
        setInitialFocus(search);
        addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> addTyped())
                .dimensions(leftX + colW - 52, searchY, 52, 20).build());

        for (int i = 0; i < SUGGESTIONS; i++) {
            final int idx = i;
            suggestionBtns[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                if (suggestionEntries[idx] != null) addItem(suggestionEntries[idx].id());
            }).dimensions(leftX, searchY + 22 + i * 22, colW, 20).build());
        }

        // Liste der wertvollen Items (rechts)
        for (int i = 0; i < ROWS; i++) {
            final int idx = i;
            rowBtns[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                int real = page * ROWS + idx;
                if (real >= 0 && real < cfg.valuableItems.size()) {
                    cfg.valuableItems.remove(real);
                    cfg.save();
                    updateRows();
                    updateSuggestions();
                }
            }).dimensions(rightX, searchY + i * 22, colW, 20).build());
        }
        pageY = searchY + ROWS * 22;
        prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page--; updateRows(); })
                .dimensions(rightX, pageY, 40, 20).build());
        nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page++; updateRows(); })
                .dimensions(rightX + colW - 40, pageY, 40, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"), b -> close())
                .dimensions(width / 2 - 100, height - 26, 200, 20).build());

        updateSuggestions();
        updateRows();
    }

    private ButtonWidget toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        return addDrawableChild(ButtonWidget.builder(toggleText(label, get.getAsBoolean()), b -> {
            boolean v = !get.getAsBoolean();
            set.accept(v);
            b.setMessage(toggleText(label, v));
            cfg.save();
        }).dimensions(x, y, w, 20).build());
    }

    private static Text toggleText(String label, boolean on) {
        return Text.literal(label + ": ")
                .append(Text.literal(on ? "AN" : "AUS").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    private Text delayText() {
        return Text.literal("Wartezeit: " + cfg.scanDelayTicks + " Ticks");
    }

    // ---------------------------------------------------------------- Items & Suche

    private void buildEntries() {
        entries = new ArrayList<>();
        byId = new HashMap<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR) continue;
            String name = item.getName().getString();
            String nameN = norm(name);
            String pathN = norm(id.getPath());
            Set<String> w = new LinkedHashSet<>();
            w.addAll(Arrays.asList(nameN.split(" ")));
            w.addAll(Arrays.asList(pathN.split(" ")));
            w.remove("");
            Entry e = new Entry(id.toString(), id.getPath(), name, nameN,
                    (nameN + "|" + pathN).replace(" ", ""), w.toArray(new String[0]), new ItemStack(item));
            entries.add(e);
            byId.put(e.id(), e);
        }
    }

    /** Kleinbuchstaben, ohne Umlaute/Akzente (ä->a, ß->ss), Sonderzeichen -> Leerzeichen. */
    private static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replace("ß", "ss");
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Wie gut passt ein Suchwort auf die Wörter eines Items? Kleiner = besser, MAX_VALUE = kein Treffer. */
    private static int scoreToken(String t, String[] words) {
        int best = Integer.MAX_VALUE;
        int maxd = t.length() >= 8 ? 2 : (t.length() >= 4 ? 1 : 0);
        for (String w : words) {
            int s = Integer.MAX_VALUE;
            if (w.equals(t)) s = 0;
            else if (w.startsWith(t)) s = 1;
            else if (t.length() >= 2 && w.contains(t)) s = 3;
            else if (maxd > 0) {
                int d = Integer.MAX_VALUE;
                if (Math.abs(w.length() - t.length()) <= maxd) d = lev(t, w);
                if (w.length() > t.length()) { // Tippfehler mitten im Tippen: Wortanfang vergleichen
                    d = Math.min(d, lev(t, w.substring(0, t.length())));
                }
                if (d <= maxd) s = 5 + d;
            }
            if (s < best) best = s;
        }
        return best;
    }

    private void updateSuggestions() {
        if (search == null || suggestionBtns[0] == null) return;
        Arrays.fill(suggestionEntries, null);

        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        String qn = norm(q);

        if (!qn.isEmpty()) {
            String[] tokens = qn.split(" ");
            String qCompact = qn.replace(" ", "");
            List<Object[]> hits = new ArrayList<>(); // {Entry, Integer score}
            for (Entry e : entries) {
                if (cfg.valuableItems.contains(e.id())) continue;
                int total = 0;
                boolean ok = true;
                for (String t : tokens) {
                    int s = scoreToken(t, e.words());
                    if (s == Integer.MAX_VALUE) { ok = false; break; }
                    total += s;
                }
                // zusammengeschriebene Eingabe, z.B. "goldapfel" für "Goldener Apfel"
                if (!ok && qCompact.length() >= 3 && e.compact().contains(qCompact)) {
                    ok = true;
                    total = 4;
                }
                if (!ok) continue;
                if (e.nameN().equals(qn)) total -= 10;
                else if (e.nameN().startsWith(qn)) total -= 2;
                hits.add(new Object[]{e, total});
            }
            hits.sort(Comparator
                    .comparingInt((Object[] h) -> (Integer) h[1])
                    .thenComparingInt(h -> ((Entry) h[0]).name().length())
                    .thenComparing(h -> ((Entry) h[0]).nameN()));
            for (int i = 0; i < SUGGESTIONS && i < hits.size(); i++) {
                suggestionEntries[i] = (Entry) hits.get(i)[0];
            }
        }
        for (int i = 0; i < SUGGESTIONS; i++) {
            suggestionBtns[i].visible = suggestionEntries[i] != null;
        }
    }

    private void updateRows() {
        if (rowBtns[0] == null) return;
        int size = cfg.valuableItems.size();
        int pages = Math.max(1, (size + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < ROWS; i++) {
            int idx = page * ROWS + i;
            rowIds[i] = idx < size ? cfg.valuableItems.get(idx) : null;
            rowBtns[i].visible = rowIds[i] != null;
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages - 1;
    }

    /** "Add"-Button: exakte ID wenn gültig, sonst der beste Autocomplete-Treffer. */
    private void addTyped() {
        String id = TpaConfig.normalizeId(search.getText());
        if (id != null && byId.containsKey(id)) {
            addItem(id);
        } else if (suggestionEntries[0] != null) {
            addItem(suggestionEntries[0].id());
        }
    }

    private void addItem(String id) {
        if (!cfg.valuableItems.contains(id)) {
            cfg.valuableItems.add(id);
            cfg.save();
        }
        page = Integer.MAX_VALUE; // auf letzte Seite springen (wird in updateRows begrenzt)
        updateRows();
        updateSuggestions(); // Suchtext bleibt stehen, das gerade hinzugefügte Item verschwindet aus den Vorschlägen
        setFocused(search);  // weitertippen ohne erneut ins Feld klicken zu müssen
    }

    // ---------------------------------------------------------------- Rendering / Schließen

    /** Zeichnet Item-Icon (mit Texturepack), Name und ID in einen Button. */
    private void drawEntry(DrawContext ctx, ButtonWidget b, ItemStack stack, String name, String path, boolean remove) {
        int x = b.getX(), y = b.getY();
        if (!stack.isEmpty()) ctx.drawItem(stack, x + 3, y + 2);
        int avail = b.getWidth() - 28 - (remove ? 14 : 0);
        int nameW = textRenderer.getWidth(name);
        if (nameW >= avail) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(name, avail)), x + 24, y + 6, 0xFFFFFFFF);
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal(name), x + 24, y + 6, 0xFFFFFFFF);
            int rest = avail - nameW - 6;
            if (rest > 20) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(path, rest)),
                        x + 24 + nameW + 6, y + 6, 0xFF8A8A8A);
            }
        }
        if (remove) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("X"), x + b.getWidth() - 12, y + 6, 0xFFFF5555);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Item suchen & hinzufügen"), leftX, 46, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Wertvolle Items (Klick = entfernen)"), rightX, 46, 0xFFAAAAAA);

        for (int i = 0; i < SUGGESTIONS; i++) {
            Entry e = suggestionEntries[i];
            if (e != null && suggestionBtns[i].visible) {
                drawEntry(ctx, suggestionBtns[i], e.stack(), e.name(), e.path(), false);
            }
        }
        for (int i = 0; i < ROWS; i++) {
            String id = rowIds[i];
            if (id == null || !rowBtns[i].visible) continue;
            Entry e = byId.get(id);
            if (e != null) drawEntry(ctx, rowBtns[i], e.stack(), e.name(), e.path(), true);
            else drawEntry(ctx, rowBtns[i], ItemStack.EMPTY, id, "(unbekannt)", true);
        }
        int pages = Math.max(1, (cfg.valuableItems.size() + ROWS - 1) / ROWS);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + "/" + pages),
                rightX + colW / 2, pageY + 6, 0xFFFFFFFF);
    }

    @Override
    public void removed() {
        cfg.save();
    }

    @Override
    public void close() {
        cfg.save();
        client.setScreen(parent);
    }
}
