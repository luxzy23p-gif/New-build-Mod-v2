package com.xfive.live.tests;

import com.xfive.live.config.Themes;
import com.xfive.live.config.XFiveConfig;
import com.xfive.live.lyrics.LrcParser;
import com.xfive.live.lyrics.LyricLine;
import com.xfive.live.lyrics.LyricsManager;
import com.xfive.live.music.PlaylistManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Uji unit untuk logika murni (tanpa Minecraft). Jalankan: java -ea ... XFiveTests <folder kerja> */
public final class XFiveTests {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Files.createDirectories(work);
        testLrc();
        testLyricsIndex();
        testPlaylist(work.resolve("playlist"));
        testConfig(work.resolve("config"));
        testThemes();
        testLrclib();
        testLyricStyle(work.resolve("lyricstyle"));
        testSpecSheet();
        testDeviceInfo();
        new DownloadTests(XFiveTests::check).run(work.resolve("download"));
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testLrc() {
        String lrc = "[ti:Demo]\n[ar:Tester]\n[offset:500]\n[00:01.50]Hello\n"
                + "[00:03.250][00:05]World\n\n[00:02.5]Mid";
        List<LyricLine> lines = LrcParser.parse(lrc);
        check("lrc jumlah baris = 4 (World dua timestamp)", lines.size() == 4);
        check("lrc urut waktu", lines.size() == 4 && lines.get(0).timeMs() <= lines.get(1).timeMs()
                && lines.get(1).timeMs() <= lines.get(2).timeMs());
        check("lrc Hello di 2000ms (1500 + offset 500)", lines.get(0).timeMs() == 2000 && lines.get(0).text().equals("Hello"));
        check("lrc Mid di 3000ms (format .x + offset)", lines.get(1).timeMs() == 3000 && lines.get(1).text().equals("Mid"));
        check("lrc World 3750ms (.250 = 250ms)", lines.get(2).timeMs() == 3750 && lines.get(2).text().equals("World"));
        check("lrc World 5500ms (tanpa fraksi)", lines.get(3).timeMs() == 5500);
        check("lrc tag metadata diabaikan", LrcParser.parse("[ar:X]\n[ti:Y]").isEmpty());
        check("lrc kosong / null aman", LrcParser.parse("").isEmpty() && LrcParser.parse(null).isEmpty());
        check("lrc offset negatif tidak di bawah 0",
                LrcParser.parse("[offset:-5000]\n[00:01.00]X").get(0).timeMs() == 0L);
        check("lrc format .xx = centisecond", LrcParser.parse("[00:00.07]A").get(0).timeMs() == 70L);
    }

    static void testLyricsIndex() {
        LyricsManager lm = new LyricsManager();
        lm.load("[00:02.00]Hello\n[00:03.00]Mid\n[00:03.75]World\n[00:05.50]World2");
        check("indeks sebelum baris pertama = -1", lm.indexAt(0) == -1);
        check("indeks tepat di 2000ms = 0", lm.indexAt(2000) == 0);
        check("indeks 2999ms masih 0", lm.indexAt(2999) == 0);
        check("indeks 3000ms = 1", lm.indexAt(3000) == 1);
        check("indeks di akhir = 3", lm.indexAt(999_999) == 3);
        check("textAt(2) = World", lm.textAt(2).equals("World"));
        check("textAt di luar jangkauan = kosong", lm.textAt(99).isEmpty());
        lm.clear();
        check("clear membuat kosong", lm.isEmpty());
        check("loadFile file tidak ada = false", !lm.loadFile(Path.of("tidak-ada-file-xyz.lrc")));
    }

