package com.xfive.live.tests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xfive.live.spotify.Pkce;
import com.xfive.live.spotify.SpotifyApi;
import com.xfive.live.spotify.SpotifyAuth;
import com.xfive.live.spotify.SpotifyClient;
import com.xfive.live.spotify.SpotifyException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Uji sambungan Spotify memakai server Spotify palsu di komputer sendiri (tanpa internet, tanpa akun). */
final class SpotifyTests {
    private final BiConsumer<String, Boolean> check;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private volatile String playerJson = "";
    private volatile int playerCode = 200;
    private volatile int commandCode = 204;
    private volatile String validToken = "tok1";

    SpotifyTests(BiConsumer<String, Boolean> check) {
        this.check = check;
    }

    private void ok(String n, boolean b) {
        check.accept(n, b);
    }

    private static final String SONG = "{\"is_playing\":true,\"progress_ms\":42000,\"device\":{\"name\":\"Pixel\",\"volume_percent\":55},"
            + "\"item\":{\"id\":\"abc123\",\"name\":\"Hello\",\"duration_ms\":295000,"
            + "\"artists\":[{\"name\":\"Adele\"},{\"name\":\"Guest\"}],\"album\":{\"name\":\"25\"}}}";

    private void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (code == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    void run(Path work) throws Exception {
        // ---- PKCE: contoh resmi dari RFC 7636 ----
        ok("pkce challenge cocok dengan contoh RFC 7636",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").equals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
        String v = Pkce.verifier();
        ok("pkce verifier 64 karakter yang diizinkan", v.length() == 64 && v.matches("[A-Za-z0-9\\-._~]+"));
        ok("pkce verifier acak", !v.equals(Pkce.verifier()));
        ok("pkce state 32 heksa", Pkce.state().matches("[0-9a-f]{32}"));

        // ---- parsing keadaan pemutaran ----
        SpotifyApi.Playback p = SpotifyApi.parsePlayback(SONG);
        ok("parse: lagu, artis (digabung), durasi", p.hasItem() && p.title().equals("Hello") && p.artist().equals("Adele, Guest")
                && p.durationMs() == 295000L && p.id().equals("abc123"));
        ok("parse: playing, posisi, volume, perangkat", p.playing() && p.progressMs() == 42000L && p.volumePercent() == 55 && p.deviceName().equals("Pixel"));
        SpotifyApi.Playback none = SpotifyApi.parsePlayback("{\"is_playing\":false,\"progress_ms\":null,\"item\":null,\"device\":{\"name\":\"X\",\"volume_percent\":null}}");
        ok("parse: tanpa lagu", !none.hasItem() && !none.playing() && none.volumePercent() == -1);
        SpotifyApi.Playback ep = SpotifyApi.parsePlayback("{\"is_playing\":true,\"progress_ms\":1,\"item\":{\"id\":\"e1\",\"name\":\"Ep\",\"duration_ms\":1000,\"show\":{\"name\":\"Podcast\"}}}");
        ok("parse: episode memakai nama show", ep.hasItem() && ep.artist().equals("Podcast"));
        boolean bad = false;
        try {
            SpotifyApi.parsePlayback("{{nope");
        } catch (SpotifyException e) {
            bad = true;
        }
        ok("parse: JSON rusak -> error rapi", bad);

        // ---- server Spotify palsu ----
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/api/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add("TOKEN " + body);
            if (body.contains("grant_type=authorization_code") && body.contains("code=goodcode") && body.contains("code_verifier=")
                    && body.contains("client_id=")) {
                send(ex, 200, "{\"access_token\":\"tok1\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"ref1\"}");
            } else if (body.contains("grant_type=refresh_token") && body.contains("refresh_token=ref1")) {
                validToken = "tok2";
                send(ex, 200, "{\"access_token\":\"tok2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
            } else {
                send(ex, 400, "{\"error\":\"invalid_grant\"}");
            }
        });
        s.createContext("/v1/me/player", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String path = ex.getRequestURI().toString();
            hits.add(ex.getRequestMethod() + " " + path + " " + auth);
            if (auth == null || !auth.equals("Bearer " + validToken)) {
                send(ex, 401, "{\"error\":{\"status\":401}}");
                return;
            }
            if (ex.getRequestMethod().equals("GET") && path.equals("/v1/me/player?additional_types=track,episode")) {
                send(ex, playerCode, playerJson);
            } else {
                send(ex, commandCode, "{}");
            }
        });
        s.start();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        SpotifyApi.accountsBase = base;
        SpotifyApi.apiBase = base;
        String cid = "0123456789abcdef0123456789abcdef";
        try {
            String url = SpotifyApi.authorizeUrl(cid, "http://127.0.0.1:8898/callback", "CHAL", "ST");
            ok("authorize URL lengkap", url.startsWith(base + "/authorize?client_id=" + cid + "&response_type=code&redirect_uri=")
                    && url.contains("code_challenge_method=S256") && url.contains("code_challenge=CHAL") && url.contains("state=ST")
                    && url.contains("scope=user-read-playback-state+user-modify-playback-state"));

            SpotifyApi.Tokens t = SpotifyApi.exchange(cid, "goodcode", "http://127.0.0.1:8898/callback", "VER");
            ok("tukar kode -> token", t.access().equals("tok1") && t.refresh().equals("ref1") && t.expiresAtMs() > System.currentTimeMillis() + 3_000_000L);
            boolean refused = false;
            try {
                SpotifyApi.exchange(cid, "badcode", "http://127.0.0.1:8898/callback", "VER");
            } catch (SpotifyException e) {
                refused = e.status() == 400 && e.getMessage().contains("refused");
            }
            ok("kode salah ditolak dengan pesan jelas", refused);
            SpotifyApi.Tokens r = SpotifyApi.refresh(cid, "ref1");
            ok("refresh: token baru, refresh token lama dipertahankan", r.access().equals("tok2") && r.refresh().equals("ref1"));
            validToken = "tok1";

            // ---- keadaan pemutaran & perintah ----
            playerJson = SONG;
            ok("playback 200 dibaca", SpotifyApi.playback("tok1").title().equals("Hello"));
            playerCode = 204;
            ok("playback 204 = tidak ada perangkat aktif (null)", SpotifyApi.playback("tok1") == null);
            playerCode = 200;
            boolean expired = false;
            try {
                SpotifyApi.playback("old");
            } catch (SpotifyException e) {
                expired = e.status() == 401;
            }
            ok("token kedaluwarsa -> 401", expired);
            hits.clear();
            SpotifyApi.pause("tok1");
            SpotifyApi.play("tok1");
            SpotifyApi.next("tok1");
            SpotifyApi.previous("tok1");
            SpotifyApi.seek("tok1", 12345L);
            SpotifyApi.volume("tok1", 150);
            ok("perintah memakai metode dan alamat yang benar", hits.equals(List.of(
                    "PUT /v1/me/player/pause Bearer tok1", "PUT /v1/me/player/play Bearer tok1",
                    "POST /v1/me/player/next Bearer tok1", "POST /v1/me/player/previous Bearer tok1",
                    "PUT /v1/me/player/seek?position_ms=12345 Bearer tok1",
                    "PUT /v1/me/player/volume?volume_percent=100 Bearer tok1")));
            commandCode = 403;
            String m403 = "";
            try {
                SpotifyApi.next("tok1");
            } catch (SpotifyException e) {
                m403 = e.getMessage();
            }
            ok("403 = perlu Premium (pesan jelas)", m403.contains("Premium"));
            commandCode = 404;
            String m404 = "";
            try {
                SpotifyApi.play("tok1");
            } catch (SpotifyException e) {
                m404 = e.getMessage();
            }
            ok("404 = buka aplikasi Spotify (pesan jelas)", m404.contains("Spotify app"));
            commandCode = 204;

            // ---- login lewat alamat lokal ----
            SpotifyAuth a = SpotifyAuth.open(cid, 0);
            ok("redirect lokal 127.0.0.1", a.redirectUri().matches("http://127\\.0\\.0\\.1:\\d+/callback"));
            String authUrl = a.authorizeUrl();
            String state = authUrl.replaceAll(".*[?&]state=([^&]+).*", "$1");
            ok("authorize URL memuat state dan challenge", state.length() == 32 && authUrl.contains("code_challenge="));
            AtomicReference<SpotifyApi.Tokens> got = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            a.awaitAsync(tk -> {
                got.set(tk);
                done.countDown();
            }, err -> done.countDown());
            ok("favicon/path lain diabaikan (404)", httpGet(a.redirectUri().replace("/callback", "/favicon.ico")) == 404);
            ok("callback benar dijawab 200", httpGet(a.redirectUri() + "?code=goodcode&state=" + state) == 200);
            ok("login selesai dan token diterima", done.await(5, TimeUnit.SECONDS) && got.get() != null && got.get().access().equals("tok1"));
            boolean tokenReq = hits.stream().anyMatch(h -> h.startsWith("TOKEN grant_type=authorization_code")
                    && h.contains("code_verifier=") && !h.contains("client_secret"));
            ok("tukar kode memakai verifier PKCE, tanpa client secret", tokenReq);

            SpotifyAuth wrong = SpotifyAuth.open(cid, 0);
            AtomicReference<String> err = new AtomicReference<>();
            CountDownLatch d2 = new CountDownLatch(1);
            wrong.awaitAsync(tk -> d2.countDown(), e -> {
                err.set(e);
                d2.countDown();
            });
            ok("state salah ditolak 400", httpGet(wrong.redirectUri() + "?code=goodcode&state=SALAH") == 400);
            ok("state salah -> error, bukan token", d2.await(5, TimeUnit.SECONDS) && err.get() != null && err.get().contains("did not match"));

            SpotifyAuth denied = SpotifyAuth.open(cid, 0);
            AtomicReference<String> err2 = new AtomicReference<>();
            CountDownLatch d3 = new CountDownLatch(1);
            String st3 = denied.authorizeUrl().replaceAll(".*[?&]state=([^&]+).*", "$1");
            denied.awaitAsync(tk -> d3.countDown(), e -> {
                err2.set(e);
                d3.countDown();
            });
            httpGet(denied.redirectUri() + "?error=access_denied&state=" + st3);
            ok("login dibatalkan -> pesan jelas", d3.await(5, TimeUnit.SECONDS) && err2.get() != null && err2.get().contains("cancelled"));

            SpotifyAuth busy1 = SpotifyAuth.open(cid, 0);
            int port = Integer.parseInt(busy1.redirectUri().replaceAll(".*:(\\d+)/callback", "$1"));
            boolean busy = false;
            try {
                SpotifyAuth.open(cid, port);
            } catch (IOException e) {
                busy = e.getMessage().contains("busy");
            }
            busy1.cancel();
            ok("port terpakai -> pesan jelas", busy);

            // ---- SpotifyClient ----
            Path file = work.resolve("spotify.properties");
            SpotifyClient c = new SpotifyClient(file);
            ok("client id tidak valid ditolak", !c.setClientId("abc") && !c.setClientId("") && c.clientId().isEmpty());
            ok("client id valid disimpan (huruf kecil)", c.setClientId("0123456789ABCDEF0123456789abcdef") && c.clientId().equals(cid));
            ok("client id disamarkan", c.maskedClientId().equals("0123...cdef"));
            ok("belum terhubung", !c.connected() && c.begin() != null);
            c.disconnect();
            SpotifyClient c2 = new SpotifyClient(file);
            c2.load();
            ok("client id tersimpan di file dan dibaca lagi", c2.clientId().equals(cid) && !c2.connected());
            ok("tanpa client id tidak bisa login", new SpotifyClient(null).begin() == null);

            // keadaan + interpolasi posisi
            SpotifyClient pc = new SpotifyClient(null);
            ok("tanpa data: tidak ada lagu, posisi 0", pc.track() == null && pc.positionMs() == 0L && !pc.isPlaying());
            pc.apply(p);
            ok("track dari keadaan; objek sama selama lagu sama", pc.track() != null && pc.track() == pc.track()
                    && pc.track().title().equals("Hello") && pc.track().artist().equals("Adele, Guest"));
            long p0 = pc.positionMs();
            Thread.sleep(400);
            long p1 = pc.positionMs();
            ok("posisi berjalan sendiri saat memutar", p0 >= 42000L && p1 - p0 >= 350L && p1 - p0 <= 700L);
            ok("durasi dari Spotify", pc.durationMs() == 295000L && pc.isPlaying());
            SpotifyApi.Playback paused = SpotifyApi.parsePlayback(SONG.replace("\"is_playing\":true", "\"is_playing\":false"));
            pc.apply(paused);
            long q0 = pc.positionMs();
            Thread.sleep(250);
            ok("posisi diam saat dijeda", pc.positionMs() == q0 && q0 == 42000L && !pc.isPlaying());
            SpotifyApi.Playback end = SpotifyApi.parsePlayback(SONG.replace("\"progress_ms\":42000", "\"progress_ms\":999999"));
            pc.apply(end);
            ok("posisi tidak melewati durasi", pc.positionMs() <= 295000L);
            var track1 = pc.track();
            pc.apply(SpotifyApi.parsePlayback(SONG.replace("abc123", "zzz999").replace("Hello", "Other")));
            ok("lagu berganti -> objek track baru", pc.track() != track1 && pc.track().title().equals("Other"));
            pc.apply(null);
            ok("tidak ada perangkat -> tidak ada lagu", pc.track() == null && !pc.isPlaying());
        } finally {
            s.stop(0);
            SpotifyApi.accountsBase = "https://accounts.spotify.com";
            SpotifyApi.apiBase = "https://api.spotify.com";
        }
    }

    private static int httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }
}
