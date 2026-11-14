package com.xfive.live.config;

/**
 * Isi dan waktu animasi halaman Spesifikasi. Murni Java supaya bisa diuji tanpa Minecraft.
 * Semua fungsi menerima detik sejak halaman dibuka (s) dan mengembalikan kemajuan 0..1 (atau hitungan).
 */
public final class SpecSheet {
    public static final String TITLE = "Infinix GT30 Pro";
    public static final String[][] ROWS = {
            {"Chipset", "Dimensity 8350 Ultimate"},
            {"RAM / ROM", "8 GB / 256 GB"},
            {"GPU", "Mali"},
            {"OS", "XOS 16"}
    };

    private static final String GLYPHS = "01#%&@$ABCDEFGHKLMNRSTXYZ<>/=+";
    public static final double HEADING_START = 0.75;
    public static final double HEADING_PER_CHAR = 0.045;
    public static final double HEADING_SETTLE = 0.22;
    private static final double ROW_START = 1.35;
    private static final double ROW_STEP = 0.22;

    private SpecSheet() {
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double easeOutBack(double t) {
        t = clamp(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double u = t - 1.0;
        return 1.0 + c3 * u * u * u + c1 * u * u;
    }

    private static double easeOutCubic(double t) {
        double u = 1.0 - clamp(t);
        return 1.0 - u * u * u;
    }

    /** Garis tepi HP tergambar 0..1 (selesai di 0,55 detik). */
    public static float outline(double s) {
        return (float) easeOutCubic(s / 0.55);
    }

    /** Skala HP: mulai kecil, sedikit melewati 1, lalu menetap di 1. */
    public static float phoneScale(double s) {
        return (float) (0.2 + 0.8 * easeOutBack(s / 0.6));
    }

    /** Putaran HP dalam radian: mulai miring, menetap di 0. */
    public static float phoneTurn(double s) {
        return (float) (-0.55 * (1.0 - easeOutCubic(s / 0.6)));
    }

    /** Chip muncul membal setelah HP tergambar. */
    public static float chip(double s) {
        return (float) easeOutBack((s - 0.5) / 0.45);
    }

    /** Kaki chip memanjang bergantian (i = 0..2). */
    public static float pin(double s, int i) {
        return (float) easeOutCubic((s - 0.7 - i * 0.05) / 0.2);
    }

    /** Batang memori ke-i (0..1) melebar dari tengah. */
    public static float bar(double s, int i) {
        return (float) easeOutBack((s - 0.9 - i * 0.15) / 0.4);
    }

    /** 0 = belum muncul, 1 = huruf acak, 2 = huruf sudah jadi. */
    public static int headingState(int index, double s) {
        double local = s - HEADING_START - index * HEADING_PER_CHAR;
        if (local < 0) {
            return 0;
        }
        return local < HEADING_SETTLE ? 1 : 2;
    }

    /** Detik saat seluruh judul sudah jadi. */
    public static double headingDone() {
        return HEADING_START + (TITLE.length() - 1) * HEADING_PER_CHAR + HEADING_SETTLE;
    }

    /** Huruf acak untuk efek glitch, tergantung seed. */
    public static char glyph(int seed) {
        int h = seed * 0x9E3779B1;
        h ^= h >>> 15;
        return GLYPHS.charAt(Math.floorMod(h, GLYPHS.length()));
    }

    /** Kemunculan baris ke-i (0..1), muncul bergantian. */
    public static float rowIn(int i, double s) {
        return (float) easeOutCubic((s - ROW_START - i * ROW_STEP) / 0.35);
    }

    /** Jumlah huruf nilai baris ke-i yang sudah diketik (55 huruf per detik). */
    public static int valueChars(int length, int i, double s) {
        double since = s - ROW_START - i * ROW_STEP - 0.15;
        if (since <= 0) {
            return 0;
        }
        return (int) Math.min(length, Math.floor(since * 55.0));
    }

    /** Detik saat seluruh animasi masuk selesai. */
    public static double total() {
        return ROW_START + (ROWS.length - 1) * ROW_STEP + 0.15 + 0.6;
    }
}
