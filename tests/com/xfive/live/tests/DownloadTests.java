package com.xfive.live.tests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xfive.live.download.AudioSniffer;
import com.xfive.live.download.DownloadJob;
import com.xfive.live.download.DownloadManager;
import com.xfive.live.download.FileNames;
import com.xfive.live.download.MiniJson;
import com.xfive.live.download.OnlineSearch;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Uji fitur download: bagian murni + server HTTP palsu di komputer sendiri. */
final class DownloadTests {
    private final BiConsumer<String, Boolean> check;

    DownloadTests(BiConsumer<String, Boolean> check) {
        this.check = check;
    }

    private void ok(String n, boolean b) {
        check.accept(n, b);
    }

    static byte[] mp3(int size) {
        byte[] b = new byte[size];
        b[0] = 'I';
        b[1] = 'D';
        b[2] = '3';
        for (int i = 3; i < size; i++) {
            b[i] = (byte) (i * 31);
        }
        return b;
    }

    static byte[] ogg(int size) {
        byte[] b = mp3(size);
        b[0] = 'O';
        b[1] = 'g';
        b[2] = 'g';
        b[3] = 'S';
        return b;
    }

    void run(Path work) throws Exception {
        pure();
        server(work);
    }

    // ---------------------------------------------------------------
    void pure() throws Exception {
        Object j = MiniJson.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\\\"\",true,null],\"b\":{\"c\":\"d\"}, \"e\":-3e2}");
        Map<String, Object> m = MiniJson.asMap(j);
        ok("json map", m != null && m.size() == 3);
        List<Object> a = MiniJson.asList(m.get("a"));
        ok("json list", a.size() == 5 && a.get(0).equals(1.0) && a.get(1).equals(2.5));
        ok("json escapes", "x\n\u00e9\"".equals(a.get(2)) && a.get(3) == Boolean.TRUE && a.get(4) == null);
        ok("json nested + exp", "d".equals(MiniJson.asMap(m.get("b")).get("c")) && m.get("e").equals(-300.0));
        ok("json str helpers", "5".equals(MiniJson.str(5.0)) && "q".equals(MiniJson.str(List.of("q", "r")))
                && MiniJson.num("123", 0) == 123 && MiniJson.num("zz", 7) == 7 && MiniJson.num(null, 9) == 9);
        for (String bad : new String[] {"", "{", "[1,", "{\"a\"}", "{\"a\":}", "tru", "\"abc", "{} x", "[1 2]", "{\"a\":\"\\q\"}"}) {
            boolean threw = false;
            try {
                MiniJson.parse(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            ok("json rejects [" + bad + "]", threw);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            deep.append('[');
        }
        boolean deepThrew = false;
        try {
            MiniJson.parse(deep.toString());
        } catch (IllegalArgumentException e) {
            deepThrew = true;
        }
        ok("json depth limit", deepThrew);

        ok("sniff mp3 id3", "mp3".equals(AudioSniffer.sniff(mp3(64), 64)));
        byte[] frame = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0, 0, 0};
        ok("sniff mp3 frame", "mp3".equals(AudioSniffer.sniff(frame, 6)));
        ok("sniff ogg", "ogg".equals(AudioSniffer.sniff(ogg(64), 64)));
        byte[] wav = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
        ok("sniff wav", "wav".equals(AudioSniffer.sniff(wav, wav.length)));
        byte[] m4a = "\0\0\0\u0020ftypM4A \0\0".getBytes(StandardCharsets.ISO_8859_1);
        ok("sniff m4a", "m4a".equals(AudioSniffer.sniff(m4a, m4a.length)));
        byte[] html = "<!DOCTYPE html><html>".getBytes(StandardCharsets.ISO_8859_1);
        ok("sniff html not audio", AudioSniffer.sniff(html, html.length) == null && AudioSniffer.looksLikeHtml(html, html.length));
        ok("sniff too short", AudioSniffer.sniff(new byte[] {1, 2}, 2) == null && AudioSniffer.sniff(null, 0) == null);

        ok("ext of", "mp3".equals(FileNames.extensionOf("A.B.MP3")) && "".equals(FileNames.extensionOf("abc")));
        ok("ext from content type", "mp3".equals(FileNames.extensionFromContentType("audio/mpeg; charset=x"))
                && "ogg".equals(FileNames.extensionFromContentType("application/ogg"))
                && FileNames.extensionFromContentType("text/html") == null);
        ok("html content type", FileNames.isHtmlContentType("text/html; charset=utf-8") && !FileNames.isHtmlContentType("audio/mpeg"));
        ok("cd quoted", "My Song.mp3".equals(FileNames.fromContentDisposition("attachment; filename=\"My Song.mp3\"")));
        ok("cd star", "Lagu \u00e9.mp3".equals(FileNames.fromContentDisposition("attachment; filename*=UTF-8''Lagu%20%C3%A9.mp3")));
        ok("cd plain", "a.mp3".equals(FileNames.fromContentDisposition("attachment; filename=a.mp3; size=3")));
        ok("cd none", FileNames.fromContentDisposition("inline") == null && FileNames.fromContentDisposition(null) == null);
        ok("url name", "My Song.mp3".equals(FileNames.fromUrl("https://x.com/a/My%20Song.mp3?x=1"))
                && FileNames.fromUrl("https://x.com/") == null && FileNames.fromUrl("https://x.com") == null);
        ok("sanitize basic", "a b.mp3".equals(FileNames.sanitize("a:b?.MP3", "mp3")) || "a b.mp3".equals(FileNames.sanitize("a b.mp3", "mp3")));
        ok("sanitize no double ext", "Song.mp3".equals(FileNames.sanitize("Song.mp3", "mp3")));
        ok("sanitize path traversal", !FileNames.sanitize("../../etc/passwd", "mp3").contains("/")
                && !FileNames.sanitize("..\\..\\x", "mp3").contains("\\") && FileNames.sanitize("..", "mp3").equals("song.mp3"));
        ok("sanitize empty", "song.mp3".equals(FileNames.sanitize("   ", "mp3")) && "song.ogg".equals(FileNames.sanitize(null, "ogg")));
        ok("sanitize control chars", !FileNames.sanitize("a\u0000b\nc", "mp3").matches(".*[\\x00-\\x1f].*"));
        ok("sanitize long", FileNames.sanitize("x".repeat(500), "mp3").length() <= 104);
        ok("sanitize dots", "name.mp3".equals(FileNames.sanitize("...name...", "mp3")));
        ok("http url", FileNames.isHttpUrl("https://a.com/x.mp3") && FileNames.isHttpUrl(" http://a.com/x ")
                && !FileNames.isHttpUrl("ftp://a.com/x") && !FileNames.isHttpUrl("hello world")
                && !FileNames.isHttpUrl("https://") && !FileNames.isHttpUrl("https://a b.com") && !FileNames.isHttpUrl(null)
                && !FileNames.isHttpUrl("file:///etc/passwd") && !FileNames.isHttpUrl("javascript:alert(1)"));

        ok("clean query", "ab c".equals(OnlineSearch.cleanQuery("(ab) AND:\"c\"").replace("AND", "").replaceAll("\\s+", " ").strip())
                || OnlineSearch.cleanQuery("a+b:c").equals("a b c"));
        ok("clean query empty", OnlineSearch.cleanQuery("()!!").isEmpty() && OnlineSearch.cleanQuery(null).isEmpty());
        ok("search url", OnlineSearch.searchUrl("jazz piano", 10).startsWith("https://archive.org/advancedsearch.php?q=")
                && OnlineSearch.searchUrl("jazz piano", 10).contains("mediatype%3Aaudio")
                && OnlineSearch.searchUrl("jazz piano", 10).contains("rows=10"));
        ok("download url encode", "https://archive.org/download/id1/dir/My%20Song%20%231.mp3"
                .equals(OnlineSearch.downloadUrl("id1", "dir/My Song #1.mp3")));

        String sj = "{\"response\":{\"numFound\":3,\"docs\":["
                + "{\"identifier\":\"abc\",\"title\":\"Title A\",\"creator\":\"Artist\",\"downloads\":500},"
                + "{\"identifier\":\"def\",\"creator\":[\"First\",\"Second\"],\"downloads\":\"7\"},"
                + "{\"title\":\"no id\"}]}}";
        List<OnlineSearch.Result> rs = OnlineSearch.parseSearch(sj);
        ok("parse search count", rs.size() == 2);
        ok("parse search fields", rs.get(0).title().equals("Title A") && rs.get(0).creator().equals("Artist") && rs.get(0).downloads() == 500);
        ok("parse search fallbacks", rs.get(1).title().equals("def") && rs.get(1).creator().equals("First") && rs.get(1).downloads() == 7);
        ok("parse search empty", OnlineSearch.parseSearch("{\"response\":{\"docs\":[]}}").isEmpty());
        boolean t1 = false;
        try {
            OnlineSearch.parseSearch("<html>");
        } catch (IOException e) {
            t1 = true;
        }
        boolean t2 = false;
        try {
            OnlineSearch.parseSearch("{\"x\":1}");
        } catch (IOException e) {
            t2 = true;
        }
        ok("parse search bad json rejected", t1 && t2);

        String mj = "{\"files\":["
                + "{\"name\":\"cover.jpg\",\"format\":\"JPEG\",\"size\":\"1000\"},"
                + "{\"name\":\"t1.flac\",\"format\":\"Flac\",\"size\":\"9999999\"},"
                + "{\"name\":\"t1.mp3\",\"format\":\"VBR MP3\",\"size\":\"4000000\"},"
                + "{\"name\":\"sub/t2.mp3\",\"format\":\"128Kbps MP3\",\"size\":\"3000000\"},"
                + "{\"name\":\"t1.ogg\",\"format\":\"Ogg Vorbis\",\"size\":\"3500000\"},"
                + "{\"name\":\"tiny.mp3\",\"size\":\"100\"},"
                + "{\"name\":\"huge.mp3\",\"size\":\"999999999999\"},"
                + "{\"name\":\"../evil.mp3\",\"size\":\"500000\"},"
                + "{\"name\":\"nosize.mp3\"}]}";
        List<OnlineSearch.Track> ts = OnlineSearch.pickTracks("id", mj, 200L * 1024 * 1024);
        ok("pick prefers mp3 only", ts.size() == 3 && ts.stream().allMatch(t -> t.name().endsWith(".mp3")));
        ok("pick drops tiny/huge/evil", ts.stream().noneMatch(t -> t.name().contains("tiny") || t.name().contains("huge") || t.name().contains("..")));
        ok("pick url", ts.get(1).url().equals("https://archive.org/download/id/sub/t2.mp3"));
        List<OnlineSearch.Track> og = OnlineSearch.pickTracks("id", "{\"files\":[{\"name\":\"a.ogg\",\"size\":\"900000\"},{\"name\":\"b.wav\",\"size\":\"900000\"}]}", 1L << 30);
        ok("pick falls back to ogg", og.size() == 1 && og.get(0).name().equals("a.ogg"));
        StringBuilder many = new StringBuilder("{\"files\":[");
        for (int i = 0; i < 40; i++) {
            many.append(i > 0 ? "," : "").append("{\"name\":\"t").append(i).append(".mp3\",\"size\":\"900000\"}");
        }
        many.append("]}");
        ok("pick capped", OnlineSearch.pickTracks("id", many.toString(), 1L << 30).size() == OnlineSearch.MAX_TRACKS);
        ok("pick none", OnlineSearch.pickTracks("id", "{\"files\":[{\"name\":\"a.jpg\"}]}", 1L << 30).isEmpty());
    }

