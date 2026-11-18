package com.xfive.live.music;

/** Antrean lagu online (daftar tangga, hasil cari). Dipakai saat lagu online selesai atau Next/Prev ditekan. */
public interface OnlineQueue {
    /** Memutar lagu berikutnya. false jika antrean habis atau tidak ada. */
    boolean next(boolean shuffle, boolean wrap);

    /** Memutar lagu sebelumnya. false jika sudah di awal. */
    boolean previous();
}
