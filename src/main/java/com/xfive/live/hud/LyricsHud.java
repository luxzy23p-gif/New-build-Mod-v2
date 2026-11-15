package com.xfive.live.hud;

import com.xfive.live.XFiveLiveClient;
import com.xfive.live.config.XFiveConfig;
import com.xfive.live.lyrics.LyricStyle;
import com.xfive.live.lyrics.LyricsManager;
import com.xfive.live.render.Glass;
import com.xfive.live.render.GlassPanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * HUD lirik: baris sebelumnya, baris aktif (disorot), dan baris berikutnya.
 * Posisi default mengikuti pilihan Atas/Tengah/Bawah. Jika digeser di menu, posisi bebas dipakai.
 */
public final class LyricsHud {
    public static final int WIDTH = 260;

    private LyricsHud() {
    }

    /** Kotak lirik saat ini: {x, y, lebar, tinggi}. Dipakai juga oleh menu untuk menggeser. */
    public static int[] box() {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        int[] pos = c.hudPositions.get("lyricsHud");
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int h = 3 * lineHeight(c) + 8;
        if (isFree(pos)) {
            return new int[] {pos[0], pos[1], WIDTH, h};
        }
        return new int[] {(sw - WIDTH) / 2, presetBaseY(c, sh) - 6, WIDTH, h};
    }

    public static void render(GuiGraphicsExtractor g) {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        if (!c.enableLyrics) {
            return;
        }
        LyricsManager ly = XFiveLiveClient.LYRICS;
        if (ly.isEmpty()) {
            return;
        }
        long songPos = LyricStyle.shifted(XFiveLiveClient.MUSIC.positionMs(), c.lyricsOffsetMs);
        int idx = ly.indexAt(songPos);
        float countdown = c.lyricsCountdown ? LyricStyle.countdown(ly.lines(), idx, songPos) : -1f;
        if (idx < 0 && countdown < 0f) {
            return;
        }
        if (c.karaokeMode) {
            renderKaraoke(g, c, ly, idx, songPos, countdown);
            return;
        }
        Font font = GlassPanelRenderer.font();
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int lineH = lineHeight(c);
        int[] pos = c.hudPositions.get("lyricsHud");
        int cx;
        int baseY;
        if (isFree(pos)) {
            cx = pos[0] + WIDTH / 2;
            baseY = pos[1] + 6;
        } else {
            cx = sw / 2;
            baseY = presetBaseY(c, sh);
        }
        int highlight = c.highlightColor;
        float alpha = c.hudOpacity * c.customTransparency;
        int accent = GlassPanelRenderer.accent(c.theme);

        if (c.lyricsPreview) {
            drawCentered(g, font, ly.textAt(idx - 1), cx, baseY, GlassPanelRenderer.MUTED);
            drawCentered(g, font, ly.textAt(idx + 1), cx, baseY + lineH * 2, GlassPanelRenderer.MUTED);
        }
        if (countdown >= 0f) {
            drawCountdown(g, cx, baseY + lineH, highlight, countdown);
        } else {
            long since = songPos - ly.lines().get(idx).timeMs();
            drawActive(g, font, ly.textAt(idx), cx, baseY + lineH, highlight, c,
                    since, LyricStyle.lineDuration(ly.lines(), idx));
        }
        if (!c.lyricsMinimal) {
            int w = Math.min(sw - 16, WIDTH);
            g.fill(cx - w / 2, baseY - 4, cx + w / 2, baseY - 3, GlassPanelRenderer.argb(accent, alpha));
        }
    }

    // ---- Mode karaoke: panel lirik tepat di bawah Dynamic Bar ----

    private static float karaokeY = Float.NaN;
    private static long karaokeNs = 0L;