    // ---------------------------------------------------------------
    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        if (type != null) {
            ex.getResponseHeaders().add("Content-Type", type);
        }
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        }
        ex.close();
    }

    private static void waitFor(DownloadJob j, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!j.finished() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }

    void server(Path work) throws Exception {
        Files.createDirectories(work);
        AtomicInteger flaky = new AtomicInteger();
        AtomicInteger slowStopped = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/ok.mp3", ex -> send(ex, 200, "audio/mpeg", mp3(20_000)));
        s.createContext("/redir", ex -> {
            ex.getResponseHeaders().add("Location", "/ok.mp3");
            send(ex, 302, null, new byte[0]);
        });
        s.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            send(ex, 302, null, new byte[0]);
        });
        s.createContext("/page", ex -> send(ex, 200, "text/html", "<html>nope</html>".getBytes(StandardCharsets.UTF_8)));
        s.createContext("/disguised.mp3", ex -> send(ex, 200, "audio/mpeg",
                ("<!DOCTYPE html><html>" + "x".repeat(5000) + "</html>").getBytes(StandardCharsets.UTF_8)));
        s.createContext("/garbage.mp3", ex -> send(ex, 200, "audio/mpeg", new byte[5000]));
        s.createContext("/nf", ex -> send(ex, 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8)));
        s.createContext("/flaky.mp3", ex -> {
            if (flaky.incrementAndGet() == 1) {
                send(ex, 503, "text/plain", "busy".getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 200, "audio/mpeg", mp3(9000));
            }
        });
        s.createContext("/cd", ex -> {
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"../../Evil: Name?.mp3\"");
            send(ex, 200, "audio/mpeg", mp3(9000));
        });
        s.createContext("/chunked", ex -> {
            ex.getResponseHeaders().add("Content-Type", "audio/ogg");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(ogg(30_000));
            }
        });
        s.createContext("/big.mp3", ex -> {
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, 5_000_000);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(mp3(5_000_000));
            } catch (IOException ignored) {
                // klien menutup koneksi
            }
        });
        s.createContext("/bigchunked.mp3", ex -> {
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(mp3(1_500_000));
            } catch (IOException ignored) {
                // klien menutup koneksi
            }
        });
        s.createContext("/slow.mp3", ex -> {
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, 900_000);
            try (OutputStream o = ex.getResponseBody()) {
                byte[] b = mp3(900_000);
                for (int i = 0; i < 900_000; i += 20_000) {
                    o.write(b, i, 20_000);
                    o.flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException ignored) {
                slowStopped.incrementAndGet();
            }
        });
        java.net.ServerSocket raw = new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
        Thread rawT = new Thread(() -> {
            while (!raw.isClosed()) {
                try (java.net.Socket c = raw.accept()) {
                    c.getInputStream().read(new byte[4096]);
                    OutputStream o = c.getOutputStream();
                    o.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 20000\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    o.write(mp3(5_000));
                    o.flush();
                } catch (IOException ignored) {
                    // selesai
                }
            }
        }, "raw");
        rawT.setDaemon(true);
        rawT.start();
        s.start();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        Path dir = work.resolve("music");
        List<DownloadJob> saved = new CopyOnWriteArrayList<>();
        DownloadManager dm = new DownloadManager(() -> dir, 1_000_000, 2, 1, 50, saved::add);
        try {
            DownloadJob a = dm.enqueueUrl(base + "/ok.mp3", null);
            waitFor(a, 5000);
            ok("dl ok state", a.state == DownloadJob.State.DONE);
            ok("dl ok file", Files.isRegularFile(dir.resolve("ok.mp3")) && Files.size(dir.resolve("ok.mp3")) == 20_000);
            ok("dl ok content", java.util.Arrays.equals(Files.readAllBytes(dir.resolve("ok.mp3")), mp3(20_000)));
            ok("dl ok callback", saved.contains(a));
            ok("dl ok no .part left", !Files.exists(dir.resolve("ok.mp3.part")));
            ok("dl ok progress 1", a.progress() == 1f);

            DownloadJob a2 = dm.enqueueUrl(base + "/ok.mp3", null);
            waitFor(a2, 5000);
            ok("dl same name not overwritten", a2.state == DownloadJob.State.DONE
                    && Files.exists(dir.resolve("ok (2).mp3")) && Files.exists(dir.resolve("ok.mp3")));

            DownloadJob r = dm.enqueueUrl(base + "/redir", "Redirected");
            waitFor(r, 5000);
            ok("dl redirect followed", r.state == DownloadJob.State.DONE && r.result != null
                    && r.result.getFileName().toString().startsWith("ok"));

            DownloadJob lp = dm.enqueueUrl(base + "/loop", null);
            waitFor(lp, 5000);
            ok("dl redirect loop fails", lp.state == DownloadJob.State.FAILED && lp.message.contains("redirect"));

            DownloadJob pg = dm.enqueueUrl(base + "/page", null);
            waitFor(pg, 5000);
            ok("dl html rejected", pg.state == DownloadJob.State.FAILED && pg.message.contains("web page"));

            DownloadJob dg = dm.enqueueUrl(base + "/disguised.mp3", null);
            waitFor(dg, 5000);
            ok("dl disguised html rejected", dg.state == DownloadJob.State.FAILED && dg.message.contains("web page")
                    && !Files.exists(dir.resolve("disguised.mp3")));

            DownloadJob gb = dm.enqueueUrl(base + "/garbage.mp3", null);
            waitFor(gb, 5000);
            ok("dl garbage rejected", gb.state == DownloadJob.State.FAILED && !Files.exists(dir.resolve("garbage.mp3")));

            DownloadJob nf = dm.enqueueUrl(base + "/nf", null);
            waitFor(nf, 5000);
            ok("dl 404 message", nf.state == DownloadJob.State.FAILED && nf.message.contains("404"));

            DownloadJob fl = dm.enqueueUrl(base + "/flaky.mp3", null);
            waitFor(fl, 8000);
            ok("dl retries after 503", fl.state == DownloadJob.State.DONE && flaky.get() == 2);

            DownloadJob cd = dm.enqueueUrl(base + "/cd", null);
            waitFor(cd, 5000);
            ok("dl content-disposition sanitized", cd.state == DownloadJob.State.DONE
                    && cd.result.getParent().equals(dir) && !cd.result.getFileName().toString().matches(".*[:?/\\\\].*"));

            DownloadJob ch = dm.enqueueUrl(base + "/chunked", "Chunked Track");
            waitFor(ch, 5000);
            ok("dl chunked ogg named from title", ch.state == DownloadJob.State.DONE
                    && ch.result.getFileName().toString().endsWith(".ogg") && Files.size(ch.result) == 30_000);

            DownloadJob bg = dm.enqueueUrl(base + "/big.mp3", null);
            waitFor(bg, 5000);
            ok("dl size limit by header", bg.state == DownloadJob.State.FAILED && bg.message.contains("big")
                    && !Files.exists(dir.resolve("big.mp3")));

            DownloadJob bc = dm.enqueueUrl(base + "/bigchunked.mp3", null);
            waitFor(bc, 5000);
            ok("dl size limit while streaming", bc.state == DownloadJob.State.FAILED
                    && !Files.exists(dir.resolve("bigchunked.mp3")) && !Files.exists(dir.resolve("bigchunked.mp3.part")));

            DownloadJob ct = dm.enqueueUrl("http://127.0.0.1:" + raw.getLocalPort() + "/cut.mp3", null);
            waitFor(ct, 8000);
            ok("dl cut off detected", ct.state == DownloadJob.State.FAILED && !Files.exists(dir.resolve("cut.mp3"))
                    && !Files.exists(dir.resolve("cut.mp3.part")));

            Path blocker = work.resolve("iamafile");
            Files.writeString(blocker, "x");
            DownloadManager dm2 = new DownloadManager(() -> blocker.resolve("sub"), 1_000_000, 1, 1, 50, j -> { });
            try {
                DownloadJob nw = dm2.enqueueUrl(base + "/ok.mp3", null);
                waitFor(nw, 5000);
                ok("dl unwritable folder explained", nw.state == DownloadJob.State.FAILED && nw.message.contains("Cannot save"));
            } finally {
                dm2.shutdown();
            }

            DownloadJob bad = dm.enqueueUrl("ftp://x/y.mp3", null);
            waitFor(bad, 5000);
            ok("dl non-http rejected", bad.state == DownloadJob.State.FAILED);

            DownloadJob dead = dm.enqueueUrl("http://127.0.0.1:1/none.mp3", null);
            waitFor(dead, 15000);
            ok("dl connection refused fails cleanly", dead.state == DownloadJob.State.FAILED);

            DownloadJob sl = dm.enqueueUrl(base + "/slow.mp3", null);
            long t0 = System.currentTimeMillis();
            while (sl.state != DownloadJob.State.RUNNING || sl.done < 10_000) {
                Thread.sleep(10);
                if (System.currentTimeMillis() - t0 > 5000) {
                    break;
                }
            }
            ok("dl progress while running", sl.done > 0 && sl.progress() > 0f && sl.progress() < 1f);
            dm.cancel(sl);
            waitFor(sl, 5000);
            ok("dl cancel state", sl.state == DownloadJob.State.CANCELLED);
            ok("dl cancel removes .part", !Files.exists(dir.resolve("slow.mp3.part")) && !Files.exists(dir.resolve("slow.mp3")));

            long before = dm.jobs().size();
            dm.retry(nf);
            ok("retry replaces job", dm.jobs().size() == before && !dm.jobs().contains(nf));
            dm.clearFinished();
            Thread.sleep(300);
            dm.clearFinished();
            ok("clear finished", dm.jobs().stream().allMatch(j -> !j.finished()));

            int v = dm.version();
            DownloadJob dup1 = dm.enqueueUrl(base + "/slow.mp3", null);
            DownloadJob dup2 = dm.enqueueUrl(base + "/slow.mp3", null);
            ok("duplicate running link reused", dup1 == dup2 && dm.version() > v);
            dm.cancel(dup1);
            waitFor(dup1, 5000);

            long leftovers;
            try (var st = Files.list(dir)) {
                leftovers = st.filter(p -> p.getFileName().toString().endsWith(".part")).count();
            }
            ok("no .part files left behind", leftovers == 0);
            ok("busy flag", !dm.busy() || dm.jobs().stream().anyMatch(j -> !j.finished()));
        } finally {
            dm.shutdown();
            raw.close();
            s.stop(0);
        }
    }
}
