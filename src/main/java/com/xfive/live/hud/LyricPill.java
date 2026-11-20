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

import java.util.ArrayList;
import java.util.List;

/**
 * Lirik dalam satu pil di tengah atas layar, sejajar dengan notch/kamera dan Dynamic Island:
 * waktu lagu di kiri, titik putih, teks lirik berjalan (kata yang sedang dinyanyikan disorot kapsul putih,
 * baris berikutnya pudar di belakangnya), dan gelombang kecil di kanan. Waktu baris diambil dari lirik bersinkron,
 * jadi mengikuti lagu secara otomatis (termasuk saat dijeda atau digeser).
 *
 * BELUM DIVERIFIKASI terhadap jar Minecraft 26.2 (lihat README).
 */
public final class LyricPill {
    public static final int H = 20;
    private static final String SEP = "  ·  ";

    // tata letak baris yang sedang tampil, dihitung ulang hanya saat barisnya berganti
    private static String layoutKey = "";
    private static int[] wordX = new int[0];
    private static int[] wordW = new int[0];
    private static int[] wordLen = new int[0];
    private static int curW;
    private static int nextX;
    private static int totalW;

    private static float scroll;
    private static float visible;
    private static float yNow = -1f;
    private static long lastNs;

    private LyricPill() {
    }

    private static boolean isFree(int[] pos) {
        return pos[0] >= 0 && pos[1] >= 0;
    }

    private static int pillWidth(int sw) {
        return Math.max(150, Math.min(sw - 100, 280));
    }

    /** Tempat pil: sama dengan Dynamic Island; jika pulau sedang tampil, tepat di bawahnya. */
    private static int targetY() {
        int[] isl = DynamicIslandHud.box();
        int y = isl[1];
        if (DynamicIslandHud.visible()) {
            y = isl[1] + Math.max(DynamicIslandHud.TOP, isl[3]) + 4;
        }
        return y;
    }

    /** Kotak pil: {x, y, lebar, tinggi}. Dipakai juga oleh menu untuk menggeser. */
    public static int[] box() {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int w = pillWidth(sw);
        int[] pos = c.hudPositions.get("lyricsHud");
        if (isFree(pos)) {
            return new int[] {pos[0], pos[1], w, H};
        }
        return new int[] {(sw - w) / 2, DynamicIslandHud.box()[1], w, H};
    }

