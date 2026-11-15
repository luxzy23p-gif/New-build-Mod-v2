package com.xfive.live.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Membersihkan folder cache untuk lagu yang diputar online (bukan Library). */
public final class CacheCleaner {
    private CacheCleaner() {
    }

    /** Menghapus semua file di cache (dipanggil saat game mulai). File .part ikut dihapus. */
    public static void purge(Path dir) {
        for (Path p : files(dir, true)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException | RuntimeException ignored) {
                // file dipakai / tidak bisa dihapus, biarkan
            }
        }
    }

    /** Menghapus file tertua sampai total ukuran <= maxBytes. File "keep" dan .part tidak disentuh. */
    public static void trim(Path dir, long maxBytes, Path keep) {
        List<Path> list = files(dir, false);
        long total = 0;
        for (Path p : list) {
            total += size(p);
        }
        list.sort(Comparator.comparingLong(CacheCleaner::modified));
        for (Path p : list) {
            if (total <= maxBytes) {
                return;
            }
            if (keep != null && p.equals(keep)) {
                continue;
            }
            long sz = size(p);
            try {
                if (Files.deleteIfExists(p)) {
                    total -= sz;
                }
            } catch (IOException | RuntimeException ignored) {
                // lewati
            }
        }
    }

    private static List<Path> files(Path dir, boolean includePart) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(Files::isRegularFile)
                    .filter(p -> includePart || !p.getFileName().toString().endsWith(".part"))
                    .forEach(out::add);
        } catch (IOException | RuntimeException ignored) {
            // folder tidak bisa dibaca
        }
        return out;
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
