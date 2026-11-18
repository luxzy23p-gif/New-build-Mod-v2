package com.xfive.live.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Antrean putar sederhana (daftar + posisi). Murni Java supaya bisa diuji tanpa Minecraft. */
public final class PlayQueue<T> {
    private List<T> items = List.of();
    private int index = -1;
    private final Random random;

    public PlayQueue() {
        this(new Random());
    }

    public PlayQueue(Random random) {
        this.random = random;
    }

    /** Mengganti isi antrean dan mulai dari posisi start (di luar rentang = kosong). */
    public synchronized void set(List<T> list, int start) {
        items = new ArrayList<>(list);
        index = start >= 0 && start < items.size() ? start : -1;
    }

    public synchronized void clear() {
        items = List.of();
        index = -1;
    }

    public synchronized boolean active() {
        return index >= 0;
    }

    public synchronized int index() {
        return index;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized T current() {
        return index >= 0 ? items.get(index) : null;
    }

    /**
     * Pindah ke lagu berikutnya. Acak: lagu lain secara acak. Di ujung daftar: kembali ke awal jika wrap,
     * selain itu null (posisi tidak berubah).
     */
    public synchronized T advance(boolean shuffle, boolean wrap) {
        if (index < 0 || items.isEmpty()) {
            return null;
        }
        if (shuffle && items.size() > 1) {
            int pick;
            do {
                pick = random.nextInt(items.size());
            } while (pick == index);
            index = pick;
            return items.get(index);
        }
        if (index + 1 < items.size()) {
            index++;
        } else if (wrap) {
            index = 0;
        } else {
            return null;
        }
        return items.get(index);
    }

    /** Lagu sebelumnya, atau null jika sudah di awal. */
    public synchronized T back() {
        if (index <= 0) {
            return null;
        }
        index--;
        return items.get(index);
    }
}
