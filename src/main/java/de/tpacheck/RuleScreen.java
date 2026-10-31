package de.tpacheck;

import net.minecraft.component.DataComponentTypes;
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
import java.util.List;

/**
 * Bedingungen für ein einzelnes wertvolles Item: Mindestanzahl, Verzauberungen (verzauberbare Items und Bücher),
 * Flugdauer (Feuerwerk) bzw. Effekt (Tränke, getippte Pfeile).
 */
public class RuleScreen extends Screen {
    private static final int[] COUNTS = {1, 2, 4, 8, 16, 32, 64};
    private static final int SUG = 6;
    private static final int REQS = 6;
    private static final int EFFECT_MAX_LEVEL = 10;

    private enum Kind { ENCHANT, EFFECT, NONE }

    private record Opt(String id, String name, String path, int max, Search.Doc doc) {}

    private final Screen parent;
    private final ItemRule rule;
    private final TpaConfig cfg = TpaInvseeCheck.CONFIG;
    private final ItemStack stack;
    private final String itemName;
    private final Kind kind;
    private final boolean firework;
    private final ItemStack pickIcon;
    private List<Opt> options;

    private TextFieldWidget search;
    private final ButtonWidget[] sugBtns = new ButtonWidget[SUG];
    private final Opt[] sugOpts = new Opt[SUG];
    private final ButtonWidget[] reqLabel = new ButtonWidget[REQS];
    private final ButtonWidget[] reqMinus = new ButtonWidget[REQS];
    private final ButtonWidget[] reqPlus = new ButtonWidget[REQS];
    private final ButtonWidget[] reqDel = new ButtonWidget[REQS];
    private ButtonWidget prevBtn, nextBtn;
    private int page = 0;
    private int visSug = SUG, visReqs = REQS;
    private int leftX, rightX, colW, pageY;

    public RuleScreen(Screen parent, ItemRule rule) {
        super(Text.literal("Bedingungen"));
        this.parent = parent;
        this.rule = rule;

        Identifier ident = Identifier.tryParse(rule.id);
        Item item = (ident != null && Registries.ITEM.containsId(ident)) ? Registries.ITEM.get(ident) : Items.AIR;
        ItemStack st = new ItemStack(item);
        this.stack = st;
        this.itemName = item == Items.AIR ? rule.id : item.getName().getString();
        this.firework = item == Items.FIREWORK_ROCKET;

        boolean potionLike = item == Items.POTION || item == Items.SPLASH_POTION
                || item == Items.LINGERING_POTION || item == Items.TIPPED_ARROW;
        boolean enchantable = item == Items.BOOK || item == Items.ENCHANTED_BOOK
                || (!st.isEmpty() && (st.isDamageable() || st.contains(DataComponentTypes.ENCHANTABLE)));
        this.kind = potionLike ? Kind.EFFECT : (enchantable ? Kind.ENCHANT : Kind.NONE);
        this.pickIcon = new ItemStack(kind == Kind.EFFECT ? Items.POTION : Items.ENCHANTED_BOOK);
    }

    // ---------------------------------------------------------------- Aufbau

    private List<Opt> buildOptions() {
        List<Opt> l = new ArrayList<>();
        if (kind == Kind.ENCHANT) {
            for (RuleText.Ench e : RuleText.ENCHANTS) {
                String name = RuleText.enchantName(e.id());
                String path = pathOf(e.id());
                l.add(new Opt(e.id(), name, path, e.max(), Search.doc(name, path)));
            }
        } else if (kind == Kind.EFFECT) {
            for (Identifier id : Registries.STATUS_EFFECT.getIds()) {
                String name = Registries.STATUS_EFFECT.get(id).getName().getString();
                l.add(new Opt(id.toString(), name, id.getPath(), EFFECT_MAX_LEVEL, Search.doc(name, id.getPath())));
            }
        }
        return l;
    }

