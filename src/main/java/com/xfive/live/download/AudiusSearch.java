package com.xfive.live.download;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pencarian lagu di Audius (platform musik terbuka dengan API publik untuk aplikasi pihak ketiga).
 * Setiap hasil adalah SATU lagu lengkap yang boleh diputar (stream), tanpa disimpan.
 */
public final class AudiusSearch {
    /** Alamat API (bisa diganti untuk pengujian). */
    public static volatile String host = "https://api.audius.co";
    public static final String APP_NAME = "XFiveLive";

    private AudiusSearch() {
    }

    public static String cleanQuery(String q) {
        return q == null ? "" : q.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
    }

    public static String searchUrl(String query, int limit) {
        return host + "/v1/tracks/search?query=" + URLEncoder.encode(cleanQuery(query), StandardCharsets.UTF_8)
                + "&limit=" + limit + "&app_name=" + APP_NAME;
    }

    public static List<Song> search(String query, int limit) throws IOException {
        if (cleanQuery(query).isEmpty()) {
            throw new DownloadException("Type a song or artist name", false);
        }
        return parse(Http.getText(searchUrl(query, limit), 3_000_000));
    }

    /** Judul dari situs pengunduh sering diawali "y2mate.com - "; dibuang agar rapi. */
    static String cleanTitle(String t) {
        String s = t == null ? "" : t.strip();
        s = s.replaceFirst("(?i)^(y2mate\\.com|ytmp3\\.\\w+|savefrom\\.net)\\s*[-_]\\s*", "");
        return s.isEmpty() ? "Untitled" : s;
    }

    public static List<Song> parse(String json) throws IOException {
        List<Song> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            List<Object> data = root == null ? null : MiniJson.asList(root.get("data"));
            if (data == null) {
                throw new DownloadException("Search answer was not understood", false);
            }
            for (Object o : data) {
                Map<String, Object> d = MiniJson.asMap(o);
                if (d == null) {
                    continue;
                }
                String id = MiniJson.str(d.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (Boolean.FALSE.equals(d.get("is_streamable")) || Boolean.TRUE.equals(d.get("is_stream_gated"))) {
                    continue;
                }
                Map<String, Object> access = MiniJson.asMap(d.get("access"));
                if (access != null && Boolean.FALSE.equals(access.get("stream"))) {
                    continue;
                }
                Map<String, Object> user = MiniJson.asMap(d.get("user"));
                String artist = user == null ? null : MiniJson.str(user.get("name"));
                out.add(new Song(id, cleanTitle(MiniJson.str(d.get("title"))),
                        artist == null || artist.isBlank() ? "Unknown Artist" : artist.strip(),
                        (int) MiniJson.num(d.get("duration"), 0), ""));
            }
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Search answer was not understood", false);
        }
        return out;
    }
}
