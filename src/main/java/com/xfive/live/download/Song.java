package com.xfive.live.download;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Satu lagu online yang bisa diputar. Dua jenis: lagu lengkap dari Audius (url kosong, alamat dibentuk dari id)
 * dan cuplikan 30 detik dari Apple (id berawalan "it:", url berisi alamat cuplikan).
 */
public record Song(String id, String title, String artist, int seconds, String url) {
    public static final String PREVIEW_PREFIX = "it:";

    /** Alamat audio yang dipakai pemutar. */
    public String streamUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        return AudiusSearch.host + "/v1/tracks/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "/stream?app_name=" + AudiusSearch.APP_NAME;
    }

    /** true untuk cuplikan 30 detik (bukan lagu lengkap). */
    public boolean isPreview() {
        return id != null && id.startsWith(PREVIEW_PREFIX);
    }

    public String duration() {
        if (isPreview()) {
            return "0:30";
        }
        return seconds <= 0 ? "" : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
