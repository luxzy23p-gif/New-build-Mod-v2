package com.xfive.live.lyrics;

import com.xfive.live.download.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Sumber lirik tambahan: API publik Better Lyrics (betterlyrics.org). Tanpa API key hanya lagu yang sudah ada di
 * cache mereka yang dijawab; selebihnya menjawab 401, dan itu dianggap "tidak ada". Jawabannya TTML yang diubah ke
 * LRC per baris, lalu tetap diperiksa panjangnya terhadap lagu. Dipanggil dari thread latar saja.
 */
public final class BetterLyricsFetcher {
    /** Alamat API (bisa diganti untuk pengujian). */
    public static volatile String base = "https://api.betterlyrics.org";
    private static final String AGENT = "XFiveLive/1.1 (Minecraft mod; https://github.com/luxzy23p-gif/New-build-Mod-v2)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BetterLyricsFetcher() {
    }

    public static String url(String artist, String title, long durationMs) {
        StringBuilder u = new StringBuilder(base).append("/getLyrics?s=").append(enc(title));
        if (artist != null && !artist.isBlank()) {
            u.append("&a=").append(enc(artist));
        }
        if (durationMs > 0) {
            u.append("&d=").append(Math.max(1, Math.round(durationMs / 1000.0)));
        }
        return u.toString();
    }

    /**
     * LRC bersinkron untuk lagu ini, atau Result dengan alasan. Lirik hanya dipakai jika wajar untuk panjang lagu
     * (LyricsPicker.plausible), supaya versi lain yang melenceng tidak ikut.
     */
    public static LrclibFetcher.Result fetch(String artist, String title, long durationMs) {
        if (title == null || title.isBlank()) {
            return new LrclibFetcher.Result(null, "");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url(artist, title, durationMs)))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", AGENT)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 401 || res.statusCode() == 404) {
                return new LrclibFetcher.Result(null, "Better Lyrics does not have this song cached.");
            }
            if (res.statusCode() == 429) {
                return new LrclibFetcher.Result(null, "Better Lyrics is busy right now (too many requests).");
            }
            if (res.statusCode() != 200) {
                return new LrclibFetcher.Result(null, "");
            }
            String lrc = TtmlParser.toLrc(ttmlOf(res.body()));
            if (lrc.isEmpty()) {
                return new LrclibFetcher.Result(null, "Better Lyrics answered, but without usable lyrics.");
            }
            if (durationMs > 0 && !LyricsPicker.plausible(lrc, durationMs)) {
                return new LrclibFetcher.Result(null, "Better Lyrics has lyrics that do not fit this song's length, so they are hidden.");
            }
            return new LrclibFetcher.Result(lrc, "");
        } catch (IOException | RuntimeException e) {
            return new LrclibFetcher.Result(null, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LrclibFetcher.Result(null, "");
        }
    }

    /** Isi TTML dari jawaban: JSON {"ttml": "..."} atau langsung dokumen TTML. */
    static String ttmlOf(String body) {
        if (body == null) {
            return "";
        }
        String t = body.strip();
        if (t.startsWith("<")) {
            return t;
        }
        try {
            Map<String, Object> m = MiniJson.asMap(MiniJson.parse(t));
            String v = m == null ? null : MiniJson.str(m.get("ttml"));
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8);
    }
}