    /**
     * Panel karaoke: baris yang sedang dinyanyikan lebih besar, menyala huruf demi huruf mengikuti lagu,
     * dengan garis progres tipis di bawahnya dan baris berikutnya di bawah itu. Panel menempel di bawah
     * Dynamic Bar (mengikuti animasi buka/tutupnya). Hanya fill dan teks, tanpa shader.
     */
    private static void renderKaraoke(GuiGraphicsExtractor g, XFiveConfig c, LyricsManager ly, int idx,
                                      long songPos, float countdown) {
        Font font = GlassPanelRenderer.font();
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int[] ib = DynamicIslandHud.box();
        int cx = ib[0] + ib[2] / 2;
        if (ib[2] <= 0) {
            cx = sw / 2;
        }
        int w = Math.min(sw - 12, 300);
        cx = Math.max(w / 2 + 6, Math.min(sw - w / 2 - 6, cx));
        int px = cx - w / 2;

        boolean islandOn = c.enableMusic && c.dynamicIsland;
        int bottom = islandOn ? DynamicIslandHud.visibleBottom() : -1;
        float target = bottom >= 0 ? bottom + 5 : ib[1];
        long now = System.nanoTime();
        float dt = karaokeNs == 0L ? 1f : Math.min(0.1f, (now - karaokeNs) / 1_000_000_000f);
        karaokeNs = now;
        if (Float.isNaN(karaokeY) || !c.animations) {
            karaokeY = target;
        } else {
            karaokeY += (target - karaokeY) * Math.min(1f, dt * 12f);
        }
        int py = Math.round(karaokeY);

        int hl = c.highlightColor;
        int accent = GlassPanelRenderer.accent(c.theme);
        float alpha = c.hudOpacity * Math.max(0.35f, c.customTransparency);

        if (countdown >= 0f) {
            int h = 22;
            GlassPanelRenderer.panel(g, px, py, w, h, alpha, accent);
            drawCountdown(g, cx, py + 6, hl, countdown);
            return;
        }
        String full = ly.textAt(idx);
        if (full == null || full.isEmpty()) {
            return;
        }
        String next = ly.textAt(idx + 1);
        long since = songPos - ly.lines().get(idx).timeMs();
        long dur = LyricStyle.lineDuration(ly.lines(), idx);
        float frac = LyricStyle.karaoke(since, dur);
        float e = LyricStyle.enter(since, c.animations);

        int inner = w - 24;
        String line = GlassPanelRenderer.fit(font, full, (int) (inner / 1.0f));
        int lw = GlassPanelRenderer.width(font, line);
        float sc = lw * 1.4f <= inner ? 1.4f : 1f;
        int curH = Math.round(9 * sc);
        boolean hasNext = next != null && !next.isEmpty();
        int h = 7 + curH + 4 + 2 + (hasNext ? 4 + 9 : 0) + 6;
        GlassPanelRenderer.panel(g, px, py, w, h, alpha, accent);

        int ty = py + 7 + Math.round((1f - e) * 6f);
        float textAlpha = Math.min(1f, e * 1.4f);
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) ty);
        g.pose().scale(sc, sc);
        int x0 = -lw / 2;
        int dim = Glass.mix(0xFF000000 | 0x9AA3B5, 0xFF000000 | hl, 0.30f) & 0xFFFFFF;
        GlassPanelRenderer.textAlpha(g, font, line, x0, 0, dim, textAlpha);
        float chars = LyricStyle.karaokeChars(line.length(), frac);
        int whole = (int) chars;
        if (whole > 0) {
            GlassPanelRenderer.textAlpha(g, font, line.substring(0, whole), x0, 0, hl, textAlpha);
        }
        float part = chars - whole;
        if (whole < line.length() && part > 0.02f) {
            int cxp = x0 + GlassPanelRenderer.width(font, line.substring(0, whole));
            GlassPanelRenderer.textAlpha(g, font, String.valueOf(line.charAt(whole)), cxp, 0, hl, textAlpha * part);
        }
        g.pose().popMatrix();

        int by = py + 7 + curH + 4;
        int bx0 = px + 12;
        int bx1 = px + w - 12;
        g.fill(bx0, by, bx1, by + 2, GlassPanelRenderer.argb(0xFFFFFF, 0.16f * alpha));
        g.fill(bx0, by, bx0 + Math.round((bx1 - bx0) * frac), by + 2, GlassPanelRenderer.argb(hl, Math.min(1f, alpha + 0.2f)));
        if (hasNext) {
            String nl = GlassPanelRenderer.fit(font, next, inner);
            GlassPanelRenderer.text(g, font, nl, cx - GlassPanelRenderer.width(font, nl) / 2, by + 6,
                    GlassPanelRenderer.MUTED);
        }
    }

    /** true jika posisi lirik sudah diseret bebas (bukan posisi preset). Nilai -1 berarti preset. */
    private static boolean isFree(int[] pos) {
        return pos[0] >= 0 && pos[1] >= 0;
    }

    private static int lineHeight(XFiveConfig c) {
        return c.lyricsFontSize + 3; // jarak baris, belum menskala teks
    }

    /** Baris pertama untuk posisi preset Atas/Tengah/Bawah. */
    private static int presetBaseY(XFiveConfig c, int sh) {
        return switch (c.lyricsPosition) {
            case 0 -> 20;
            case 1 -> sh / 2 - lineHeight(c);
            default -> sh - 70;
        };
    }

    /** Baris aktif dengan animasi masuk dan sorot karaoke. Semua murni fill/teks, tanpa shader. */
    private static void drawActive(GuiGraphicsExtractor g, Font font, String full, int cx, int y, int hl,
                                   XFiveConfig c, long since, long dur) {
        if (full == null || full.isEmpty()) {
            return;
        }
        String anim = c.lyricsAnim;
        float e = LyricStyle.enter(since, !"none".equals(anim) && !"typewriter".equals(anim));
        float alpha = 1f;
        float dy = 0f;
        float scale = 1f;
        String shown = full;
        switch (anim) {
            case "fade" -> alpha = e;
            case "rise" -> {
                alpha = e;
                dy = (1f - e) * 8f;
            }
            case "drop" -> {
                alpha = e;
                dy = -(1f - e) * 8f;
            }
            case "pop" -> {
                scale = LyricStyle.popScale(e);
                alpha = Math.min(1f, e * 1.6f);
            }
            case "typewriter" -> shown = full.substring(0, LyricStyle.typedChars(full.length(), since, dur));
            case "words" -> {
                String[] parts = full.strip().split("\\s+");
                shown = LyricStyle.firstWords(full, LyricStyle.revealedWords(parts.length, since, dur));
            }
            case "wave" -> {
                if (since < LyricStyle.waveTotalMs(full.length())) {
                    drawWave(g, font, full, cx, y, hl, since);
                    return;
                }
            }
            default -> {
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        int x = cx - GlassPanelRenderer.width(font, full) / 2;
        int ty = y + Math.round(dy);
        boolean pop = scale != 1f;
        if (pop) {
            g.pose().pushMatrix();
            g.pose().translate((float) cx, (float) (ty + 4));
            g.pose().scale(scale, scale);
            g.pose().translate((float) -cx, (float) -(ty + 4));
        }
        if (c.lyricsKaraoke) {
            int dim = Glass.mix(0xFF000000 | 0x9AA3B5, 0xFF000000 | hl, 0.30f) & 0xFFFFFF;
            GlassPanelRenderer.textAlpha(g, font, shown, x, ty, dim, alpha);
            int n = Math.min(shown.length(), Math.round(full.length() * LyricStyle.karaoke(since, dur)));
            if (n > 0) {
                GlassPanelRenderer.textAlpha(g, font, shown.substring(0, n), x, ty, hl, alpha);
            }
        } else {
            GlassPanelRenderer.textAlpha(g, font, shown, x, ty, hl, alpha);
        }
        if (pop) {
            g.pose().popMatrix();
        }
    }

    /** Animasi gelombang: huruf jatuh masuk satu per satu. Hanya dipakai sebentar saat baris baru muncul. */
    private static void drawWave(GuiGraphicsExtractor g, Font font, String full, int cx, int y, int hl, long since) {
        int x0 = cx - GlassPanelRenderer.width(font, full) / 2;
        int n = full.length();
        for (int i = 0; i < n; i++) {
            char ch = full.charAt(i);
            if (ch == ' ') {
                continue;
            }
            float t = LyricStyle.waveChar(i, n, since);
            if (t <= 0f) {
                continue;
            }
            int x = x0 + GlassPanelRenderer.width(font, full.substring(0, i));
            int dy = Math.round((1f - t) * -9f);
            GlassPanelRenderer.textAlpha(g, font, String.valueOf(ch), x, y + dy, hl, t);
        }
    }

    /** Tiga titik yang terisi satu per satu selama jeda instrumental. */
    private static void drawCountdown(GuiGraphicsExtractor g, int cx, int y, int hl, float progress) {
        int size = 5;
        int gap = 5;
        int total = 3 * size + 2 * gap;
        int x0 = cx - total / 2;
        for (int i = 0; i < 3; i++) {
            float fill = Math.max(0f, Math.min(1f, progress * 3f - i));
            int x = x0 + i * (size + gap);
            g.fill(x, y + 2, x + size, y + 2 + size, GlassPanelRenderer.argb(0xFFFFFF, 0.22f));
            if (fill > 0f) {
                g.fill(x, y + 2, x + size, y + 2 + size, GlassPanelRenderer.argb(hl, 0.35f + 0.65f * fill));
            }
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int rgb) {
        if (s == null || s.isEmpty()) {
            return;
        }
        int x = cx - GlassPanelRenderer.width(font, s) / 2;
        GlassPanelRenderer.text(g, font, s, x, y, rgb);
    }
}
