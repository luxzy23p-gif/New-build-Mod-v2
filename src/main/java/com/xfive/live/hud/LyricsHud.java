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
            renderNote(g, c);
            return;
        }
        long songPos = LyricStyle.shifted(XFiveLiveClient.MUSIC.positionMs(), c.lyricsOffsetMs);
        int idx = ly.indexAt(songPos);
        float countdown = c.lyricsCountdown ? LyricStyle.countdown(ly.lines(), idx, songPos) : -1f;
        if (idx < 0 && countdown < 0f) {
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

        if (c.lyricsBubble) {
            drawBubble(g, font, c, ly, idx, cx, baseY, lineH, sw, alpha, accent);
        }
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

    /** Kapsul kaca gelap di belakang lirik supaya terbaca di atas dunia Minecraft yang ramai. */
    private static void drawBubble(GuiGraphicsExtractor g, Font font, XFiveConfig c, LyricsManager ly, int idx, int cx,
                                   int baseY, int lineH, int sw, float alpha, int accent) {
        int textW = Math.max(60, GlassPanelRenderer.width(font, ly.textAt(idx)));
        int top = c.lyricsPreview ? baseY : baseY + lineH;
        int bottom = (c.lyricsPreview ? baseY + lineH * 2 : baseY + lineH) + 9;
        if (c.lyricsPreview) {
            textW = Math.max(textW, GlassPanelRenderer.width(font, ly.textAt(idx - 1)));
            textW = Math.max(textW, GlassPanelRenderer.width(font, ly.textAt(idx + 1)));
        }
        int w = Math.min(sw - 8, textW + 28);
        int x = cx - w / 2;
        int y = top - 6;
        int h = bottom - top + 12;
        float a = Math.max(0.35f, alpha);
        Glass.roundRect(g, x, y, w, h, Math.min(10, h / 2), Glass.argb(0x0B0D14, 0.62f * a),
                Glass.argb(0x14161F, 0.72f * a));
        Glass.border(g, x, y, w, h, Math.min(10, h / 2), Glass.argb(accent, 0.45f * a), Glass.argb(0xFFFFFF, 0.10f * a));
    }

    /** Beberapa detik pertama lagu: beri tahu status lirik (sedang dicari, atau kenapa tidak ada). */
    private static void renderNote(GuiGraphicsExtractor g, XFiveConfig c) {
        if (!c.autoLyrics && XFiveLiveClient.lyricsNote().isEmpty()) {
            return;
        }
        String note = XFiveLiveClient.lyricsNote();
        if (note.isEmpty() || XFiveLiveClient.MUSIC.currentTrack() == null
                || XFiveLiveClient.MUSIC.positionMs() > 6_000L) {
            return;
        }
        Font font = GlassPanelRenderer.font();
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        String text = GlassPanelRenderer.fit(font, note, Math.max(60, sw - 40));
        int w = GlassPanelRenderer.width(font, text) + 20;
        int y = presetBaseY(c, sh) + lineHeight(c);
        Glass.roundRect(g, (sw - w) / 2, y - 5, w, 19, 9, Glass.argb(0x0B0D14, 0.55f));
        drawCentered(g, font, text, sw / 2, y, GlassPanelRenderer.MUTED);
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
