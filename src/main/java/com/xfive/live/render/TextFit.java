package com.xfive.live.render;

import java.util.function.ToIntFunction;

/** Memotong teks dengan "..." agar muat. Murni logika (tanpa Minecraft), jadi bisa diuji. */
public final class TextFit {
    private TextFit() {
    }

    /**
     * Awalan terpanjang dari s yang, ditambah "...", masih muat dalam maxPx (minimal 1 huruf).
     * Memakai pencarian biner: sekitar log2(panjang) pengukuran, bukan satu pengukuran per huruf.
     */
    public static String fit(ToIntFunction<String> width, String s, int maxPx) {
        if (s.isEmpty() || width.applyAsInt(s) <= maxPx) {
            return s;
        }
        int lo = 1; // awalan 1 huruf selalu diterima
        int hi = s.length() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width.applyAsInt(s.substring(0, mid) + "...") <= maxPx) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "...";
    }
}
