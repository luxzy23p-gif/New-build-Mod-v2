package com.xfive.live.spotify;

import com.xfive.live.download.MiniJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Panggilan ke Spotify Web API resmi. Hanya kode murni Java (HttpURLConnection) supaya bisa diuji dengan server palsu.
 * Mod ini TIDAK memutar suara Spotify sendiri: ia membaca dan mengontrol aplikasi Spotify yang sedang berjalan.
 */
public final class SpotifyApi {
    public static volatile String accountsBase = "https://accounts.spotify.com";
    public static volatile String apiBase = "https://api.spotify.com";
    public static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final String UA = "XFiveLive/1.1 (Minecraft mod; Spotify remote)";

    public record Tokens(String access, String refresh, long expiresAtMs) {
    }

    /** Keadaan pemutaran. progressMs dihitung saat jawaban diterima. */
    public record Playback(boolean hasItem, boolean playing, long progressMs, long durationMs, String id,
                           String title, String artist, int volumePercent, String deviceName) {
    }

    private record Resp(int code, String text, String retryAfter) {
    }

    private SpotifyApi() {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String authorizeUrl(String clientId, String redirectUri, String challenge, String state) {
        return accountsBase + "/authorize?client_id=" + enc(clientId) + "&response_type=code&redirect_uri="
                + enc(redirectUri) + "&code_challenge_method=S256&code_challenge=" + enc(challenge)
                + "&state=" + enc(state) + "&scope=" + enc(SCOPES);
    }

    public static Tokens exchange(String clientId, String code, String redirectUri, String verifier)
            throws IOException {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier);
        return tokens(call("POST", accountsBase + "/api/token", null, body), null);
    }

