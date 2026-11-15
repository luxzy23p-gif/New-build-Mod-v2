package com.xfive.live.download;

import java.nio.file.Path;

/** Satu unduhan (atau satu permintaan "ambil semua lagu dari hasil pencarian"). Field dibaca dari thread game. */
public final class DownloadJob {
    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public final int id;
    /** Alamat file. */
    public final String url;
    /** Nama tampilan sementara (judul hasil pencarian atau nama dari link). */
    public final String title;

    /** Putar langsung (disimpan di folder cache, bukan di Library). */
    public volatile boolean stream;
    /** Nama file diambil dari judul ("Artis - Judul"), bukan dari alamat file. */
    public volatile boolean nameFromTitle;
    public volatile String artist = "";
    public volatile String songTitle = "";

    public volatile State state = State.QUEUED;
    public volatile String message = "Waiting...";
    public volatile long done;
    public volatile long total = -1;
    public volatile String fileName = "";
    public volatile Path result;
    volatile boolean cancel;

    DownloadJob(int id, String url, String title) {
        this.id = id;
        this.url = url;
        this.title = title == null || title.isBlank() ? "Song" : title;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    /** 0..1, atau -1 jika ukuran belum diketahui. */
    public float progress() {
        long t = total;
        if (t <= 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(1f, done / (float) t));
    }

    public String displayName() {
        return fileName.isEmpty() ? title : fileName;
    }
}
