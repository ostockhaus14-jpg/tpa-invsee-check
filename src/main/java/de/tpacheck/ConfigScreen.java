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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Settings-Screen für Mod Menu: Schalter, Item-Suche mit Autocomplete und Liste der wertvollen Items. */
public class ConfigScreen extends Screen {
    private static final int SUGGESTIONS = 6;
    private static final int ROWS = 7;
    private static final int[] DELAYS = {5, 10, 20, 40};

    private record Entry(String id, String path, String name, Search.Doc doc, ItemStack stack) {}

    private final Screen parent;
    private final TpaConfig cfg = TpaInvseeCheck.CONFIG;

    private List<Entry> entries;
    private Map<String, Entry> byId;

    private TextFieldWidget search;
    private final ButtonWidget[] suggestionBtns = new ButtonWidget[SUGGESTIONS];
    private final Entry[] suggestionEntries = new Entry[SUGGESTIONS];
    private final ButtonWidget[] rowBtns = new ButtonWidget[ROWS];
    private final ButtonWidget[] rowDel = new ButtonWidget[ROWS];
    private final ItemRule[] rowRules = new ItemRule[ROWS];
    private ButtonWidget prevBtn, nextBtn;
    private int page = 0;
    private int leftX, rightX, colW, pageY;
    private int visSug = SUGGESTIONS, visRows = ROWS;
    private boolean compact;

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
        compact = bw < 125;

        // So viele Zeilen anzeigen, wie bei der aktuellen Fenstergröße Platz haben
        int searchY = 58;
        int avail = height - 26 - 6 - searchY;
        visSug = Math.max(1, Math.min(SUGGESTIONS, avail / 22));
        visRows = Math.max(1, Math.min(ROWS, (avail - 24) / 22));
        Arrays.fill(suggestionBtns, null);
        Arrays.fill(rowBtns, null);
        Arrays.fill(rowDel, null);
        Arrays.fill(suggestionEntries, null);
        Arrays.fill(rowRules, null);

        // Obere Reihe: Schalter
        toggle(leftX, 20, bw, "Mod aktiv", () -> cfg.enabled, v -> cfg.enabled = v);
        toggle(leftX + bw + 4, 20, bw, compact ? "Rüstung" : "Rüstung/Offhand",
                () -> cfg.scanEquipment, v -> cfg.scanEquipment = v);
        addDrawableChild(ButtonWidget.builder(delayText(), b -> {
            int next = DELAYS[0];
            for (int d : DELAYS) {
                if (d > cfg.scanDelayTicks) { next = d; break; }
            }
            cfg.scanDelayTicks = next;
            b.setMessage(delayText());
            cfg.save();
        }).dimensions(leftX + 2 * (bw + 4), 20, bw, 20).build());

        // Suche (links)
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

