package com.xfive.live.config;

/** Daftar tema warna UI dan HUD. Murni Java supaya bisa diuji tanpa Minecraft. */
public final class Themes {
    public static final String[] IDS = {"purple", "cyan", "rose", "emerald", "amber", "ocean", "crimson", "mono"};
    public static final String[] NAMES = {"Purple", "Cyan", "Rose", "Emerald", "Amber", "Ocean", "Crimson", "Mono"};
    public static final int[] COLORS = {0x965CFF, 0x40DFFF, 0xFF5C8A, 0x3DDC97, 0xFFB347, 0x4C8DFF, 0xFF4D4D, 0xE6E6E6};

    /** Id tema untuk warna buatan sendiri (diatur lewat Hue, Saturation, Brightness). */
    public static final String CUSTOM = "custom";

    private static volatile float customH = 0.74f;
    private static volatile float customS = 0.64f;
    private static volatile float customV = 1.00f;
    private static volatile int customRgb = hsvToRgb(0.74f, 0.64f, 1.0f);

    private Themes() {
    }

    public static boolean isCustom(String id) {
        return id != null && CUSTOM.equals(id.strip());
    }

    /** Mengatur warna kustom dari HSV (masing-masing 0..1; hue berputar). */
    public static void setCustomHsv(float h, float s, float v) {
        float hh = h - (float) Math.floor(h);
        customH = hh;
        customS = clamp01(s);
        customV = clamp01(v);
        customRgb = hsvToRgb(customH, customS, customV);
    }

    public static float customHue() {
        return customH;
    }

    public static float customSat() {
        return customS;
    }

    public static float customVal() {
        return customV;
    }

    public static int customColor() {
        return customRgb;
    }

    private static float clamp01(float f) {
        return Float.isNaN(f) ? 0f : Math.max(0f, Math.min(1f, f));
    }

    /** HSV (0..1) ke RGB 0xRRGGBB. */
    public static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int i = (int) Math.floor(hh) % 6;
        float f = hh - (float) Math.floor(hh);
        float ss = clamp01(s);
        float vv = clamp01(v);
        float p = vv * (1f - ss);
        float q = vv * (1f - ss * f);
        float t = vv * (1f - ss * (1f - f));
        float r;
        float g;
        float b;
        switch (i) {
            case 0 -> { r = vv; g = t; b = p; }
            case 1 -> { r = q; g = vv; b = p; }
            case 2 -> { r = p; g = vv; b = t; }
            case 3 -> { r = p; g = q; b = vv; }
            case 4 -> { r = t; g = p; b = vv; }
            default -> { r = vv; g = p; b = q; }
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    /** Warna campuran dua RGB (t = 0 hasilnya a, t = 1 hasilnya b). */
    public static int blend(int a, int b, float t) {
        float k = clamp01(t);
        int r = Math.round(((a >> 16) & 0xFF) * (1f - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1f - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1f - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** Indeks tema, atau 0 (purple) jika id tidak dikenal. */
    public static int indexOf(String id) {
        if (id != null) {
            for (int i = 0; i < IDS.length; i++) {
                if (IDS[i].equals(id.strip())) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static String normalize(String id) {
        if (isCustom(id)) {
            return CUSTOM;
        }
        return IDS[indexOf(id)];
    }

    public static int accent(String id) {
        if (isCustom(id)) {
            return customRgb;
        }
        return COLORS[indexOf(id)];
    }

    public static String next(String id) {
        return IDS[(indexOf(id) + 1) % IDS.length];
    }
}
