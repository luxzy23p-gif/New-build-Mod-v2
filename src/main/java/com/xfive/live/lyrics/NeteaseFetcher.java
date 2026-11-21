package com.xfive.live.lyrics;

import com.xfive.live.download.MiniJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sumber lirik tambahan: NetEase Cloud Music (music.163.com). Titik akhirnya tidak resmi, tetapi dipakai banyak
 * pemutar musik. Lagu dipilih dari judul, artis, dan durasi; liriknya lalu diperiksa panjangnya terhadap lagu.
 * Dipanggil dari thread latar saja. Kegagalan apa pun berarti "tidak ada".
 */
public final class NeteaseFetcher {
    /** Alamat API (bisa diganti untuk pengujian). */
    public static volatile String base = "https://music.163.com/api/";

    private NeteaseFetcher() {
    }

    /** Daftar lagu dari jawaban pencarian: kandidat tanpa lirik, dan id lagunya (paralel). */
    static List<LyricsPicker.Candidate> parseSearch(String json, List<String> ids) {
        List<LyricsPicker.Candidate> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> result = root == null ? null : MiniJson.asMap(root.get("result"));
            List<Object> songs = result == null ? List.of() : MiniJson.asList(result.get("songs"));
            for (Object o : songs) {
                Map<String, Object> m = MiniJson.asMap(o);
                if (m == null || m.get("id") == null) {
                    continue;
                }
                StringBuilder who = new StringBuilder();
                for (Object a : MiniJson.asList(m.get("artists"))) {
                    Map<String, Object> am = MiniJson.asMap(a);
                    if (am != null && !MiniJson.str(am.get("name")).isBlank()) {
                        who.append(who.length() == 0 ? "" : " ").append(MiniJson.str(am.get("name")));
                    }
                }
                double sec = MiniJson.num(m.get("duration"), 0L) / 1000.0;
                out.add(new LyricsPicker.Candidate(MiniJson.str(m.get("name")), who.toString(), sec, false, null));
                ids.add(String.valueOf(MiniJson.num(m.get("id"), 0L)));
            }
        } catch (RuntimeException e) {
            out.clear();
            ids.clear();
        }
        return out;
    }

    /** Teks LRC dari jawaban lirik, atau null. */
    static String parseLyric(String json) {
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> lrc = root == null ? null : MiniJson.asMap(root.get("lrc"));
            String text = lrc == null ? "" : MiniJson.str(lrc.get("lyric"));
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
                String body = WebText.postForm(base + "search/get/", "s=" + WebText.enc(q) + "&type=1&limit=10&offset=0",
                        "https://music.163.com/");
                List<String> ids = new ArrayList<>();
                List<LyricsPicker.Candidate> metas = parseSearch(body, ids);
                for (int i : LyricsPicker.shortlist(metas, clean, artist, durationMs, 3)) {
                    String lrc = parseLyric(WebText.get(base + "song/lyric?id=" + ids.get(i) + "&lv=1&kv=1&tv=-1",
                            "https://music.163.com/"));
                    if (lrc != null && LyricsPicker.plausible(lrc, durationMs)) {
                        return new LrclibFetcher.Result(lrc, "");
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
