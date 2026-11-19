package com.xfive.live.music;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Menggabungkan playlist dan pemutar: putar, jeda, berhenti, berikutnya, sebelumnya,
 * mode ulang (mati, semua, satu), acak, dan volume.
 */
public final class MusicManager {
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;
    private static final long RESTART_THRESHOLD_MS = 3_000L;
    private static final long ADVANCE_WAIT_NANOS = 25_000_000_000L;

    /** Pemutar luar (Spotify). Aktif hanya jika pengguna menyalakan mode ini. */
    private volatile RemotePlayer remote;
    private volatile boolean remoteMode;

    private volatile OnlineQueue onlineQueue;
    /** Setelah antrean online diminta memutar lagu berikutnya, tick tidak meminta lagi sampai batas waktu ini. */
    private volatile long advanceUntilNanos;
    private final PlaylistManager playlist = new PlaylistManager();
    private final AudioPlayer player = new AudioPlayer();
    private final Random random = new Random();
    private Path folder;
    private String status = "Belum ada musik";
    private PlaylistManager.Track loadedTrack;
    /** Lagu yang diputar online (file sementara di cache). Null = memutar dari Library. */
    private volatile PlaylistManager.Track online;
    private boolean error = false;
    private int repeatMode = REPEAT_ALL;
    private boolean shuffle = false;