        for (int i = 0; i < visSug; i++) {
            final int idx = i;
            suggestionBtns[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                if (suggestionEntries[idx] != null) addItem(suggestionEntries[idx].id());
            }).dimensions(leftX, searchY + 22 + i * 22, colW, 20).build());
        }

        // Liste der wertvollen Items (rechts): Klick auf die Zeile = Bedingungen bearbeiten, X = entfernen
        for (int i = 0; i < visRows; i++) {
            final int idx = i;
            rowBtns[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                ItemRule r = rowRules[idx];
                if (r != null) client.setScreen(new RuleScreen(this, r));
            }).dimensions(rightX, searchY + i * 22, colW - 22, 20).build());
            rowDel[i] = addDrawableChild(ButtonWidget.builder(Text.literal("X").formatted(Formatting.RED), b -> {
                ItemRule r = rowRules[idx];
                if (r != null) {
                    cfg.rules.remove(r);
                    cfg.save();
                    updateRows();
                    updateSuggestions();
                }
            }).dimensions(rightX + colW - 20, searchY + i * 22, 20, 20).build());
        }
        pageY = searchY + visRows * 22;
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
        return Text.literal(compact ? "Wartezeit: " + cfg.scanDelayTicks : "Wartezeit: " + cfg.scanDelayTicks + " Ticks");
    }

    // ---------------------------------------------------------------- Items & Suche

    private void buildEntries() {
        entries = new ArrayList<>();
        byId = new HashMap<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR) continue;
            String name = item.getName().getString();
            Entry e = new Entry(id.toString(), id.getPath(), name, Search.doc(name, id.getPath()), new ItemStack(item));
            entries.add(e);
            byId.put(e.id(), e);
        }
    }

    private boolean hasPlainRule(String id) {
        for (ItemRule r : cfg.rules) {
            if (r.isPlain() && r.id.equals(id)) return true;
        }
        return false;
    }

    private void updateSuggestions() {
        if (search == null || suggestionBtns[0] == null) return;
        Arrays.fill(suggestionEntries, null);
        List<Entry> top = Search.top(search.getText(), entries, Entry::doc, visSug);
        for (int i = 0; i < visSug && i < top.size(); i++) suggestionEntries[i] = top.get(i);
        for (int i = 0; i < visSug; i++) {
            if (suggestionBtns[i] != null) suggestionBtns[i].visible = suggestionEntries[i] != null;
        }
    }

    private int pageCount() {
        return Math.max(1, (cfg.rules.size() + visRows - 1) / visRows);
    }

    private void updateRows() {
        if (rowBtns[0] == null) return;
        int size = cfg.rules.size();
        page = Math.max(0, Math.min(page, pageCount() - 1));
        for (int i = 0; i < visRows; i++) {
            int idx = page * visRows + i;
            rowRules[i] = idx < size ? cfg.rules.get(idx) : null;
            if (rowBtns[i] != null) rowBtns[i].visible = rowRules[i] != null;
            if (rowDel[i] != null) rowDel[i].visible = rowRules[i] != null;
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pageCount() - 1;
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
        if (!hasPlainRule(id)) { // gleiche Standardregel nicht doppelt anlegen
            cfg.rules.add(new ItemRule(id));
            cfg.save();
        }
        page = Integer.MAX_VALUE; // auf letzte Seite springen (wird in updateRows begrenzt)
        updateRows();
        updateSuggestions(); // Suchtext bleibt stehen
        setFocused(search);  // weitertippen ohne erneut ins Feld klicken zu müssen
    }

    // ---------------------------------------------------------------- Rendering / Schließen

    /** Zeichnet Item-Icon (mit Texturepack), Name und Zusatztext in einen Button. */
    private void drawEntry(DrawContext ctx, ButtonWidget b, ItemStack stack, String name, String second, int secondColor) {
        int x = b.getX(), y = b.getY();
        if (!stack.isEmpty()) ctx.drawItem(stack, x + 3, y + 2);
        int avail = b.getWidth() - 28;
        int nameW = textRenderer.getWidth(name);
        if (nameW >= avail) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(name, avail)), x + 24, y + 6, 0xFFFFFFFF);
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal(name), x + 24, y + 6, 0xFFFFFFFF);
            int rest = avail - nameW - 6;
            if (rest > 20 && !second.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(second, rest)),
                        x + 24 + nameW + 6, y + 6, secondColor);
            }
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Item suchen & hinzufügen"), leftX, 46, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Wertvolle Items (Klick = Bedingungen)"), rightX, 46, 0xFFAAAAAA);

        for (int i = 0; i < visSug; i++) {
            Entry e = suggestionEntries[i];
            if (e != null && suggestionBtns[i] != null && suggestionBtns[i].visible) {
                boolean in = hasPlainRule(e.id());
                drawEntry(ctx, suggestionBtns[i], e.stack(), e.name(), in ? "in der Liste" : e.path(),
                        in ? 0xFF55FF55 : 0xFF8A8A8A);
            }
        }
        for (int i = 0; i < visRows; i++) {
            ItemRule r = rowRules[i];
            if (r == null || rowBtns[i] == null || !rowBtns[i].visible) continue;
            Entry e = byId.get(r.id);
            String summary = RuleText.summary(r);
            if (e != null) {
                drawEntry(ctx, rowBtns[i], e.stack(), e.name(), summary.isEmpty() ? e.path() : summary,
                        summary.isEmpty() ? 0xFF8A8A8A : 0xFFFFD75E);
            } else {
                drawEntry(ctx, rowBtns[i], ItemStack.EMPTY, r.id, "(unbekannt)", 0xFFFF5555);
            }
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + "/" + pageCount()),
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
