package de.texturepackbuilder.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Farben und kleine Zeichenhelfer für ein einheitliches Aussehen. */
final class Theme {
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFB0B0B8;
    static final int DIM = 0xFF7A7A84;
    static final int ACCENT = 0xFF62C554;
    static final int FOCUS = 0xFFFFFFFF;
    static final int BADGE = 0xFFFFD84A;
    static final int WARN = 0xFFFF9A3C;
    static final int BAR = 0xE0101016;
    static final int PANEL = 0xB4141419;
    static final int PANEL_DARK = 0xFF0C0C10;
    static final int BORDER = 0xFF3A3A46;

    private Theme() {}

    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        outline(g, x, y, w, h, BORDER);
    }

    static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Überschrift in einem Panel mit kleiner Akzentlinie. */
    static void heading(GuiGraphics g, Font f, Component c, int x, int y, int w) {
        text(g, f, c, x, y, w, TEXT);
        g.fill(x, y + 10, x + Math.min(w, 24), y + 11, ACCENT);
    }

    /** Eine Zeile, die bei zu wenig Platz abgeschnitten wird. */
    static void text(GuiGraphics g, Font f, Component c, int x, int y, int maxW, int color) {
        List<FormattedCharSequence> lines = f.split(c, Math.max(10, maxW));
        if (!lines.isEmpty()) g.drawString(f, lines.get(0), x, y, color);
    }

    /** Text mit Zeilenumbruch, höchstens maxLines Zeilen. Gibt die Anzahl gezeichneter Zeilen zurück. */
    static int wrapped(GuiGraphics g, Font f, Component c, int x, int y, int maxW, int maxLines, int color) {
        List<FormattedCharSequence> lines = f.split(c, Math.max(10, maxW));
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) g.drawString(f, lines.get(i), x, y + i * 10, color);
        return n;
    }

    static void centered(GuiGraphics g, Font f, Component c, int cx, int y, int maxW, int color) {
        List<FormattedCharSequence> lines = f.split(c, Math.max(10, maxW));
        if (lines.isEmpty()) return;
        FormattedCharSequence s = lines.get(0);
        g.drawString(f, s, cx - f.width(s) / 2, y, color);
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
}
