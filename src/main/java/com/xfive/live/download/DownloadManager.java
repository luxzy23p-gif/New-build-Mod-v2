package com.xfive.live.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Antrean unduhan di latar belakang (maks 2 sekaligus) dan pencarian lagu online (Audius).
 * Dua jenis unduhan: "Get" (disimpan ke folder musik) dan "Play" (disimpan sementara di folder cache
 * lalu langsung diputar, tidak masuk Library). Layar membaca {@link #version()} untuk tahu kapan
 * daftar perlu digambar ulang.
 */
public final class DownloadManager {
    public enum SearchState { IDLE, SEARCHING, DONE, ERROR }

    private static final long CACHE_MAX_BYTES = 150L * 1024 * 1024;

    private final Supplier<Path> dir;
    private final Supplier<Path> cacheDir;
    private final Downloader downloader;
    private final ExecutorService pool;
    private final Consumer<DownloadJob> onSaved;
    private final Consumer<DownloadJob> onStreamReady;

    private final List<DownloadJob> jobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger version = new AtomicInteger();
    private final ConcurrentHashMap<String, Path> streamCache = new ConcurrentHashMap<>();

    private volatile List<HitCharts.Hit> hitsId = List.of();
    private volatile List<HitCharts.Hit> hitsWest = List.of();
    private volatile String hitsMessage = "";
    private volatile long hitsLoadedAt = 0L;
    private volatile boolean hitsLoading = false;
    private volatile String hitMessage = "";

    private volatile SearchState searchState = SearchState.IDLE;
    private volatile String searchMessage = "";
    private volatile List<AudiusSearch.Song> results = List.of();
    private final AtomicInteger searchSeq = new AtomicInteger();

    public DownloadManager(Supplier<Path> dir, long maxBytes, int threads, int retries, long backoffMs,
                           Consumer<DownloadJob> onSaved) {
        this(dir, dir, maxBytes, threads, retries, backoffMs, onSaved, j -> { });
    }

    /**
     * @param onSaved       dipanggil dari thread unduhan setiap lagu "Get" selesai tersimpan.
     * @param onStreamReady dipanggil saat lagu "Play" sudah siap di cache (job.result = file).
     *                      Pemanggil yang pindah ke thread game.
     */
    public DownloadManager(Supplier<Path> dir, Supplier<Path> cacheDir, long maxBytes, int threads, int retries,
                           long backoffMs, Consumer<DownloadJob> onSaved, Consumer<DownloadJob> onStreamReady) {
        this.dir = dir;
        this.cacheDir = cacheDir;
        this.downloader = new Downloader(maxBytes, retries, backoffMs);
        this.onSaved = onSaved;
        this.onStreamReady = onStreamReady;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "xfive-download");
            t.setDaemon(true);
            return t;
        });
    }

    public int version() {
        return version.get();
    }

    public List<DownloadJob> jobs() {
        return jobs;
    }

    public SearchState searchState() {
        return searchState;
    }

    public String searchMessage() {
        return searchMessage;
    }

    public List<AudiusSearch.Song> results() {
        return results;
    }

    public List<HitCharts.Hit> hitsIndo() {
        return hitsId;
    }

    public List<HitCharts.Hit> hitsWest() {
        return hitsWest;
    }

    /** Pesan status tangga lagu ("Loading hits...", atau kesalahan); kosong jika baik-baik saja. */
    public String hitsMessage() {
        return hitsMessage;
    }

    /** Hasil terakhir dari tombol Play pada lagu tangga (misalnya "tidak ditemukan"). */
    public String hitMessage() {
        return hitMessage;
    }

    public boolean busy() {
        for (DownloadJob j : jobs) {
            if (!j.finished()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ unduhan

    /** Menambah unduhan dari link langsung. Mengembalikan job yang sama jika link itu sedang berjalan. */
    public DownloadJob enqueueUrl(String url, String title) {
        return enqueueUrl(url, title, false);
    }

    /** stream=true: simpan sementara di cache lalu putar (tidak masuk Library). */
    public DownloadJob enqueueUrl(String url, String title, boolean stream) {
        String u = url.strip();
        return enqueue(u, title == null || title.isBlank() ? nameFromUrl(u) : title, stream, false, "", "");
    }

    /**
     * Memutar (stream=true) atau menyimpan (stream=false) SATU lagu hasil pencarian.
     * Menyimpan hanya untuk lagu yang pengunggahnya mengizinkan unduhan; selain itu mengembalikan null.
     */
    public DownloadJob enqueueSong(AudiusSearch.Song song, boolean stream) {
        if (!stream && !song.downloadable()) {
            return null;
        }
        return enqueue(song.streamUrl(), song.artist() + " - " + song.title(), stream, true, song.artist(),
                song.title());
    }

    private DownloadJob enqueue(String url, String label, boolean stream, boolean nameFromTitle, String artist,
                                String songTitle) {
        for (DownloadJob j : jobs) {
            if (!j.finished() && url.equals(j.url) && j.stream == stream) {
                return j;
            }
        }
        DownloadJob job = new DownloadJob(ids.incrementAndGet(), url, label);
        job.stream = stream;
        job.nameFromTitle = nameFromTitle;
        job.artist = artist;
        job.songTitle = songTitle;
        if (stream) {
            Path cached = streamCache.get(url);
            if (cached != null && Files.isRegularFile(cached)) {
                job.result = cached;
                job.fileName = cached.getFileName().toString();
                job.total = job.done = fileSize(cached);
                job.state = DownloadJob.State.DONE;
                job.message = "Ready";
                jobs.add(job);
                version.incrementAndGet();
                notifyReady(job);
                return job;
            }
        }
        jobs.add(job);
        version.incrementAndGet();
        pool.execute(() -> work(job));
        return job;
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String nameFromUrl(String u) {
        String n = FileNames.fromUrl(u);
        return n == null ? "Song" : FileNames.sanitize(n, "x").replaceFirst("\\.x$", "");
    }

    private void notifyReady(DownloadJob job) {
        try {
            onStreamReady.accept(job);
        } catch (RuntimeException ignored) {
            // gagal memulai pemutaran tidak boleh merusak antrean
        }
    }

    private void work(DownloadJob job) {
        if (job.cancel) {
            end(job, DownloadJob.State.CANCELLED, "Cancelled");
            return;
        }
        try {
            downloader.run(job, job.stream ? cacheDir.get() : dir.get());
            job.state = DownloadJob.State.DONE;
            job.message = job.stream ? "Ready" : "Saved";
            version.incrementAndGet();
            if (job.stream) {
                streamCache.put(job.url, job.result);
                CacheCleaner.trim(cacheDir.get(), CACHE_MAX_BYTES, job.result);
                notifyReady(job);
            } else {
                try {
                    onSaved.accept(job);
                } catch (RuntimeException ignored) {
                    // gagal memuat ulang daftar tidak boleh membuat unduhan dianggap gagal
                }
            }
        } catch (Downloader.CancelledException e) {
            end(job, DownloadJob.State.CANCELLED, "Cancelled");
        } catch (IOException e) {
            end(job, DownloadJob.State.FAILED, "Failed: " + (e.getMessage() == null ? "unknown problem" : e.getMessage()));
        } catch (RuntimeException e) {
            end(job, DownloadJob.State.FAILED, "Failed: " + e.getClass().getSimpleName());
        }
    }

    private void end(DownloadJob job, DownloadJob.State s, String msg) {
        job.state = s;
        job.message = msg;
        version.incrementAndGet();
    }

    public void cancel(DownloadJob job) {
        if (!job.finished()) {
            job.cancel = true;
            if (job.state == DownloadJob.State.QUEUED) {
                end(job, DownloadJob.State.CANCELLED, "Cancelled");
            }
        }
    }

    /** Mencoba lagi unduhan yang gagal/dibatalkan: job lama diganti job baru. */
    public void retry(DownloadJob job) {
        if (!job.finished() || job.state == DownloadJob.State.DONE) {
            return;
        }
        jobs.remove(job);
        enqueue(job.url, job.title, job.stream, job.nameFromTitle, job.artist, job.songTitle);
        version.incrementAndGet();
    }

    public void clearFinished() {
        List<DownloadJob> gone = new ArrayList<>();
        for (DownloadJob j : jobs) {
            if (j.finished()) {
                gone.add(j);
            }
        }
        jobs.removeAll(gone);
        version.incrementAndGet();
    }

    public void remove(DownloadJob job) {
        if (job.finished()) {
            jobs.remove(job);
            version.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ pencarian

    /** Mencari lagu di Audius (latar belakang). Pencarian lama yang tertinggal diabaikan. */
    public void search(String query) {
        final int seq = searchSeq.incrementAndGet();
        hitMessage = "";
        searchState = SearchState.SEARCHING;
        searchMessage = "Searching...";
        results = List.of();
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                List<AudiusSearch.Song> r = AudiusSearch.search(query, 25);
                if (seq != searchSeq.get()) {
                    return;
                }
                results = r;
                searchState = SearchState.DONE;
                searchMessage = r.isEmpty() ? "No results. Try other words." : "";
            } catch (IOException | RuntimeException e) {
                if (seq != searchSeq.get()) {
                    return;
                }
                searchState = SearchState.ERROR;
                searchMessage = "Search failed: " + (e.getMessage() == null ? "no internet?" : e.getMessage());
            }
            version.incrementAndGet();
        }, "xfive-search");
        t.setDaemon(true);
        t.start();
    }

    /** Memuat tangga lagu Indonesia dan barat di latar belakang (dipakai ulang selama 30 menit). */
    public void loadHits(boolean force) {
        if (hitsLoading || (!force && hitsLoadedAt != 0L && System.currentTimeMillis() - hitsLoadedAt < 30 * 60_000L)) {
            return;
        }
        hitsLoading = true;
        hitsMessage = "Loading hits...";
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            String err = "";
            try {
                hitsId = HitCharts.load("id", 25);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "no internet?" : e.getMessage();
            }
            try {
                hitsWest = HitCharts.load("us", 25);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "no internet?" : e.getMessage();
            }
            if (!hitsId.isEmpty() || !hitsWest.isEmpty()) {
                hitsLoadedAt = System.currentTimeMillis();
                hitsMessage = "";
            } else {
                hitsMessage = "Could not load hits: " + err;
            }
            hitsLoading = false;
            version.incrementAndGet();
        }, "xfive-hits");
        t.setDaemon(true);
        t.start();
    }

    /** Memutar lagu tangga: cari di Audius, putar hasil yang cocok. Jika tidak ada, pesan "tidak tersedia". */
    public void playHit(HitCharts.Hit hit) {
        hitMessage = "Looking for \"" + hit.name() + "\"...";
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                AudiusSearch.Song song = HitCharts.bestMatch(hit, AudiusSearch.search(hit.query(), 15));
                if (song == null) {
                    hitMessage = "Not available to play: " + hit.name() + ". Tap Find to see similar songs.";
                } else {
                    hitMessage = "Loading to play: " + hit.name();
                    enqueueSong(song, true);
                }
            } catch (IOException | RuntimeException e) {
                hitMessage = "Search failed: " + (e.getMessage() == null ? "no internet?" : e.getMessage());
            }
            version.incrementAndGet();
        }, "xfive-hit");
        t.setDaemon(true);
        t.start();
    }

    /** Menutup hasil pencarian dan kembali ke tangga lagu. */
    public void clearSearch() {
        searchSeq.incrementAndGet();
        searchState = SearchState.IDLE;
        searchMessage = "";
        results = List.of();
        version.incrementAndGet();
    }

    public void shutdown() {
        for (DownloadJob j : jobs) {
            j.cancel = true;
        }
        pool.shutdownNow();
    }
}
