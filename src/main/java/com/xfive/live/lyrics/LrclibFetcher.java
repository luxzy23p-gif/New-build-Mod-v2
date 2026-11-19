package com.xfive.live.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Mengambil lirik sinkron (format LRC) dari lrclib.net, layanan lirik gratis tanpa akun.
 * Dipanggil dari thread latar, tidak pernah dari thread game. Hanya kode murni Java supaya bisa diuji.
 */
public final class LrclibFetcher {
    private static final String BASE = "https://lrclib.net/api/";
    private static final String AGENT = "XFiveLive/1.1 (https://github.com/luxzy23p-gif/mod-live-streaming)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private LrclibFetcher() {
    }

    /** Hasil pencarian: teks LRC (atau null) dan alasan singkat jika tidak ada. */
    public record Result(String text, String reason) {
    }

    /**
     * Teks LRC bersinkron yang cocok dengan lagu ini, atau null jika tidak ada versi yang pasti sinkron.
     * durationMs wajib diketahui: lirik dipilih hanya jika durasinya hampir sama dengan lagu.
     */
    public static String fetch(String artist, String title, long durationMs) {
        return fetchDetailed(artist, title, durationMs).text();
    }

    /** Seperti fetch(), tetapi memberi alasan yang jelas kalau lirik tidak ditemukan. */
    public static Result fetchDetailed(String artist, String title, long durationMs) {
        if (title == null || title.isBlank() || durationMs <= 0L) {
            return new Result(null, "Song length unknown, so lyrics cannot be matched.");
        }
        boolean hasArtist = artist != null && !artist.isBlank();
        java.util.List<LyricsPicker.Candidate> seen = new java.util.ArrayList<>();
        boolean reached = false;
        try {
            long sec = Math.round(durationMs / 1000.0);
            if (hasArtist) {
                String body = get(BASE + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                        + "&duration=" + Math.max(1, Math.min(3600, sec)));
                reached |= body != null;
                java.util.List<LyricsPicker.Candidate> got = LyricsPicker.parse(body);
                seen.addAll(got);
                LyricsPicker.Candidate c = LyricsPicker.pick(got, title, artist, durationMs);
                if (c != null) {
                    return new Result(c.synced(), "");
                }
                pause();
            }
            String clean = cleanTitle(title);
            String[] urls = {
                    BASE + "search?track_name=" + enc(clean) + (hasArtist ? "&artist_name=" + enc(artist) : ""),
                    BASE + "search?q=" + enc((hasArtist ? artist + " " : "") + clean),
                    BASE + "search?q=" + enc(clean)};
            for (String url : urls) {
                String body = get(url);
                reached |= body != null;
                java.util.List<LyricsPicker.Candidate> got = LyricsPicker.parse(body);
                seen.addAll(got);
                LyricsPicker.Candidate c = LyricsPicker.pick(got, title, artist, durationMs);
                if (c == null && !clean.equals(title.strip())) {
                    c = LyricsPicker.pick(got, clean, artist, durationMs);
                }
                if (c != null) {
                    return new Result(c.synced(), "");
                }
                pause();
            }
        } catch (IOException | RuntimeException e) {
            if (seen.isEmpty()) {
                return new Result(null, "Could not reach the lyrics service (check your internet).");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "");
        }
        if (seen.isEmpty() && !reached) {
            return new Result(null, "Could not reach the lyrics service (check your internet).");
        }
        return new Result(null, LyricsPicker.reason(seen, clean(title), artist, durationMs));
    }

    private static String clean(String title) {
        return cleanTitle(title);
    }

    /** Judul tanpa tag di ujung seperti "(Official Video)" atau "[Lyrics]". */
    public static String cleanTitle(String title) {
        String t = title.strip().replaceAll("^\\d{1,3}\\s*(?:[.)]|\\s-\\s)\\s*", "")
                .replaceAll("\\s*[(\\[][^)\\]]*[)\\]]\\s*$", "").strip();
        return t.isEmpty() ? title.strip() : t;
    }

    /** Jeda singkat antar permintaan supaya tidak melewati batas lrclib (sekitar 90 permintaan per menit). */
    private static void pause() throws InterruptedException {
        Thread.sleep(300L);
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", AGENT)
                .GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return res.statusCode() == 200 ? res.body() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8);
    }

    /** Nilai "syncedLyrics" non-kosong pertama di JSON (objek tunggal atau array hasil pencarian). */
    public static String firstSynced(String json) {
        String key = "\"syncedLyrics\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(key, from);
            if (k < 0) {
                return null;
            }
            int i = k + key.length();
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':')) {
                i++;
            }
            if (i < json.length() && json.charAt(i) == '"') {
                String value = readString(json, i + 1);
                if (value != null && value.contains("[")) {
                    return value;
                }
            }
            from = k + key.length();
        }
    }

    /** Membaca string JSON mulai tepat setelah tanda kutip pembuka, termasuk escape. */
    private static String readString(String s, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= s.length()) {
                return null;
            }
            char e = s.charAt(i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        return null;
                    }
                    try {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException bad) {
                        return null;
                    }
                    i += 4;
                }
                default -> out.append(e); // \" \\ \/
            }
        }
        return null;
    }

    /** Nama file aman untuk lirik hasil unduhan: "Artis - Judul". */
    public static String fileStem(String artist, String title) {
        String raw = (artist == null || artist.isBlank() ? "" : artist.strip() + " - ") + title.strip();
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
