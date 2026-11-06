package de.texturepackbuilder.core;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import de.texturepackbuilder.TexturePackBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Blitzwechsel: schreibt geänderte Texturen direkt in die bereits geladenen GPU-Texturen
 * (Textur-Atlanten für Blöcke, Items, GUI, Partikel … und einzelne Texturen wie Rüstung oder End-Kristall).
 * Dauert Millisekunden statt eines kompletten Ressourcen-Neuladens.
 *
 * Geht nur, wenn jede geänderte Datei eine PNG-Textur ist, die schon geladen ist, nicht animiert ist und
 * dieselbe Größe hat. Sonst liefert trySwap false und es wird normal neu geladen.
 */
public final class HotSwap {
    private record Job(GpuTexture target, NativeImage image, int x, int y) {}

    private HotSwap() {}

    /** Muss auf dem Render-Thread laufen. paths = Pfade wie "assets/minecraft/textures/item/totem_of_undying.png". */
    public static boolean trySwap(Collection<String> paths) {
        Minecraft mc = Minecraft.getInstance();
        List<Job> jobs = new ArrayList<>();
        try {
            for (String path : paths) {
                if (path.equals("pack.png") || path.equals("pack.mcmeta") || path.equals("credits.txt")
                        || path.equals(Dirs.GENERATED_MARKER)) continue;
                if (!path.startsWith("assets/") || !path.endsWith(".png") || !path.contains("/textures/")) return fail(jobs);
                String[] parts = path.split("/", 3);
                String ns = parts[1], rest = parts[2]; // rest = "textures/…png"
                Identifier fileId = Identifier.fromNamespaceAndPath(ns, rest);

                byte[] data = read(mc, fileId);
                if (data == null) return fail(jobs);
                NativeImage img = NativeImage.read(data);

                Job job = atlasJob(mc, ns, rest, img);
                if (job == null) job = textureJob(mc, fileId, img);
                if (job == null) {
                    img.close();
                    return fail(jobs);
                }
                jobs.add(job);
            }
        } catch (Throwable t) {
            TexturePackBuilder.LOG.warn("Blitzwechsel nicht möglich, lade normal neu", t);
            return fail(jobs);
        }

        try {
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            for (Job j : jobs) upload(enc, j);
            return true;
        } catch (Throwable t) {
            TexturePackBuilder.LOG.warn("Blitzwechsel fehlgeschlagen, lade normal neu", t);
            return false;
        } finally {
            for (Job j : jobs) j.image().close();
        }
    }

    private static boolean fail(List<Job> jobs) {
        for (Job j : jobs) j.image().close();
        return false;
    }

    /** Liest die Datei so, wie Minecraft sie jetzt sehen würde (oberstes Pack gewinnt, Ordner-Packs werden live gelesen). */
    private static byte[] read(Minecraft mc, Identifier id) {
        try {
            Optional<Resource> r = mc.getResourceManager().getResource(id);
            if (r.isEmpty()) return null;
            try (InputStream in = r.get().open()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Sprite in einem der Atlanten suchen. */
    private static Job atlasJob(Minecraft mc, String ns, String rest, NativeImage img) {
        String s = rest.substring("textures/".length(), rest.length() - 4);
        List<String> candidates = new ArrayList<>();
        candidates.add(s);
        if (s.startsWith("gui/sprites/")) candidates.add(s.substring("gui/sprites/".length()));
        if (s.startsWith("particle/")) candidates.add(s.substring("particle/".length()));

        Job[] found = {null};
        boolean[] blocked = {false};
        mc.getAtlasManager().forEach((atlasId, atlas) -> {
            if (found[0] != null || blocked[0]) return;
            for (String c : candidates) {
                Identifier sid = Identifier.fromNamespaceAndPath(ns, c);
                TextureAtlasSprite sp = atlas.getSprite(sid);
                if (sp == null || sp == atlas.missingSprite() || !sp.contents().name().equals(sid)) continue;
                if (sp.contents().isAnimated()
                        || sp.contents().width() != img.getWidth() || sp.contents().height() != img.getHeight()) {
                    blocked[0] = true; // gefunden, aber nicht tauschbar → normal neu laden
                    return;
                }
                found[0] = new Job(atlas.getTexture(), img, sp.getX(), sp.getY());
                return;
            }
        });
        if (blocked[0]) throw new IllegalStateException("Sprite " + s + " ist animiert oder hat eine andere Größe");
        return found[0];
    }

    /** Einzelne Textur (z. B. entity/end_crystal, entity/equipment/humanoid/netherite). */
    private static Job textureJob(Minecraft mc, Identifier fileId, NativeImage img) {
        AbstractTexture t = mc.getTextureManager().getTexture(fileId);
        if (t == null || t instanceof TextureAtlas) return null;
        GpuTexture g = t.getTexture();
        if (g == null || g.isClosed() || g.getWidth(0) != img.getWidth() || g.getHeight(0) != img.getHeight()) return null;
        return new Job(g, img, 0, 0);
    }

    private static void upload(CommandEncoder enc, Job j) {
        int levels = Math.max(1, j.target().getMipLevels());
        NativeImage level = j.image();
        List<NativeImage> temp = new ArrayList<>();
        try {
            for (int l = 0; l < levels; l++) {
                int w = level.getWidth(), h = level.getHeight();
                if (w < 1 || h < 1) break;
                enc.writeToTexture(j.target(), level, l, 0, j.x() >> l, j.y() >> l, w, h, 0, 0);
                if (l + 1 < levels) {
                    if (w < 2 || h < 2) break;
                    level = downscale(level);
                    temp.add(level);
                }
            }
        } finally {
            for (NativeImage i : temp) i.close();
        }
    }

    /** Halbe Größe, Mittelwert aus 2×2 Pixeln (für die Mipmap-Stufen). */
    private static NativeImage downscale(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, r = 0, gr = 0, b = 0;
                for (int dy = 0; dy < 2; dy++)
                    for (int dx = 0; dx < 2; dx++) {
                        int p = src.getPixel(Math.min(src.getWidth() - 1, x * 2 + dx), Math.min(src.getHeight() - 1, y * 2 + dy));
                        a += (p >>> 24) & 0xFF;
                        r += (p >>> 16) & 0xFF;
                        gr += (p >>> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                out.setPixel(x, y, ((a / 4) << 24) | ((r / 4) << 16) | ((gr / 4) << 8) | (b / 4));
            }
        }
        return out;
    }
}
