package com.xfive.live.lyrics;

import com.xfive.live.download.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Memilih lirik bersinkron yang BENAR untuk sebuah lagu. Murni Java (tanpa Minecraft) supaya bisa diuji.
 * Aturan utama: lirik hanya dipakai jika judul dan artis cocok DAN durasinya hampir sama dengan lagu
 * (versi radio edit, live, atau remix punya waktu berbeda, jadi liriknya tidak akan sinkron).
 */
public final class LyricsPicker {
    /** Selisih durasi terbesar (detik) yang masih dianggap versi lagu yang sama. */
    public static final double MAX_DIFF_SEC = 4.0;

    public record Candidate(String title, String artist, double durationSec, boolean instrumental, String synced) {
    }

    private LyricsPicker() {
    }

    /** Membaca jawaban lrclib: satu objek atau array objek. Entri rusak dilewati. */
    public static List<Candidate> parse(String json) {
        List<Candidate> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        List<Object> items = root instanceof List ? MiniJson.asList(root) : List.of(root == null ? "" : root);
        for (Object o : items) {
            Map<String, Object> m = MiniJson.asMap(o);
            if (m == null || m.isEmpty()) {
                continue;
            }
            Object inst = m.get("instrumental");
            String synced = MiniJson.str(m.get("syncedLyrics"));
            double dur = MiniJson.num(m.get("duration"), 0L);
            Object d = m.get("duration");
            if (d instanceof Number n) {
                dur = n.doubleValue();
            }
            out.add(new Candidate(MiniJson.str(m.get("trackName")), MiniJson.str(m.get("artistName")), dur,
                    Boolean.TRUE.equals(inst), synced));
        }
        return out;
    }

    /** Huruf kecil, tanpa tanda kurung "(Official Video)", "feat.", dan tanda baca. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        t = t.replaceAll("\\b(feat|ft|featuring)\\b.*$", " ");
        t = t.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return t.replaceAll("\\s+", " ");
    }

    public static boolean sameTitle(String want, String got) {
        String a = normalize(want);
        String b = normalize(got);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b);
    }

    /** Artis cocok jika ada kata (3 huruf atau lebih) yang sama. Jika artis yang dicari kosong, dianggap cocok. */
    public static boolean sameArtist(String want, String got) {
        String a = normalize(want);
        if (a.isEmpty() || a.equals("unknown artist") || a.equals("unknown")) {
            return true;
        }
        String b = normalize(got);
        if (b.isEmpty()) {
            return false;
        }
        for (String w : a.split(" ")) {
            if (w.length() >= 3 && (" " + b + " ").contains(" " + w + " ")) {
                return true;
            }
        }
        return a.equals(b);
    }

    /**
     * Kandidat terbaik, atau null. durationMs harus diketahui (&gt; 0): tanpa durasi tidak ada cara
     * memastikan sinkron, jadi tidak ada lirik yang dipilih.
     */
    public static Candidate pick(List<Candidate> list, String title, String artist, long durationMs) {
        if (list == null || durationMs <= 0L) {
            return null;
        }
        double want = durationMs / 1000.0;
        Candidate best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Candidate c : list) {
            if (c == null || c.instrumental() || c.synced() == null || !c.synced().contains("[")) {
                continue;
            }
            if (!sameTitle(title, c.title()) || !sameArtist(artist, c.artist())) {
                continue;
            }
            if (c.durationSec() <= 0) {
                continue;
            }
            double diff = Math.abs(c.durationSec() - want);
            if (diff > MAX_DIFF_SEC) {
                continue;
            }
            if (!plausible(c.synced(), durationMs)) {
                continue;
            }
            if (diff < bestDiff) {
                best = c;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * Pemeriksaan akhir isi LRC: minimal 3 baris bertanda waktu, tidak melewati akhir lagu,
     * dan menutupi sebagian besar lagu (lirik yang berhenti di tengah biasanya versi lain).
     */
    public static boolean plausible(String lrc, long durationMs) {
        List<LyricLine> lines = LrcParser.parse(lrc);
        int real = 0;
        long last = -1L;
        for (LyricLine l : lines) {
            if (!l.text().isBlank()) {
                real++;
                last = Math.max(last, l.timeMs());
            }
        }
        if (real < 3) {
            return false;
        }
        if (durationMs > 0L) {
            if (last > durationMs + 3_000L) {
                return false;
            }
            if (last < durationMs * 0.35) {
                return false;
            }
        }
        return true;
    }

    /** true jika lirik yang sudah terpasang jelas tidak cocok dengan lagunya (melewati akhir lagu). */
    public static boolean clearlyWrong(List<LyricLine> lines, long durationMs) {
        if (lines == null || lines.isEmpty() || durationMs <= 0L) {
            return false;
        }
        return lines.get(lines.size() - 1).timeMs() > durationMs + 5_000L;
    }
}