    private static String pathOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    @Override
    protected void init() {
        if (options == null) options = buildOptions();

        colW = Math.min(190, (width - 30) / 2);
        leftX = width / 2 - colW - 5;
        rightX = width / 2 + 5;

        int top = 62;
        int avail = height - 26 - 6 - top;
        visSug = Math.max(1, Math.min(SUG, avail / 22));
        visReqs = Math.max(1, Math.min(REQS, (avail - 24) / 22));
        Arrays.fill(sugBtns, null);
        Arrays.fill(sugOpts, null);
        Arrays.fill(reqLabel, null);
        Arrays.fill(reqMinus, null);
        Arrays.fill(reqPlus, null);
        Arrays.fill(reqDel, null);
        prevBtn = null;
        nextBtn = null;

        // Obere Reihe: Mindestanzahl (links) und typabhängige Einstellung (rechts)
        addDrawableChild(ButtonWidget.builder(countText(), b -> {
            int next = COUNTS[0];
            for (int c : COUNTS) {
                if (c > rule.minCount) { next = c; break; }
            }
            rule.minCount = next;
            b.setMessage(countText());
            cfg.save();
        }).dimensions(leftX, 24, colW, 20).build());

        if (kind == Kind.ENCHANT) {
            addDrawableChild(ButtonWidget.builder(modeText(), b -> {
                rule.anyEnchant = !rule.anyEnchant;
                b.setMessage(modeText());
                cfg.save();
            }).dimensions(rightX, 24, colW, 20).build());
        } else if (firework) {
            addDrawableChild(ButtonWidget.builder(flightText(), b -> {
                rule.minFlight = rule.minFlight >= 3 ? 0 : rule.minFlight + 1;
                b.setMessage(flightText());
                cfg.save();
            }).dimensions(rightX, 24, colW, 20).build());
        }

        if (kind != Kind.NONE) {
            // Suche + Vorschläge (links)
            String old = search == null ? "" : search.getText();
            search = new TextFieldWidget(textRenderer, leftX, top, colW, 20, Text.literal("Suche"));
            search.setMaxLength(64);
            search.setPlaceholder(Text.literal(kind == Kind.ENCHANT ? "Verzauberung suchen..." : "Effekt suchen..."));
            search.setText(old);
            search.setChangedListener(s -> updateSuggestions());
            addDrawableChild(search);
            setInitialFocus(search);

            for (int i = 0; i < visSug; i++) {
                final int idx = i;
                sugBtns[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                    if (sugOpts[idx] != null) addOpt(sugOpts[idx]);
                }).dimensions(leftX, top + 22 + i * 22, colW, 20).build());
            }

