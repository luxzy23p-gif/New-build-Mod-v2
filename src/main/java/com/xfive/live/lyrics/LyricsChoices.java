package com.xfive.live.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daftar pilihan lirik untuk dipilih sendiri oleh pemain (kalau pencarian otomatis tidak menemukan yang pas).
 * Kelas murni, tanpa jaringan, supaya bisa diuji.
 */
public final class LyricsChoices {
    /** Penanda di dalam file .lrc yang dipilih sendiri: file ini tidak boleh dibuang oleh pemeriksaan otomatis. */
    public static final String MARKER = "[by:xfive-manual]";
    public static final int MAX = 8;

    private LyricsChoices() {
    }

    /** Satu pilihan; diffSec = durasi lirik dikurangi durasi lagu (0 jika durasi lagu belum diketahui). */
    public record Option(String title, String artist, double durationSec, double diffSec, boolean titleMatch,
                         String synced) {
        /** Teks pendek untuk tombol: "Judul - Artis  3:25 (+2 s)". */
        public String label() {
            String name = (title == null || title.isBlank() ? "?" : title)
                    + (artist == null || artist.isBlank() ? "" : " - " + artist);
            int total = (int) Math.round(durationSec);
            String time = total > 0 ? "  " + total / 60 + ":" + (total % 60 < 10 ? "0" : "") + total % 60 : "";
            String diff = "";
            if (total > 0 && Math.abs(diffSec) >= 1.0) {
                diff = " (" + (diffSec > 0 ? "+" : "") + Math.round(diffSec) + " s)";
            }
            return name + time + diff;
        }
    }

    /**
     * Pilihan yang layak: bersinkron (minimal 3 baris bertanda waktu), bukan instrumental, tanpa duplikat.
     * Urutan: judul cocok dulu, lalu selisih durasi terkecil. durationMs <= 0 berarti durasi tidak diketahui.
     */
    public static List<Option> rank(List<LyricsPicker.Candidate> list, String title, String artist, long durationMs) {
        List<Option> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        double want = durationMs > 0L ? durationMs / 1000.0 : 0.0;
        Set<String> seen = new HashSet<>();
        for (LyricsPicker.Candidate c : list) {
            if (c == null || c.instrumental() || c.synced() == null || !c.synced().contains("[")) {
                continue;
            }
            if (realLines(c.synced()) < 3) {
                continue;
            }
            String key = LyricsPicker.normalize(c.title()) + "|" + LyricsPicker.normalize(c.artist()) + "|"
                    + Math.round(c.durationSec()) + "|" + c.synced().length();
            if (!seen.add(key)) {
                continue;
            }
            boolean tm = LyricsPicker.sameTitle(title, c.title())
                    || LyricsPicker.sameTitle(LrclibFetcher.cleanTitle(title == null ? "" : title), c.title())
                    || LyricsPicker.sameTitle(artist, c.title());
            double diff = want > 0 && c.durationSec() > 0 ? c.durationSec() - want : 0.0;
            out.add(new Option(c.title(), c.artist(), c.durationSec(), diff, tm, c.synced()));
        }
        out.sort(Comparator.comparing((Option o) -> !o.titleMatch())
                .thenComparingDouble(o -> want > 0 && o.durationSec() > 0 ? Math.abs(o.diffSec()) : 0.0));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static int realLines(String lrc) {
        int n = 0;
        for (LyricLine l : LrcParser.parse(lrc)) {
            if (!l.text().isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** Teks yang disimpan ke file: penanda manual di baris pertama. */
    public static String mark(String lrc) {
        return lrc.contains(MARKER) ? lrc : MARKER + "\n" + lrc;
    }

    public static boolean isMarked(String lrc) {
        return lrc != null && lrc.contains(MARKER);
    }
}
