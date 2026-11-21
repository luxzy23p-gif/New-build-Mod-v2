package com.xfive.live.lyrics;

import com.xfive.live.download.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Sumber lirik tambahan: Kugou (kugou.com). Titik akhirnya tidak resmi. Alurnya: cari lagu (dapat hash), cari lirik
 * untuk hash itu (dapat id dan accesskey), lalu unduh LRC (base64). Lirik diperiksa panjangnya terhadap lagu.
 * Dipanggil dari thread latar saja. Kegagalan apa pun berarti "tidak ada".
 */
public final class KugouFetcher {
    /** Alamat pencarian lagu dan alamat lirik (bisa diganti untuk pengujian). */
    public static volatile String songBase = "http://mobilecdn.kugou.com/api/v3/search/song";
    public static volatile String lyricBase = "http://lyrics.kugou.com/";

    private KugouFetcher() {
    }

    /** Daftar lagu dari jawaban pencarian: kandidat tanpa lirik, dan hash-nya (paralel). */
    static List<LyricsPicker.Candidate> parseSongs(String json, List<String> hashes) {
        List<LyricsPicker.Candidate> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> data = root == null ? null : MiniJson.asMap(root.get("data"));
            List<Object> info = data == null ? List.of() : MiniJson.asList(data.get("info"));
            for (Object o : info) {
                Map<String, Object> m = MiniJson.asMap(o);
                if (m == null || MiniJson.str(m.get("hash")).isBlank()) {
                    continue;
                }
                out.add(new LyricsPicker.Candidate(MiniJson.str(m.get("songname")), MiniJson.str(m.get("singername")),
                        MiniJson.num(m.get("duration"), 0L), false, null));
                hashes.add(MiniJson.str(m.get("hash")));
            }
        } catch (RuntimeException e) {
            out.clear();
            hashes.clear();
        }
        return out;
    }

    /** {id, accesskey} dari jawaban pencarian lirik, atau null. */
    static String[] parseLyricRef(String json) {
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            List<Object> cands = root == null ? List.of() : MiniJson.asList(root.get("candidates"));
            for (Object o : cands) {
                Map<String, Object> m = MiniJson.asMap(o);
                if (m != null && !MiniJson.str(m.get("accesskey")).isBlank() && m.get("id") != null) {
                    return new String[] {String.valueOf(MiniJson.num(m.get("id"), 0L)), MiniJson.str(m.get("accesskey"))};
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Teks LRC dari jawaban unduhan (field content berisi base64), atau null. */
    static String parseDownload(String json) {
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            String b64 = root == null ? "" : MiniJson.str(root.get("content"));
            if (b64.isBlank()) {
                return null;
            }
            String text = new String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            return text.contains("[") ? text : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static LrclibFetcher.Result fetch(String artist, String title, long durationMs) {
        if (title == null || title.isBlank() || durationMs <= 0L) {
            return new LrclibFetcher.Result(null, "");
        }
        boolean hasArtist = artist != null && !artist.isBlank() && !artist.strip().equalsIgnoreCase("unknown artist");
        String clean = LrclibFetcher.cleanTitle(title);
        try {
            String[] queries = hasArtist ? new String[] {artist + " " + clean, clean} : new String[] {clean};
            for (String q : queries) {
                String body = WebText.get(songBase + "?format=json&page=1&pagesize=8&keyword=" + WebText.enc(q), null);
                List<String> hashes = new ArrayList<>();
                List<LyricsPicker.Candidate> metas = parseSongs(body, hashes);
                for (int i : LyricsPicker.shortlist(metas, clean, artist, durationMs, 3)) {
                    String ref = null;
                    String[] r = parseLyricRef(WebText.get(lyricBase + "search?ver=1&man=yes&client=pc&keyword="
                            + WebText.enc(q) + "&duration=" + durationMs + "&hash=" + WebText.enc(hashes.get(i)), null));
                    if (r != null) {
                        String lrc = parseDownload(WebText.get(lyricBase + "download?ver=1&client=pc&fmt=lrc&charset=utf8&id="
                                + WebText.enc(r[0]) + "&accesskey=" + WebText.enc(r[1]), null));
                        if (lrc != null && LyricsPicker.plausible(lrc, durationMs)) {
                            return new LrclibFetcher.Result(lrc, "");
                        }
                    }
                    Thread.sleep(200L);
                }
                Thread.sleep(200L);
            }
        } catch (IOException | RuntimeException e) {
            return new LrclibFetcher.Result(null, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new LrclibFetcher.Result(null, "");
    }
}
