package de.texturepackbuilder.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.TexturePackBuilder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Katalog aller PvP-Items und Spielmodi, geladen aus assets/texturepackbuilder/pvp_items.json. */
public final class PvpCatalog {
    public record Item(String id, boolean extra, String nameKey, List<String> icons, Pattern match, List<String> search) {
        public boolean matches(String path) {
            return path.startsWith("assets/minecraft/textures/") && match.matcher(TextureNames.shortPath(path)).matches();
        }

        /** Vollständige Pfade der Icon-Kandidaten. */
        public List<String> iconPaths() {
            List<String> out = new ArrayList<>();
            for (String i : icons) out.add("assets/minecraft/textures/" + i + ".png");
            return out;
        }
    }

    public record Mode(String id, Set<String> items) {
        public boolean includes(Item i) {
            return items == null || items.contains(i.id());
        }

        public String key() {
            return "tpb.mode." + id;
        }
    }

    public static final List<Item> ITEMS = new ArrayList<>();
    public static final List<Mode> MODES = new ArrayList<>();

    static {
        try (InputStream in = PvpCatalog.class.getResourceAsStream("/assets/texturepackbuilder/pvp_items.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject o = el.getAsJsonObject();
                ITEMS.add(new Item(o.get("id").getAsString(),
                        "extras".equals(o.get("group").getAsString()),
                        o.get("name").getAsString(),
                        strings(o.getAsJsonArray("icon")),
                        Pattern.compile(o.get("match").getAsString()),
                        strings(o.getAsJsonArray("search"))));
            }
            for (JsonElement el : root.getAsJsonArray("modes")) {
                JsonObject o = el.getAsJsonObject();
                Set<String> items = o.has("items") ? new LinkedHashSet<>(strings(o.getAsJsonArray("items"))) : null;
                MODES.add(new Mode(o.get("id").getAsString(), items));
            }
        } catch (Exception e) {
            TexturePackBuilder.LOG.error("PvP-Katalog konnte nicht geladen werden", e);
            if (MODES.isEmpty()) MODES.add(new Mode("all", null));
        }
    }

    private PvpCatalog() {}

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    /** Zu welchem PvP-Item gehört ein Texturpfad (oder null)? */
    public static Item itemFor(String path) {
        for (Item i : ITEMS) if (i.matches(path)) return i;
        return null;
    }
}
