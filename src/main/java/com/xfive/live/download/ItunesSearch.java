package com.xfive.live.download;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pencarian katalog Apple (iTunes Search API, publik tanpa kunci). Setiap lagu hanya punya cuplikan
 * 30 detik resmi, jadi dipakai sebagai cadangan saat lagu lengkap tidak ada di Audius.
 */
public final class ItunesSearch {
    /** Alamat API (bisa diganti untuk pengujian). */
    public static volatile String host = "https://itunes.apple.com";

    private ItunesSearch() {
    }

    public static String searchUrl(String query, int limit) {
        return host + "/search?media=music&entity=song&limit=" + limit + "&term="
                + URLEncoder.encode(AudiusSearch.cleanQuery(query), StandardCharsets.UTF_8);
    }

    public static List<Song> search(String query, int limit) throws IOException {
        if (AudiusSearch.cleanQuery(query).isEmpty()) {
            throw new DownloadException("Type a song or artist name", false);
        }
        return parse(Http.getText(searchUrl(query, limit), 3_000_000));
    }

    public static List<Song> parse(String json) throws IOException {
        List<Song> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            List<Object> results = root == null ? null : MiniJson.asList(root.get("results"));
            if (results == null) {
                throw new DownloadException("Search answer was not understood", false);
            }
            for (Object o : results) {
                Map<String, Object> r = MiniJson.asMap(o);
                if (r == null) {
                    continue;
                }
                String preview = MiniJson.str(r.get("previewUrl"));
                String name = MiniJson.str(r.get("trackName"));
                String id = MiniJson.str(r.get("trackId"));
                if (preview == null || preview.isBlank() || name == null || name.isBlank() || id == null) {
                    continue;
                }
                String artist = MiniJson.str(r.get("artistName"));
                out.add(new Song(Song.PREVIEW_PREFIX + id.strip(), name.strip(),
                        artist == null || artist.isBlank() ? "Unknown Artist" : artist.strip(), 30, preview.strip()));
            }
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Search answer was not understood", false);
        }
        return out;
    }
}
