package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.TextureNames.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Suche über alle Texturen.
 * - findet über den Namen in der Spielsprache ("Stein") und den Pfad, der englisch ist ("stone")
 * - mehrere Wörter: alle müssen passen ("eiche tür", "oak door")
 * - "@name" filtert nach Pack, z. B. "@faithful"
 * - Treffer werden nach Relevanz sortiert: exakter Name > Wortanfang > enthalten > nur im Pfad
 */
public final class Search {
    public enum Sort {
        RELEVANCE("tpb.sort.relevance"), NAME("tpb.sort.name"), VARIANTS("tpb.sort.variants");

        public final String key;

        Sort(String key) {
            this.key = key;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public record Options(String query, Category category, boolean onlyMultiple, boolean onlySelected, Sort sort) {}

    private record Scored(TextureEntry entry, String name, int score) {}

    private Search() {}

    public static List<TextureEntry> run(TextureIndex idx, Options o) {
        if (idx == null) return List.of();
        List<String> words = new ArrayList<>();
        List<String> packFilters = new ArrayList<>();
        for (String raw : TextureNames.normalize(o.query()).trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            if (raw.startsWith("@") && raw.length() > 1) packFilters.add(raw.substring(1));
            else words.add(raw);
        }
        String fullQuery = String.join(" ", words);

        List<Scored> hits = new ArrayList<>();
        for (TextureEntry e : idx.entries.values()) {
            if (o.category() != Category.ALL && TextureNames.category(e.path) != o.category()) continue;
            if (o.onlyMultiple() && e.variants.size() < 2) continue;
            if (o.onlySelected() && !Resolve.isCustom(idx, e.path)) continue;
            if (!packFilters.isEmpty() && !matchesPacks(e, packFilters)) continue;

            String display = TextureNames.displayName(e.path);
            String name = TextureNames.normalize(display);
            String path = TextureNames.normalize(TextureNames.shortPath(e.path));
            int score = 0;
            boolean all = true;
            for (String w : words) {
                int s = scoreWord(w, name, path);
                if (s == 0) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (!all) continue;
            if (!fullQuery.isEmpty() && name.equals(fullQuery)) score += 200;
            hits.add(new Scored(e, display, score));
        }

        Comparator<Scored> byName = Comparator.comparing(s -> s.name().toLowerCase());
        Comparator<Scored> cmp = switch (o.sort()) {
            case RELEVANCE -> Comparator.comparingInt(Scored::score).reversed().thenComparing(byName);
            case NAME -> byName;
            case VARIANTS -> Comparator.<Scored>comparingInt(s -> s.entry().variants.size()).reversed().thenComparing(byName);
        };
        hits.sort(cmp);
        List<TextureEntry> out = new ArrayList<>(hits.size());
        for (Scored s : hits) out.add(s.entry());
        return out;
    }

    private static int scoreWord(String w, String name, String path) {
        if (name.equals(w)) return 100;
        if (name.startsWith(w)) return 60;
        if ((" " + name).contains(" " + w)) return 40;
        if (name.contains(w)) return 20;
        String file = path.substring(path.lastIndexOf(' ') + 1);
        if (file.startsWith(w)) return 15;
        if (path.contains(w)) return 8;
        return 0;
    }

    private static boolean matchesPacks(TextureEntry e, List<String> filters) {
        for (String f : filters) {
            boolean found = false;
            for (Variant v : e.variants) {
                for (Model.SourceRef r : v.sources) {
                    if (TextureNames.normalize(r.pack().name()).contains(f)) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            if (!found) return false;
        }
        return true;
    }
}
