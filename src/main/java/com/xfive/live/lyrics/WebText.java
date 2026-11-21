package com.xfive.live.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Permintaan HTTP kecil untuk sumber lirik tambahan. Hanya dipanggil dari thread latar. */
final class WebText {
    static final String AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private WebText() {
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s.strip(), StandardCharsets.UTF_8);
    }

    /** Isi jawaban jika status 200, selain itu null. */
    static String get(String url, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", AGENT);
        if (referer != null) {
            b.header("Referer", referer);
        }
        return send(b.GET().build());
    }

    static String postForm(String url, String body, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", AGENT).header("Content-Type", "application/x-www-form-urlencoded");
        if (referer != null) {
            b.header("Referer", referer);
        }
        return send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static String send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return res.statusCode() == 200 ? res.body() : null;
    }
}
