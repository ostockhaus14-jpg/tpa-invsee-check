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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ablauf: TPA-Nachricht erkannt -> /invsee NAME -> Container-Screen (6 Reihen) scannen
 * -> bei Fund Buttons einblenden, sonst Fenster schließen.
 */
public final class TpaChecker {
    /**
     * Erkennt NUR die Zeile "Annehmen - /tpaccept NAME tpa". Bei tpahere steht dort etwas anderes
     * (tpahere / "tpa here"), das wird per Lookahead ausgeschlossen.
     */
    private static final Pattern TPA_ACCEPT = Pattern.compile(
            "/tpaccept\\s+([A-Za-z0-9_.]{1,20})\\s+tpa\\b(?!\\s*here)", Pattern.CASE_INSENSITIVE);

    // Layout des Invsee-Menüs (6 Reihen = 54 Slots), passend zu deinem Screenshot:
    // 0-35 Inventar | 36 Armorstand-Label | 37-40 Rüstung | 41-44 Barrieren
    // 45 Nether-Star-Label | 46 Offhand | 47-53 Barrieren
    private static final int INVENTORY_END = 36;
    private static final int ARMOR_FIRST = 37, ARMOR_LAST = 40;
    private static final int OFFHAND = 46;

    private enum Phase { IDLE, WAITING, SCANNING, FOUND }

    private static final Deque<String> queue = new ArrayDeque<>();
    private static Phase phase = Phase.IDLE;
    private static String current;
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
            if (screen instanceof GenericContainerScreen gcs) buildButtons(gcs, w, h);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            queue.clear();
            release();
        });
    }

    // ---------------------------------------------------------------- Chat

    private static void onMessage(Text message) {
        TpaConfig cfg = TpaInvseeCheck.CONFIG;
        if (!cfg.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!serverAllowed(mc)) return;
        Matcher m = TPA_ACCEPT.matcher(message.getString());
        while (m.find()) {
            String name = m.group(1);
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) continue;
            if (name.equalsIgnoreCase(current)) continue;
            boolean queued = false;
            for (String q : queue) if (q.equalsIgnoreCase(name)) queued = true;
            if (!queued) queue.add(name);
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
        if (mc.player == null || mc.getNetworkHandler() == null) {
            if (phase != Phase.IDLE) release();
            return;
        }
        if (cooldown > 0) cooldown--;

        switch (phase) {
            case IDLE -> {
                if (cooldown == 0 && !queue.isEmpty() && TpaInvseeCheck.CONFIG.enabled) start(mc);
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
                } else if (ticks > 60) {
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
        current = queue.poll();
        if (current == null) return;
        ticks = 0;
        phase = Phase.WAITING;
        baseSyncId = mc.player.currentScreenHandler.syncId;
        mc.getNetworkHandler().sendChatCommand("invsee " + current);
    }

    private static void release() {
        phase = Phase.IDLE;
        current = null;
        activeScreen = null;
        setButtonsVisible(false);
    }

    // ---------------------------------------------------------------- Scan

    private static boolean isScannableSlot(int i) {
        if (i < INVENTORY_END) return true;
        if (!TpaInvseeCheck.CONFIG.scanEquipment) return false;
        return (i >= ARMOR_FIRST && i <= ARMOR_LAST) || i == OFFHAND;
    }

    private static List<String> scan(GenericContainerScreen screen) {
        GenericContainerScreenHandler h = screen.getScreenHandler();
        Set<String> valuable = new HashSet<>(TpaInvseeCheck.CONFIG.valuableItems);
        if (valuable.isEmpty()) return List.of();

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        int size = h.getRows() * 9; // nur der Container, nie das eigene Inventar darunter
        for (int i = 0; i < size; i++) {
            if (!isScannableSlot(i)) continue;
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty() || st.isOf(Items.BARRIER)) continue;
            String id = Registries.ITEM.getId(st.getItem()).toString();
            if (!valuable.contains(id)) continue;
            counts.merge(id, st.getCount(), Integer::sum);
            names.putIfAbsent(id, st.getName().getString());
        }
        List<String> out = new ArrayList<>();
        counts.forEach((id, c) -> out.add(c + "x " + names.get(id)));
        return out;
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
