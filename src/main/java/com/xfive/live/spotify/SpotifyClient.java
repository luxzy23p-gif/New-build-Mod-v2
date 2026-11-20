package com.xfive.live.spotify;

import com.xfive.live.music.PlaylistManager;
import com.xfive.live.music.RemotePlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Menghubungkan mod ke aplikasi Spotify milik pengguna lewat Web API resmi: membaca lagu yang diputar
 * dan mengirim play/pause/next/previous/seek/volume. Semua panggilan jaringan berjalan di thread latar.
 */
public final class SpotifyClient implements RemotePlayer {
    private static final long POLL_MS = 1500L;

    private final Path file;
    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "xfive-spotify");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger version = new AtomicInteger();

    private volatile String clientId = "";
    private volatile String refreshToken = "";
    private String accessToken = "";
    private long expiresAtMs;

    private volatile SpotifyApi.Playback last;
    private volatile long lastNanos;
    private volatile String message = "";
    private volatile long backoffUntilNanos;
    private volatile boolean active;
    private ScheduledFuture<?> pollTask;
    private SpotifyAuth auth;
    private PlaylistManager.Track cachedTrack;
    private String cachedId = "";

    public SpotifyClient(Path file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ penyimpanan

    public void load() {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                return;
            }
        }
        clientId = p.getProperty("spotify.clientId", "").strip();
        refreshToken = p.getProperty("spotify.refreshToken", "").strip();
        bump();
    }

    private void save() {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("spotify.clientId", clientId);
        p.setProperty("spotify.refreshToken", refreshToken);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "XFiveLive Spotify login. Do not share this file.");
            }
        } catch (IOException | RuntimeException ignored) {
            // gagal menyimpan: login tetap berlaku sampai game ditutup
        }
    }

    // ------------------------------------------------------------------ status untuk UI

    /** Naik setiap kali status berubah, supaya UI tahu kapan harus menggambar ulang daftar. */
    public int version() {
        return version.get();
    }

    private void bump() {
        version.incrementAndGet();
    }

    private void say(String text) {
        message = text == null ? "" : text;
        bump();
    }

    public String message() {
        return message;
    }

    public String clientId() {
        return clientId;
    }

    public boolean connected() {
        return !refreshToken.isEmpty();
    }

    /** Client ID Spotify berisi 32 karakter heksadesimal. */
    public static boolean validClientId(String id) {
        return id != null && id.strip().matches("[0-9a-fA-F]{32}");
    }

    public boolean setClientId(String id) {
        if (!validClientId(id)) {
            say("That is not a Spotify Client ID (32 letters and numbers).");
            return false;
        }
        clientId = id.strip().toLowerCase(java.util.Locale.ROOT);
        save();
        say("Client ID saved.");
        return true;
    }

    /** Client ID disamarkan untuk ditampilkan: "1a2b...9z8y". */
    public String maskedClientId() {
        String c = clientId;
        return c.length() < 12 ? "not set" : c.substring(0, 4) + "..." + c.substring(c.length() - 4);
    }

    // ------------------------------------------------------------------ login

    /** Memulai login. Mengembalikan alamat yang harus dibuka di browser, atau null (lihat message()). */
    public synchronized String begin() {
        if (clientId.isEmpty()) {
            say("Paste your Client ID first.");
            return null;
        }
        if (auth != null) {
            auth.cancel();
        }
        try {
            SpotifyAuth a = SpotifyAuth.open(clientId, SpotifyAuth.PORT);
            auth = a;
            a.awaitAsync(tk -> {
                synchronized (SpotifyClient.this) {
                    accessToken = tk.access();
                    expiresAtMs = tk.expiresAtMs();
                    refreshToken = tk.refresh() == null ? "" : tk.refresh();
                    auth = null;
                }
                save();
                say("Connected to Spotify.");
                if (active) {
                    schedulePolling();
                }
            }, err -> {
                synchronized (SpotifyClient.this) {
                    auth = null;
                }
                say(err);
            });
            say("Waiting for you to log in on the Spotify page...");
            return a.authorizeUrl();
        } catch (IOException e) {
            say(e.getMessage() == null ? "Cannot start the login." : e.getMessage());
            return null;
        }
    }

    public synchronized void disconnect() {
        if (auth != null) {
            auth.cancel();
            auth = null;
        }
        refreshToken = "";
        accessToken = "";
        expiresAtMs = 0L;
        last = null;
        stopPolling();
        save();
        say("Disconnected from Spotify.");
    }

    public void shutdown() {
        synchronized (this) {
            if (auth != null) {
                auth.cancel();
            }
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ token

    private interface Op {
        void run(String access) throws IOException;
    }

    private synchronized String token(boolean force) throws IOException {
        if (refreshToken.isEmpty()) {
            throw new SpotifyException(0, "Not connected to Spotify.");
        }
        if (force || accessToken.isEmpty() || System.currentTimeMillis() > expiresAtMs - 60_000L) {
            try {
                SpotifyApi.Tokens t = SpotifyApi.refresh(clientId, refreshToken);
                accessToken = t.access();
                expiresAtMs = t.expiresAtMs();
                if (t.refresh() != null && !t.refresh().isBlank() && !t.refresh().equals(refreshToken)) {
                    refreshToken = t.refresh();
                    save();
                }
            } catch (SpotifyException e) {
                if (e.status() == 400 || e.status() == 401) {
                    refreshToken = "";
                    accessToken = "";
                    last = null;
                    save();
                    throw new SpotifyException(e.status(), "Spotify login expired. Connect again.");
                }
                throw e;
            }
        }
        return accessToken;
    }

    private void withToken(Op op) throws IOException {
        try {
            op.run(token(false));
        } catch (SpotifyException e) {
            if (e.status() == 401 && connected()) {
                op.run(token(true));
            } else {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ membaca keadaan

    @Override
    public synchronized void setActive(boolean on) {
        active = on;
        if (on && connected()) {
            schedulePolling();
        } else {
            stopPolling();
        }
    }

    private synchronized void schedulePolling() {
        if (pollTask == null || pollTask.isDone()) {
            pollTask = pool.scheduleWithFixedDelay(this::pollSafely, 0L, POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException ignored) {
            // satu putaran gagal tidak boleh menghentikan pembacaan berikutnya
        }
    }

    /** Satu kali membaca keadaan pemutaran (dipanggil berkala di thread latar). */
    void poll() {
        if (System.nanoTime() < backoffUntilNanos || !connected()) {
            return;
        }
        try {
            SpotifyApi.Playback[] box = new SpotifyApi.Playback[1];
            withToken(a -> box[0] = SpotifyApi.playback(a));
            apply(box[0]);
            if (!message.isEmpty() && !message.startsWith("Connected") && !message.startsWith("Client ID")) {
                say("");
            }
        } catch (SpotifyException e) {
            if (e.status() == 429) {
                backoffUntilNanos = System.nanoTime() + 5_000_000_000L;
            }
            say(e.getMessage());
        } catch (IOException e) {
            say("Cannot reach Spotify.");
        }
    }

    /** Memasang keadaan baru. Lompatan kecil (< 250 ms) diabaikan supaya lirik tidak bergetar. */
    public void apply(SpotifyApi.Playback p) {
        long now = System.nanoTime();
        SpotifyApi.Playback old = last;
        if (p != null && old != null && p.hasItem() && old.hasItem() && p.id().equals(old.id())
                && p.playing() && old.playing()) {
            long predicted = predicted(old, now);
            if (Math.abs(predicted - p.progressMs()) < 250L) {
                last = new SpotifyApi.Playback(p.hasItem(), p.playing(), predicted, p.durationMs(), p.id(), p.title(),
                        p.artist(), p.volumePercent(), p.deviceName());
                lastNanos = now;
                return;
            }
        }
        last = p;
        lastNanos = now;
        bump();
    }

    private long predicted(SpotifyApi.Playback p, long nowNanos) {
        long pos = p.progressMs();
        if (p.playing()) {
            pos += (nowNanos - lastNanos) / 1_000_000L;
        }
        long dur = p.durationMs();
        return dur > 0 ? Math.max(0L, Math.min(dur, pos)) : Math.max(0L, pos);
    }

    // ------------------------------------------------------------------ RemotePlayer

    @Override
    public synchronized PlaylistManager.Track track() {
        SpotifyApi.Playback p = last;
        if (p == null || !p.hasItem()) {
            return null;
        }
        if (cachedTrack == null || !p.id().equals(cachedId)) {
            cachedId = p.id();
            cachedTrack = new PlaylistManager.Track(Path.of("spotify", p.id()), p.title(),
                    p.artist().isEmpty() ? "Spotify" : p.artist());
        }
        return cachedTrack;
    }

    @Override
    public long positionMs() {
        SpotifyApi.Playback p = last;
        return p == null ? 0L : predicted(p, System.nanoTime());
    }

    @Override
    public long durationMs() {
        SpotifyApi.Playback p = last;
        return p == null ? 0L : p.durationMs();
    }

    @Override
    public boolean isPlaying() {
        SpotifyApi.Playback p = last;
        return p != null && p.playing();
    }

    private void optimistic(boolean playing, long positionMs) {
        SpotifyApi.Playback p = last;
        if (p != null) {
            last = new SpotifyApi.Playback(p.hasItem(), playing, positionMs, p.durationMs(), p.id(), p.title(),
                    p.artist(), p.volumePercent(), p.deviceName());
            lastNanos = System.nanoTime();
        }
    }

    private void send(Op op, long repollMs) {
        if (!connected()) {
            say("Connect Spotify first (Settings > Sources).");
            return;
        }
        pool.execute(() -> {
            try {
                withToken(op);
                if (repollMs >= 0) {
                    pool.schedule(this::pollSafely, repollMs, TimeUnit.MILLISECONDS);
                }
            } catch (SpotifyException e) {
                say(e.getMessage());
            } catch (IOException e) {
                say("Cannot reach Spotify.");
            }
        });
    }

    private volatile java.util.function.Consumer<String> linkOpener;

    /** Pembuka tautan (browser/aplikasi). Dipakai saat Spotify Free tidak bisa dikontrol lewat API. */
    public void setLinkOpener(java.util.function.Consumer<String> opener) {
        linkOpener = opener;
    }

    /**
     * Mencari lagu ini di Spotify lalu memutarnya PENUH di aplikasi Spotify. Memblokir, jadi hanya dipanggil dari
     * thread latar. Akun Premium: lagu langsung diputar. Akun Free (API menolak perintah putar) atau tidak ada
     * perangkat aktif: lagu dibuka di aplikasi Spotify lewat tautan, pengguna cukup menekan play, dan mod tetap
     * mengikuti lagunya (membaca status boleh untuk akun Free).
     */
    public com.xfive.live.download.OnlineManager.FullResult playFull(String title, String artist) {
        if (!connected()) {
            return com.xfive.live.download.OnlineManager.FullResult.fail("Connect Spotify (Settings > Sources) to hear the full song.");
        }
        try {
            SpotifyApi.Found[] hit = {null};
            String[] result = {null};
            boolean[] direct = {false};
            withToken(a -> {
                for (SpotifyApi.Found f : SpotifyApi.searchTrack(a, title, artist)) {
                    if (com.xfive.live.lyrics.LyricsPicker.sameTitle(title, f.title())
                            && com.xfive.live.lyrics.LyricsPicker.sameArtist(artist, f.artist())) {
                        hit[0] = f;
                        break;
                    }
                }
                if (hit[0] == null) {
                    return;
                }
                try {
                    SpotifyApi.playUri(a, hit[0].uri());
                    direct[0] = true;
                } catch (SpotifyException e) {
                    if (e.status() != 403 && e.status() != 404) {
                        throw e;
                    }
                    result[0] = e.status() == 403
                            ? "Spotify Free cannot be controlled by apps, so the song was opened in Spotify: press play there. The mod follows it."
                            : "No Spotify device is active, so the song was opened in Spotify: press play there. The mod follows it.";
                }
            });
            if (hit[0] == null) {
                return com.xfive.live.download.OnlineManager.FullResult.fail("This song was not found on Spotify.");
            }
            if (direct[0]) {
                pool.schedule(this::pollSafely, 900L, TimeUnit.MILLISECONDS);
                return com.xfive.live.download.OnlineManager.FullResult.ok("Playing the full song on Spotify.");
            }
            java.util.function.Consumer<String> open = linkOpener;
            if (open == null) {
                return com.xfive.live.download.OnlineManager.FullResult.fail(result[0].replace("the song was opened in Spotify: press play there. The mod follows it.", "open the Spotify app and play once."));
            }
            open.accept("https://open.spotify.com/track/" + hit[0].uri().substring(hit[0].uri().lastIndexOf(':') + 1));
            return com.xfive.live.download.OnlineManager.FullResult.ok(result[0]);
        } catch (SpotifyException e) {
            return com.xfive.live.download.OnlineManager.FullResult.fail(e.getMessage());
        } catch (IOException e) {
            return com.xfive.live.download.OnlineManager.FullResult.fail("Cannot reach Spotify.");
        }
    }

    @Override
    public void toggle() {
        boolean playing = isPlaying();
        optimistic(!playing, positionMs());
        send(a -> {
            if (playing) {
                SpotifyApi.pause(a);
            } else {
                SpotifyApi.play(a);
            }
        }, 500L);
    }

    @Override
    public void pause() {
        if (isPlaying() && connected()) {
            optimistic(false, positionMs());
            send(SpotifyApi::pause, 500L);
        }
    }

    @Override
    public void next() {
        send(SpotifyApi::next, 700L);
    }

    @Override
    public void previous() {
        send(SpotifyApi::previous, 700L);
    }

    @Override
    public void seekMs(long positionMs) {
        long target = Math.max(0L, positionMs);
        optimistic(isPlaying(), target);
        send(a -> SpotifyApi.seek(a, target), 600L);
    }

    @Override
    public void setVolume(float volume) {
        int pct = Math.round(Math.max(0f, Math.min(1f, volume)) * 100f);
        send(a -> SpotifyApi.volume(a, pct), -1L);
    }
}