            // Gewählte Bedingungen (rechts): Label, -, +, X
            for (int i = 0; i < visReqs; i++) {
                final int idx = i;
                int y = top + i * 22;
                reqLabel[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {})
                        .dimensions(rightX, y, colW - 66, 20).build());
                reqLabel[i].active = false;
                reqMinus[i] = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> changeLevel(page * visReqs + idx, -1))
                        .dimensions(rightX + colW - 64, y, 20, 20).build());
                reqPlus[i] = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> changeLevel(page * visReqs + idx, 1))
                        .dimensions(rightX + colW - 42, y, 20, 20).build());
                reqDel[i] = addDrawableChild(ButtonWidget.builder(Text.literal("X").formatted(Formatting.RED),
                        b -> removeReq(page * visReqs + idx)).dimensions(rightX + colW - 20, y, 20, 20).build());
            }
            pageY = top + visReqs * 22;
            prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page--; updateReqs(); })
                    .dimensions(rightX, pageY, 40, 20).build());
            nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page++; updateReqs(); })
                    .dimensions(rightX + colW - 40, pageY, 40, 20).build());
        }

        // Unten: Regel löschen / Fertig
        addDrawableChild(ButtonWidget.builder(Text.literal("Regel löschen").formatted(Formatting.RED), b -> {
            cfg.rules.remove(rule);
            cfg.save();
            client.setScreen(parent);
        }).dimensions(width / 2 - 154, height - 26, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"), b -> close())
                .dimensions(width / 2 + 4, height - 26, 150, 20).build());

        updateSuggestions();
        updateReqs();
    }

    private Text countText() {
        return Text.literal("Mindestanzahl: " + rule.minCount);
    }

    private Text modeText() {
        return Text.literal("Verzauberungen: " + (rule.anyEnchant ? "EINE davon" : "ALLE"));
    }

    private Text flightText() {
        return Text.literal(rule.minFlight <= 0 ? "Flugdauer: egal" : "Flugdauer: mind. " + rule.minFlight);
    }

    // ---------------------------------------------------------------- Bedingungen

    private int reqCount() {
        if (kind == Kind.ENCHANT) return rule.enchants.size();
        if (kind == Kind.EFFECT) return rule.effect.isEmpty() ? 0 : 1;
        return 0;
    }

    private int pageCount() {
        return Math.max(1, (reqCount() + visReqs - 1) / visReqs);
    }

    private String reqName(int i) {
        if (kind == Kind.ENCHANT) return RuleText.enchantName(rule.enchants.get(i).id);
        return RuleText.effectName(rule.effect);
    }

    private int reqLevel(int i) {
        return kind == Kind.ENCHANT ? rule.enchants.get(i).level : rule.effectLevel;
    }

    private void changeLevel(int i, int d) {
        if (i < 0 || i >= reqCount()) return;
        if (kind == Kind.ENCHANT) {
            ItemRule.EnchantReq q = rule.enchants.get(i);
            q.level = Math.max(1, Math.min(RuleText.enchantMax(q.id), q.level + d));
        } else {
            rule.effectLevel = Math.max(1, Math.min(EFFECT_MAX_LEVEL, rule.effectLevel + d));
        }
        cfg.save();
    }

    private void removeReq(int i) {
        if (i < 0 || i >= reqCount()) return;
        if (kind == Kind.ENCHANT) {
            rule.enchants.remove(i);
        } else {
            rule.effect = "";
            rule.effectLevel = 1;
        }
        cfg.save();
        updateReqs();
        updateSuggestions();
    }

    private boolean isSelected(String id) {
        if (kind == Kind.ENCHANT) {
            for (ItemRule.EnchantReq q : rule.enchants) {
                if (q.id.equals(id)) return true;
            }
            return false;
        }
        return id.equals(rule.effect);
    }

    private void addOpt(Opt o) {
        if (kind == Kind.ENCHANT) {
            if (!isSelected(o.id())) rule.enchants.add(new ItemRule.EnchantReq(o.id(), o.max())); // Standard: Höchststufe
        } else {
            rule.effect = o.id();
            rule.effectLevel = 1;
        }
        cfg.save();
        page = Integer.MAX_VALUE;
        updateReqs();
        updateSuggestions();
        setFocused(search); // Suchtext bleibt stehen, man kann direkt weitertippen
    }

    private void updateSuggestions() {
        if (search == null || sugBtns[0] == null) return;
        Arrays.fill(sugOpts, null);
        List<Opt> pool = new ArrayList<>();
        for (Opt o : options) {
            if (!isSelected(o.id())) pool.add(o);
        }
        List<Opt> top = Search.top(search.getText(), pool, Opt::doc, visSug);
        for (int i = 0; i < visSug && i < top.size(); i++) sugOpts[i] = top.get(i);
        for (int i = 0; i < visSug; i++) {
            if (sugBtns[i] != null) sugBtns[i].visible = sugOpts[i] != null;
        }
    }

    private void updateReqs() {
        if (reqLabel[0] == null) return;
        int n = reqCount();
        page = Math.max(0, Math.min(page, pageCount() - 1));
        for (int i = 0; i < visReqs; i++) {
            boolean vis = page * visReqs + i < n;
            if (reqLabel[i] != null) reqLabel[i].visible = vis;
            if (reqMinus[i] != null) reqMinus[i].visible = vis;
            if (reqPlus[i] != null) reqPlus[i].visible = vis;
            if (reqDel[i] != null) reqDel[i].visible = vis;
        }
        if (prevBtn != null) prevBtn.active = page > 0;
        if (nextBtn != null) nextBtn.active = page < pageCount() - 1;
    }

    // ---------------------------------------------------------------- Rendering / Schließen

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        String t = "Bedingungen: " + itemName;
        int x0 = width / 2 - (textRenderer.getWidth(t) + 20) / 2;
        if (!stack.isEmpty()) ctx.drawItem(stack, x0, 3);
        ctx.drawTextWithShadow(textRenderer, Text.literal(t), x0 + 20, 8, 0xFFFFFFFF);

        if (kind == Kind.NONE) {
            String note = firework
                    ? "Feuerwerk: Die Flugdauer stellst du oben rechts ein."
                    : "Für dieses Item gibt es nur die Mindestanzahl.";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(note), width / 2, 70, 0xFFAAAAAA);
            return;
        }

        ctx.drawTextWithShadow(textRenderer,
                Text.literal(kind == Kind.ENCHANT ? "Verzauberung suchen & hinzufügen" : "Effekt suchen"),
                leftX, 50, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(kind == Kind.ENCHANT ? "Benötigt (Stufe mind.)" : "Benötigter Effekt (Stufe mind.)"),
                rightX, 50, 0xFFAAAAAA);

        for (int i = 0; i < visSug; i++) {
            Opt o = sugOpts[i];
            if (o == null || sugBtns[i] == null || !sugBtns[i].visible) continue;
            ButtonWidget b = sugBtns[i];
            ctx.drawItem(pickIcon, b.getX() + 3, b.getY() + 2);
            int avail = b.getWidth() - 28;
            int nameW = textRenderer.getWidth(o.name());
            if (nameW >= avail) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(o.name(), avail)),
                        b.getX() + 24, b.getY() + 6, 0xFFFFFFFF);
            } else {
                ctx.drawTextWithShadow(textRenderer, Text.literal(o.name()), b.getX() + 24, b.getY() + 6, 0xFFFFFFFF);
                int rest = avail - nameW - 6;
                if (rest > 20) {
                    ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(o.path(), rest)),
                            b.getX() + 24 + nameW + 6, b.getY() + 6, 0xFF8A8A8A);
                }
            }
        }

        for (int i = 0; i < visReqs; i++) {
            if (reqLabel[i] == null || !reqLabel[i].visible) continue;
            int idx = page * visReqs + i;
            if (idx >= reqCount()) continue;
            ButtonWidget b = reqLabel[i];
            String name = reqName(idx);
            String lvl = "mind. " + reqLevel(idx);
            int avail = b.getWidth() - 8;
            int nameW = textRenderer.getWidth(name);
            if (nameW >= avail) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(name, avail)),
                        b.getX() + 4, b.getY() + 6, 0xFFFFFFFF);
            } else {
                ctx.drawTextWithShadow(textRenderer, Text.literal(name), b.getX() + 4, b.getY() + 6, 0xFFFFFFFF);
                int rest = avail - nameW - 6;
                if (rest > 20) {
                    ctx.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(lvl, rest)),
                            b.getX() + 4 + nameW + 6, b.getY() + 6, 0xFFFFD75E);
                }
            }
        }
        if (reqCount() > visReqs) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + "/" + pageCount()),
                    rightX + colW / 2, pageY + 6, 0xFFFFFFFF);
        }
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
