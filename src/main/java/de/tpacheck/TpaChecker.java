package de.tpacheck;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ablauf: TPA-Nachricht erkannt -> (wenn kein anderes Fenster offen ist) /invsee NAME
 * -> Container-Screen (6 Reihen) scannen -> bei Fund Buttons einblenden, sonst Fenster schließen.
 */
public final class TpaChecker {
    /**
     * Erkennt NUR die Zeile "Annehmen - /tpaccept NAME tpa". Bei tpahere steht dort etwas anderes
     * (tpahere / "tpa here"), das wird per Lookahead ausgeschlossen.
     */
    private static final Pattern TPA_ACCEPT = Pattern.compile(
            "/tpaccept\\s+([A-Za-z0-9_.]{1,20})\\s+tpa\\b(?!\\s*here)", Pattern.CASE_INSENSITIVE);
    /** Alte Farbcodes ("§a") entfernen, falls ein Server sie als normalen Text schickt. */
    private static final Pattern FORMAT_CODES = Pattern.compile("\u00a7.");

    // Layout des Invsee-Menüs (6 Reihen = 54 Slots), passend zum Screenshot:
    // 0-35 Inventar | 36 Armorstand-Label | 37-40 Rüstung | 41-44 Barrieren
    // 45 Nether-Star-Label | 46 Offhand | 47-53 Barrieren
    private static final int INVENTORY_END = 36;
    private static final int ARMOR_FIRST = 37, ARMOR_LAST = 40;
    private static final int OFFHAND = 46;

    private static final int MAX_QUEUE = 5;               // Schutz gegen Spam
    private static final long REQUEST_TTL = 2400;         // Ticks (2 min): ältere Anfragen verwerfen
    private static final long SAME_PLAYER_COOLDOWN = 200; // Ticks (10 s): gleicher Spieler wird nicht ständig neu geprüft
    private static final int WAIT_FOR_SCREEN_TICKS = 60;  // so lange auf das Invsee-Fenster warten

    private enum Phase { IDLE, WAITING, SCANNING, FOUND }

    private record Pending(String name, long created) {}

    private static final Deque<Pending> queue = new ArrayDeque<>();
    private static final Map<String, Long> lastScan = new HashMap<>();
    private static Phase phase = Phase.IDLE;
    private static String current;
    private static long now;
    private static int ticks;
    private static int cooldown;
    private static int baseSyncId;
    private static Screen activeScreen;

    private static Screen uiScreen;
    private static ButtonWidget labelBtn, acceptBtn, denyBtn;