    /** Dekode lagu berjalan di thread ini supaya game tidak tersendat saat pindah lagu. */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xfive-audio-loader");
        t.setDaemon(true);
        return t;
    });
    private CompletableFuture<AudioPlayer.Prepared> loading;
    private PlaylistManager.Track loadingTrack;

    public int setFolder(Path dir) {
        player.stop();
        folder = dir;
        loadedTrack = null;
        int n = playlist.scan(dir);
        status = n == 0 ? "Folder kosong atau tidak ditemukan" : n + " lagu";
        return n;
    }

    public Path folder() {
        return folder;
    }

    /** Memakai daftar sumber campuran (folder dan file). Dipanggil saat startup. */
    public int setSources(java.util.List<Path> sources) {
        player.stop();
        loadedTrack = null;
        int n = playlist.scanSources(sources);
        status = n == 0 ? "Belum ada lagu. Isi folder xfive-music." : n + " lagu";
        return n;
    }

    public void setRemote(RemotePlayer r) {
        remote = r;
    }

    /** true jika tombol dan informasi pemutar sedang mengikuti pemutar luar (Spotify). */
    public boolean remoteMode() {
        return remoteMode && remote != null;
    }

    /** Menyalakan atau mematikan mode pemutar luar. Menyalakannya menjeda lagu lokal supaya tidak bertabrakan. */
    public void setRemoteMode(boolean on) {
        RemotePlayer r = remote;
        if (r == null) {
            remoteMode = false;
            return;
        }
        if (on) {
            if (player.isPlaying()) {
                player.pause();
            }
            remoteMode = true;
            r.setActive(true);
        } else {
            leaveRemote();
        }
    }

    /** Dipanggil sebelum memutar lagu lokal atau online: Spotify dijeda dan kontrol kembali ke pemutar mod. */
    private void leaveRemote() {
        RemotePlayer r = remote;
        if (remoteMode && r != null) {
            remoteMode = false;
            r.pause();
            r.setActive(false);
        }
        remoteMode = false;
    }

    /** true jika lagu ini milik pemutar luar. */
    public boolean isRemoteTrack(PlaylistManager.Track t) {
        RemotePlayer r = remote;
        return t != null && remoteMode && r != null && t == r.track();
    }

    /** Memuat dan memutar lagu aktif. Mengembalikan false jika gagal (lihat status()). */
    public boolean playCurrent() {
        leaveRemote();
        PlaylistManager.Track track = online != null ? online : playlist.current();
        if (track == null) {
            status = "Playlist kosong";
            return false;
        }
        if (loadedTrack == null || !loadedTrack.path().equals(track.path())) {
            // Lagu baru didekode di thread latar. Pemutaran dimulai otomatis oleh tick() saat siap.
            requestLoad(track);
            return true;
        }
        player.setVolume(player.volume());
        player.play();
        if (!player.isPlaying()) {
            status = "Gagal mulai: " + player.debugState();
            error = true;
            return false;
        }
        error = false;
        status = "Memutar";
        return true;
    }

    /** Memutar lagu dari file sementara (Play online). Tidak masuk Library; Next/Prev kembali ke Library. */
    public void playOnline(PlaylistManager.Track track) {
        leaveRemote();
        advanceUntilNanos = 0L;
        online = track;
        requestLoad(track);
    }

    public void setOnlineQueue(OnlineQueue queue) {
        onlineQueue = queue;
    }

    /** True jika lagu yang aktif berasal dari Play online. */
    public boolean isOnline() {
        return online != null;
    }

    public boolean isOnlineTrack(PlaylistManager.Track t) {
        return t != null && t == online;
    }

    /** Memutar lagu Library nomor i (menggantikan lagu online jika ada). */
    public void playIndex(int i) {
        leaveRemote();
        advanceUntilNanos = 0L;
        online = null;
        if (playlist.select(i)) {
            playCurrent();
        }
    }

    /** Memperbarui daftar lagu TANPA menghentikan lagu yang sedang diputar. */
    public int refresh(java.util.List<Path> sources) {
        PlaylistManager.Track before = playlist.current();
        int n = playlist.scanSources(sources);
        if (before != null) {
            java.util.List<PlaylistManager.Track> list = playlist.tracks();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).path().equals(before.path())) {
                    playlist.select(i);
                    break;
                }
            }
        }
        // Status dari pemutaran (Memutar, Dijeda, Berhenti) tidak ditimpa saat menu dibuka.
        if (loadedTrack == null && loading == null && !error) {
            status = n == 0 ? "Belum ada lagu. Isi folder xfive-music." : n + " lagu";
        }
        return n;
    }

    public void togglePause() {
        if (remoteMode()) {
            remote.toggle();
            return;
        }
        if (loading != null) {
            return; // lagu sedang dimuat
        }
        if (player.isPlaying()) {
            player.pause();
            status = "Dijeda";
        } else if (player.isLoaded()) {
            player.play();
            status = "Memutar";
        } else {
            playCurrent();
        }
    }

    /** Menghentikan lagu dan mengembalikan posisi ke awal. Putar lagi akan mulai dari awal. */
    public void stop() {
        player.stop();
        if (playlist.current() != null) {
            status = "Berhenti";
        }
    }

    public void next() {
        if (remoteMode()) {
            remote.next();
            return;
        }
        if (online != null) {
            OnlineQueue q = onlineQueue;
            if (q != null && q.next(shuffle, repeatMode == REPEAT_ALL)) {
                advanceUntilNanos = System.nanoTime() + ADVANCE_WAIT_NANOS;
                return;
            }
            online = null;
            if (playlist.size() > 0) {
                playCurrent();
            } else {
                player.stop();
                loadedTrack = null;
                status = "Selesai";
            }
            return;
        }
        int size = playlist.size();
        if (shuffle && size > 1) {
            int current = playlist.currentIndex();
            int pick;
            do {
                pick = random.nextInt(size);
            } while (pick == current);
            playlist.select(pick);
        } else {
            playlist.next();
        }
        playCurrent();
    }

    public void previous() {
        if (remoteMode()) {
            remote.previous();
            return;
        }
        if (online != null) {
            OnlineQueue q = onlineQueue;
            if (player.positionMs() <= RESTART_THRESHOLD_MS && q != null && q.previous()) {
                advanceUntilNanos = System.nanoTime() + ADVANCE_WAIT_NANOS;
                return;
            }
            player.seekMs(0L);
            return;
        }
        if (player.positionMs() > RESTART_THRESHOLD_MS) {
            player.seekMs(0L);
        } else {
            playlist.previous();
            playCurrent();
        }
    }

    /** Pindah ke posisi tertentu di lagu, dengan fraction 0..1. */
    public void seekTo(float fraction) {
        if (remoteMode()) {
            long d = remote.durationMs();
            if (d > 0) {
                remote.seekMs((long) (Math.max(0f, Math.min(1f, fraction)) * d));
            }
            return;
        }
        long dur = player.durationMs();
        if (dur > 0) {
            player.seekMs((long) (Math.max(0f, Math.min(1f, fraction)) * dur));
        }
    }

    public void setVolume(float volume) {
        if (remoteMode()) {
            remote.setVolume(volume);
        }
        player.setVolume(volume);
    }

    public float volume() {
        return player.volume();
    }

    /** Dipanggil dari client tick. Mengurus lagu yang sudah habis sesuai mode ulang. */
    public void tick() {
        if (remoteMode()) {
            return;
        }
        if (loading != null) {
            finishLoad();
            return;
        }
        if (!player.isFinished()) {
            return;
        }
        if (repeatMode == REPEAT_ONE && loadedTrack != null) {
            player.stop();
            playCurrent();
            return;
        }
        if (online != null) {
            if (System.nanoTime() < advanceUntilNanos) {
                return; // lagu berikutnya sedang dicari/dimuat
            }
            OnlineQueue q = onlineQueue;
            if (q != null && q.next(shuffle, repeatMode == REPEAT_ALL)) {
                advanceUntilNanos = System.nanoTime() + ADVANCE_WAIT_NANOS;
                return;
            }
            player.stop();
            status = "Selesai";
            return;
        }
        boolean lastTrack = playlist.currentIndex() >= playlist.size() - 1;
        if (repeatMode == REPEAT_OFF && !shuffle && lastTrack) {
            player.stop();
            status = "Selesai";
            return;
        }
        next();
    }

    /** Menghentikan lagu lama lalu mulai mendekode lagu baru di thread latar. */
    private void requestLoad(PlaylistManager.Track track) {
        player.stop();
        loadedTrack = null;
        loadingTrack = track;
        status = "Memuat...";
        error = false;
        Path file = track.path();
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                return AudioPlayer.prepare(file);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                throw new CompletionException(e);
            }
        }, loader);
    }

    /** Dipanggil dari tick. Jika dekode selesai, serahkan ke OpenAL (cepat) lalu putar. */
    private void finishLoad() {
        CompletableFuture<AudioPlayer.Prepared> f = loading;
        if (f == null || !f.isDone()) {
            return;
        }
        loading = null;
        PlaylistManager.Track track = loadingTrack;
        try {
            AudioPlayer.Prepared prepared = f.join();
            if (!player.attach(prepared)) {
                status = "Gagal: " + player.lastError();
                error = true;
                loadedTrack = null;
                return;
            }
            loadedTrack = track;
            player.setVolume(player.volume());
            player.play();
            if (player.isPlaying()) {
                error = false;
                status = "Memutar";
            } else {
                status = "Gagal mulai: " + player.debugState();
                error = true;
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            status = "Gagal: " + msg;
            error = true;
            loadedTrack = null;
        }
    }

    /** True selama lagu baru masih didekode di thread latar. */
    public boolean isLoading() {
        return !remoteMode() && loading != null;
    }

    public int repeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(int mode) {
        repeatMode = Math.max(REPEAT_OFF, Math.min(REPEAT_ONE, mode));
    }

    /** Pindah ke mode ulang berikutnya: mati, semua, satu, lalu mati lagi. */
    public int cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        return repeatMode;
    }

    public boolean shuffle() {
        return shuffle;
    }

    public void setShuffle(boolean on) {
        shuffle = on;
    }

    public boolean toggleShuffle() {
        shuffle = !shuffle;
        return shuffle;
    }

    public void setStatus(String text) {
        status = text;
    }

    public PlaylistManager.Track currentTrack() {
        if (remoteMode()) {
            return remote.track();
        }
        PlaylistManager.Track o = online;
        return o != null ? o : playlist.current();
    }

    public long positionMs() {
        return remoteMode() ? remote.positionMs() : player.positionMs();
    }

    public long durationMs() {
        return remoteMode() ? remote.durationMs() : player.durationMs();
    }

    public boolean isPlaying() {
        return remoteMode() ? remote.isPlaying() : player.isPlaying();
    }

    /** True jika pemutaran terakhir gagal. Dipakai pop-up untuk menampilkan pesan error. */
    public boolean hasError() {
        return error;
    }

    public String status() {
        return status;
    }

    public PlaylistManager playlist() {
        return playlist;
    }

    public void shutdown() {
        loader.shutdownNow();
        player.close();
    }
}
