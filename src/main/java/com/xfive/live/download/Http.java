package com.xfive.live.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Permintaan HTTP sederhana: pengalihan diikuti manual (maks 6), hanya http/https, ada batas waktu. */
public final class Http {
    static final String UA = "XFiveLive/1.1 (Minecraft mod; music player)";
    static final int CONNECT_MS = 15_000;
    static final int READ_MS = 20_000;
    private static final int MAX_HOPS = 6;

    private Http() {
    }

    /** Mengambil isi (misalnya gambar sampul) dengan batas ukuran. */
    public static byte[] getBytes(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new DownloadException("Server answered HTTP " + code, code >= 500 || code == 429);
            }
            long len = c.getContentLengthLong();
            if (len > maxBytes) {
                throw new DownloadException("Answer too large", false);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new DownloadException("Answer too large", false);
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Membuka koneksi yang sudah mendapat respons akhir (bukan pengalihan). Pemanggil wajib disconnect(). */
    static HttpURLConnection open(String url) throws IOException {
        String cur = url.strip();
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URI uri;
            try {
                uri = URI.create(cur.replace(" ", "%20"));
                uri = URI.create(uri.toASCIIString());
            } catch (IllegalArgumentException e) {
                throw new DownloadException("Link is not valid", false);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new DownloadException("Only http/https links work", false);
            }
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "audio/*,application/json;q=0.9,*/*;q=0.5");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isBlank()) {
                    throw new DownloadException("Server redirect has no address", false);
                }
                try {
                    cur = uri.resolve(loc.strip().replace(" ", "%20")).toString();
                } catch (IllegalArgumentException e) {
                    throw new DownloadException("Server redirect is broken", false);
                }
                continue;
            }
            return c;
        }
        throw new DownloadException("Too many redirects", false);
    }

    /** Mengambil teks (UTF-8) dengan batas ukuran. */
    public static String getText(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new DownloadException("Server answered HTTP " + code, code >= 500 || code == 429);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new DownloadException("Answer too large", false);
                    }
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
