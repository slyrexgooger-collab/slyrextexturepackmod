package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.Bundle;
import de.texturepackbuilder.core.Model.Origin;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.SourceRef;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.PvpCatalog.Item;
import de.texturepackbuilder.core.Styles.Style;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft die Auswahl-Logik ohne Minecraft: vor allem, dass sie Pack-Updates und Neu-Scans übersteht. */
class SelectionLogicTest {
    static final String TOTEM = "assets/minecraft/textures/item/totem_of_undying.png";
    static final String PEARL = "assets/minecraft/textures/item/ender_pearl.png";
    static final String SWORD = "assets/minecraft/textures/item/netherite_sword.png";

    @BeforeAll
    static void gameDir() throws Exception {
        System.setProperty("tpb.gameDir", Files.createTempDirectory("tpb-test").toString());
    }

    @BeforeEach
    void reset() {
        Selection.items.clear();
        Selection.textures.clear();
        Selection.favorites.clear();
    }

    static SourcePack pack(String id, String name) {
        return new SourcePack(id, name, Path.of(name + ".zip"), true, Origin.MODRINTH, 0, "");
    }

    /** Baut einen Index: jede Zeile = {pack, pfad, hash}. Gleiche Hashes landen in derselben Variante. */
    static TextureIndex index(List<SourcePack> packs, Object[][] rows) {
        TextureIndex idx = new TextureIndex(new ArrayList<>(packs));
        for (Object[] r : rows) {
            SourcePack p = (SourcePack) r[0];
            String path = (String) r[1], hash = (String) r[2];
            TextureEntry te = idx.entries.computeIfAbsent(path, TextureEntry::new);
            Variant v = te.find(hash);
            if (v == null) {
                v = new Variant(hash, 16, 16);
                te.variants.add(v);
            }
            v.sources.add(new SourceRef(p, path, null));
        }
        return idx;
    }

    static Item item(String id) {
        Item it = PvpCatalog.byId(id);
        assertNotNull(it, "Katalog enthält " + id);
        return it;
    }

    @Test
    void catalogLoadsAndMatches() {
        assertTrue(PvpCatalog.ITEMS.size() >= 29);
        assertEquals("totem", PvpCatalog.itemFor(TOTEM).id());
        assertEquals("sword", PvpCatalog.itemFor(SWORD).id());
        assertEquals("crosshair", PvpCatalog.itemFor("assets/minecraft/textures/gui/sprites/hud/crosshair.png").id());
        assertEquals("hud", PvpCatalog.itemFor("assets/minecraft/textures/gui/sprites/hud/heart/full.png").id());
    }

    @Test
    void choosingAStyleSurvivesPackUpdate() {
        SourcePack v1 = pack("mr:abc", "Cool Totem");
        TextureIndex before = index(List.of(v1), new Object[][]{{v1, TOTEM, "h1"}});
        Item totem = item("totem");
        Style style = Styles.styles(before, totem).get(0);
        Styles.apply(totem, style);
        assertEquals(style.key(), Styles.current(before, totem));

        // Update: neue Datei, neuer Name, andere Pixel – aber dasselbe Modrinth-Projekt
        SourcePack v2 = new SourcePack("mr:abc", "Cool Totem", Path.of("cool-totem-2.0.zip"), true, Origin.MODRINTH, 1, "");
        TextureIndex after = index(List.of(v2), new Object[][]{{v2, TOTEM, "h2"}});
        String cur = Styles.current(after, totem);
        assertNotEquals(Styles.MIXED, cur);
        assertNotEquals(Styles.MISSING, cur);
        assertNotEquals(Styles.VANILLA, cur);

        Map<String, Resolve.Src> plan = Resolve.plan(after);
        assertEquals(v2.location(), plan.get(TOTEM).pack().location(), "neue Version wird benutzt");
    }

    @Test
    void duplicateStylesAreMergedAndBothPackIdsSelectIt() {
        SourcePack a = pack("mr:a", "Pack A"), b = pack("mr:b", "Pack B");
        TextureIndex idx = index(List.of(a, b), new Object[][]{{a, PEARL, "same"}, {b, PEARL, "same"}});
        Item pearl = item("pearl");
        List<Style> styles = Styles.styles(idx, pearl);
        assertEquals(1, styles.size(), "gleiches Bild aus zwei Packs = ein Style");
        assertEquals(2, styles.get(0).packs().size());

        Selection.items.put("pearl", "mr:b"); // z. B. früher über Pack B gewählt
        assertEquals(styles.get(0).key(), Styles.current(idx, pearl));
    }

