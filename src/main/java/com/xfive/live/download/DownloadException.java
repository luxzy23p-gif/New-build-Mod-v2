package com.xfive.live.download;

import java.io.IOException;

/** Kegagalan unduhan dengan pesan yang bisa ditampilkan ke pemain. */
public final class DownloadException extends IOException {
    private static final long serialVersionUID = 1L;
    public final boolean retryable;

    public DownloadException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }
}
