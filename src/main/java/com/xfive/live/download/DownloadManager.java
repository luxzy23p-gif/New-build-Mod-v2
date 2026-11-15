package com.xfive.live.download;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Antrean unduhan di latar belakang (maks 2 sekaligus) dan pencarian online.
 * Layar membaca {@link #version()} untuk tahu kapan daftar perlu digambar ulang.
 */
public final class DownloadManager {
    public enum SearchState { IDLE, SEARCHING, DONE, ERROR }

    private final Supplier<Path> dir;
    private final Downloader downloader;
    private final long maxBytes;
    private final ExecutorService pool;
    private final Consumer<DownloadJob> onSaved;

    private final List<DownloadJob> jobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger version = new AtomicInteger();

    private volatile SearchState searchState = SearchState.IDLE;
    private volatile String searchMessage = "";
    private volatile List<OnlineSearch.Result> results = List.of();
    private final AtomicInteger searchSeq = new AtomicInteger();

    /** @param onSaved dipanggil dari thread unduhan setiap lagu selesai tersimpan (pemanggil yang pindah thread). */
    public DownloadManager(Supplier<Path> dir, long maxBytes, int threads, int retries, long backoffMs,
                           Consumer<DownloadJob> onSaved) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.downloader = new Downloader(maxBytes, retries, backoffMs);
        this.onSaved = onSaved;
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

    public List<OnlineSearch.Result> results() {
        return results;
    }

    public boolean busy() {
        for (DownloadJob j : jobs) {
            if (!j.finished()) {
                return true;
            }
        }
        return false;
    }

    /** Menambah unduhan dari link langsung. Mengembalikan job yang sama jika link itu sedang berjalan. */
    public DownloadJob enqueueUrl(String url, String title) {
        String u = url.strip();
        for (DownloadJob j : jobs) {
            if (!j.finished() && u.equals(j.url)) {
                return j;
            }
        }
        DownloadJob job = new DownloadJob(ids.incrementAndGet(), u, null, title == null || title.isBlank()
                ? nameFromUrl(u) : title);
        add(job);
        return job;
    }

    /** Mengambil semua lagu dari satu hasil pencarian. */
    public DownloadJob enqueueItem(OnlineSearch.Result r) {
        for (DownloadJob j : jobs) {
            if (!j.finished() && r.identifier().equals(j.identifier)) {
                return j;
            }
        }
        DownloadJob job = new DownloadJob(ids.incrementAndGet(), null, r.identifier(), r.title());
        add(job);
        return job;
    }

    private static String nameFromUrl(String u) {
        String n = FileNames.fromUrl(u);
        return n == null ? "Song" : FileNames.sanitize(n, "x").replaceFirst("\\.x$", "");
    }

    private void add(DownloadJob job) {
        jobs.add(job);
        version.incrementAndGet();
        pool.execute(() -> work(job));
    }

    private void work(DownloadJob job) {
        if (job.cancel) {
            end(job, DownloadJob.State.CANCELLED, "Cancelled");
            return;
        }
        try {
            if (job.url == null) {
                resolve(job);
                return;
            }
            downloader.run(job, dir.get());
            job.state = DownloadJob.State.DONE;
            job.message = "Saved";
            version.incrementAndGet();
            try {
                onSaved.accept(job);
            } catch (RuntimeException ignored) {
                // gagal memuat ulang daftar tidak boleh membuat unduhan dianggap gagal
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

    private void resolve(DownloadJob job) throws IOException {
        job.state = DownloadJob.State.RESOLVING;
        job.message = "Looking for songs...";
        version.incrementAndGet();
        List<OnlineSearch.Track> tracks = OnlineSearch.tracks(job.identifier, maxBytes);
        if (job.cancel) {
            throw new Downloader.CancelledException();
        }
        if (tracks.isEmpty()) {
            throw new DownloadException("No song files found here", false);
        }
        jobs.remove(job);
        for (OnlineSearch.Track t : tracks) {
            String label = tracks.size() == 1 ? job.title : job.title + " - " + FileNames.sanitize(t.name(), "x").replaceFirst("\\.x$", "");
            DownloadJob child = new DownloadJob(ids.incrementAndGet(), t.url(), null, label);
            add(child);
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

    /** Mencoba lagi unduhan yang gagal/dibatalkan: job lama diganti job baru. */
    public void retry(DownloadJob job) {
        if (!job.finished() || job.state == DownloadJob.State.DONE) {
            return;
        }
        jobs.remove(job);
        if (job.url != null) {
            enqueueUrl(job.url, job.title);
        } else {
            enqueueItem(new OnlineSearch.Result(job.identifier, job.title, "", 0));
        }
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

    /** Mencari di Internet Archive (latar belakang). Pencarian lama yang tertinggal diabaikan. */
    public void search(String query) {
        final int seq = searchSeq.incrementAndGet();
        searchState = SearchState.SEARCHING;
        searchMessage = "Searching...";
        results = List.of();
        version.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                List<OnlineSearch.Result> r = OnlineSearch.search(query, 25);
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

    public void shutdown() {
        for (DownloadJob j : jobs) {
            j.cancel = true;
        }
        pool.shutdownNow();
    }
}
