package com.xfive.live.download;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pencarian musik bebas/legal di Internet Archive (archive.org). */
public final class OnlineSearch {
    public static final int MAX_TRACKS = 12;
    private static final long MIN_TRACK_BYTES = 50_000;

    private OnlineSearch() {
    }

    public record Result(String identifier, String title, String creator, long downloads) {
    }

    public record Track(String name, String url, long size, String format, String title, String artist) {
        /** Judul untuk ditampilkan: judul dari metadata, atau nama file tanpa ekstensi dan nomor urut. */
        public String displayTitle() {
            if (title != null && !title.isBlank()) {
                return title.strip();
            }
            String n = name.substring(name.lastIndexOf('/') + 1);
            String ext = FileNames.extensionOf(n);
            if (!ext.isEmpty()) {
                n = n.substring(0, n.length() - ext.length() - 1);
            }
            n = n.replace('_', ' ').replaceAll("^(\\d{1,2}\\s*[.\\-_)]+\\s*|0\\d\\s+)", "").replaceAll("\\s+", " ").strip();
            return n.isEmpty() ? name : n;
        }
    }

    /** Membuang karakter khusus pencarian Lucene agar permintaan tidak error. */
    public static String cleanQuery(String q) {
        if (q == null) {
            return "";
        }
        return q.replaceAll("[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]", " ").replaceAll("\\s+", " ").strip();
    }

    public static String searchUrl(String query, int rows) {
        String q = "(" + cleanQuery(query) + ") AND mediatype:audio";
        return "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&fl=identifier,title,creator,downloads&rows=" + rows
                + "&sort=" + URLEncoder.encode("downloads desc", StandardCharsets.UTF_8) + "&output=json";
    }

    public static String metadataUrl(String identifier) {
        return "https://archive.org/metadata/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    }

    public static String downloadUrl(String identifier, String fileName) {
        StringBuilder b = new StringBuilder("https://archive.org/download/")
                .append(URLEncoder.encode(identifier, StandardCharsets.UTF_8).replace("+", "%20"));
        for (String seg : fileName.split("/")) {
            b.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.toString();
    }

    public static List<Result> search(String query, int rows) throws IOException {
        if (cleanQuery(query).isEmpty()) {
            throw new DownloadException("Type a song or artist name", false);
        }
        return parseSearch(Http.getText(searchUrl(query, rows), 2_000_000));
    }

    public static List<Track> tracks(String identifier, long maxBytes) throws IOException {
        return pickTracks(identifier, Http.getText(metadataUrl(identifier), 8_000_000), maxBytes);
    }

    public static List<Result> parseSearch(String json) throws IOException {
        List<Result> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            Map<String, Object> resp = root == null ? null : MiniJson.asMap(root.get("response"));
            List<Object> docs = resp == null ? null : MiniJson.asList(resp.get("docs"));
            if (docs == null) {
                throw new DownloadException("Search answer was not understood", false);
            }
            for (Object o : docs) {
                Map<String, Object> d = MiniJson.asMap(o);
                if (d == null) {
                    continue;
                }
                String id = MiniJson.str(d.get("identifier"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                String title = MiniJson.str(d.get("title"));
                String creator = MiniJson.str(d.get("creator"));
                out.add(new Result(id, title == null || title.isBlank() ? id : title.strip(),
                        creator == null ? "" : creator.strip(), MiniJson.num(d.get("downloads"), 0)));
            }
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Search answer was not understood", false);
        }
        return out;
    }

    /** Memilih file audio dari metadata: MP3 diutamakan, lalu Ogg, lalu sisanya; maksimal MAX_TRACKS. */
    public static List<Track> pickTracks(String identifier, String json, long maxBytes) throws IOException {
        List<Track> mp3 = new ArrayList<>();
        List<Track> ogg = new ArrayList<>();
        List<Track> other = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.asMap(MiniJson.parse(json));
            List<Object> files = root == null ? null : MiniJson.asList(root.get("files"));
            if (files == null) {
                throw new DownloadException("Nothing to download here", false);
            }
            for (Object o : files) {
                Map<String, Object> f = MiniJson.asMap(o);
                if (f == null) {
                    continue;
                }
                String name = MiniJson.str(f.get("name"));
                if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/")) {
                    continue;
                }
                String ext = FileNames.extensionOf(name);
                if (!FileNames.EXTENSIONS.contains(ext)) {
                    continue;
                }
                long size = MiniJson.num(f.get("size"), -1);
                if (size >= 0 && (size < MIN_TRACK_BYTES || size > maxBytes)) {
                    continue;
                }
                Track t = new Track(name, downloadUrl(identifier, name), size, MiniJson.str(f.get("format")),
                        MiniJson.str(f.get("title")), MiniJson.str(f.get("artist")));
                switch (ext) {
                    case "mp3": mp3.add(t); break;
                    case "ogg": ogg.add(t); break;
                    default: other.add(t); break;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Item answer was not understood", false);
        }
        List<Track> pick = !mp3.isEmpty() ? mp3 : !ogg.isEmpty() ? ogg : other;
        return pick.size() <= MAX_TRACKS ? pick : new ArrayList<>(pick.subList(0, MAX_TRACKS));
    }
}
