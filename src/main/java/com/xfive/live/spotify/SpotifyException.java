package com.xfive.live.spotify;

import java.io.IOException;

/** Kegagalan berbicara dengan Spotify. status = kode HTTP (0 jika tidak ada jawaban). Pesan siap ditampilkan. */
public final class SpotifyException extends IOException {
    private final int status;

    public SpotifyException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