    public static void render(GuiGraphicsExtractor g, XFiveConfig c, LyricsManager ly) {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016f : Math.min(0.05f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;

        var music = XFiveLiveClient.MUSIC;
        long songPos = LyricStyle.shifted(music.positionMs(), c.lyricsOffsetMs);
        String cur = "";
        String next = "";
        int idx = -1;
        boolean show;
        String note = "";
        if (ly.isEmpty()) {
            note = XFiveLiveClient.lyricsNote();
            show = !note.isEmpty() && music.currentTrack() != null && music.positionMs() <= 6_000L
                    && (c.autoLyrics || !note.isEmpty());
        } else {
            idx = ly.indexAt(songPos);
            cur = ly.textAt(idx);
            next = ly.textAt(idx + 1);
            if (idx < 0) {
                next = ly.textAt(0);
            }
            show = !(cur.isBlank() && next.isBlank());
        }
        // masuk/keluar halus
        visible += ((show ? 1f : 0f) - visible) * Math.min(1f, dt * 10f);
        if (!show && visible < 0.02f) {
            visible = 0f;
            return;
        }
        if (visible < 0.02f) {
            return;
        }

        Font font = GlassPanelRenderer.font();
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int w = pillWidth(sw);
        int[] pos = c.hudPositions.get("lyricsHud");
        int x;
        int ty = targetY();
        if (isFree(pos)) {
            x = pos[0];
            yNow = pos[1];
        } else {
            x = (sw - w) / 2;
            yNow = yNow < 0f ? ty : yNow + (ty - yNow) * Math.min(1f, dt * 12f);
        }
        int y = Math.round(yNow);
        float a = Math.max(0.35f, c.hudOpacity * c.customTransparency) * visible;
        int accent = GlassPanelRenderer.accent(c.theme);

        Glass.roundRect(g, x, y, w, H, H / 2, Glass.argb(0x07080D, 0.90f * a));
        Glass.border(g, x, y, w, H, H / 2, Glass.argb(0xFFFFFF, 0.30f * a), Glass.argb(0xFFFFFF, 0.14f * a));

        // waktu lagu di kiri pil
        String time = formatTime(music.positionMs());
        GlassPanelRenderer.textAlpha(g, font, time, x - GlassPanelRenderer.width(font, time) - 6, y + (H - 9) / 2,
                0xFFFFFF, visible);

        // titik putih di kiri dalam pil
        Glass.disc(g, x + 11, y + H / 2, 4, Glass.argb(0xFFFFFF, 0.95f * visible));

        // gelombang di kanan
        double time2 = System.nanoTime() / 1_000_000_000.0;
        boolean playing = music.isPlaying();
        int[] base = {5, 9, 6, 8};
        for (int i = 0; i < base.length; i++) {
            double wave = playing ? 0.55 + 0.45 * Math.sin(time2 * 7 + i * 1.7) : 0.5;
            int bh = Math.max(2, (int) Math.round(base[i] * wave));
            int bx = x + w - 22 + i * 4;
            g.fill(bx, y + H / 2 - bh / 2, bx + 2, y + H / 2 - bh / 2 + bh, Glass.argb(0xFFFFFF, 0.95f * visible));
        }

        int innerL = x + 22;
        int innerR = x + w - 26;
        int innerW = innerR - innerL;
        int textY = y + (H - 9) / 2;
        g.enableScissor(innerL, y, innerR, y + H);
        if (!note.isEmpty()) {
            GlassPanelRenderer.textAlpha(g, font, GlassPanelRenderer.fit(font, note, innerW), innerL, textY,
                    GlassPanelRenderer.MUTED & 0xFFFFFF, visible);
        } else {
            drawLyrics(g, font, c, ly, idx, cur, next, songPos, innerL, innerW, y, textY, visible, accent);
        }
        g.disableScissor();
    }

    private static void drawLyrics(GuiGraphicsExtractor g, Font font, XFiveConfig c, LyricsManager ly, int idx, String cur,
                                   String next, long songPos, int innerL, int innerW, int y, int textY, float a,
                                   int accent) {
        layout(font, cur, next);
        boolean beforeStart = idx < 0;
        float countdown = c.lyricsCountdown ? LyricStyle.countdown(ly.lines(), idx, songPos) : -1f;

        // kata yang sedang dinyanyikan (juga dipakai untuk menggeser teks walau sorot kata dimatikan)
        int word = -1;
        if (!cur.isBlank() && !beforeStart) {
            long since = songPos - ly.lines().get(idx).timeMs();
            long sing = LyricStyle.singMs(cur.length(), LyricStyle.lineDuration(ly.lines(), idx));
            word = LyricStyle.wordAt(wordLen, since, sing);
        }
        int focus = word >= 0 ? wordX[word] : 0;
        float target = LyricStyle.scrollTarget(focus, innerW, totalW);
        scroll += (target - scroll) * 0.18f;
        if (Math.abs(target - scroll) < 0.5f) {
            scroll = target;
        }
        int off = innerL - Math.round(scroll);

        if (countdown >= 0f) {
            drawDots(g, innerL + innerW / 2, y + H / 2, countdown, a);
            return;
        }
        boolean karaoke = c.lyricsKaraoke && word >= 0;
        for (int i = 0; i < wordX.length; i++) {
            int wx = off + wordX[i];
            if (wx + wordW[i] < innerL - 2 || wx > innerL + innerW + 2) {
                continue;
            }
            String s = WORDS.get(i);
            if (karaoke && i == word) {
                Glass.roundRect(g, wx - 3, y + 4, wordW[i] + 6, H - 8, (H - 8) / 2, Glass.argb(0xF2F4F8, 0.96f * a));
                GlassPanelRenderer.textAlpha(g, font, s, wx, textY, 0x14161F, a);
            } else {
                int rgb = beforeStart ? 0x8E95A6 : (karaoke && i > word ? 0xC9CEDA : 0xFFFFFF);
                GlassPanelRenderer.textAlpha(g, font, s, wx, textY, rgb, a);
            }
        }
        if (!next.isBlank()) {
            // separator "  ·  " lalu baris berikutnya, pudar
            int sx = off + curW;
            if (!cur.isBlank() && sx < innerL + innerW + 4) {
                GlassPanelRenderer.textAlpha(g, font, SEP, sx, textY, 0x8E95A6, a);
            }
            int nx = off + nextX;
            if (nx < innerL + innerW + 4) {
                GlassPanelRenderer.textAlpha(g, font, next, nx, textY, 0x8E95A6, a);
            }
        }
    }

    private static final List<String> WORDS = new ArrayList<>();

    /** Menghitung posisi tiap kata dan baris berikutnya. Hanya dijalankan saat teks atau font berganti. */
    private static void layout(Font font, String cur, String next) {
        String key = XFiveLiveClient.CONFIG.uiFont + '\u0001' + cur + '\u0001' + next;
        if (key.equals(layoutKey)) {
            return;
        }
        layoutKey = key;
        WORDS.clear();
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        int n = cur.length();
        while (i < n) {
            while (i < n && cur.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int s = i;
            while (i < n && cur.charAt(i) != ' ') {
                i++;
            }
            spans.add(new int[] {s, i});
            WORDS.add(cur.substring(s, i));
        }
        wordX = new int[spans.size()];
        wordW = new int[spans.size()];
        wordLen = new int[spans.size()];
        for (int k = 0; k < spans.size(); k++) {
            int[] sp = spans.get(k);
            wordX[k] = GlassPanelRenderer.width(font, cur.substring(0, sp[0]));
            wordW[k] = GlassPanelRenderer.width(font, WORDS.get(k));
            wordLen[k] = sp[1] - sp[0];
        }
        curW = cur.isBlank() ? 0 : GlassPanelRenderer.width(font, cur);
        nextX = curW + (cur.isBlank() ? 0 : GlassPanelRenderer.width(font, SEP));
        totalW = nextX + (next.isBlank() ? 0 : GlassPanelRenderer.width(font, next));
        scroll = 0f;
    }

    /** Tiga titik yang terisi satu per satu selama jeda instrumental. */
    private static void drawDots(GuiGraphicsExtractor g, int cx, int cy, float progress, float a) {
        int size = 4;
        int gap = 4;
        int x0 = cx - (3 * size + 2 * gap) / 2;
        for (int i = 0; i < 3; i++) {
            float fill = Math.max(0f, Math.min(1f, progress * 3f - i));
            int x = x0 + i * (size + gap);
            g.fill(x, cy - size / 2, x + size, cy + size / 2, Glass.argb(0xFFFFFF, (0.25f + 0.70f * fill) * a));
        }
    }

    private static String formatTime(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }
}
