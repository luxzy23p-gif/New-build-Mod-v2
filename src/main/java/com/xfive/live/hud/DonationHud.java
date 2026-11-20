package com.xfive.live.hud;

import com.xfive.live.XFiveLiveClient;
import com.xfive.live.config.XFiveConfig;
import com.xfive.live.donate.DonationAlerts;
import com.xfive.live.render.Glass;
import com.xfive.live.render.GlassPanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Alert donasi: kartu kaca emas di tengah atas layar (di bawah Dynamic Island dan pil lirik) yang meluncur turun,
 * menampilkan nama donatur, jumlah, dan pesan, lalu menghilang. Beberapa alert antre satu per satu.
 *
 * BELUM DIVERIFIKASI terhadap jar Minecraft 26.2 (lihat README).
 */
public final class DonationHud {
    private static final int H = 34;
    private static final long FADE_MS = 350L;
    private static final int GOLD = 0xFFC83D;

    private DonationHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        if (!c.donationAlerts) {
            return;
        }
        long now = System.currentTimeMillis();
        long show = c.donationSeconds * 1000L;
        DonationAlerts.Alert a = XFiveLiveClient.DONATIONS.tick(now, show);
        if (a == null) {
            return;
        }
        float vis = DonationAlerts.visibility(now - XFiveLiveClient.DONATIONS.startedMs(), show, FADE_MS);
        if (vis <= 0.01f) {
            return;
        }
        Font font = GlassPanelRenderer.font();
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int w = Math.max(160, Math.min(sw - 24, 250));
        int x = (sw - w) / 2;
        int y = baseY(c) + Math.round((1f - vis) * -14f);

        Glass.roundRect(g, x, y, w, H, 10, Glass.argb(0x0B0D14, 0.92f * vis));
        Glass.border(g, x, y, w, H, 10, Glass.argb(GOLD, 0.85f * vis), Glass.argb(GOLD, 0.35f * vis));

        // koin emas bertuliskan Rp
        int cx = x + 17;
        int cy = y + H / 2;
        Glass.disc(g, cx, cy, 10, Glass.argb(GOLD, 0.95f * vis));
        GlassPanelRenderer.textAlpha(g, font, "Rp", cx - GlassPanelRenderer.width(font, "Rp") / 2, cy - 4, 0x3A2A00, vis);

        int tx = x + 34;
        String amount = DonationAlerts.formatRupiah(a.amount());
        int amountW = GlassPanelRenderer.width(font, amount);
        int nameMax = Math.max(30, w - 34 - 10 - amountW - 8);
        GlassPanelRenderer.textAlpha(g, font, GlassPanelRenderer.fit(font, a.name(), nameMax), tx, y + 7, 0xFFFFFF, vis);
        GlassPanelRenderer.textAlpha(g, font, amount, x + w - 10 - amountW, y + 7, GOLD, vis);
        String msg = a.message().isEmpty() ? "sent a donation" : a.message();
        GlassPanelRenderer.textAlpha(g, font, GlassPanelRenderer.fit(font, msg, w - 34 - 10), tx, y + 19, 0xB4BAC8, vis);
    }

    /** Di bawah Dynamic Island (jika sedang tampil) dan pil lirik (jika dipakai). */
    private static int baseY(XFiveConfig c) {
        int[] isl = DynamicIslandHud.box();
        int y = isl[1];
        if (DynamicIslandHud.visible()) {
            y += Math.max(DynamicIslandHud.TOP, isl[3]) + 4;
        }
        if (c.enableLyrics && c.lyricsPill) {
            y += LyricPill.H + 4;
        }
        return y;
    }
}
