package com.xfive.live.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Aturan nama file unduhan: aman, ber-ekstensi audio, dan tidak menimpa file yang sudah ada. */
public final class FileNames {
    public static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "ogg", "wav");
    private static final int MAX_BASE = 100;

    private FileNames() {
    }

    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    public static String extensionFromContentType(String ct) {
        if (ct == null) {
            return null;
        }
        String c = ct.toLowerCase(Locale.ROOT);
        int sc = c.indexOf(';');
        if (sc >= 0) {
            c = c.substring(0, sc);
        }
        c = c.strip();
        switch (c) {
            case "audio/mpeg":
            case "audio/mp3":
            case "audio/x-mpeg":
                return "mp3";
            case "audio/mp4":
            case "audio/x-m4a":
            case "audio/m4a":
            case "audio/aac":
                return "m4a";
            case "audio/ogg":
            case "application/ogg":
            case "audio/vorbis":
                return "ogg";
            case "audio/wav":
            case "audio/x-wav":
            case "audio/wave":
            case "audio/vnd.wave":
                return "wav";
            default:
                return null;
        }
    }

    public static boolean isHtmlContentType(String ct) {
        if (ct == null) {
            return false;
        }
        String c = ct.toLowerCase(Locale.ROOT);
        return c.startsWith("text/html") || c.startsWith("application/xhtml") || c.startsWith("text/xml");
    }

    /** Nama file dari header Content-Disposition (mendukung filename*=UTF-8''...), atau null. */
    public static String fromContentDisposition(String cd) {
        if (cd == null) {
            return null;
        }
        String lower = cd.toLowerCase(Locale.ROOT);
        int star = lower.indexOf("filename*=");
        if (star >= 0) {
            String v = cd.substring(star + 10).strip();
            int semi = v.indexOf(';');
            if (semi >= 0) {
                v = v.substring(0, semi).strip();
            }
            int q = v.indexOf("''");
            if (q >= 0) {
                v = v.substring(q + 2);
            }
            try {
                String dec = URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (!dec.isBlank()) {
                    return dec;
                }
            } catch (IllegalArgumentException ignored) {
                // lanjut ke filename=
            }
        }
        int f = lower.indexOf("filename=");
        if (f >= 0) {
            String v = cd.substring(f + 9).strip();
            if (v.startsWith("\"")) {
                int end = v.indexOf('"', 1);
                v = end > 0 ? v.substring(1, end) : v.substring(1);
            } else {
                int semi = v.indexOf(';');
                if (semi >= 0) {
                    v = v.substring(0, semi);
                }
                v = v.strip();
            }
            return v.isBlank() ? null : v;
        }
        return null;
    }

    /** Nama file dari bagian akhir path URL (sudah di-decode), atau null. */
    public static String fromUrl(String url) {
        try {
            String p = URI.create(url).getPath();
            if (p == null || p.isEmpty() || p.endsWith("/")) {
                return null;
            }
            String last = p.substring(p.lastIndexOf('/') + 1);
            String dec = URLDecoder.decode(last.replace("+", "%2B"), StandardCharsets.UTF_8);
            return dec.isBlank() ? null : dec;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Membersihkan nama menjadi nama file yang aman dengan ekstensi yang diberikan.
     * Ekstensi lama (mp3/m4a/ogg/wav) dibuang dulu agar tidak dobel.
     */
    public static String sanitize(String raw, String ext) {
        String s = raw == null ? "" : raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        String e = extensionOf(s);
        if (EXTENSIONS.contains(e)) {
            s = s.substring(0, s.length() - e.length() - 1);
        }
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < 32 || c == 127 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                c = ' ';
            }
            b.append(c);
        }
        String base = b.toString().replaceAll("\\s+", " ").strip();
        while (base.startsWith(".")) {
            base = base.substring(1).strip();
        }
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1).strip();
        }
        if (base.length() > MAX_BASE) {
            base = base.substring(0, MAX_BASE).strip();
        }
        if (base.isEmpty()) {
            base = "song";
        }
        return base + "." + ext;
    }

    /** Nama yang belum dipakai di folder: "lagu.mp3", "lagu (2).mp3", ... (juga menghindari .part). */
    public static Path unique(Path dir, String fileName) {
        Path p = dir.resolve(fileName);
        if (!Files.exists(p) && !Files.exists(dir.resolve(fileName + ".part"))) {
            return p;
        }
        String ext = extensionOf(fileName);
        String base = fileName.substring(0, fileName.length() - ext.length() - 1);
        for (int n = 2; n < 1000; n++) {
            Path c = dir.resolve(base + " (" + n + ")." + ext);
            if (!Files.exists(c) && !Files.exists(dir.resolve(c.getFileName() + ".part"))) {
                return c;
            }
        }
        return dir.resolve(base + " (" + System.nanoTime() + ")." + ext);
    }

    /** Hanya http/https dan punya host. */
    public static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.strip();
        String l = t.toLowerCase(Locale.ROOT);
        if (!(l.startsWith("http://") || l.startsWith("https://")) || t.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI u = URI.create(t);
            return u.getHost() != null && !u.getHost().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
