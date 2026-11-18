package com.xfive.live.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Gambar panel gelap yang elegan dan ringan: hanya beberapa fill per panel (gradasi dibuat dari beberapa pita saja,
 * tanpa shader, tanpa tekstur, tanpa alokasi per frame), jadi aman untuk HP.
 * BELUM DIVERIFIKASI terhadap jar Minecraft 26.2 (lihat README).
 */
public final class Glass {
    private Glass() {
    }

    public static int argb(int rgb, float alpha) {
        return GlassPanelRenderer.argb(rgb, alpha);
    }

    /** Campuran dua warna ARGB per kanal. t = 0 memberi a, t = 1 memberi b. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 255;
        int ar = (a >>> 16) & 255;
        int ag = (a >>> 8) & 255;
        int ab = a & 255;
        int ba = (b >>> 24) & 255;
        int br = (b >>> 16) & 255;
        int bg = (b >>> 8) & 255;
        int bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** Berapa piksel baris i dipotong dari kiri dan kanan oleh sudut membulat. */
    private static int inset(int i, int h, int r) {
        float edge = Math.min(i + 0.5f, h - i - 0.5f);
        if (edge >= r) {
            return 0;
        }
        float d = r - edge;
        return Math.round(r - (float) Math.sqrt(Math.max(0f, r * r - d * d)));
    }

    /**
     * Persegi membulat dengan gradasi halus dari warna atas ke warna bawah (paling banyak 16 pita di bagian
     * tengah, baris sudut digambar satu per satu). Jika kedua warna sama, hanya beberapa fill.
     */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        boolean flat = top == bottom;
        for (int i = 0; i < r; i++) {
            int ins = inset(i, h, r);
            g.fill(x + ins, y + i, x + w - ins, y + i + 1, flat ? top : mix(top, bottom, (i + 0.5f) / h));
            int j = h - 1 - i;
            g.fill(x + ins, y + j, x + w - ins, y + j + 1, flat ? top : mix(top, bottom, (j + 0.5f) / h));
        }
        int midTop = y + r;
        int midBot = y + h - r;
        if (midBot <= midTop) {
            return;
        }
        if (flat) {
            g.fill(x, midTop, x + w, midBot, top);
            return;
        }
        int midH = midBot - midTop;
        int bands = Math.max(1, Math.min(16, midH / 3));
        for (int k = 0; k < bands; k++) {
            int ya = midTop + midH * k / bands;
            int yb = midTop + midH * (k + 1) / bands;
            if (yb > ya) {
                g.fill(x, ya, x + w, yb, mix(top, bottom, ((ya + yb) / 2f - y) / h));
            }
        }
    }

    /** Bercak warna lembut: lingkaran berlapis (tepi pudar) dengan total alpha di tengah = alpha. */
    public static void glow(GuiGraphicsExtractor g, int cx, int cy, int r, int rgb, float alpha) {
        int rings = 6;
        int c = argb(rgb, alpha / rings);
        for (int k = 0; k < rings; k++) {
            int rr = Math.round(r * (rings - k) / (float) rings);
            if (rr > 0) {
                disc(g, cx, cy, rr, c);
            }
        }
    }

    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int argb) {
        roundRect(g, x, y, w, h, r, argb, argb);
    }

    /** Garis tepi 1 piksel mengikuti sudut membulat (warna atas untuk separuh atas, bawah untuk separuh bawah). */
    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 2 || h <= 2) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        g.fill(x + r, y, x + w - r, y + 1, top);
        g.fill(x + r, y + h - 1, x + w - r, y + h, bottom);
        int half = h / 2;
        int a = y + r;
        int b = y + h - r;
        if (b > a) {
            int split = Math.max(a, Math.min(b, y + half));
            if (split > a) {
                g.fill(x, a, x + 1, split, top);
                g.fill(x + w - 1, a, x + w, split, top);
            }
            if (b > split) {
                g.fill(x, split, x + 1, b, bottom);
                g.fill(x + w - 1, split, x + w, b, bottom);
            }
        }
        for (int i = 0; i < r; i++) {
            int ins = inset(i, h, r);
            int c = i < half ? top : bottom;
            g.fill(x + ins, y + i, x + ins + 1, y + i + 1, c);
            g.fill(x + w - ins - 1, y + i, x + w - ins, y + i + 1, c);
            int j = h - 1 - i;
            int c2 = j < half ? top : bottom;
            g.fill(x + ins, y + j, x + ins + 1, y + j + 1, c2);
            g.fill(x + w - ins - 1, y + j, x + w - ins, y + j + 1, c2);
        }
    }

    public static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        for (int dy = -r; dy <= r; dy++) {
            int half = Math.round((float) Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }

    /**
     * Panel gelap elegan: badan dua nada (biru gelap ke hampir hitam), garis kilau tipis di tepi atas,
     * garis aksen tipis di bawah, dan tepi halus. Sekitar 30 fill saja.
     */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float alpha, int accent) {
        if (alpha <= 0.01f || w <= 2 || h <= 2) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        // warna aksen tema memberi rona tipis pada badan panel
        roundRect(g, x, y, w, h, r, mix(argb(0x1A1E2C, 0.90f * alpha), argb(accent, 0.90f * alpha), 0.10f),
                mix(argb(0x0A0C13, 0.94f * alpha), argb(accent, 0.94f * alpha), 0.04f));
        g.fill(x + r + 1, y + 1, x + w - r - 1, y + 2, argb(0xFFFFFF, 0.10f * alpha));
        g.fill(x + r + 2, y + h - 2, x + w - r - 2, y + h - 1, argb(accent, 0.55f * alpha));
        border(g, x, y, w, h, r, argb(0xFFFFFF, 0.20f * alpha), argb(0xFFFFFF, 0.07f * alpha));
    }
}
