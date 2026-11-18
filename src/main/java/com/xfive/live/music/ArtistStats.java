package com.xfive.live.music;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Menghitung artis teratas dari daftar lagu. Murni Java supaya bisa diuji tanpa Minecraft. */
public final class ArtistStats {
    public record Entry(String name, int count) {
    }

    private ArtistStats() {
    }

    /** true untuk nama artis yang tidak berarti ("Unknown Artist", kosong). */
    public static boolean isUnknown(String artist) {
        if (artist == null) {
            return true;
        }
        String a = artist.strip().toLowerCase(Locale.ROOT);
        return a.isEmpty() || a.startsWith("unknown");
    }

    /** Artis dengan lagu terbanyak (sama banyak: urut abjad), paling banyak {@code limit} artis. */
    public static List<Entry> top(List<String> artists, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> shown = new HashMap<>();
        if (artists != null) {
            for (String raw : artists) {
                if (isUnknown(raw)) {
                    continue;
                }
                String name = raw.strip();
                String key = name.toLowerCase(Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
                shown.putIfAbsent(key, name);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(new Entry(shown.get(e.getKey()), e.getValue()));
        }
        out.sort((x, y) -> {
            int c = Integer.compare(y.count(), x.count());
            return c != 0 ? c : x.name().compareToIgnoreCase(y.name());
        });
        return limit < out.size() ? new ArrayList<>(out.subList(0, Math.max(0, limit))) : out;
    }
}
