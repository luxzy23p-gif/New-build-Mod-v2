package com.xfive.live.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Mengunduh satu file lagu dengan aman:
 * tulis ke "nama.part", cek isi file (bukan halaman web), batasi ukuran, cek ruang kosong,
 * lalu ganti nama ke nama akhir. File lama tidak pernah ditimpa. Gagal/batal => .part dihapus.
 */
public final class Downloader {
    public static final long DEFAULT_MAX_BYTES = 200L * 1024 * 1024;
    private static final long MIN_BYTES = 2048;
    private static final long FREE_MARGIN = 20L * 1024 * 1024;
    private static final Object NAME_LOCK = new Object();

    private final long maxBytes;
    private final int retries;
    private final long backoffMs;

    public Downloader(long maxBytes, int retries, long backoffMs) {
        this.maxBytes = maxBytes;
        this.retries = Math.max(0, retries);
        this.backoffMs = Math.max(0, backoffMs);
    }

    /** Menjalankan unduhan lengkap dengan percobaan ulang. Hasil ada di job.result; error dilempar. */
    public void run(DownloadJob job, Path dir) throws IOException {
        int attempt = 0;
        while (true) {
            checkCancel(job);
            try {
                attemptOnce(job, dir);
                return;
            } catch (CancelledException e) {
                throw e;
            } catch (DownloadException e) {
                if (!e.retryable || attempt >= retries) {
                    throw e;
                }
            } catch (java.nio.file.FileSystemException e) {
                throw new DownloadException("Cannot save to the cache folder ("
                        + (e.getReason() != null ? e.getReason() : e.getClass().getSimpleName()) + ")", false);
            } catch (IOException e) {
                if (attempt >= retries) {
                    throw new DownloadException("Connection problem: " + shorten(e), true);
                }
            }
            attempt++;
            job.message = "Retrying (" + attempt + "/" + retries + ")...";
            job.done = 0;
            sleep(job, backoffMs * attempt);
        }
    }

    private static String shorten(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static void sleep(DownloadJob job, long ms) throws CancelledException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancel(job);
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.cancel = true;
                throw new CancelledException();
            }
        }
    }

    static void checkCancel(DownloadJob job) throws CancelledException {
        if (job.cancel || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    /** Dilempar saat pemain membatalkan. */
    public static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Cancelled");
        }
    }

    private void attemptOnce(DownloadJob job, Path dir) throws IOException {
        job.state = DownloadJob.State.RUNNING;
        job.message = "Connecting...";
        job.done = 0;
        job.total = -1;
        Files.createDirectories(dir);

        HttpURLConnection c = Http.open(job.url);
        Path part = null;
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new DownloadException("Server answered HTTP " + code
                        + (code == 403 || code == 401 ? " (not allowed)" : code == 404 ? " (not found)" : ""),
                        code >= 500 || code == 429 || code == 408);
            }
            String ct = c.getContentType();
            if (FileNames.isHtmlContentType(ct)) {
                throw new DownloadException("This link is a web page, not a song file", false);
            }
            long len = c.getContentLengthLong();
            if (len > maxBytes) {
                throw new DownloadException("File is too big (" + (len / 1048576) + " MB)", false);
            }
            if (len >= 0 && len < MIN_BYTES) {
                throw new DownloadException("File is too small to be a song", false);
            }
            job.total = len;
            long free = Long.MAX_VALUE;
            try {
                free = Files.getFileStore(dir).getUsableSpace();
            } catch (IOException | RuntimeException ignored) {
                // sebagian penyimpanan tidak bisa ditanya ruang kosongnya; lanjut saja
            }
            long need = (len > 0 ? len : 50L * 1024 * 1024) + FREE_MARGIN;
            if (free < need) {
                throw new DownloadException("Not enough free storage", false);
            }

            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[32 * 1024];
                int have = 0;
                while (have < 16) {
                    checkCancel(job);
                    int n = in.read(buf, have, 16 - have);
                    if (n < 0) {
                        break;
                    }
                    have += n;
                }
                String ext = AudioSniffer.sniff(buf, have);
                if (ext == null) {
                    if (AudioSniffer.looksLikeHtml(buf, have)) {
                        throw new DownloadException("This link is a web page, not a song file", false);
                    }
                    throw new DownloadException("Not a supported song (mp3, m4a, ogg, wav)", false);
                }

                String raw = job.nameFromTitle ? job.title
                        : FileNames.fromContentDisposition(c.getHeaderField("Content-Disposition"));
                if (raw == null) {
                    raw = FileNames.fromUrl(c.getURL().toString());
                }
                if (raw == null || raw.isBlank()) {
                    raw = job.title;
                }
                String name = FileNames.sanitize(raw, ext);

                Path target;
                synchronized (NAME_LOCK) {
                    while (true) {
                        target = FileNames.unique(dir, name);
                        part = dir.resolve(target.getFileName() + ".part");
                        try {
                            Files.createFile(part);
                            break;
                        } catch (FileAlreadyExistsException e) {
                            // nama dipakai unduhan lain, cari nama berikutnya
                        }
                    }
                }
                job.fileName = target.getFileName().toString();
                job.message = "Downloading";

                long done = 0;
                long lastUi = 0;
                try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    out.write(buf, 0, have);
                    done = have;
                    while (true) {
                        checkCancel(job);
                        int n = in.read(buf);
                        if (n < 0) {
                            break;
                        }
                        out.write(buf, 0, n);
                        done += n;
                        if (done > maxBytes) {
                            throw new DownloadException("File is too big", false);
                        }
                        long now = System.nanoTime();
                        if (now - lastUi > 100_000_000L) {
                            job.done = done;
                            lastUi = now;
                        }
                    }
                }
                job.done = done;
                if (len > 0 && done != len) {
                    throw new DownloadException("Download was cut off", true);
                }
                if (done < MIN_BYTES) {
                    throw new DownloadException("File is too small to be a song", false);
                }
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target);
                }
                part = null;
                job.total = done;
                job.result = target;
            }
        } finally {
            c.disconnect();
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // sisa .part tidak masalah, tidak dibaca sebagai lagu
                }
            }
        }
    }
}
