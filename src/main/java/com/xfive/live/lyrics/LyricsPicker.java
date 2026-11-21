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
        t = t.replaceAll("^\\s*\\d{1,3}\\s*(?:[.)]|\\s-\\s)\\s*", ""); // "01. Judul", "01 - Judul"
        t = t.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        t = t.replaceAll("\\b(feat|ft|featuring)\\b.*$", " ");
        t = t.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return t.replaceAll("\\s+", " ");
    }

    private static final java.util.Set<String> NOISE = java.util.Set.of("remaster", "remastered", "version", "edit",
            "single", "mono", "stereo", "deluxe", "album", "radio", "original", "explicit", "clean", "official", "audio");

    /** Judul tanpa kata keterangan versi ("Remastered 2015", "Single Version") yang tidak mengubah lagunya. */
    static String core(String s) {
        String n = normalize(s);
        if (n.isEmpty()) {
            return n;
        }
        StringBuilder out = new StringBuilder();
        for (String w : n.split(" ")) {
            if (NOISE.contains(w) || w.matches("(19|20)\\d\\d")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(w);
        }
        return out.length() == 0 ? n : out.toString();
    }

    public static boolean sameTitle(String want, String got) {
        String a = normalize(want);
        String b = normalize(got);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || core(want).equals(core(got));
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
            if (c.durationSec() <= 0) {
                continue;
            }
            double diff = Math.abs(c.durationSec() - want);
            if (diff > MAX_DIFF_SEC || !titleArtistOk(c, title, artist, diff)) {
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
     * Untuk sumber yang hanya memberi daftar lagu (tanpa lirik): indeks lagu yang judul dan artisnya cocok dan
     * durasinya wajar, yang paling dekat durasinya lebih dulu. Maksimal max indeks. Durasi tidak diketahui
     * (di salah satu sisi) berarti hanya judul dan artis yang diperiksa, dan liriknya nanti diperiksa panjangnya.
     */
    public static List<Integer> shortlist(List<Candidate> metas, String title, String artist, long durationMs, int max) {
        List<Integer> idx = new ArrayList<>();
        if (metas == null) {
            return idx;
        }
        double want = durationMs / 1000.0;
        double[] diffs = new double[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            Candidate c = metas.get(i);
            if (c == null || c.instrumental()) {
                continue;
            }
            boolean known = durationMs > 0L && c.durationSec() > 0;
            double diff = known ? Math.abs(c.durationSec() - want) : MAX_DIFF_SEC;
            if (known && diff > MAX_DIFF_SEC) {
                continue;
            }
            if (titleArtistOk(c, title, artist, diff)) {
                diffs[i] = diff;
                idx.add(i);
            }
        }
        idx.sort((a, b) -> Double.compare(diffs[a], diffs[b]));
        return idx.size() > max ? new ArrayList<>(idx.subList(0, max)) : idx;
    }

    /**
     * Judul dan artis cocok. Keringanan yang aman: (1) nama file tertukar "Judul - Artis", (2) artis beda ejaan atau
     * aksara tetapi judulnya sama persis (minimal 4 huruf) dan durasinya selisih paling banyak 2 detik.
     */
    private static boolean titleArtistOk(Candidate c, String title, String artist, double diff) {
        boolean t = sameTitle(title, c.title());
        if (t && sameArtist(artist, c.artist())) {
            return true;
        }
        if (!t && sameTitle(artist, c.title()) && sameArtist(title, c.artist())) {
            return true;
        }
        return t && diff <= STRICT_DIFF_SEC && normalize(title).length() >= 4;
    }

    /** Selisih durasi (detik) yang cukup ketat untuk menerima artis yang ejaannya berbeda. */
    public static final double STRICT_DIFF_SEC = 2.0;

    /**
     * Alasan singkat (bahasa Inggris, tampil di Now Playing) kenapa tidak ada lirik yang dipilih dari daftar ini.
     * Dipanggil hanya jika pick() mengembalikan null.
     */
    public static String reason(List<Candidate> list, String title, String artist, long durationMs) {
        if (list == null || list.isEmpty()) {
            return "No lyrics for this song on lrclib yet.";
        }
        double want = durationMs / 1000.0;
        boolean titleSeen = false;
        boolean onlyPlain = false;
        double nearest = Double.MAX_VALUE;
        double nearestSigned = 0;
        boolean weak = false;
        for (Candidate c : list) {
            if (c == null || !(sameTitle(title, c.title()) || sameTitle(artist, c.title()))) {
                continue;
            }
            titleSeen = true;
            if (c.instrumental()) {
                return "This song is marked instrumental, so there are no lyrics.";
            }
            if (c.synced() == null || !c.synced().contains("[")) {
                onlyPlain = true;
                continue;
            }
            if (!plausible(c.synced(), durationMs)) {
                weak = true;
            }
            double d = c.durationSec() - want;
            if (Math.abs(d) < nearest) {
                nearest = Math.abs(d);
                nearestSigned = d;
            }
        }
        if (!titleSeen) {
            return "Lyrics were found only for other songs. Check the file name (Artist - Title).";
        }
        if (nearest == Double.MAX_VALUE) {
            return onlyPlain ? "Only unsynced lyrics exist, so they are hidden (they would not follow the song)."
                    : "No synced lyrics for this song.";
        }
        if (nearest > MAX_DIFF_SEC) {
            return "Lyrics exist for a version " + Math.round(nearest) + " s "
                    + (nearestSigned > 0 ? "longer" : "shorter") + " than yours, so they would be out of sync.";
        }
        return weak ? "The lyrics found do not fit this song's length, so they are hidden."
                : "No synced lyrics matched this song and artist.";
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
