package de.texturepackbuilder.core;

import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Macht aus Texturpfaden lesbare Namen in der Spielsprache
 * ("assets/minecraft/textures/block/oak_log_top.png" → "Eichenstamm") und ordnet Kategorien zu.
 */
public final class TextureNames {
    public enum Category {
        ALL("tpb.cat.all"), BLOCK("tpb.cat.block"), ITEM("tpb.cat.item"), ENTITY("tpb.cat.entity"),
        GUI("tpb.cat.gui"), PARTICLE("tpb.cat.particle"), OTHER("tpb.cat.other");

        public final String key;

        Category(String key) {
            this.key = key;
        }
    }

    private static final Pattern SUFFIX = Pattern.compile(
            "_(top|side|bottom|front|back|end|inner|outer|on|off|overlay|lit|open|closed|base|stage\\d+|\\d+)$");
    private static final Map<String, String> CACHE = new HashMap<>();
    private static String cacheLang = "";

    private TextureNames() {}

    /** Pfad ab "textures/", ohne .png – z. B. "block/oak_log_top". */
    public static String shortPath(String path) {
        int i = path.indexOf("/textures/");
        String s = i >= 0 ? path.substring(i + 10) : path;
        return s.endsWith(".png") ? s.substring(0, s.length() - 4) : s;
    }

    public static Category category(String path) {
        String s = shortPath(path);
        int slash = s.indexOf('/');
        String first = slash >= 0 ? s.substring(0, slash) : "";
        return switch (first) {
            case "block" -> Category.BLOCK;
            case "item" -> Category.ITEM;
            case "entity" -> Category.ENTITY;
            case "gui" -> Category.GUI;
            case "particle" -> Category.PARTICLE;
            default -> Category.OTHER;
        };
    }

    public static synchronized String displayName(String path) {
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!lang.equals(cacheLang)) {
            CACHE.clear();
            cacheLang = lang;
        }
        return CACHE.computeIfAbsent(path, TextureNames::compute);
    }

    private static String compute(String path) {
        String[] parts = path.split("/");
        String ns = parts.length > 1 ? parts[1] : "minecraft";
        String[] segs = shortPath(path).split("/");
        String folder = segs[0];
        String file = segs[segs.length - 1];
        String base = SUFFIX.matcher(file).replaceAll("");
        Language lang = Language.getInstance();

        String[] candidates = switch (folder) {
            case "block" -> new String[]{"block." + ns + "." + file, "block." + ns + "." + base, "item." + ns + "." + file};
            case "item" -> new String[]{"item." + ns + "." + file, "block." + ns + "." + file, "item." + ns + "." + base};
            case "entity" -> new String[]{"entity." + ns + "." + (segs.length > 2 ? segs[1] : file),
                    "entity." + ns + "." + file, "entity." + ns + "." + base};
            case "mob_effect" -> new String[]{"effect." + ns + "." + file};
            default -> new String[0];
        };
        for (String key : candidates) {
            if (lang.has(key)) {
                String name = lang.getOrDefault(key);
                return file.equals(base) || folder.equals("entity") ? name : name + " (" + pretty(file.substring(base.length())) + ")";
            }
        }
        return pretty(file);
    }

    private static String pretty(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.replace('_', ' ').trim().split(" +")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** Kleinschreibung, ohne Akzente/Umlaute-Punkte, Unterstriche als Leerzeichen – für die Suche. */
    public static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('_', ' ').replace('/', ' ').replace('ß', 's');
    }
}
