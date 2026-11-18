package com.xfive.live.download;

import java.util.ArrayList;
import java.util.List;

/**
 * Lagu online favorit disimpan sebagai satu baris teks per lagu (id, judul, artis, detik, alamat cuplikan;
 * dipisah tab). Murni Java supaya bisa diuji tanpa Minecraft.
 */
public final class FavoriteSongs {
    private FavoriteSongs() {
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip();
    }

    public static String encode(Song s) {
        return clean(s.id()) + "\t" + clean(s.title()) + "\t" + clean(s.artist()) + "\t" + Math.max(0, s.seconds())
                + "\t" + clean(s.url());
    }

    /** Null jika baris rusak (id kosong atau kurang kolom). */
    public static Song decode(String line) {
        if (line == null) {
            return null;
        }
        String[] f = line.split("\t", -1);
        if (f.length < 3 || f[0].isBlank()) {
            return null;
        }
        int sec = 0;
        if (f.length > 3) {
            try {
                sec = Math.max(0, Integer.parseInt(f[3].strip()));
            } catch (NumberFormatException ignored) {
                sec = 0;
            }
        }
        String url = f.length > 4 ? f[4].strip() : "";
        String title = f[1].isBlank() ? "Untitled" : f[1];
        String artist = f[2].isBlank() ? "Unknown Artist" : f[2];
        return new Song(f[0].strip(), title, artist, sec, url);
    }

    public static List<Song> decodeAll(List<String> lines) {
        List<Song> out = new ArrayList<>();
        for (String l : lines) {
            Song s = decode(l);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public static int indexOf(List<String> lines, String songId) {
        for (int i = 0; i < lines.size(); i++) {
            Song s = decode(lines.get(i));
            if (s != null && s.id().equals(songId)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean contains(List<String> lines, String songId) {
        return indexOf(lines, songId) >= 0;
    }

    /** Menambah jika belum ada, menghapus jika sudah ada. Mengembalikan true jika sekarang jadi favorit. */
    public static boolean toggle(List<String> lines, Song s) {
        int i = indexOf(lines, s.id());
        if (i >= 0) {
            lines.remove(i);
            return false;
        }
        lines.add(0, encode(s));
        return true;
    }
}
