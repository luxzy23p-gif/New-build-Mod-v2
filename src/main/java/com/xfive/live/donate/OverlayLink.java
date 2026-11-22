package com.xfive.live.donate;

import java.net.URI;
import java.util.Locale;

/**
 * Link overlay SociaBuzz yang ditempel pemain (Alert, Media Share, Song Share). Murni Java supaya bisa diuji.
 * Link ini rahasia (siapa pun yang punya bisa melihat alert-mu), jadi di layar hanya ditampilkan sebagian.
 */
public final class OverlayLink {
    public static final int MAX_LEN = 600;

    private OverlayLink() {
    }

    /** Link yang sudah dirapikan, atau null kalau bukan link https://sociabuzz.com/... yang masuk akal. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().replaceAll("[\\p{Cntrl}\\s]+", "");
        if (s.isEmpty() || s.length() > MAX_LEN) {
            return null;
        }
        if (!s.toLowerCase(Locale.ROOT).startsWith("http")) {
            s = "https://" + s;
        }
        try {
            URI u = URI.create(s);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean ok = "https".equalsIgnoreCase(u.getScheme())
                    && (host.equals("sociabuzz.com") || host.endsWith(".sociabuzz.com"))
                    && u.getPath() != null && u.getPath().length() > 1;
            return ok ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tampilan aman untuk layar: alamat situs dan 4 huruf terakhir id, sisanya disembunyikan. */
    public static String mask(String url) {
        String n = url == null ? null : normalize(url);
        if (n == null) {
            return "Not set";
        }
        int q = n.indexOf('?');
        String path = q >= 0 ? n.substring(0, q) : n;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String id = slash >= 0 ? path.substring(slash + 1) : path;
        String tail = id.length() <= 4 ? id : id.substring(id.length() - 4);
        return "sociabuzz.com/.../****" + tail;
    }
}
