package de.tpacheck;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Fehlertolerante Suche für den Autocomplete (reines Java, ohne Minecraft-Klassen). */
public final class Search {
    private Search() {}

    /** Durchsuchbare Darstellung eines Eintrags (Anzeigename + ID, normalisiert). */
    public record Doc(String name, String nameN, String compact, String[] words,
                      String[] nameWords, String[] pathWords) {}

    public record Query(String qn, String[] tokens, String compact) {
        public boolean isEmpty() { return qn.isEmpty(); }
    }

    private record Scored<T>(T item, int score, Doc doc) {}

    public static Query query(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        String qn = norm(q);
        return new Query(qn, qn.isEmpty() ? new String[0] : qn.split(" "), qn.replace(" ", ""));
    }

    public static Doc doc(String name, String idPath) {
        String nameN = norm(name);
        String pathN = norm(idPath);
        String[] nw = nameN.isEmpty() ? new String[0] : nameN.split(" ");
        String[] pw = pathN.isEmpty() ? new String[0] : pathN.split(" ");
        Set<String> all = new LinkedHashSet<>();
        all.addAll(Arrays.asList(nw));
        all.addAll(Arrays.asList(pw));
        return new Doc(name, nameN, (nameN + "|" + pathN).replace(" ", ""),
                all.toArray(new String[0]), nw, pw);
    }

    /** Kleinbuchstaben, ohne Umlaute/Akzente (ä->a, ß->ss), Sonderzeichen -> Leerzeichen. */
    public static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replace("\u00df", "ss");
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Die besten Treffer (beste zuerst). */
    public static <T> List<T> top(String raw, Collection<T> items, Function<T, Doc> docOf, int limit) {
        Query q = query(raw);
        List<T> out = new ArrayList<>();
        if (q.isEmpty() || limit <= 0) return out;
        List<Scored<T>> hits = new ArrayList<>();
        for (T it : items) {
            Doc d = docOf.apply(it);
            int s = score(q, d);
            if (s != Integer.MAX_VALUE) hits.add(new Scored<>(it, s, d));
        }
        hits.sort(Comparator.<Scored<T>>comparingInt(h -> h.score())
                .thenComparingInt(h -> h.doc().name().length())
                .thenComparing(h -> h.doc().nameN()));
        for (int i = 0; i < hits.size() && i < limit; i++) out.add(hits.get(i).item());
        return out;
    }

    /** Kleiner = besser, Integer.MAX_VALUE = kein Treffer. */
    public static int score(Query q, Doc d) {
        if (q.isEmpty()) return Integer.MAX_VALUE;
        int total = 0;
        boolean ok = true;
        for (String t : q.tokens()) {
            int s = scoreToken(t, d.words());
            if (s == Integer.MAX_VALUE) { ok = false; break; }
            total += s;
        }
        if (!ok) {
            String c = q.compact();
            if (c.length() >= 3 && (d.compact().contains(c)
                    || compoundMatch(c, d.nameWords()) || compoundMatch(c, d.pathWords()))) {
                total = 4;
            } else {
                return Integer.MAX_VALUE;
            }
        }
        if (d.nameN().equals(q.qn())) total -= 10;
        else if (d.nameN().startsWith(q.qn())) total -= 2;
        return total;
    }

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

    /** "goldapfel" passt auf "Goldener Apfel": Eingabe aus Wortanfängen zusammengesetzt, Wörter dürfen fehlen. */
    private static boolean compoundMatch(String q, String[] words) {
        String rest = q;
        int used = 0;
        for (String w : words) {
            if (rest.isEmpty()) break;
            int l = 0;
            int max = Math.min(w.length(), rest.length());
            while (l < max && w.charAt(l) == rest.charAt(l)) l++;
            if (l >= 2 || (l >= 1 && l == rest.length())) {
                rest = rest.substring(l);
                used++;
            }
        }
        return rest.isEmpty() && used >= 2;
    }
}
