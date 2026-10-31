package de.tpacheck;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Settings-Screen für Mod Menu: Schalter, Item-Suche mit Autocomplete und Liste der wertvollen Items. */
public class ConfigScreen extends Screen {
    private static final int SUGGESTIONS = 4;
    private static final int ROWS = 7;
    private static final int[] DELAYS = {5, 10, 20, 40};

    private record Entry(String id, String path, String name, String nameLower) {}

    private final Screen parent;
    private final TpaConfig cfg = TpaInvseeCheck.CONFIG;

    private List<Entry> entries;
    private TextFieldWidget search;
    private final ButtonWidget[] suggestionBtns = new ButtonWidget[SUGGESTIONS];
    private final String[] suggestionIds = new String[SUGGESTIONS];
    private final ButtonWidget[] rowBtns = new ButtonWidget[ROWS];
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
        if (entries == null) entries = buildEntries();

        colW = Math.min(190, (width - 30) / 2);
        leftX = width / 2 - colW - 5;
        rightX = width / 2 + 5;

        int y = 30;
        toggle(leftX, y, colW, "Mod aktiv", () -> cfg.enabled, v -> cfg.enabled = v);
        toggle(leftX, y + 22, colW, "Rüstung/Offhand prüfen", () -> cfg.scanEquipment, v -> cfg.scanEquipment = v);
        addDrawableChild(ButtonWidget.builder(delayText(), b -> {
            int next = DELAYS[0];
            for (int d : DELAYS) {
                if (d > cfg.scanDelayTicks) { next = d; break; }
            }
            cfg.scanDelayTicks = next;
            b.setMessage(delayText());
            cfg.save();
        }).dimensions(leftX, y + 44, colW, 20).build());

        // Suche + Hinzufügen
        int searchY = 100;
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
                if (suggestionIds[idx] != null) addItem(suggestionIds[idx]);
            }).dimensions(leftX, searchY + 22 + i * 22, colW, 20).build());
        }

        // Liste der wertvollen Items
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
            }).dimensions(rightX, 30 + i * 22, colW, 20).build());
        }
        pageY = 30 + ROWS * 22;
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
        return Text.literal("Scan-Wartezeit: " + cfg.scanDelayTicks + " Ticks");
    }

    // ---------------------------------------------------------------- Items

    private static List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR) continue;
            String name = item.getName().getString();
            String s = id.toString();
            list.add(new Entry(s, id.getPath(), name, name.toLowerCase(Locale.ROOT)));
        }
        return list;
    }

    private static String niceName(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident != null && Registries.ITEM.containsId(ident)) {
            Item it = Registries.ITEM.get(ident);
            if (it != Items.AIR) return it.getName().getString() + " (" + ident.getPath() + ")";
        }
        return id + " (?)";
    }

    private String trim(String s, int maxWidth) {
        return textRenderer.trimToWidth(s, maxWidth);
    }

    private void updateSuggestions() {
        if (search == null || suggestionBtns[0] == null) return;
        Arrays.fill(suggestionIds, null);
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());

        List<Entry> prefix = new ArrayList<>();
        List<Entry> contains = new ArrayList<>();
        if (!q.isEmpty()) {
            String[] tokens = q.split("[\\s_]+");
            for (Entry e : entries) {
                if (cfg.valuableItems.contains(e.id())) continue;
                String hay = e.path() + " " + e.nameLower();
                boolean all = true;
                for (String t : tokens) {
                    if (!t.isEmpty() && !hay.contains(t)) { all = false; break; }
                }
                if (!all) continue;
                if (e.path().startsWith(tokens[0]) || e.nameLower().startsWith(tokens[0])) prefix.add(e);
                else contains.add(e);
            }
        }
        Comparator<Entry> cmp = Comparator.comparing(Entry::nameLower);
        prefix.sort(cmp);
        contains.sort(cmp);
        prefix.addAll(contains);

        for (int i = 0; i < SUGGESTIONS; i++) {
            if (i < prefix.size()) {
                Entry e = prefix.get(i);
                suggestionIds[i] = e.id();
                suggestionBtns[i].setMessage(Text.literal(trim("+ " + e.name() + " (" + e.path() + ")", colW - 8)));
                suggestionBtns[i].visible = true;
            } else {
                suggestionBtns[i].visible = false;
            }
        }
    }

    private void updateRows() {
        if (rowBtns[0] == null) return;
        int size = cfg.valuableItems.size();
        int pages = Math.max(1, (size + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < ROWS; i++) {
            int idx = page * ROWS + i;
            if (idx < size) {
                rowBtns[i].setMessage(Text.literal(trim("[X] " + niceName(cfg.valuableItems.get(idx)), colW - 8)));
                rowBtns[i].visible = true;
            } else {
                rowBtns[i].visible = false;
            }
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages - 1;
    }

    /** "Add"-Button: exakte ID wenn gültig, sonst der beste Autocomplete-Treffer. */
    private void addTyped() {
        String typed = search.getText();
        String id = TpaConfig.normalizeId(typed);
        Identifier ident = id == null ? null : Identifier.tryParse(id);
        if (ident != null && Registries.ITEM.containsId(ident) && Registries.ITEM.get(ident) != Items.AIR) {
            addItem(id);
        } else if (suggestionIds[0] != null) {
            addItem(suggestionIds[0]);
        }
    }

    private void addItem(String id) {
        if (!cfg.valuableItems.contains(id)) {
            cfg.valuableItems.add(id);
            cfg.save();
        }
        page = Integer.MAX_VALUE; // auf letzte Seite springen (wird in updateRows begrenzt)
        search.setText(""); // löst updateSuggestions() aus
        updateRows();
    }

    // ---------------------------------------------------------------- Rendering / Schließen

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Einstellungen & Item suchen"), leftX, 19, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Wertvolle Items (Klick = entfernen)"), rightX, 19, 0xFFAAAAAA);
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
