package de.texturepackbuilder.gui;

import com.mojang.blaze3d.platform.NativeImage;
import de.texturepackbuilder.TexturePackBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Lädt Vorschaubilder bei Bedarf als GPU-Texturen und gibt sie beim Schließen wieder frei. */
final class PreviewCache {
    record Tex(Identifier id, int w, int h) {}

    @FunctionalInterface
    interface Loader {
        byte[] load() throws IOException;
    }

    private static int counter = 0;
    private final Map<String, Tex> loaded = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private int budget;

    /** Pro Frame nur wenige Bilder laden, damit das Spiel nicht ruckelt. */
    void beginFrame() {
        budget = 8;
    }

    Tex get(String key, Loader loader) {
        Tex t = loaded.get(key);
        if (t != null || failed.contains(key) || budget <= 0) return t;
        budget--;
        try {
            byte[] data = loader.load();
            if (data == null) {
                failed.add(key);
                return null;
            }
            NativeImage img = NativeImage.read(data);
            Identifier id = Identifier.fromNamespaceAndPath(TexturePackBuilder.MOD_ID, "preview/" + (counter++));
            t = new Tex(id, img.getWidth(), img.getHeight());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "tpb-preview", img));
            loaded.put(key, t);
            return t;
        } catch (Exception e) {
            failed.add(key);
            return null;
        }
    }

    void clear() {
        for (Tex t : loaded.values()) Minecraft.getInstance().getTextureManager().release(t.id());
        loaded.clear();
        failed.clear();
    }

    /** Zeichnet eine Textur in ein Quadrat; Animationen zeigen das erste Bild, Seitenverhältnis bleibt erhalten. */
    static void draw(GuiGraphics g, Tex t, int x, int y, int size) {
        int srcW = t.w();
        int srcH = t.h();
        if (srcH > srcW && srcH % srcW == 0) srcH = srcW;
        int dw = size, dh = size;
        if (srcW > srcH) dh = Math.max(1, size * srcH / srcW);
        else if (srcH > srcW) dw = Math.max(1, size * srcW / srcH);
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x + (size - dw) / 2, y + (size - dh) / 2,
                0f, 0f, dw, dh, srcW, srcH, t.w(), t.h());
    }

    /** Schachbrett-Hintergrund, damit transparente Pixel erkennbar sind. */
    static void checker(GuiGraphics g, int x, int y, int size) {
        int cell = Math.max(4, size / 8);
        for (int cy = 0; cy < size; cy += cell)
            for (int cx = 0; cx < size; cx += cell) {
                int c = ((cx / cell + cy / cell) & 1) == 0 ? 0xFF2A2A30 : 0xFF202026;
                g.fill(x + cx, y + cy, x + Math.min(size, cx + cell), y + Math.min(size, cy + cell), c);
            }
    }
}