    private TpaChecker() {}

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onMessage(message);
        });
        ClientTickEvents.END_CLIENT_TICK.register(TpaChecker::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            // Nur während eines laufenden Checks Buttons anlegen (normale Kisten bleiben unberührt)
            if (phase != Phase.IDLE && screen instanceof GenericContainerScreen gcs) buildButtons(gcs, w, h);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            queue.clear();
            lastScan.clear();
            release();
        });
    }

    // ---------------------------------------------------------------- Chat

    private static void onMessage(Text message) {
        TpaConfig cfg = TpaInvseeCheck.CONFIG;
        if (!cfg.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!serverAllowed(mc)) return;

        String text = FORMAT_CODES.matcher(message.getString().replace('\u00a0', ' ')).replaceAll("");
        Matcher m = TPA_ACCEPT.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) continue;
            if (name.equalsIgnoreCase(current)) continue;
            boolean queued = false;
            for (Pending p : queue) {
                if (p.name().equalsIgnoreCase(name)) { queued = true; break; }
            }
            if (queued || queue.size() >= MAX_QUEUE) continue;
            Long last = lastScan.get(name.toLowerCase(Locale.ROOT));
            if (last != null && now - last < SAME_PLAYER_COOLDOWN) continue;
            queue.add(new Pending(name, now));
        }
    }

    private static boolean serverAllowed(MinecraftClient mc) {
        String f = TpaInvseeCheck.CONFIG.serverFilter;
        if (f == null || f.isBlank()) return true;
        ServerInfo info = mc.getCurrentServerEntry();
        return info != null && info.address != null
                && info.address.toLowerCase(Locale.ROOT).contains(f.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- Tick / Zustandsmaschine

    private static void tick(MinecraftClient mc) {
        now++;
        if (mc.player == null || mc.getNetworkHandler() == null) {
            if (phase != Phase.IDLE) release();
            return;
        }
        if (cooldown > 0) cooldown--;

        switch (phase) {
            case IDLE -> {
                if (!TpaInvseeCheck.CONFIG.enabled) {
                    queue.clear();
                    return;
                }
                while (!queue.isEmpty() && now - queue.peek().created() > REQUEST_TTL) queue.poll();
                // Nur starten, wenn der Spieler gerade kein Fenster offen hat (Chat, Kiste, Menü ...),
                // damit nichts unterbrochen oder weggeschlossen wird.
                if (cooldown == 0 && !queue.isEmpty() && mc.currentScreen == null) start(mc);
            }
            case WAITING -> {
                ticks++;
                Screen s = mc.currentScreen;
                if (s instanceof GenericContainerScreen g
                        && g.getScreenHandler().getRows() == 6
                        && g.getScreenHandler().syncId != baseSyncId) {
                    activeScreen = s;
                    phase = Phase.SCANNING;
                    ticks = 0;
                } else if (ticks > WAIT_FOR_SCREEN_TICKS) {
                    chat(mc, Formatting.RED, "Kein /invsee-Fenster für " + current
                            + " erhalten (keine Rechte, Spieler offline oder anderes Menü?).");
                    release();
                    cooldown = 10;
                }
            }
            case SCANNING -> {
                ticks++;
                if (mc.currentScreen != activeScreen) { // Fenster wurde geschlossen
                    release();
                    return;
                }
                List<String> found = scan((GenericContainerScreen) activeScreen);
                if (!found.isEmpty()) {
                    phase = Phase.FOUND;
                    if (uiScreen != activeScreen) { // Sicherheitsnetz: Buttons fehlen noch
                        buildButtons((GenericContainerScreen) activeScreen, activeScreen.width, activeScreen.height);
                    }
                    setButtonsVisible(true);
                    chat(mc, Formatting.GOLD, "PLAYER GEFUNDEN: " + current + " hat " + String.join(", ", found));
                } else if (ticks >= TpaInvseeCheck.CONFIG.scanDelayTicks) {
                    chat(mc, Formatting.GRAY, current + ": nichts Wertvolles gefunden.");
                    mc.player.closeHandledScreen();
                    release();
                    cooldown = 10;
                }
            }
            case FOUND -> {
                if (mc.currentScreen != activeScreen) release(); // Spieler hat das Fenster selbst geschlossen
            }
        }
    }

    private static void start(MinecraftClient mc) {
        Pending p = queue.poll();
        if (p == null) return;
        current = p.name();
        lastScan.put(current.toLowerCase(Locale.ROOT), now);
        lastScan.values().removeIf(t -> now - t > 6000);
        ticks = 0;
        phase = Phase.WAITING;
        baseSyncId = mc.player.currentScreenHandler.syncId;
        mc.getNetworkHandler().sendChatCommand("invsee " + current);
    }

    private static void release() {
        setButtonsVisible(false);
        labelBtn = null;
        acceptBtn = null;
        denyBtn = null;
        uiScreen = null;
        activeScreen = null;
        current = null;
        phase = Phase.IDLE;
    }

    // ---------------------------------------------------------------- Scan

    private static boolean isScannableSlot(int i) {
        if (i < INVENTORY_END) return true;
        if (!TpaInvseeCheck.CONFIG.scanEquipment) return false;
        return (i >= ARMOR_FIRST && i <= ARMOR_LAST) || i == OFFHAND;
    }

    private static List<String> scan(GenericContainerScreen screen) {
        List<ItemRule> rules = TpaInvseeCheck.CONFIG.rules;
        if (rules.isEmpty()) return List.of();

        GenericContainerScreenHandler h = screen.getScreenHandler();
        List<ItemStack> stacks = new ArrayList<>();
        int size = Math.min(h.getRows() * 9, h.slots.size()); // nur der Container, nie das eigene Inventar darunter
        for (int i = 0; i < size; i++) {
            if (!isScannableSlot(i)) continue;
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty() || st.isOf(Items.BARRIER)) continue;
            stacks.add(st);
        }

        Set<String> out = new LinkedHashSet<>();
        for (ItemRule r : rules) {
            int sum = 0;
            ItemStack sample = null;
            for (ItemStack st : stacks) {
                if (ItemMatcher.matches(r, st)) {
                    sum += st.getCount();
                    if (sample == null) sample = st;
                }
            }
            if (sample != null && sum >= r.minCount) out.add(sum + "x " + ItemMatcher.describe(sample));
        }
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------- Buttons über dem Inventar

    private static void buildButtons(GenericContainerScreen screen, int w, int h) {
        int bgH = 114 + screen.getScreenHandler().getRows() * 18;
        int top = (h - bgH) / 2;
        int y = Math.max(2, top - 24);
        int total = 130 + 2 + 100 + 2 + 80;
        int x = Math.max(2, (w - total) / 2);

        labelBtn = ButtonWidget.builder(
                Text.literal("PLAYER GEFUNDEN").formatted(Formatting.GOLD, Formatting.BOLD), b -> {})
                .dimensions(x, y, 130, 20).build();
        labelBtn.active = false;
        acceptBtn = ButtonWidget.builder(
                Text.literal("TPA ANNEHMEN").formatted(Formatting.GREEN, Formatting.BOLD), b -> respond(true))
                .dimensions(x + 132, y, 100, 20).build();
        denyBtn = ButtonWidget.builder(
                Text.literal("ABLEHNEN").formatted(Formatting.RED, Formatting.BOLD), b -> respond(false))
                .dimensions(x + 234, y, 80, 20).build();

        uiScreen = screen;
        Screens.getButtons(screen).add(labelBtn);
        Screens.getButtons(screen).add(acceptBtn);
        Screens.getButtons(screen).add(denyBtn);
        setButtonsVisible(phase == Phase.FOUND && screen == activeScreen);
    }

    private static void setButtonsVisible(boolean visible) {
        boolean v = visible && uiScreen != null && uiScreen == activeScreen;
        if (labelBtn != null) labelBtn.visible = v;
        if (acceptBtn != null) acceptBtn.visible = v;
        if (denyBtn != null) denyBtn.visible = v;
    }

    private static void respond(boolean accept) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (phase != Phase.FOUND || current == null || mc.player == null || mc.getNetworkHandler() == null) return;
        String name = current;
        mc.getNetworkHandler().sendChatCommand((accept ? "tpaccept " : "tpdeny ") + name + " tpa");
        mc.player.closeHandledScreen();
        release();
        cooldown = 10;
    }

    private static void chat(MinecraftClient mc, Formatting color, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[TPA-Check] " + msg).formatted(color), false);
        }
    }
}
