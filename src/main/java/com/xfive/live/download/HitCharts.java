package com.xfive.live.download;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tangga lagu terpopuler saat ini (Apple Music "Most Played") per negara: id = Indonesia, us = barat.
 * Hanya daftar judul dan artis; audionya dicari terpisah di Audius, jadi tidak semua lagu tersedia.
 */
public final class HitCharts {
    /** Alamat dasar (bisa diganti untuk pengujian). */
    public static volatile String base = "https://rss.marketingtools.apple.com";

    private HitCharts() {
    }

    public record Hit(String name, String artist) {
        /** Kata kunci pencarian: judul lalu artis utama. */
        public String query() {
            return name + " " + firstArtist(artist);
        }
    }

    public static String url(String country, int count) {
        return base + "/api/v2/" + country + "/music/most-played/" + count + "/songs.json";
    }

    public static List<Hit> load(String country, int count) throws IOException {
        return parse(Http.getText(url(country, count), 2_000_000));
    }

    public static List<Hit> parse(String json) throws IOException {
        List<Hit> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> feed = root == null ? null : MiniJson.asMap(root.get("feed"));
            List<Object> results = feed == null ? null : MiniJson.asList(feed.get("results"));
            if (results == null) {
                throw new DownloadException("Chart answer was not understood", false);
            }
            for (Object o : results) {
                Map<String, Object> r = MiniJson.asMap(o);
                if (r == null) {
                    continue;
                }
                String name = MiniJson.str(r.get("name"));
                String artist = MiniJson.str(r.get("artistName"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                out.add(new Hit(name.strip(), artist == null ? "" : artist.strip()));
            }
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Chart answer was not understood", false);
        }
        return out;
    }

    static String firstArtist(String artist) {
        String a = artist == null ? "" : artist;
        for (String sep : new String[] {",", " & ", " feat.", " ft.", " x ", " X "}) {
            int i = a.indexOf(sep);
            if (i > 0) {
                a = a.substring(0, i);
            }
        }
        return a.strip();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * Memilih hasil Audius yang paling cocok dengan lagu tangga: judul lagu harus ada di judul hasil,
     * dan artis utama harus ada di judul atau nama pengunggah. Mengembalikan null jika tidak ada yang cocok
     * (lebih baik "tidak ada" daripada memutar lagu yang salah).
     */
    public static AudiusSearch.Song bestMatch(Hit hit, List<AudiusSearch.Song> songs) {
        String name = norm(hit.name());
        String artist = norm(firstArtist(hit.artist()));
        if (name.isEmpty()) {
            return null;
        }
        for (AudiusSearch.Song s : songs) {
            String title = norm(s.title());
            String who = norm(s.artist());
            boolean nameOk = (" " + title + " ").contains(" " + name + " ");
            boolean artistOk = artist.isEmpty() || (" " + title + " " + who + " ").contains(" " + artist + " ")
                    || who.contains(artist) || artist.contains(who) && !who.isEmpty();
            if (nameOk && artistOk) {
                return s;
            }
        }
        return null;
    }
}
