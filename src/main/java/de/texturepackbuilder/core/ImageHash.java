package de.texturepackbuilder.core;

import com.mojang.blaze3d.platform.NativeImage;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Berechnet einen Fingerabdruck über die Pixel eines Bildes (nicht über die Datei).
 * So werden auch Texturen als Duplikat erkannt, die nur anders komprimiert oder
 * mit anderen PNG-Metadaten gespeichert wurden.
 */
public final class ImageHash {
    public record Info(String hash, int width, int height) {}

    private ImageHash() {}

    public static Info of(byte[] png) {
        if (png == null) return null;
        try (NativeImage img = NativeImage.read(png)) {
            int w = img.getWidth();
            int h = img.getHeight();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ByteBuffer.allocate(8).putInt(w).putInt(h).array());
            ByteBuffer row = ByteBuffer.allocate(w * 4);
            for (int y = 0; y < h; y++) {
                row.clear();
                for (int x = 0; x < w; x++) {
                    int p = img.getPixel(x, y);
                    if ((p >>> 24) == 0) p = 0; // komplett transparente Pixel gelten als gleich
                    row.putInt(p);
                }
                md.update(row.array());
            }
            return new Info(HexFormat.of().formatHex(md.digest()).substring(0, 32), w, h);
        } catch (Exception e) {
            return null;
        }
    }
}
