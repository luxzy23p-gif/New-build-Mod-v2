package com.xfive.live.music;

/**
 * Pemutar di luar mod (misalnya aplikasi Spotify) yang bisa dikontrol dari pemutar XFiveLive.
 * Saat mode ini aktif, judul, posisi, dan tombol kontrol mengikuti pemutar tersebut, jadi HUD,
 * Dynamic Bar, dan lirik ikut bekerja tanpa perubahan.
 */
public interface RemotePlayer {
    /** Lagu yang sedang aktif, atau null. Objek yang sama dikembalikan selama lagunya sama. */
    PlaylistManager.Track track();

    long positionMs();

    long durationMs();

    boolean isPlaying();

    void toggle();

    void next();

    void previous();

    void seekMs(long positionMs);

    /** Volume 0..1. */
    void setVolume(float volume);

    /** Menjeda jika sedang berbunyi (dipakai saat pindah ke lagu lokal/online). */
    void pause();

    /** Dipanggil saat mode ini dihidupkan/dimatikan supaya pembacaan keadaan bisa dimulai/dihentikan. */
    void setActive(boolean active);
}