    @Test
    void removedPackShowsMissingButKeepsSelection() {
        SourcePack a = pack("mr:a", "Pack A");
        TextureIndex idx = index(List.of(a), new Object[][]{{a, TOTEM, "h"}});
        Item totem = item("totem");
        Styles.apply(totem, Styles.styles(idx, totem).get(0));

        TextureIndex empty = index(List.of(), new Object[][]{});
        assertEquals(Styles.MISSING, Styles.current(empty, totem));
        assertEquals("mr:a", Selection.items.get("totem"), "Auswahl bleibt gespeichert");
        assertTrue(Resolve.plan(empty).isEmpty());

        // Pack kommt zurück → wieder aktiv
        assertNotEquals(Styles.MISSING, Styles.current(idx, totem));
    }

    @Test
    void singleTextureOverridesAndVanillaOverride() {
        SourcePack a = pack("mr:a", "A"), b = pack("mr:b", "B");
        TextureIndex idx = index(List.of(a, b), new Object[][]{
                {a, SWORD, "a1"}, {b, SWORD, "b1"},
                {a, "assets/minecraft/textures/item/diamond_sword.png", "a2"}});
        Item sword = item("sword");
        Style fromA = null;
        for (Style s : Styles.styles(idx, sword)) if (s.hasPackId("mr:a")) fromA = s;
        Styles.apply(sword, fromA);
        assertEquals("a1", Resolve.variant(idx, SWORD).hash);

        Selection.textures.put(SWORD, new Selection.Pick("mr:b", "b1"));
        assertEquals("b1", Resolve.variant(idx, SWORD).hash, "Einzel-Auswahl schlägt Item-Style");
        assertEquals(Styles.MIXED, Styles.current(idx, sword));

        Selection.textures.put(SWORD, new Selection.Pick(Selection.Pick.VANILLA, ""));
        assertNull(Resolve.variant(idx, SWORD));
        Map<String, Resolve.Src> plan = Resolve.plan(idx);
        assertFalse(plan.containsKey(SWORD), "Vanilla erzwungen");
        assertTrue(plan.containsKey("assets/minecraft/textures/item/diamond_sword.png"), "Rest des Styles bleibt");

        // Style neu wählen hebt Einzel-Auswahlen dieses Items auf
        Styles.apply(sword, fromA);
        assertTrue(Selection.textures.isEmpty());
        assertEquals(fromA.key(), Styles.current(idx, sword));
    }

    @Test
    void vanillaChoiceClearsItem() {
        SourcePack a = pack("mr:a", "A");
        TextureIndex idx = index(List.of(a), new Object[][]{{a, TOTEM, "h"}});
        Item totem = item("totem");
        Styles.apply(totem, Styles.styles(idx, totem).get(0));
        Styles.apply(totem, null);
        assertEquals(Styles.VANILLA, Styles.current(idx, totem));
        assertTrue(Resolve.plan(idx).isEmpty());
    }

    @Test
    void modelBundlesAreExportedWithStyle() {
        SourcePack a = pack("mr:3d", "3D Totem");
        TextureIndex idx = index(List.of(a), new Object[][]{});
        String model = "assets/minecraft/models/item/totem_of_undying.json";
        String tex = "assets/minecraft/textures/item/totem_3d/body.png";
        idx.bundles.put("totem", Map.of(a, new Bundle(Set.of(model, tex), "x")));
        Item totem = item("totem");
        List<Style> styles = Styles.styles(idx, totem);
        assertEquals(1, styles.size(), "reines 3D-Pack wird als Style erkannt");
        assertTrue(styles.get(0).has3d());
        Styles.apply(totem, styles.get(0));
        Map<String, Resolve.Src> plan = Resolve.plan(idx);
        assertTrue(plan.containsKey(model));
        assertTrue(plan.containsKey(tex));
    }

    @Test
    void selectionSurvivesSaveAndLoad() throws Exception {
        Selection.items.put("totem", "mr:abc");
        Selection.textures.put(SWORD, new Selection.Pick("mr:b", "b1"));
        Selection.favorites.add("totem|mr:abc");
        Selection.save();
        String json = Files.readString(Dirs.selectionFile());
        assertTrue(json.contains("\"version\": 3"));
        assertTrue(json.contains("mr:abc"));
    }
}