    /** Memperbarui access token. Refresh token baru dipakai jika Spotify mengirimnya, kalau tidak yang lama tetap. */
    public static Tokens refresh(String clientId, String refreshToken) throws IOException {
        String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId);
        return tokens(call("POST", accountsBase + "/api/token", null, body), refreshToken);
    }

    private static Tokens tokens(Resp r, String keepRefresh) throws IOException {
        if (r.code != 200) {
            throw new SpotifyException(r.code, "Spotify login was refused (" + r.code + "). Check the Client ID and redirect link.");
        }
        Map<String, Object> m;
        try {
            m = MiniJson.asMap(MiniJson.parse(r.text));
        } catch (RuntimeException e) {
            m = null;
        }
        String access = m == null ? null : MiniJson.str(m.get("access_token"));
        if (access == null || access.isBlank()) {
            throw new SpotifyException(r.code, "Spotify sent an unreadable login answer.");
        }
        String refresh = MiniJson.str(m.get("refresh_token"));
        if (refresh == null || refresh.isBlank()) {
            refresh = keepRefresh;
        }
        long expires = Math.max(60L, MiniJson.num(m.get("expires_in"), 3600L));
        return new Tokens(access, refresh, System.currentTimeMillis() + expires * 1000L);
    }

    /** Keadaan pemutaran saat ini, atau null jika tidak ada perangkat Spotify yang aktif (204). */
    public static Playback playback(String access) throws IOException {
        Resp r = call("GET", apiBase + "/v1/me/player?additional_types=track,episode", access, null);
        if (r.code == 204 || (r.code == 200 && r.text.isBlank())) {
            return null;
        }
        check(r);
        return parsePlayback(r.text);
    }

    public static Playback parsePlayback(String json) throws IOException {
        Map<String, Object> m;
        try {
            m = MiniJson.asMap(MiniJson.parse(json));
        } catch (RuntimeException e) {
            throw new SpotifyException(200, "Spotify sent an unreadable answer.");
        }
        if (m == null) {
            return null;
        }
        boolean playing = Boolean.TRUE.equals(m.get("is_playing"));
        long progress = Math.max(0L, MiniJson.num(m.get("progress_ms"), 0L));
        Map<String, Object> dev = MiniJson.asMap(m.get("device"));
        int vol = dev == null || !(dev.get("volume_percent") instanceof Double)
                ? -1 : (int) MiniJson.num(dev.get("volume_percent"), -1L);
        String devName = dev == null ? "" : String.valueOf(MiniJson.str(dev.get("name")));
        Map<String, Object> item = MiniJson.asMap(m.get("item"));
        if (item == null) {
            return new Playback(false, playing, progress, 0L, "", "", "", vol, devName);
        }
        String id = MiniJson.str(item.get("id"));
        String title = MiniJson.str(item.get("name"));
        long dur = Math.max(0L, MiniJson.num(item.get("duration_ms"), 0L));
        String artist = "";
        List<Object> artists = MiniJson.asList(item.get("artists"));
        if (artists != null) {
            StringBuilder sb = new StringBuilder();
            for (Object a : artists) {
                Map<String, Object> am = MiniJson.asMap(a);
                String n = am == null ? null : MiniJson.str(am.get("name"));
                if (n != null && !n.isBlank()) {
                    sb.append(sb.length() == 0 ? "" : ", ").append(n);
                }
            }
            artist = sb.toString();
        } else {
            Map<String, Object> show = MiniJson.asMap(item.get("show"));
            artist = show == null ? "" : String.valueOf(MiniJson.str(show.get("name")));
        }
        boolean ok = id != null && !id.isBlank() && title != null && !title.isBlank();
        return new Playback(ok, playing, progress, dur, ok ? id : "", ok ? title : "", artist, vol, devName);
    }

    // ---- perintah (butuh Spotify Premium) ----

    public static void play(String access) throws IOException {
        command("PUT", "/v1/me/player/play", access);
    }

    public static void pause(String access) throws IOException {
        command("PUT", "/v1/me/player/pause", access);
    }

    public static void next(String access) throws IOException {
        command("POST", "/v1/me/player/next", access);
    }

    public static void previous(String access) throws IOException {
        command("POST", "/v1/me/player/previous", access);
    }

    public static void seek(String access, long positionMs) throws IOException {
        command("PUT", "/v1/me/player/seek?position_ms=" + Math.max(0L, positionMs), access);
    }

    public static void volume(String access, int percent) throws IOException {
        command("PUT", "/v1/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)), access);
    }

    /** Satu lagu hasil pencarian Spotify. */
    public record Found(String uri, String title, String artist, long durationMs) {
    }

    /** Mencari lagu di katalog Spotify (maksimal 5 hasil). */
    public static List<Found> searchTrack(String access, String title, String artist) throws IOException {
        String q = "track:" + title.replace('"', ' ').strip()
                + (artist == null || artist.isBlank() ? "" : " artist:" + artist.replace('"', ' ').strip());
        Resp r = call("GET", apiBase + "/v1/search?type=track&limit=5&q=" + enc(q), access, null);
        check(r);
        return parseSearch(r.text);
    }

    /** Membaca jawaban pencarian: tracks.items[] dengan uri, name, duration_ms, artists[].name. */
    public static List<Found> parseSearch(String json) throws IOException {
        List<Found> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> tracks = root == null ? null : MiniJson.asMap(root.get("tracks"));
            List<Object> items = tracks == null ? null : MiniJson.asList(tracks.get("items"));
            if (items == null) {
                throw new SpotifyException(0, "Spotify search answer was not understood.");
            }
            for (Object o : items) {
                Map<String, Object> m = MiniJson.asMap(o);
                if (m == null) {
                    continue;
                }
                String uri = MiniJson.str(m.get("uri"));
                String name = MiniJson.str(m.get("name"));
                if (uri == null || uri.isBlank() || name == null) {
                    continue;
                }
                StringBuilder who = new StringBuilder();
                List<Object> artists = MiniJson.asList(m.get("artists"));
                if (artists != null) {
                    for (Object a : artists) {
                        Map<String, Object> am = MiniJson.asMap(a);
                        String an = am == null ? null : MiniJson.str(am.get("name"));
                        if (an != null && !an.isBlank()) {
                            who.append(who.length() == 0 ? "" : ", ").append(an);
                        }
                    }
                }
                out.add(new Found(uri, name, who.toString(), (long) MiniJson.num(m.get("duration_ms"), 0)));
            }
        } catch (RuntimeException e) {
            throw new SpotifyException(0, "Spotify search answer was not understood.");
        }
        return out;
    }

    /** Memutar satu lagu penuh di perangkat Spotify yang aktif (butuh Premium). */
    public static void playUri(String access, String uri) throws IOException {
        String body = "{\"uris\":[\"" + uri.replace("\\", "").replace("\"", "") + "\"]}";
        Resp r = call("PUT", apiBase + "/v1/me/player/play", access, body, "application/json");
        if (r.code >= 200 && r.code < 300) {
            return;
        }
        check(r);
    }

    private static void command(String method, String path, String access) throws IOException {
        Resp r = call(method, apiBase + path, access, null);
        if (r.code >= 200 && r.code < 300) {
            return;
        }
        check(r);
    }

    private static void check(Resp r) throws IOException {
        if (r.code >= 200 && r.code < 300) {
            return;
        }
        String msg = switch (r.code) {
            case 401 -> "Spotify login expired.";
            case 403 -> "Spotify refused this. Playback control needs Spotify Premium.";
            case 404 -> "No Spotify device is active. Open the Spotify app and press play once.";
            case 429 -> "Spotify says: too many requests. Waiting a moment.";
            default -> "Spotify error " + r.code + ".";
        };
        throw new SpotifyException(r.code, msg);
    }

    /** Detik tunggu yang diminta Spotify (header Retry-After) pada jawaban 429, atau 0. */
    public static long retryAfterSec(SpotifyException e) {
        return e.status() == 429 ? 3L : 0L;
    }

    private static Resp call(String method, String url, String bearer, String formBody) throws IOException {
        return call(method, url, bearer, formBody, "application/x-www-form-urlencoded");
    }

    private static Resp call(String method, String url, String bearer, String formBody, String contentType)
            throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new SpotifyException(0, "Bad Spotify address.");
        }
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(12_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (formBody != null) {
                byte[] b = formBody.getBytes(StandardCharsets.UTF_8);
                c.setRequestProperty("Content-Type", contentType);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            } else if (method.equals("PUT") || method.equals("POST")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0 && bo.size() < 1_000_000) {
                        bo.write(buf, 0, n);
                    }
                    text = bo.toString(StandardCharsets.UTF_8);
                }
            }
            return new Resp(code, text, c.getHeaderField("Retry-After"));
        } catch (SpotifyException e) {
            throw e;
        } catch (IOException e) {
            throw new SpotifyException(0, "Cannot reach Spotify. Check the internet.");
        } finally {
            c.disconnect();
        }
    }
}