    static void testPlaylist(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Adele - Hello.mp3"), "x");
        Files.writeString(dir.resolve("plain.wav"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve("Band - Song - Live.ogg"), "x");

        PlaylistManager pm = new PlaylistManager();
        check("scan hanya format didukung (3 file)", pm.scan(dir) == 3);
        check("parse artis dan judul", pm.tracks().get(0).artist().equals("Adele")
                && pm.tracks().get(0).title().equals("Hello"));
        check("judul boleh mengandung ' - '", pm.tracks().get(1).artist().equals("Band")
                && pm.tracks().get(1).title().equals("Song - Live"));
        check("file tanpa separator = Unknown Artist", pm.tracks().get(2).artist().equals("Unknown Artist")
                && pm.tracks().get(2).title().equals("plain"));
        check("indeks awal 0", pm.currentIndex() == 0);
        check("next 0->1", pm.next() != null && pm.currentIndex() == 1);
        check("next 1->2", pm.next() != null && pm.currentIndex() == 2);
        check("next wrap 2->0", pm.next() != null && pm.currentIndex() == 0);
        check("previous wrap 0->2", pm.previous() != null && pm.currentIndex() == 2);
        Path dl = Path.of("Why_Don_t_We_-_8_Letters__Lyrics_(1280).mp3");
        check("nama dengan garis bawah dibaca rapi", PlaylistManager.parse(dl).artist().equals("Why Don t We")
                && PlaylistManager.parse(dl).title().equals("8 Letters Lyrics"));
        check("tag resmi di ujung judul dibuang",
                PlaylistManager.parse(Path.of("Adele - Hello (Official Video).mp3")).title().equals("Hello"));
        check("tag biasa di tengah judul tetap",
                PlaylistManager.parse(Path.of("A - Song (Remix) Live.mp3")).title().equals("Song (Remix) Live"));
        check("m4a termasuk format yang dipindai", PlaylistManager.SUPPORTED_EXTENSIONS.contains(".m4a"));
        check("nama m4a dibaca artis dan judul", PlaylistManager.parse(Path.of("Dua Lipa - Levitating.m4a")).artist().equals("Dua Lipa")
                && PlaylistManager.parse(Path.of("Dua Lipa - Levitating.m4a")).title().equals("Levitating"));
        Files.writeString(dir.resolve("Extra - Aac.m4a"), "x");
        check("scan menemukan file m4a (4 lagu)", new PlaylistManager().scan(dir) == 4);
        Files.deleteIfExists(dir.resolve("Extra - Aac.m4a"));
        check("folder tidak ada = 0 lagu", new PlaylistManager().scan(dir.resolve("nope")) == 0);
        testScanSources(dir.resolveSibling("sources"));
    }

    static void testScanSources(Path base) throws Exception {
        Path folder = base.resolve("src-folder");
        Path other = base.resolve("other");
        Files.createDirectories(folder);
        Files.createDirectories(other);
        Files.writeString(folder.resolve("Artist - Track1.mp3"), "x");
        Files.writeString(other.resolve("Solo.wav"), "x");
        PlaylistManager pm = new PlaylistManager();
        int n = pm.scanSources(List.of(folder, other.resolve("Solo.wav"), base.resolve("tidak-ada"), folder));
        check("scanSources gabung folder + file tanpa duplikat (2 lagu)", n == 2);
        check("scanSources membaca artis dari folder", pm.tracks().get(0).artist().equals("Artist"));
        check("scanSources melewati sumber yang tidak ada", pm.size() == 2);
    }

    static void testThemes() {
        check("tema tidak dikenal jadi purple", Themes.normalize("xyz").equals("purple") && Themes.normalize(null).equals("purple"));
        check("tema rose dikenal", Themes.normalize("rose").equals("rose") && Themes.accent("rose") == 0xFF5C8A);
        check("tema berputar kembali ke awal", Themes.next(Themes.IDS[Themes.IDS.length - 1]).equals("purple"));
        check("jumlah id, nama, warna sama", Themes.IDS.length == Themes.NAMES.length && Themes.IDS.length == Themes.COLORS.length);
    }

    static void testConfig(Path dir) throws Exception {
        Files.createDirectories(dir);
        XFiveConfig a = new XFiveConfig(dir);
        a.hudScale = 1.5f;
        a.lyricsFontSize = 14;
        a.theme = "cyan";
        a.themeStyle = "dark_music";
        a.musicFolder = "/music";
        a.hudPositions.get("musicHud")[1] = 77;
        a.islandW = 300;
        a.islandH = 100;
        a.islandColor = 3;
        a.islandShowPaused = true;
        a.islandCard = false;
        a.islandAutoHide = false;
        a.islandShowSec = 9;
        a.repeatMode = 2;
        a.shuffle = true;
        a.save();

        XFiveConfig b = new XFiveConfig(dir);
        b.load();
        check("config hudScale tersimpan", b.hudScale == 1.5f);
        check("config fontSize tersimpan", b.lyricsFontSize == 14);
        check("config theme tersimpan", b.theme.equals("cyan"));
        check("config UI style tersimpan", b.themeStyle.equals("dark_music"));
        check("config folder tersimpan", b.musicFolder.equals("/music"));
        check("config posisi HUD tersimpan", b.hudPositions.get("musicHud")[1] == 77);
        check("config ukuran dan warna island tersimpan", b.islandW == 300 && b.islandH == 100 && b.islandColor == 3);
        check("config island jeda, kartu, ulang, acak tersimpan",
                b.islandShowPaused && !b.islandCard && b.repeatMode == 2 && b.shuffle
                        && !b.islandAutoHide && b.islandShowSec == 9);

        Files.writeString(dir.resolve(XFiveConfig.FILE_NAME), "general.hudScale=99\nlyrics.fontSize=-3\n");
        XFiveConfig c = new XFiveConfig(dir);
        c.load();
        check("config nilai di atas batas dikunci ke 2.0", c.hudScale == 2.0f);
        check("config nilai di bawah batas dikunci ke 6", c.lyricsFontSize == 6);

        Files.writeString(dir.resolve(XFiveConfig.FILE_NAME), "lyrics.fontSize=abc\n");
        XFiveConfig e = new XFiveConfig(dir);
        e.load();
        check("config nilai rusak memakai default", e.lyricsFontSize == 10);
    }

    private static void testLrclib() {
        String one = "{\"id\":1,\"syncedLyrics\":\"[00:01.00] Hello\\n[00:05.50] World \\\"q\\\"\\u00e9\"}";
        String got = com.xfive.live.lyrics.LrclibFetcher.firstSynced(one);
        check("lrclib synced found", got != null && got.startsWith("[00:01.00] Hello\n[00:05.50]"));
        check("lrclib escapes", got != null && got.contains("\"q\"\u00e9"));
        String arr = "[{\"syncedLyrics\":null,\"plainLyrics\":\"x\"},{\"syncedLyrics\":\"[00:02.00] Ok\"}]";
        check("lrclib array skips null", "[00:02.00] Ok".equals(com.xfive.live.lyrics.LrclibFetcher.firstSynced(arr)));
        check("lrclib none", com.xfive.live.lyrics.LrclibFetcher.firstSynced("{\"syncedLyrics\":null}") == null);
        check("lrclib stem safe", !com.xfive.live.lyrics.LrclibFetcher.fileStem("A/B", "C:D?").matches(".*[/:?].*"));
        LyricsManager m = new LyricsManager();
        m.load(got);
        check("lrclib parses into lines", m.lines().size() == 2);
    }

    static void testLyricStyle(Path dir) throws Exception {
        check("anim indexOf default", com.xfive.live.lyrics.LyricStyle.indexOf("???") == 0);
        check("anim next wraps", com.xfive.live.lyrics.LyricStyle.next("none").equals("fade"));
        check("anim enter start 0", com.xfive.live.lyrics.LyricStyle.enter(0, true) == 0f);
        check("anim enter end 1", com.xfive.live.lyrics.LyricStyle.enter(1000, true) == 1f);
        check("anim off is 1", com.xfive.live.lyrics.LyricStyle.enter(0, false) == 1f);
        check("pop settles at 1", com.xfive.live.lyrics.LyricStyle.popScale(1f) == 1f);
        float p0 = com.xfive.live.lyrics.LyricStyle.popScale(0f);
        check("pop starts small", p0 >= 0.69f && p0 <= 0.8f);
        java.util.List<LyricLine> ls = java.util.List.of(new LyricLine(1000, "a"), new LyricLine(4000, "b"));
        check("line duration", com.xfive.live.lyrics.LyricStyle.lineDuration(ls, 0) == 3000);
        check("last line duration", com.xfive.live.lyrics.LyricStyle.lineDuration(ls, 1) == 4000);
        check("karaoke half", Math.abs(com.xfive.live.lyrics.LyricStyle.karaoke(1275, 3000) - 0.5f) < 0.01f);
        check("karaoke done", com.xfive.live.lyrics.LyricStyle.karaoke(9999, 3000) == 1f);
        check("typed none at 0", com.xfive.live.lyrics.LyricStyle.typedChars(10, 0, 3000) == 0);
        check("typed all later", com.xfive.live.lyrics.LyricStyle.typedChars(10, 5000, 3000) == 10);
        check("shifted floor", com.xfive.live.lyrics.LyricStyle.shifted(100, -500) == 0);
        check("anim ids/names same length", com.xfive.live.lyrics.LyricStyle.IDS.length == com.xfive.live.lyrics.LyricStyle.NAMES.length);
        check("anim wave known", "wave".equals(com.xfive.live.lyrics.LyricStyle.normalize("wave")));
        check("anim words known", "words".equals(com.xfive.live.lyrics.LyricStyle.normalize("words")));
        check("first words 2", com.xfive.live.lyrics.LyricStyle.firstWords("halo dunia yang indah", 2).equals("halo dunia"));
        check("first words 0", com.xfive.live.lyrics.LyricStyle.firstWords("halo dunia", 0).isEmpty());
        check("first words all", com.xfive.live.lyrics.LyricStyle.firstWords("halo dunia", 9).equals("halo dunia"));
        check("revealed words none", com.xfive.live.lyrics.LyricStyle.revealedWords(4, 0, 3000) == 0);
        check("revealed words all", com.xfive.live.lyrics.LyricStyle.revealedWords(4, 9000, 3000) == 4);
        check("wave char start", com.xfive.live.lyrics.LyricStyle.waveChar(0, 10, 0) == 0f);
        check("wave char later letter waits", com.xfive.live.lyrics.LyricStyle.waveChar(9, 10, 100) == 0f);
        check("wave char done", com.xfive.live.lyrics.LyricStyle.waveChar(9, 10, 5000) == 1f);
        check("wave total positive", com.xfive.live.lyrics.LyricStyle.waveTotalMs(10) > 260);
        java.util.List<com.xfive.live.lyrics.LyricLine> gap = java.util.List.of(
                new com.xfive.live.lyrics.LyricLine(8000, "a"),
                new com.xfive.live.lyrics.LyricLine(30000, "b"),
                new com.xfive.live.lyrics.LyricLine(33000, "c"));
        check("countdown intro", com.xfive.live.lyrics.LyricStyle.countdown(gap, -1, 4000) == 0.5f);
        check("countdown holds line first", com.xfive.live.lyrics.LyricStyle.countdown(gap, 0, 10000) < 0f);
        check("countdown in gap", com.xfive.live.lyrics.LyricStyle.countdown(gap, 0, 21000) > 0.4f
                && com.xfive.live.lyrics.LyricStyle.countdown(gap, 0, 21000) < 0.6f);
        check("countdown short gap none", com.xfive.live.lyrics.LyricStyle.countdown(gap, 1, 31500) < 0f);
        check("countdown last line none", com.xfive.live.lyrics.LyricStyle.countdown(gap, 2, 40000) < 0f);
        check("countdown short intro none", com.xfive.live.lyrics.LyricStyle.countdown(
                java.util.List.of(new com.xfive.live.lyrics.LyricLine(2000, "x")), -1, 500) < 0f);
        Files.createDirectories(dir);
        XFiveConfig a = new XFiveConfig(dir);
        a.lyricsAnim = "pop";
        a.lyricsKaraoke = false;
        a.lyricsPreview = false;
        a.lyricsOffsetMs = -250;
        a.save();
        XFiveConfig b = new XFiveConfig(dir);
        b.load();
        check("config lyric anim saved", b.lyricsAnim.equals("pop") && !b.lyricsKaraoke && !b.lyricsPreview
                && b.lyricsOffsetMs == -250);
        Files.writeString(dir.resolve(XFiveConfig.FILE_NAME), "lyrics.anim=zzz\nlyrics.offsetMs=99999\n");
        XFiveConfig c = new XFiveConfig(dir);
        c.load();
        check("config bad anim falls back", c.lyricsAnim.equals("fade"));
        check("config offset clamped", c.lyricsOffsetMs == 3000);
    }

    static void testSpecSheet() {
        check("spec title", com.xfive.live.config.SpecSheet.TITLE.equals("Infinix GT30 Pro"));
        check("spec rows", com.xfive.live.config.SpecSheet.ROWS.length == 4
                && com.xfive.live.config.SpecSheet.ROWS[0][1].equals("Dimensity 8350 Ultimate")
                && com.xfive.live.config.SpecSheet.ROWS[1][1].equals("8 GB / 256 GB")
                && com.xfive.live.config.SpecSheet.ROWS[2][1].equals("Mali")
                && com.xfive.live.config.SpecSheet.ROWS[3][1].equals("XOS 16"));
        check("spec outline start/end", com.xfive.live.config.SpecSheet.outline(0) == 0f
                && com.xfive.live.config.SpecSheet.outline(5) == 1f);
        check("spec phone settles", Math.abs(com.xfive.live.config.SpecSheet.phoneScale(5) - 1f) < 0.001f
                && Math.abs(com.xfive.live.config.SpecSheet.phoneTurn(5)) < 0.001f);
        check("spec phone starts small", com.xfive.live.config.SpecSheet.phoneScale(0) < 0.3f);
        check("spec chip later", com.xfive.live.config.SpecSheet.chip(0.2) < 0.001f && com.xfive.live.config.SpecSheet.chip(3) > 0.99f);
        check("spec pin done", com.xfive.live.config.SpecSheet.pin(5, 2) == 1f);
        check("spec bar done", com.xfive.live.config.SpecSheet.bar(5, 1) > 0.99f);
        check("spec heading hidden at 0", com.xfive.live.config.SpecSheet.headingState(0, 0) == 0);
        check("spec heading scrambles", com.xfive.live.config.SpecSheet.headingState(0, com.xfive.live.config.SpecSheet.HEADING_START + 0.05) == 1);
        check("spec heading settles", com.xfive.live.config.SpecSheet.headingState(0, 5) == 2);
        check("spec heading last later", com.xfive.live.config.SpecSheet.headingState(15, com.xfive.live.config.SpecSheet.HEADING_START + 0.2) == 0);
        check("spec heading done time", com.xfive.live.config.SpecSheet.headingDone() > com.xfive.live.config.SpecSheet.HEADING_START);
        check("spec glyph stable", com.xfive.live.config.SpecSheet.glyph(77) == com.xfive.live.config.SpecSheet.glyph(77));
        boolean glyphOk = true;
        for (int i = -50; i < 500; i++) { char c = com.xfive.live.config.SpecSheet.glyph(i); glyphOk &= c > 31 && c < 127; }
        check("spec glyph printable", glyphOk);
        check("spec row hidden", com.xfive.live.config.SpecSheet.rowIn(0, 0) == 0f);
        check("spec row staggered", com.xfive.live.config.SpecSheet.rowIn(3, 1.6) < com.xfive.live.config.SpecSheet.rowIn(0, 1.6));
        check("spec row done", com.xfive.live.config.SpecSheet.rowIn(3, 9) == 1f);
        check("spec value none then all", com.xfive.live.config.SpecSheet.valueChars(10, 0, 0) == 0
                && com.xfive.live.config.SpecSheet.valueChars(10, 0, 9) == 10);
        check("spec total after heading", com.xfive.live.config.SpecSheet.total() > 1.5);
    }

    static void testDeviceInfo() {
        var DI = com.xfive.live.config.DeviceInfo.class;
        String gp = "[ro.product.brand]: [INFINIX]\n[ro.product.marketing_name]: [Infinix GT 30 Pro]\n"
                + "[ro.product.model]: [X6877]\n[ro.soc.model]: [MT6897]\n[ro.soc.manufacturer]: [Mediatek]\n"
                + "[ro.build.version.release]: [16]\n[ro.hardware.egl]: [mali]\nnoise line\n[empty.key]: []\n";
        var props = com.xfive.live.config.DeviceInfo.parseGetprop(gp);
        check("getprop parsed", props.size() == 8 && "X6877".equals(props.get("ro.product.model")) && "".equals(props.get("empty.key")));
        check("build.prop parsed", com.xfive.live.config.DeviceInfo.parseBuildProp("# c\nro.a=1\n\nro.b = two \nbad").size() == 2);
        check("meminfo parsed", com.xfive.live.config.DeviceInfo.parseMemTotalKb("MemTotal:        7742172 kB\nMemFree: 1 kB") == 7742172L
                && com.xfive.live.config.DeviceInfo.parseMemTotalKb("nothing") == 0L);
        check("ram label rounds up", "8 GB".equals(com.xfive.live.config.DeviceInfo.ramLabel(7_742_172L))
                && "6 GB".equals(com.xfive.live.config.DeviceInfo.ramLabel(5_700_000L))
                && "12 GB".equals(com.xfive.live.config.DeviceInfo.ramLabel(11_600_000L))
                && "4 GB".equals(com.xfive.live.config.DeviceInfo.ramLabel(3_900_000L))
                && "?".equals(com.xfive.live.config.DeviceInfo.ramLabel(0)));
        check("rom label rounds up", "256 GB".equals(com.xfive.live.config.DeviceInfo.romLabel(240_000_000_000L))
                && "128 GB".equals(com.xfive.live.config.DeviceInfo.romLabel(113_000_000_000L))
                && "1 TB".equals(com.xfive.live.config.DeviceInfo.romLabel(900_000_000_000L))
                && "?".equals(com.xfive.live.config.DeviceInfo.romLabel(-1)));
        check("gpu from renderer", "Mali-G615 MC6".equals(com.xfive.live.config.DeviceInfo.gpuFromRenderer("Mali-G615 MC6"))
                && "Mali-G615 MC6".equals(com.xfive.live.config.DeviceInfo.gpuFromRenderer("Zink Vulkan 1.3 (Mali-G615 MC6)"))
                && "Adreno (TM) 740".equals(com.xfive.live.config.DeviceInfo.gpuFromRenderer("Adreno (TM) 740"))
                && com.xfive.live.config.DeviceInfo.gpuFromRenderer("llvmpipe (LLVM 15)") == null
                && com.xfive.live.config.DeviceInfo.gpuFromRenderer(null) == null);
        var r = com.xfive.live.config.DeviceInfo.build(props, 7_742_172L, 240_000_000_000L, "", "Linux", "6.1", "aarch64", 8);
        check("android build", r.android() && r.rows().length == 4 && "Infinix GT 30 Pro".equals(r.title()));
        check("android rows", "Chipset".equals(r.rows()[0][0]) && "Dimensity 8350 Ultimate".equals(r.rows()[0][1])
                && "8 GB / 256 GB".equals(r.rows()[1][1]) && "Mali".equals(r.rows()[2][1]) && "XOS 16".equals(r.rows()[3][1]));
        var r2 = com.xfive.live.config.DeviceInfo.build(props, 7_742_172L, 240_000_000_000L, "Mali-G615 MC6", "Linux", "6.1", "aarch64", 8);
        check("gpu prefers opengl name", "Mali-G615 MC6".equals(r2.rows()[2][1]));
        var other = new java.util.HashMap<String, String>();
        other.put("ro.product.brand", "samsung");
        other.put("ro.product.model", "SM-S928B");
        other.put("ro.soc.model", "SM8650");
        other.put("ro.build.version.release", "15");
        other.put("ro.hardware.egl", "adreno");
        var r3 = com.xfive.live.config.DeviceInfo.build(other, 11_900_000L, 480_000_000_000L, "", "Linux", "", "aarch64", 8);
        check("other android phone", "Samsung SM-S928B".equals(r3.title()) && "Snapdragon 8 Gen 3".equals(r3.rows()[0][1])
                && "12 GB / 512 GB".equals(r3.rows()[1][1]) && "Adreno".equals(r3.rows()[2][1]) && "Android 15".equals(r3.rows()[3][1]));
        var unknown = new java.util.HashMap<String, String>();
        unknown.put("ro.product.model", "Foo");
        unknown.put("ro.soc.model", "mt6999");
        var r4 = com.xfive.live.config.DeviceInfo.build(unknown, 0, 0, "", "Linux", "", "aarch64", 8);
        check("unknown parts say so", "MediaTek MT6999".equals(r4.rows()[0][1]) && "Unknown".equals(r4.rows()[2][1])
                && "Unknown".equals(r4.rows()[3][1]) && r4.rows()[1][1].equals("? / ?"));
        var pc = com.xfive.live.config.DeviceInfo.build(java.util.Map.of(), 16_000_000L, 500_000_000_000L,
                "NVIDIA GeForce RTX 3060/PCIe/SSE2", "Windows 11", "10.0", "amd64", 12);
        check("pc build", !pc.android() && "CPU".equals(pc.rows()[0][0]) && pc.rows()[0][1].contains("12 cores")
                && "16 GB / 512 GB".equals(pc.rows()[1][1]) && pc.rows()[2][1].startsWith("GeForce RTX 3060")
                && pc.rows()[3][1].equals("Windows 11 10.0"));
        com.xfive.live.config.SpecSheet.use("Dev", r.rows());
        check("spec sheet uses detected", "Dev".equals(com.xfive.live.config.SpecSheet.title())
                && com.xfive.live.config.SpecSheet.rows() == r.rows());
        com.xfive.live.config.SpecSheet.use("Bad", new String[][] {{"a", "b"}});
        check("spec sheet rejects wrong row count", com.xfive.live.config.SpecSheet.title().equals(com.xfive.live.config.SpecSheet.TITLE));
        com.xfive.live.config.SpecSheet.usePreset();
        check("device detect is null before start", com.xfive.live.config.DeviceInfo.detected() == null);
    }
}
