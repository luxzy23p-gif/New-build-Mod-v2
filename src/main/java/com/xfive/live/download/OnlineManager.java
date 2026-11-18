package com.xfive.live.download;

import com.xfive.live.music.OnlineQueue;

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
 * Pemutaran lagu online tanpa menyimpan ke Library: pencarian (Audius lengkap + cuplikan 30 detik Apple),
 * tangga lagu per negara, antrean putar (lanjut otomatis), dan pemuatan ke folder cache sementara.
 * Layar membaca {@link #version()} untuk tahu kapan daftar perlu digambar ulang.
 */
public final class OnlineManager implements OnlineQueue {
    public enum SearchState { IDLE, SEARCHING, DONE, ERROR }

    /** Satu baris daftar putar: lagu yang sudah pasti (Song) atau lagu tangga yang masih dicarikan audionya (Hit). */
    public record Entry(Song song, HitCharts.Hit hit) {
        public static Entry of(Song s) {
            return new Entry(s, null);
        }

        public static Entry of(HitCharts.Hit h) {
            return new Entry(null, h);
        }

        public String title() {
            return song != null ? song.title() : hit.name();
        }

        public String artist() {
            return song != null ? song.artist() : hit.artist();
        }
    }

    /** Tangga lagu satu negara (Apple Music "Most Played"). */
    public record Chart(String id, String title, String country, int color) {
    }

    public static final List<Chart> CHARTS = List.of(
            new Chart("id", "Top Indonesia", "ID", 0xE0364C),
            new Chart("us", "Top USA", "US", 0x3B82F6),
            new Chart("gb", "Top UK", "GB", 0x8B5CF6),
            new Chart("kr", "Top Korea", "KR", 0xEC4899),
            new Chart("jp", "Top Japan", "JP", 0xF97316),
            new Chart("in", "Top India", "IN", 0xF59E0B),
            new Chart("br", "Top Brazil", "BR", 0x10B981),
            new Chart("my", "Top Malaysia", "MY", 0x06B6D4),
            new Chart("ph", "Top Philippines", "PH", 0x6366F1),
            new Chart("th", "Top Thailand", "TH", 0x14B8A6),
            new Chart("de", "Top Germany", "DE", 0xEF4444),
            new Chart("fr", "Top France", "FR", 0x0EA5E9));

    public static Chart chart(String id) {
        for (Chart c : CHARTS) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static final long CACHE_MAX_BYTES = 150L * 1024 * 1024;
    private static final int CHART_SIZE = 50;
    private static final int MAX_AUTO_SKIPS = 5;

    private final Supplier<Path> cacheDir;
    private final Downloader downloader;
    private final ExecutorService pool;
    private final Consumer<DownloadJob> onReady;

    private final List<DownloadJob> jobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger version = new AtomicInteger();
    private final AtomicInteger playSeq = new AtomicInteger();
    private final ConcurrentHashMap<String, Path> streamCache = new ConcurrentHashMap<>();

    private static final class ChartState {
        volatile List<HitCharts.Hit> hits = List.of();
        volatile String message = "";
        volatile boolean loading;
        volatile long loadedAt;
    }

    private final ConcurrentHashMap<String, ChartState> charts = new ConcurrentHashMap<>();

    private volatile SearchState searchState = SearchState.IDLE;
    private volatile String searchMessage = "";
    private volatile String searchQuery = "";
    private volatile List<Song> results = List.of();
    private final AtomicInteger searchSeq = new AtomicInteger();

    private final PlayQueue<Entry> queue = new PlayQueue<>();
    private volatile Entry nowEntry;
    private volatile Song nowSong;
    private volatile String message = "";

    /**
     * @param cacheDir folder cache sementara (tempat file lagu disimpan sebelum diputar).
     * @param onReady  dipanggil dari thread pemuat saat file lagu siap (job.result = file, job.song = lagunya).
     *                 Pemanggil yang pindah ke thread game.
     */
    public OnlineManager(Supplier<Path> cacheDir, long maxBytes, int threads, int retries, long backoffMs,
                         Consumer<DownloadJob> onReady) {
        this.cacheDir = cacheDir;
        this.downloader = new Downloader(maxBytes, retries, backoffMs);
        this.onReady = onReady;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "xfive-online");
            t.setDaemon(true);
            return t;
        });
    }

    public int version() {
        return version.get();
    }

    // ------------------------------------------------------------------ status

    /** Pesan status pemutaran online ("Looking for ...", "Preview 30 s ...", "Not available ..."); kosong jika tidak ada. */
    public String message() {
        return message;
    }

    /** Lagu online yang sedang dimuat/diputar (null jika tidak ada). */
    public Song nowSong() {
        return nowSong;
    }

    public Entry nowEntry() {
        return nowEntry;
    }

    public boolean isCurrent(Entry e) {
        Entry n = nowEntry;
        if (n == null || e == null) {
            return false;
        }
        if (e.song() != null) {
            return n.song() != null ? n.song().id().equals(e.song().id())
                    : nowSong != null && nowSong.id().equals(e.song().id());
        }
        return n.hit() != null && n.hit().equals(e.hit());
    }

    public List<DownloadJob> jobs() {
        return jobs;
    }

    /** true selama ada lagu yang masih dimuat. */
    public boolean busy() {
        for (DownloadJob j : jobs) {
            if (!j.finished()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ pemuatan file

    /** Memuat file dari link langsung ke cache (dipakai pengujian dan pemuatan lagu). */
    public DownloadJob enqueueUrl(String url, String title) {
        String u = url.strip();
        return enqueue(u, title == null || title.isBlank() ? nameFromUrl(u) : title, false, null);
    }

    private DownloadJob enqueueSong(Song song) {
        return enqueue(song.streamUrl(), song.artist() + " - " + song.title(), true, song);
    }

    private DownloadJob enqueue(String url, String label, boolean nameFromTitle, Song song) {
        int seq = playSeq.get();
        for (DownloadJob j : jobs) {
            if (!j.finished() && url.equals(j.url)) {
                j.seq = seq;
                return j;
            }
        }
        DownloadJob job = new DownloadJob(ids.incrementAndGet(), url, label);
        job.nameFromTitle = nameFromTitle;
        job.song = song;
        job.seq = seq;
        if (song != null) {
            job.artist = song.artist();
            job.songTitle = song.title();
        }
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
        if (job.song != null) {
            if (job.seq != playSeq.get()) {
                return; // pengguna sudah memilih lagu lain
            }
            nowSong = job.song;
            message = job.song.isPreview() ? "Preview 30 s: the full song is not on the free services." : "";
        }
        version.incrementAndGet();
        try {
            onReady.accept(job);
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
            downloader.run(job, cacheDir.get());
            streamCache.put(job.url, job.result); // sebelum DONE, supaya ketukan kedua langsung memakai cache
            CacheCleaner.trim(cacheDir.get(), CACHE_MAX_BYTES, job.result);
            job.message = "Ready";
            job.state = DownloadJob.State.DONE;
            notifyReady(job);
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
        if (job.song != null && job.seq == playSeq.get()) {
            message = "Could not play \"" + job.song.title() + "\": " + msg.replaceFirst("^Failed: ", "");
        }
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

    // ------------------------------------------------------------------ memutar

    /** Memutar satu lagu (antrean hanya berisi lagu itu). */
    public void playSong(Song song) {
        playEntries(List.of(Entry.of(song)), 0);
    }

    /** Memutar daftar mulai dari urutan start; setelah selesai lanjut otomatis ke berikutnya. */
    public void playEntries(List<Entry> list, int start) {
        if (list.isEmpty() || start < 0 || start >= list.size()) {
            return;
        }
        queue.set(list, start);
        startEntry(queue.current(), true, 0);
    }

    /** Melupakan antrean dan lagu online (dipanggil saat pengguna memutar lagu dari Library). */
    public void stopOnline() {
        playSeq.incrementAndGet();
        queue.clear();
        nowEntry = null;
        nowSong = null;
        message = "";
        version.incrementAndGet();
    }

    private void startEntry(Entry e, boolean byUser, int skips) {
        final int seq = playSeq.incrementAndGet();
        nowEntry = e;
        nowSong = null;
        if (e.song() != null) {
            message = "Loading \"" + e.song().title() + "\"...";
            version.incrementAndGet();
            enqueueSong(e.song());
            return;
        }
        message = "Looking for \"" + e.hit().name() + "\"...";
        version.incrementAndGet();
        Thread t = new Thread(() -> resolveAndPlay(e, seq, byUser, skips), "xfive-resolve");
        t.setDaemon(true);
        t.start();
    }

    private void resolveAndPlay(Entry e, int seq, boolean byUser, int skips) {
        HitCharts.Hit hit = e.hit();
        Song found = null;
        String err = "";
        try {
            found = HitCharts.bestMatch(hit, AudiusSearch.search(hit.query(), 15));
        } catch (IOException | RuntimeException ex) {
            err = ex.getMessage() == null ? "no internet?" : ex.getMessage();
        }
        if (found == null) {
            try {
                for (Song s : ItunesSearch.search(hit.query(), 10)) {
                    if (HitCharts.matches(hit, s.title(), s.artist())) {
                        found = s;
                        break;
                    }
                }
            } catch (IOException | RuntimeException ex) {
                err = ex.getMessage() == null ? "no internet?" : ex.getMessage();
            }
        }
        if (seq != playSeq.get()) {
            return;
        }
        if (found != null) {
            message = "Loading \"" + hit.name() + "\"...";
            version.incrementAndGet();
            enqueueSong(found);
            return;
        }
        message = err.isEmpty() ? "Not available to play: " + hit.name() : "Search failed: " + err;
        version.incrementAndGet();
        if (!byUser && err.isEmpty() && skips < MAX_AUTO_SKIPS) {
            Entry next = queue.advance(false, false);
            if (next != null) {
                startEntry(next, false, skips + 1);
            }
        }
    }

    @Override
    public boolean next(boolean shuffle, boolean wrap) {
        Entry e = queue.advance(shuffle, wrap);
        if (e == null) {
            return false;
        }
        startEntry(e, false, 0);
        return true;
    }

    @Override
    public boolean previous() {
        Entry e = queue.back();
        if (e == null) {
            return false;
        }
        startEntry(e, true, 0);
        return true;
    }

    // ------------------------------------------------------------------ pencarian

    public SearchState searchState() {
        return searchState;
    }

    public String searchMessage() {
        return searchMessage;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public List<Song> results() {
        return results;
    }

    /** Mencari lagu (Audius lengkap dulu, lalu cuplikan Apple) di latar belakang. Pencarian lama yang tertinggal diabaikan. */
    public void search(String query) {
        final int seq = searchSeq.incrementAndGet();
        final String q = AudiusSearch.cleanQuery(query);
        searchQuery = q;
        searchState = SearchState.SEARCHING;
        searchMessage = "Searching...";
        results = List.of();
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            List<Song> all = new ArrayList<>();
            String err = "";
            try {
                all.addAll(AudiusSearch.search(q, 20));
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "no internet?" : e.getMessage();
            }
            if (seq != searchSeq.get()) {
                return;
            }
            // lagu lengkap tampil dulu; hasil sementara sudah bisa diputar
            results = new ArrayList<>(all);
            version.incrementAndGet();
            try {
                for (Song s : ItunesSearch.search(q, 20)) {
                    boolean dup = false;
                    for (Song a : all) {
                        if (HitCharts.matches(new HitCharts.Hit(s.title(), s.artist()), a.title(), a.artist())) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        all.add(s);
                    }
                }
                err = all.isEmpty() ? err : "";
            } catch (IOException | RuntimeException e) {
                if (all.isEmpty()) {
                    err = e.getMessage() == null ? "no internet?" : e.getMessage();
                }
            }
            if (seq != searchSeq.get()) {
                return;
            }
            results = all;
            if (all.isEmpty()) {
                searchState = err.isEmpty() ? SearchState.DONE : SearchState.ERROR;
                searchMessage = err.isEmpty() ? "No results. Try other words." : "Search failed: " + err;
            } else {
                searchState = SearchState.DONE;
                searchMessage = "";
            }
            version.incrementAndGet();
        }, "xfive-search");
        t.setDaemon(true);
        t.start();
    }

    public void clearSearch() {
        searchSeq.incrementAndGet();
        searchState = SearchState.IDLE;
        searchMessage = "";
        searchQuery = "";
        results = List.of();
        version.incrementAndGet();
    }

    // ------------------------------------------------------------------ tangga lagu

    private ChartState state(String id) {
        return charts.computeIfAbsent(id, k -> new ChartState());
    }

    public List<HitCharts.Hit> chartHits(String id) {
        return state(id).hits;
    }

    /** Pesan status tangga ("Loading...", atau kesalahan); kosong jika baik-baik saja. */
    public String chartMessage(String id) {
        return state(id).message;
    }

    /** Memuat satu tangga di latar belakang (dipakai ulang selama 30 menit kecuali force). */
    public void loadChart(String id, boolean force) {
        Chart c = chart(id);
        ChartState st = state(id);
        if (c == null || st.loading
                || (!force && st.loadedAt != 0L && System.currentTimeMillis() - st.loadedAt < 30 * 60_000L)) {
            return;
        }
        st.loading = true;
        st.message = "Loading...";
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                List<HitCharts.Hit> hits = HitCharts.load(c.id(), CHART_SIZE);
                st.hits = hits;
                st.loadedAt = System.currentTimeMillis();
                st.message = hits.isEmpty() ? "This chart is empty right now." : "";
            } catch (IOException | RuntimeException e) {
                st.message = "Could not load: " + (e.getMessage() == null ? "no internet?" : e.getMessage());
            }
            st.loading = false;
            version.incrementAndGet();
        }, "xfive-chart");
        t.setDaemon(true);
        t.start();
    }

    public void shutdown() {
        for (DownloadJob j : jobs) {
            j.cancel = true;
        }
        pool.shutdownNow();
    }
}
