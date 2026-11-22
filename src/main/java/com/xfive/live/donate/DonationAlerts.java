package com.xfive.live.donate;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Antrean alert donasi. Murni Java (tanpa Minecraft) supaya bisa diuji.
 * Sumber donasi apa pun (tombol Test, nanti SociaBuzz/layanan lain) cukup memanggil push().
 * Alert tampil satu per satu; antar alert ada jeda singkat supaya animasi keluar dan masuk tidak bertabrakan.
 */
public final class DonationAlerts {
    /** Jenis alert: donasi biasa, Media Share (video), atau Song Share (permintaan lagu). */
    public enum Kind { DONATE, MEDIA, SONG }

    public record Alert(Kind kind, String name, long amount, String message) {
        public Alert(String name, long amount, String message) {
            this(Kind.DONATE, name, amount, message);
        }
    }

    public static final int MAX_QUEUE = 10;
    public static final long GAP_MS = 400L;
    public static final int NAME_MAX = 24;
    public static final int MESSAGE_MAX = 120;

    private final ArrayDeque<Alert> queue = new ArrayDeque<>();
    private Alert current;
    private long startedMs;
    private long gapUntilMs;

    /** Menambah alert. false jika antrean penuh. Teks dibersihkan dari karakter kontrol dan dipotong. */
    public synchronized boolean push(String name, long amount, String message) {
        return push(Kind.DONATE, name, amount, message);
    }

    public synchronized boolean push(Kind kind, String name, long amount, String message) {
        if (queue.size() >= MAX_QUEUE) {
            return false;
        }
        String n = clean(name, NAME_MAX);
        queue.add(new Alert(kind == null ? Kind.DONATE : kind, n.isEmpty() ? "Someone" : n, Math.max(0L, amount),
                clean(message, MESSAGE_MAX)));
        return true;
    }

    /**
     * Memajukan antrean dan mengembalikan alert yang sedang tampil (atau null).
     * showMs = lama satu alert tampil.
     */
    public synchronized Alert tick(long nowMs, long showMs) {
        if (current != null && nowMs >= startedMs + showMs) {
            current = null;
            gapUntilMs = nowMs + GAP_MS;
        }
        if (current == null && nowMs >= gapUntilMs && !queue.isEmpty()) {
            current = queue.poll();
            startedMs = nowMs;
        }
        return current;
    }

    /** Waktu mulai alert yang sedang tampil (ms), untuk animasi masuk dan keluar. */
    public synchronized long startedMs() {
        return startedMs;
    }

    public synchronized int pending() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
        current = null;
    }

    /** 25000 menjadi "Rp25.000". */
    public static String formatRupiah(long amount) {
        long a = Math.max(0L, amount);
        String digits = Long.toString(a);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append('.');
            }
            out.append(digits.charAt(i));
        }
        return "Rp" + out;
    }

    /** Membuang karakter kontrol, merapikan spasi, dan memotong dengan "..." jika terlalu panjang. */
    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\p{Cntrl}\\u00A7]", " ").replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, Math.max(1, max - 3)).stripTrailing() + "...";
    }

    /** Kemajuan animasi masuk/keluar 0..1 (1 = tampil penuh). */
    public static float visibility(long sinceStartMs, long showMs, long fadeMs) {
        if (sinceStartMs < 0 || sinceStartMs > showMs) {
            return 0f;
        }
        float in = Math.min(1f, sinceStartMs / (float) fadeMs);
        float out = Math.min(1f, (showMs - sinceStartMs) / (float) fadeMs);
        float t = Math.max(0f, Math.min(in, out));
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "DonationAlerts[pending=%d]", pending());
    }
}
