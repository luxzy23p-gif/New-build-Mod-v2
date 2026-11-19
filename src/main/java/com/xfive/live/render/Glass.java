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

    private static final int[][] INSETS = new int[48][];

    /** Pemotongan sudut untuk baris 0..r-1 dari sisi atas (dan cermin untuk sisi bawah). Dihitung sekali per radius. */
    private static int[] insets(int r) {
        int[] t = r < INSETS.length ? INSETS[r] : null;
        if (t == null) {
            t = new int[r];
            for (int i = 0; i < r; i++) {
                float d = r - (i + 0.5f);
                t[i] = Math.round(r - (float) Math.sqrt(Math.max(0f, r * r - d * d)));
            }
            if (r < INSETS.length) {
                INSETS[r] = t;
            }
        }
        return t;
    }

    private static int rowInset(int i, int h, int r, int[] ins) {
        if (i < r) {
            return ins[i];
        }
        int j = h - 1 - i;
        return j < r ? ins[j] : 0;
    }

    private static int maxDelta(int a, int b) {
        int d = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            d = Math.max(d, Math.abs(((a >>> sh) & 255) - ((b >>> sh) & 255)));
        }
        return d;
    }

    /**
     * Persegi membulat dengan gradasi atas ke bawah. Baris yang bentuk dan warnanya sama digabung menjadi satu fill,
     * dan jumlah pita gradasi dibatasi sesuai selisih warna yang memang terlihat. Warna rata (atau hampir rata)
     * hanya memakai sekitar 2 x (jumlah langkah sudut) + 1 fill.
     */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        int bands = 1;
        if (top != bottom) {
            bands = Math.max(1, Math.min(Math.min(20, maxDelta(top, bottom)), h / 3));
        }
        int[] ins = insets(r);
        int runStart = 0;
        int runIns = rowInset(0, h, r, ins);
        int runBand = 0;
        for (int i = 1; i <= h; i++) {
            int in = i == h ? -1 : rowInset(i, h, r, ins);
            int bd = i == h ? -1 : i * bands / h;
            if (in != runIns || bd != runBand) {
                int col = bands == 1 ? top : mix(top, bottom, (runBand + 0.5f) / bands);
                g.fill(x + runIns, y + runStart, x + w - runIns, y + i, col);
                runStart = i;
                runIns = in;
                runBand = bd;
            }
        }
    }

    /**
     * Bercak warna lembut: 4 lingkaran berlapis (tepi pudar) dengan total alpha di tengah = alpha. Barisnya diambil
     * per beberapa piksel supaya hanya sekitar 100 fill (alpha-nya sangat tipis, jadi anak tangga tidak terlihat).
     */
    public static void glow(GuiGraphicsExtractor g, int cx, int cy, int r, int rgb, float alpha) {
        int rings = 4;
        int c = argb(rgb, alpha / rings);
        int step = Math.max(1, r / 21);
        for (int k = 0; k < rings; k++) {
            int rr = Math.round(r * (rings - k) / (float) rings);
            if (rr > 0) {
                disc(g, cx, cy, rr, c, step);
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
        // sudut: baris berurutan dengan pemotongan sama digabung; baris pertama tiap langkah juga menutup celah ke langkah sebelumnya
        int[] ins = insets(r);
        int prev = r; // garis lurus atas/bawah dimulai di x + r
        int i = 0;
        while (i < r) {
            int cur = ins[i];
            int j = i + 1;
            while (j < r && ins[j] == cur) {
                j++;
            }
            int jump = Math.max(cur + 1, prev); // lebar mendatar untuk menyambung ke langkah sebelumnya
            cornerRun(g, x, y, w, h, top, cur, jump, i, j, false);
            cornerRun(g, x, y, w, h, bottom, cur, jump, i, j, true);
            prev = cur;
            i = j;
        }
    }

    /**
     * Satu langkah sudut di sisi kiri dan kanan: potongan mendatar di baris pertama langkah, lalu satu potongan tegak
     * untuk sisa barisnya. Sisi atas memakai warna atas, sisi bawah (dicerminkan) memakai warna bawah.
     */
    private static void cornerRun(GuiGraphicsExtractor g, int x, int y, int w, int h, int c, int cur, int jump,
                                  int i0, int i1, boolean bottomSide) {
        int row = bottomSide ? h - 1 - i0 : i0;
        g.fill(x + cur, y + row, x + jump, y + row + 1, c);
        g.fill(x + w - jump, y + row, x + w - cur, y + row + 1, c);
        if (i1 - i0 > 1) {
            int ra = bottomSide ? y + h - i1 : y + i0 + 1;
            int rb = bottomSide ? y + h - i0 - 1 : y + i1;
            g.fill(x + cur, ra, x + cur + 1, rb, c);
            g.fill(x + w - cur - 1, ra, x + w - cur, rb, c);
        }
    }

    public static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        disc(g, cx, cy, r, argb, 1);
    }

    /** Lingkaran penuh; baris berurutan yang lebarnya sama digabung. step > 1 mengambil beberapa baris sekaligus. */
    public static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb, int step) {
        step = Math.max(1, step);
        int runStart = -r;
        int runHalf = -1;
        for (int dy = -r; ; dy += step) {
            boolean end = dy > r;
            int half = -2;
            if (!end) {
                float mid = step == 1 ? dy : Math.min(r, dy + (step - 1) / 2f);
                half = Math.round((float) Math.sqrt(Math.max(0f, r * r - mid * mid)));
            }
            if (half != runHalf) {
                if (runHalf >= 0) {
                    g.fill(cx - runHalf, cy + runStart, cx + runHalf + 1, cy + Math.min(dy, r + 1), argb);
                }
                runStart = dy;
                runHalf = half;
            }
            if (end) {
                break;
            }
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
