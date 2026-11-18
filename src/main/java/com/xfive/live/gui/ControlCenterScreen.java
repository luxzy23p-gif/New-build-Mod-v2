package com.xfive.live.gui;

import com.xfive.live.XFiveLiveClient;
import com.xfive.live.config.DeviceInfo;
import com.xfive.live.config.Fonts;
import com.xfive.live.config.SpecSheet;
import com.xfive.live.config.Themes;
import com.xfive.live.config.XFiveConfig;
import com.xfive.live.download.FavoriteSongs;
import com.xfive.live.download.HitCharts;
import com.xfive.live.download.OnlineManager;
import com.xfive.live.download.Song;
import com.xfive.live.hud.DynamicIslandHud;
import com.xfive.live.lyrics.LyricStyle;
import com.xfive.live.music.PlaylistManager;
import com.xfive.live.render.Glass;
import com.xfive.live.render.GlassPanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * XFiveLive Control Center bergaya aplikasi musik kaca: sidebar dengan pencarian, halaman Home, Charts,
 * Now Playing, Liked Songs, Pencarian dan Settings, serta bar pemutar di bawah. Lagu online diputar langsung
 * (tanpa unduhan). Semua digambar dengan fill sederhana supaya ringan di HP.
 * BELUM DIVERIFIKASI di perangkat (lihat README).
 */
public final class ControlCenterScreen extends Screen {
    private enum Page {
        HOME("Home"), CHARTS("Charts"), NOW("Now Playing"), FAVORITES("Liked Songs"), SEARCH("Search"),
        SETTINGS("Settings");

        final String title;

        Page(String title) {
            this.title = title;
        }
    }

    private enum Tab {
        GENERAL("General"), HUD("HUD"), CONTROLS("Controls"), LOOK("Appearance"), SPECS("Specs"),
        SOURCES("Sources");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    /** Halaman yang tampil di sidebar, berurutan. */
    private static final Page[] NAV = {Page.HOME, Page.CHARTS, Page.NOW, Page.FAVORITES};

    // ---- ikon piksel kecil (# = terisi) ----
    private static final String[] IC_HOME = {
            "....#....", "...###...", "..#####..", ".#######.", "#########", ".##...##.", ".##...##.", ".##...##."};
    private static final String[] IC_CHARTS = {
            "#.#######", ".........", "#.#######", ".........", "#.#######", ".........", "#.#######"};
    private static final String[] IC_NOTE = {
            "....#####", "....#...#", "....#...#", "....#...#", "..###.###", ".####.###", "..##...##"};
    private static final String[] IC_HEART = {
            ".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#...."};
    private static final String[] IC_GEAR = {
            "...###...", ".#.###.#.", "..#####..", "###...###", "###...###", "###...###", "..#####..", ".#.###.#.", "...###..."};
    private static final String[] IC_SEARCH = {
            "..###...", ".#...#..", "#.....#.", "#.....#.", "#.....#.", ".#...#..", "..###.#.", "......##"};
    private static final String[] IC_CLOSE = {
            "##...##", ".##.##.", "..###..", "...#...", "..###..", ".##.##.", "##...##"};
    private static final String[] IC_SHUFFLE = {
            "##.....##", ".##...##.", "..##.##..", "...###...", "..##.##..", ".##...##.", "##.....##"};
    private static final String[] IC_REPEAT = {
            "..#######..", ".##.....##.", "##.......##", "#.........#", "##.......##", ".##.....##.", "..#######.."};
    private static final String[] IC_VOLUME = {
            "...#.....", "..##..#..", "###....#.", "###.....#", "###....#.", "..##..#..", "...#....."};

    private static final int[] COVER_COLORS = {0x965CFF, 0x40DFFF, 0xFF4FD8, 0x3DDC97, 0xFFB347, 0xFF7A5C, 0x4C8DFF};

    private static final int PLAYER_H = 48;
    private static final int HEAD_H = 34;
    private static final int ROW_H = 24;
    private static final int ROW_STEP = 26;
    private static final int TOP_PAD = 10;

    private static final double OPEN_SEC = 0.45;
    private static final double CLOSE_SEC = 0.16;
    private static final long SEARCH_DEBOUNCE_NANOS = 700_000_000L;

    // ---- keadaan layar ----
    private Page page;
    private Tab tab = Tab.GENERAL;
    private String searchText = "";
    private long searchPendingAt;
    private String filterText = "";
    private boolean gridMode;
    private String chartId = "id";
    private EditBox songSearch;
    private EditBox filterBox;
    private EditBox sourceInput;
    private String pendingSource = "";
    private boolean refocusSearch;

    private double songScroll;
    private double rowsScroll;
    private int rowsH;
    private List<Row> rows = new ArrayList<>();

    private List<Item> items = new ArrayList<>();
    private long itemsSig = Long.MIN_VALUE;
    private String curKey = "";
    private String curKey2 = "";

    private int pressTarget = 0; // 0 tidak ada, 1 area isi (daftar/home), 2 baris pengaturan
    private boolean dragged;
    private double pressX;
    private double pressY;
    private double lastY;
    private Row pressedRow;
    private Row activeSlider;
    private boolean seeking;
    private boolean volumeDrag;
    private String pressedKey = "";

    private boolean hudEditMode = false;
    private String draggedHudKey = null;
    private int dragOffsetX;
    private int dragOffsetY;

    private final long openNanos = System.nanoTime();
    private long pageNanos = System.nanoTime();
    private boolean closing;
    private long closeNanos;

    // geometri (dihitung di layout())
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int sbW;
    private boolean wideSide;
    private int x0;
    private int x1;
    private int pcY;
    private final int[][] navRects = new int[NAV.length][];
    private int[] searchRect = {0, 0, 0, 0};
    private int[] settingsRect = {0, 0, 0, 0};
    private int[] userRect;
    private final int[] labelY = new int[3];
    private int likedY;
    private boolean showLabels;

    public ControlCenterScreen() {
        this(Page.HOME);
    }

    /** Membuka pusat musik langsung dari satu tombol. */
    public static ControlCenterScreen audio() {
        return new ControlCenterScreen(Page.NOW);
    }

    private ControlCenterScreen(Page page) {
        super(Component.literal("XFiveLive Control Center"));
        this.page = page;
        XFiveLiveClient.playUiSound();
        enterPage();
        buildRows();
    }

    /** Dipanggil setiap kali halaman dibuka: memuat tangga lagu yang dibutuhkan. */
    private void enterPage() {
        if (page == Page.HOME) {
            XFiveLiveClient.ONLINE.loadChart("id", false);
        } else if (page == Page.CHARTS) {
            XFiveLiveClient.ONLINE.loadChart(chartId, false);
        }
    }

    // =====================================================================
    // Tata letak
    // =====================================================================

    private void layout() {
        pw = Math.min(width - 10, 900);
        ph = Math.min(height - 10, 520);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        wideSide = pw >= 420;
        sbW = wideSide ? Math.max(100, Math.min(128, pw * 18 / 100)) : 30;
        x0 = px + 8 + sbW + 10;
        x1 = px + pw - 10;
        pcY = py + ph - 8 - PLAYER_H;
        layoutSidebar();
    }

    private void layoutSidebar() {
        int sx = px + 8;
        int sy = py + 8;
        int sh = ph - 16;
        if (!wideSide) {
            int y = sy + 6;
            searchRect = new int[] {sx + 3, y, 24, 22};
            y += 28;
            for (int i = 0; i < NAV.length; i++) {
                navRects[i] = new int[] {sx + 3, y, 24, 22};
                y += 25;
            }
            settingsRect = new int[] {sx + 3, sy + sh - 28, 24, 22};
            userRect = null;
            showLabels = false;
            return;
        }
        showLabels = sh >= 240;
        int itemH = showLabels ? 18 : 17;
        int y = sy + TOP_PAD;
        searchRect = new int[] {sx + 8, y, sbW - 16, 18};
        y += 18 + 8;
        for (int i = 0; i < NAV.length; i++) {
            if (showLabels && (i == 0 || i == 1 || i == 3)) {
                labelY[i == 0 ? 0 : i == 1 ? 1 : 2] = y;
                y += 12;
            }
            navRects[i] = new int[] {sx + 6, y, sbW - 12, itemH};
            y += itemH + 2;
        }
        likedY = y + 1;
        boolean user = sh >= 190;
        userRect = user ? new int[] {sx + 8, sy + sh - 8 - 18, sbW - 16, 18} : null;
        int setBottom = user ? userRect[1] - 6 : sy + sh - 8;
        settingsRect = new int[] {sx + 8, setBottom - 18, sbW - 16, 18};
    }

    private int[] closeRect() { return new int[] {x1 - 16, py + 10, 16, 16}; }
    private int[] segRect() { return new int[] {x1 - 16 - 6 - 62, py + 10, 62, 16}; }

    private boolean isListPage() {
        return page == Page.NOW || page == Page.FAVORITES || page == Page.CHARTS || page == Page.SEARCH;
    }

    private int[] filterRect() {
        int right = segRect()[0] - 6;
        int w = Math.min(112, right - x0 - 70);
        return new int[] {right - Math.max(0, w), py + 10, Math.max(0, w), 16};
    }

    private boolean showFilter() {
        return isListPage() && filterRect()[2] >= 56;
    }

    /** Tinggi baris tambahan di bawah judul (tab Settings, chip negara, kotak tambah folder). */
    private int subRowH() {
        return switch (page) {
            case SETTINGS -> 22;
            case CHARTS -> chipsHeight() + 4;
            case NOW -> 22;
            case SEARCH -> wideSide ? 0 : 22;
            default -> 0;
        };
    }

    private int subTop() { return py + 10 + HEAD_H + 2; }
    private int contentTop() { return subTop() + subRowH(); }
    private int contentBot() { return pcY - 6; }

    private int rowsX() { return x0; }
    private int rowsR() { return x1; }
    private int rowsTop() { return contentTop(); }
    private int rowsBot() { return contentBot(); }

    private int[] sourcePill() {
        int w = Math.max(60, x1 - x0 - 36 - 54 - 8);
        return new int[] {x0, subTop(), w, 18};
    }

    private int[] addRect() {
        int[] p = sourcePill();
        return new int[] {p[0] + p[2] + 4, p[1], 36, 18};
    }

    private int[] refreshRect() {
        int[] a = addRect();
        return new int[] {a[0] + a[2] + 4, a[1], 54, 18};
    }

    // ---- tab Settings ----

    private int[][] tabRects() {
        Tab[] tabs = Tab.values();
        Font f = GlassPanelRenderer.font();
        int[][] out = new int[tabs.length][];
        int x = x0;
        for (int i = 0; i < tabs.length; i++) {
            int w = GlassPanelRenderer.width(f, tabs[i].title) + 14;
            out[i] = new int[] {x, subTop(), w, 16};
            x += w + 4;
        }
        return out;
    }

    // ---- chip negara di Charts (membungkus ke baris berikutnya) ----

    private int[][] chipRects() {
        List<OnlineManager.Chart> cs = OnlineManager.CHARTS;
        Font f = GlassPanelRenderer.font();
        int[][] out = new int[cs.size()][];
        int x = x0;
        int y = subTop();
        for (int i = 0; i < cs.size(); i++) {
            int w = GlassPanelRenderer.width(f, shortChart(cs.get(i))) + 14;
            if (x + w > x1 && x > x0) {
                x = x0;
                y += 20;
            }
            out[i] = new int[] {x, y, w, 16};
            x += w + 4;
        }
        return out;
    }

    private int chipsHeight() {
        int[][] r = chipRects();
        int bottom = subTop() + 16;
        for (int[] c : r) {
            bottom = Math.max(bottom, c[1] + 16);
        }
        return bottom - subTop();
    }

    private static String shortChart(OnlineManager.Chart c) {
        String t = c.title();
        return t.startsWith("Top ") ? t.substring(4) : t;
    }

    // =====================================================================
    // Widget teks
    // =====================================================================

    @Override
    protected void init() {
        layout();
        layoutRows();
        Font f = GlassPanelRenderer.font();
        songSearch = null;
        filterBox = null;
        sourceInput = null;

        int[] s = searchRect;
        int[] box = wideSide ? s : (page == Page.SEARCH ? new int[] {x0, subTop(), x1 - x0, 18} : null);
        if (box != null) {
            songSearch = new EditBox(f, box[0] + 18, box[1] + 3, box[2] - 22, 12, Component.literal("Search"));
            songSearch.setBordered(false);
            songSearch.setTextColor(0xFFFFFFFF);
            songSearch.setMaxLength(100);
            songSearch.setHint(Component.literal("Search songs..."));
            songSearch.setValue(searchText);
            songSearch.setResponder(q -> {
                searchText = q;
                searchPendingAt = q.strip().length() >= 2 ? System.nanoTime() + SEARCH_DEBOUNCE_NANOS : 0L;
            });
            addRenderableWidget(songSearch);
            if (refocusSearch) {
                refocusSearch = false;
                setFocused(songSearch);
            }
        }
        if (showFilter()) {
            int[] r = filterRect();
            filterBox = new EditBox(f, r[0] + 16, r[1] + 2, r[2] - 20, 12, Component.literal("Filter"));
            filterBox.setBordered(false);
            filterBox.setTextColor(0xFFFFFFFF);
            filterBox.setMaxLength(60);
            filterBox.setHint(Component.literal("Filter this list..."));
            filterBox.setValue(filterText);
            filterBox.setResponder(q -> {
                filterText = q;
                songScroll = 0;
            });
            addRenderableWidget(filterBox);
        }
        if (page == Page.NOW) {
            int[] pill = sourcePill();
            sourceInput = new EditBox(f, pill[0] + 8, pill[1] + 3, pill[2] - 14, 12, Component.literal("Add path"));
            sourceInput.setBordered(false);
            sourceInput.setTextColor(0xFFFFFFFF);
            sourceInput.setMaxLength(300);
            sourceInput.setHint(Component.literal("Add a folder or file path"));
            sourceInput.setValue(pendingSource);
            sourceInput.setResponder(q -> pendingSource = q);
            addRenderableWidget(sourceInput);
        }
    }


    // =====================================================================
    // Animasi Spring Pop-in
    // =====================================================================

    private boolean animOn() {
        return XFiveLiveClient.CONFIG.animations;
    }

    private double openT() {
        return (System.nanoTime() - openNanos) / 1_000_000_000.0;
    }

    /** Skala panel: pegas dengan sedikit lonjakan saat dibuka, menyusut cepat saat ditutup. */
    private float panelScale() {
        if (!animOn()) {
            return 1f;
        }
        if (closing) {
            double t = Math.min(1.0, (System.nanoTime() - closeNanos) / 1_000_000_000.0 / CLOSE_SEC);
            return (float) (1.0 - 0.15 * t * t);
        }
        double t = openT();
        if (t >= OPEN_SEC) {
            return 1f;
        }
        double k = 1.0 - Math.exp(-7.0 * t) * Math.cos(16.0 * t);
        return (float) (0.80 + 0.20 * k);
    }

    private float backdropAlpha() {
        if (!animOn()) {
            return closing ? 0f : 1f;
        }
        if (closing) {
            return (float) Math.max(0.0, 1.0 - (System.nanoTime() - closeNanos) / 1_000_000_000.0 / CLOSE_SEC);
        }
        return (float) Math.min(1.0, openT() / 0.15);
    }

    private boolean settled() {
        return !closing && panelScale() == 1f;
    }

    @Override
    public void tick() {
        super.tick();
        if (searchPendingAt != 0L && System.nanoTime() >= searchPendingAt) {
            submitSearch();
        }
        if (closing && (!animOn() || (System.nanoTime() - closeNanos) / 1_000_000_000.0 >= CLOSE_SEC)) {
            finishClose();
        }
    }

    private void finishClose() {
        XFiveLiveClient.CONFIG.save();
        super.onClose();
    }

    @Override
    public void onClose() {
        if (!closing) {
            XFiveLiveClient.playUiSound();
        }
        if (closing || !animOn()) {
            finishClose();
            return;
        }
        closing = true;
        closeNanos = System.nanoTime();
        XFiveLiveClient.CONFIG.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Latar sederhana (tanpa blur bawaan Minecraft) supaya ringan di HP.
        float a = (hudEditMode ? 0.20f : 0.55f) * backdropAlpha();
        g.fill(0, 0, width, height, GlassPanelRenderer.argb(0x000000, a));
    }

    private static int a(int rgb, float alpha) {
        return GlassPanelRenderer.argb(rgb, alpha);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean in(double mx, double my, int[] r) {
        return in(mx, my, r[0], r[1], r[2], r[3]);
    }

    private static void icon(GuiGraphicsExtractor g, String[] bmp, int x, int y, int color) {
        for (int r = 0; r < bmp.length; r++) {
            String row = bmp[r];
            int c = 0;
            while (c < row.length()) {
                if (row.charAt(c) != '#') {
                    c++;
                    continue;
                }
                int s = c;
                while (c < row.length() && row.charAt(c) == '#') {
                    c++;
                }
                g.fill(x + s, y + r, x + c, y + r + 1, color);
            }
        }
    }

    private static void triRight(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        for (int i = 0; i < h; i++) {
            int w = Math.min(i, h - 1 - i) + 1;
            g.fill(x, y + i, x + w, y + i + 1, color);
        }
    }

    private static void triLeft(GuiGraphicsExtractor g, int x, int y, int h, int w0, int color) {
        for (int i = 0; i < h; i++) {
            int w = Math.min(i, h - 1 - i) + 1;
            g.fill(x + w0 - w, y + i, x + w0, y + i + 1, color);
        }
    }


    private int accent() {
        return GlassPanelRenderer.accent(XFiveLiveClient.CONFIG.theme);
    }

    private static void bigText(GuiGraphicsExtractor g, Font font, String s, int x, int y, int rgb, float scale) {
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale(scale, scale);
        GlassPanelRenderer.text(g, font, s, 0, 0, rgb);
        g.pose().popMatrix();
    }

    private static String userName() {
        try {
            String n = Minecraft.getInstance().getUser().getName();
            return n == null || n.isBlank() ? "Player" : n;
        } catch (Throwable ignored) {
            return "Player";
        }
    }

    private static int coverColor(String title) {
        return COVER_COLORS[Math.floorMod(title == null ? 0 : title.hashCode(), COVER_COLORS.length)];
    }

    private static String initial(String title) {
        return title == null || title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    // =====================================================================
    // Gambar
    // =====================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        layout();
        Font font = GlassPanelRenderer.font();

        if (hudEditMode) {
            drawHudEdit(g, font, mouseX, mouseY);
            return;
        }

        float s = panelScale();
        boolean anim = s != 1f;
        if (anim) {
            float cx = px + pw / 2f;
            float cy = py + ph / 2f;
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().scale(s, s);
            g.pose().translate(-cx, -cy);
        }

        int accent = accent();
        drawPanel(g, accent);
        drawSidebar(g, font, accent, mouseX, mouseY);
        drawHeader(g, font, accent, mouseX, mouseY);
        drawSubRow(g, font, accent, mouseX, mouseY);

        // isi halaman: geser sedikit ke atas saat berpindah halaman
        double pt = Math.min(1.0, (System.nanoTime() - pageNanos) / 1_000_000_000.0 / 0.22);
        int slide = animOn() ? (int) Math.round((1.0 - pt) * (1.0 - pt) * 8.0) : 0;
        if (slide != 0) {
            g.pose().pushMatrix();
            g.pose().translate(0f, (float) slide);
        }
        boolean clip = !anim;
        switch (page) {
            case HOME -> drawHome(g, font, accent, mouseX, mouseY, clip);
            case SETTINGS -> {
                if (tab == Tab.SPECS) {
                    drawSpecs(g, font, accent);
                } else {
                    drawRows(g, font, accent, mouseX, mouseY, clip);
                }
            }
            default -> drawItems(g, font, accent, mouseX, mouseY, clip);
        }
        if (slide != 0) {
            g.pose().popMatrix();
        }

        drawPlayer(g, font, accent, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick); // kotak teks (EditBox)

        if (anim) {
            g.pose().popMatrix();
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, int accent) {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        float alpha = 0.55f + 0.40f * Math.max(0f, Math.min(1f, c.customTransparency));
        if ("dark_music".equals(c.themeStyle)) {
            Glass.roundRect(g, px, py, pw, ph, 14, 0xFF1F2023);
            Glass.border(g, px, py, pw, ph, 14, a(0xFFFFFF, 0.14f), a(0xFFFFFF, 0.05f));
            return;
        }
        Glass.roundRect(g, px, py, pw, ph, 14,
                Glass.mix(Glass.argb(0x3C414A, 0.80f * alpha), Glass.argb(accent, 0.80f * alpha), 0.10f),
                Glass.mix(Glass.argb(0x22262C, 0.88f * alpha), Glass.argb(accent, 0.88f * alpha), 0.05f));
        // dua bercak warna lembut seperti kaca buram
        int r = Math.max(18, Math.min(46, ph / 6));
        Glass.disc(g, x0 + (x1 - x0) / 3, py + ph / 2, r, a(accent, 0.05f));
        Glass.disc(g, x0 + (x1 - x0) * 2 / 3, py + ph / 2 + r / 2, r, a(Themes.blend(accent, 0x4C8DFF, 0.6f), 0.05f));
        g.fill(px + 16, py + 1, px + pw - 16, py + 2, a(0xFFFFFF, 0.12f));
        Glass.border(g, px, py, pw, ph, 14, a(0xFFFFFF, 0.22f), a(0xFFFFFF, 0.08f));
    }

    // ---------- sidebar ----------

    private void label(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
        GlassPanelRenderer.text(g, font, text, x, y, 0x8F96A6);
    }

    private void drawSidebar(GuiGraphicsExtractor g, Font font, int accent, int mx, int my) {
        int sx = px + 8;
        int sy = py + 8;
        int sh = ph - 16;
        Glass.roundRect(g, sx, sy, sbW, sh, 12, a(0xFFFFFF, 0.09f));
        Glass.border(g, sx, sy, sbW, sh, 12, a(0xFFFFFF, 0.16f), a(0xFFFFFF, 0.05f));

        int[] sr = searchRect;
        boolean searchSel = page == Page.SEARCH;
        Glass.roundRect(g, sr[0], sr[1], sr[2], sr[3], sr[3] / 2,
                a(0xFFFFFF, searchSel ? 0.20f : (in(mx, my, sr) ? 0.13f : 0.09f)));
        icon(g, IC_SEARCH, sr[0] + (wideSide ? 6 : (sr[2] - 8) / 2), sr[1] + (sr[3] - 8) / 2,
                0xFF000000 | (searchSel ? accent : 0x9AA0AA));

        for (int i = 0; i < NAV.length; i++) {
            int[] r = navRects[i];
            boolean sel = NAV[i] == page;
            boolean hov = in(mx, my, r);
            boolean pressed = pressedKey.equals("nav" + i);
            if (sel) {
                Glass.roundRect(g, r[0], r[1], r[2], r[3], r[3] / 2, a(0xFFFFFF, 0.24f));
            } else if (hov || pressed) {
                Glass.roundRect(g, r[0], r[1], r[2], r[3], r[3] / 2, a(0xFFFFFF, pressed ? 0.14f : 0.08f));
            }
            String[] ic = navIcon(NAV[i]);
            int col = sel || NAV[i] == Page.HOME ? accent : 0xA0A6B2;
            icon(g, ic, r[0] + (wideSide ? 8 : (r[2] - 9) / 2), r[1] + (r[3] - ic.length) / 2, 0xFF000000 | col);
            if (wideSide) {
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, NAV[i].title, r[2] - 30),
                        r[0] + 22, r[1] + (r[3] - 9) / 2, sel ? 0xFFFFFF : 0xE0E3EA);
            }
        }
        if (wideSide && showLabels) {
            label(g, font, "XFIVE LIVE", sx + 10, labelY[0]);
            label(g, font, "DISCOVER", sx + 10, labelY[1]);
            label(g, font, "MY MUSIC", sx + 10, labelY[2]);
            int n = XFiveLiveClient.CONFIG.favoriteMusic.size() + XFiveLiveClient.CONFIG.favoriteOnline.size();
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font,
                    n == 0 ? "Tap a heart to like a song" : n + (n == 1 ? " liked song" : " liked songs"), sbW - 20),
                    sx + 10, likedY, 0x7E8594);
        }

        int[] st = settingsRect;
        boolean setSel = page == Page.SETTINGS;
        Glass.roundRect(g, st[0], st[1], st[2], st[3], st[3] / 2,
                a(0xFFFFFF, setSel ? 0.24f : (in(mx, my, st) ? 0.14f : 0.09f)));
        if (wideSide) {
            String t = "Settings";
            GlassPanelRenderer.text(g, font, t, st[0] + (st[2] - GlassPanelRenderer.width(font, t)) / 2,
                    st[1] + (st[3] - 9) / 2, 0xFFFFFF);
        } else {
            icon(g, IC_GEAR, st[0] + (st[2] - 9) / 2, st[1] + (st[3] - 9) / 2, 0xFF000000 | (setSel ? accent : 0xC8CCD4));
        }
        if (userRect != null) {
            int[] u = userRect;
            Glass.disc(g, u[0] + 8, u[1] + 9, 8, a(accent, 0.95f));
            String name = userName();
            GlassPanelRenderer.text(g, font, initial(name), u[0] + 8 - GlassPanelRenderer.width(font, initial(name)) / 2,
                    u[1] + 5, 0xFFFFFF);
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, name, u[2] - 22), u[0] + 21, u[1] + 5,
                    in(mx, my, u) ? accent : 0xE0E3EA);
        }
    }

    private static String[] navIcon(Page p) {
        return switch (p) {
            case HOME -> IC_HOME;
            case CHARTS -> IC_CHARTS;
            case NOW -> IC_NOTE;
            case FAVORITES -> IC_HEART;
            default -> IC_SEARCH;
        };
    }

    // ---------- judul halaman ----------

    private String headerSub() {
        OnlineManager on = XFiveLiveClient.ONLINE;
        switch (page) {
            case HOME:
                return "Play the biggest hits from Indonesia and the world";
            case CHARTS: {
                OnlineManager.Chart c = OnlineManager.chart(chartId);
                return c == null ? "" : c.title() + " this week";
            }
            case NOW: {
                var ly = XFiveLiveClient.LYRICS;
                if (!ly.isEmpty() && XFiveLiveClient.MUSIC.currentTrack() != null) {
                    long pos = LyricStyle.shifted(XFiveLiveClient.MUSIC.positionMs(), XFiveLiveClient.CONFIG.lyricsOffsetMs);
                    String line = ly.textAt(ly.indexAt(pos));
                    if (!line.isBlank()) {
                        return line;
                    }
                }
                int n = XFiveLiveClient.MUSIC.playlist().size();
                return n == 0 ? "Nothing in the queue yet" : n + (n == 1 ? " song in your library" : " songs in your library");
            }
            case FAVORITES: {
                int n = XFiveLiveClient.CONFIG.favoriteMusic.size() + XFiveLiveClient.CONFIG.favoriteOnline.size();
                return n == 0 ? "No liked songs yet" : n + (n == 1 ? " liked song" : " liked songs");
            }
            case SEARCH:
                return searchText.isBlank() ? "Type in the search box on the left"
                        : (on.searchState() == OnlineManager.SearchState.SEARCHING ? "Searching..."
                        : "Results for \"" + searchText.strip() + "\"");
            default:
                return switch (tab) {
                    case SOURCES -> "Where your songs come from";
                    case LOOK -> "Colors, style and animations";
                    case HUD -> "Dynamic Island, music and lyrics on screen";
                    case CONTROLS -> "Dynamic bar and playback";
                    case SPECS -> "About this device";
                    default -> "Make XFiveLive yours";
                };
        }
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, int accent, int mx, int my) {
        int titleY = py + 10;
        bigText(g, font, page.title, x0, titleY, 0xFFFFFF, 1.5f);
        int rightLimit = isListPage() && showFilter() ? filterRect()[0] - 8 : closeRect()[0] - 8;
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, headerSub(), Math.max(40, rightLimit - x0)),
                x0, titleY + 19, 0xB4BAC8);

        int[] cr = closeRect();
        Glass.disc(g, cr[0] + 8, cr[1] + 8, 8, a(in(mx, my, cr) ? 0xFF5C5C : 0xFFFFFF, in(mx, my, cr) ? 0.40f : 0.12f));
        icon(g, IC_CLOSE, cr[0] + 5, cr[1] + 5, 0xFFE6E8EE);

        if (isListPage()) {
            int[] sg = segRect();
            Glass.roundRect(g, sg[0], sg[1], sg[2], sg[3], 8, a(0xFFFFFF, 0.10f));
            int half = sg[2] / 2;
            int sel = gridMode ? 1 : 0;
            Glass.roundRect(g, sg[0] + sel * half, sg[1], half, sg[3], 8, a(accent, 0.95f));
            String a1 = "List";
            String a2 = "Covers";
            GlassPanelRenderer.text(g, font, a1, sg[0] + (half - GlassPanelRenderer.width(font, a1)) / 2, sg[1] + 4, 0xFFFFFF);
            GlassPanelRenderer.text(g, font, a2, sg[0] + half + (half - GlassPanelRenderer.width(font, a2)) / 2, sg[1] + 4,
                    0xFFFFFF);
            if (showFilter()) {
                int[] fr = filterRect();
                Glass.roundRect(g, fr[0], fr[1], fr[2], fr[3], 8, a(0xFFFFFF, 0.09f));
                icon(g, IC_SEARCH, fr[0] + 5, fr[1] + 4, 0xFF8A909C);
            }
        }
    }

    private void pill(GuiGraphicsExtractor g, Font font, int[] r, String label, boolean selected, boolean hov,
                      int accent) {
        Glass.roundRect(g, r[0], r[1], r[2], r[3], r[3] / 2,
                selected ? a(accent, 0.95f) : a(0xFFFFFF, hov ? 0.16f : 0.09f));
        int w = GlassPanelRenderer.width(font, label);
        GlassPanelRenderer.text(g, font, label, r[0] + (r[2] - w) / 2, r[1] + (r[3] - 9) / 2, 0xFFFFFF);
    }

    private void drawSubRow(GuiGraphicsExtractor g, Font font, int accent, int mx, int my) {
        if (page == Page.SETTINGS) {
            int[][] tr = tabRects();
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++) {
                pill(g, font, tr[i], tabs[i].title, tabs[i] == tab, in(mx, my, tr[i]), accent);
            }
        } else if (page == Page.CHARTS) {
            int[][] cr = chipRects();
            for (int i = 0; i < cr.length; i++) {
                OnlineManager.Chart c = OnlineManager.CHARTS.get(i);
                pill(g, font, cr[i], shortChart(c), c.id().equals(chartId), in(mx, my, cr[i]), accent);
            }
        } else if (page == Page.SEARCH && !wideSide) {
            Glass.roundRect(g, x0, subTop(), x1 - x0, 18, 9, a(0xFFFFFF, 0.09f));
            icon(g, IC_SEARCH, x0 + 6, subTop() + 5, 0xFF8A909C);
        } else if (page == Page.NOW) {
            int[] pill = sourcePill();
            Glass.roundRect(g, pill[0], pill[1], pill[2], pill[3], 9, a(0xFFFFFF, 0.09f));
            int[] ar = addRect();
            int[] rr = refreshRect();
            pill(g, font, ar, "Add", false, in(mx, my, ar) || pressedKey.equals("add"), accent);
            pill(g, font, rr, "Refresh", false, in(mx, my, rr) || pressedKey.equals("refresh"), accent);
        }
    }


    // =====================================================================
    // Daftar lagu (Now Playing, Liked Songs, Charts, Pencarian)
    // =====================================================================

    private static final class Item {
        final String key;
        final String title;
        final String artist;
        final String time;
        final Runnable play;
        final Runnable fav; // null = tanpa tombol hati
        boolean liked;

        Item(String key, String title, String artist, String time, Runnable play, Runnable fav) {
            this.key = key;
            this.title = title;
            this.artist = artist;
            this.time = time;
            this.play = play;
            this.fav = fav;
        }
    }

    private static boolean hit(String q, String title, String artist) {
        return q.isEmpty() || title.toLowerCase(Locale.ROOT).contains(q) || artist.toLowerCase(Locale.ROOT).contains(q);
    }

    private long itemsSignature() {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        OnlineManager on = XFiveLiveClient.ONLINE;
        long s = on.version();
        s = s * 31 + XFiveLiveClient.MUSIC.playlist().size();
        s = s * 31 + filterText.hashCode();
        s = s * 31 + searchText.strip().hashCode();
        s = s * 31 + page.ordinal();
        s = s * 31 + chartId.hashCode();
        s = s * 31 + c.favoriteMusic.size();
        s = s * 31 + c.favoriteOnline.size();
        s = s * 31 + (XFiveLiveClient.MUSIC.isOnline() ? 1 : 0);
        Song ns = on.nowSong();
        s = s * 31 + (ns == null ? 0 : ns.id().hashCode());
        return s;
    }

    private void ensureItems() {
        long sig = itemsSignature();
        if (sig == itemsSig) {
            return;
        }
        itemsSig = sig;
        items = buildItems();
    }

    private List<Item> buildItems() {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        OnlineManager on = XFiveLiveClient.ONLINE;
        String q = filterText.strip().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        Set<String> favIds = new HashSet<>();
        for (Song s : FavoriteSongs.decodeAll(c.favoriteOnline)) {
            favIds.add(s.id());
        }
        switch (page) {
            case NOW -> {
                Song ns = XFiveLiveClient.MUSIC.isOnline() ? on.nowSong() : null;
                if (ns != null && hit(q, ns.title(), ns.artist())) {
                    List<Song> one = List.of(ns);
                    out.add(songItem(ns, one, 0, favIds));
                }
                addLocal(out, q, false);
            }
            case FAVORITES -> {
                List<Song> shown = new ArrayList<>();
                for (Song s : FavoriteSongs.decodeAll(c.favoriteOnline)) {
                    if (hit(q, s.title(), s.artist())) {
                        shown.add(s);
                    }
                }
                for (int i = 0; i < shown.size(); i++) {
                    out.add(songItem(shown.get(i), shown, i, favIds));
                }
                addLocal(out, q, true);
            }
            case CHARTS -> {
                List<OnlineManager.Entry> entries = new ArrayList<>();
                for (HitCharts.Hit h : on.chartHits(chartId)) {
                    if (hit(q, h.name(), h.artist())) {
                        entries.add(OnlineManager.Entry.of(h));
                    }
                }
                for (int i = 0; i < entries.size(); i++) {
                    final int at = i;
                    OnlineManager.Entry e = entries.get(i);
                    out.add(new Item("h:" + e.hit().name() + "|" + e.hit().artist(), e.title(), e.artist(), "",
                            () -> on.playEntries(entries, at), null));
                }
            }
            case SEARCH -> {
                String sq = searchText.strip().toLowerCase(Locale.ROOT);
                if (!sq.isEmpty()) {
                    addLocalMatching(out, sq, q);
                    List<Song> found = new ArrayList<>();
                    if (sq.equals(on.searchQuery().toLowerCase(Locale.ROOT))) {
                        for (Song s : on.results()) {
                            if (hit(q, s.title(), s.artist())) {
                                found.add(s);
                            }
                        }
                    }
                    for (int i = 0; i < found.size(); i++) {
                        out.add(songItem(found.get(i), found, i, favIds));
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    private Item songItem(Song s, List<Song> queue, int at, Set<String> favIds) {
        List<OnlineManager.Entry> entries = new ArrayList<>();
        for (Song x : queue) {
            entries.add(OnlineManager.Entry.of(x));
        }
        OnlineManager on = XFiveLiveClient.ONLINE;
        Item it = new Item("s:" + s.id(), s.title(), s.artist(), s.duration(),
                () -> on.playEntries(entries, at), () -> toggleOnlineFavorite(s));
        it.liked = favIds.contains(s.id());
        return it;
    }

    private void addLocal(List<Item> out, String q, boolean onlyFavorites) {
        List<PlaylistManager.Track> tracks = XFiveLiveClient.MUSIC.playlist().tracks();
        for (int i = 0; i < tracks.size(); i++) {
            PlaylistManager.Track t = tracks.get(i);
            boolean fav = isFavorite(t);
            if (onlyFavorites && !fav) {
                continue;
            }
            if (hit(q, t.title(), t.artist())) {
                out.add(localItem(t, i, fav));
            }
        }
    }

    private void addLocalMatching(List<Item> out, String searchQ, String filterQ) {
        List<PlaylistManager.Track> tracks = XFiveLiveClient.MUSIC.playlist().tracks();
        for (int i = 0; i < tracks.size(); i++) {
            PlaylistManager.Track t = tracks.get(i);
            if (hit(searchQ, t.title(), t.artist()) && hit(filterQ, t.title(), t.artist())) {
                out.add(localItem(t, i, isFavorite(t)));
            }
        }
    }

    private Item localItem(PlaylistManager.Track t, int index, boolean fav) {
        Item it = new Item(t.path().toAbsolutePath().toString(), t.title(), t.artist(), "",
                () -> XFiveLiveClient.playLocal(index), () -> toggleFavorite(t));
        it.liked = fav;
        return it;
    }

    private static boolean isFavorite(PlaylistManager.Track t) {
        return XFiveLiveClient.CONFIG.favoriteMusic.contains(t.path().toAbsolutePath().toString());
    }

    private static void toggleFavorite(PlaylistManager.Track t) {
        String key = t.path().toAbsolutePath().toString();
        if (!XFiveLiveClient.CONFIG.favoriteMusic.add(key)) {
            XFiveLiveClient.CONFIG.favoriteMusic.remove(key);
        }
        XFiveLiveClient.CONFIG.save();
    }

    private static void toggleOnlineFavorite(Song s) {
        FavoriteSongs.toggle(XFiveLiveClient.CONFIG.favoriteOnline, s);
        XFiveLiveClient.CONFIG.save();
    }

    /** Menandai baris mana yang sedang diputar (kunci lagu lokal, lagu online, atau lagu tangga). */
    private void refreshCurrentKeys() {
        OnlineManager on = XFiveLiveClient.ONLINE;
        var music = XFiveLiveClient.MUSIC;
        curKey = "";
        curKey2 = "";
        OnlineManager.Entry e = on.nowEntry();
        if (e != null && (music.isOnline() || music.currentTrack() == null)) {
            curKey = e.song() != null ? "s:" + e.song().id() : "h:" + e.hit().name() + "|" + e.hit().artist();
            Song ns = on.nowSong();
            curKey2 = ns != null ? "s:" + ns.id() : "";
            return;
        }
        PlaylistManager.Track t = music.currentTrack();
        if (t != null && !music.isOnline()) {
            curKey = t.path().toAbsolutePath().toString();
        }
    }

    private boolean isCurrent(Item it) {
        return !curKey.isEmpty() && (it.key.equals(curKey) || (!curKey2.isEmpty() && it.key.equals(curKey2)));
    }

    // ---- ukuran daftar ----

    private int colHeaderH() { return gridMode ? 0 : 14; }
    private int listTop() { return contentTop() + colHeaderH(); }

    private int gridCols() {
        return Math.max(2, (x1 - x0 + 8) / 100);
    }

    private int gridCardW() {
        int n = gridCols();
        return (x1 - x0 - (n - 1) * 8) / n;
    }

    private int gridCardH() { return gridCardW() + 24; }

    private double maxItemScroll() {
        int n = items.size();
        int total;
        if (gridMode) {
            int rowsN = (n + gridCols() - 1) / gridCols();
            total = rowsN * (gridCardH() + 8);
        } else {
            total = n * ROW_STEP;
        }
        return Math.max(0, total - (contentBot() - listTop()));
    }

    private String emptyMessage() {
        OnlineManager on = XFiveLiveClient.ONLINE;
        if (!filterText.isBlank() && !(page == Page.SEARCH && searchText.isBlank())) {
            return "No match for this filter.";
        }
        switch (page) {
            case NOW:
                return "No songs yet. Add a folder above, or play a hit from Charts.";
            case FAVORITES:
                return "Tap the heart on a song to keep it here.";
            case CHARTS: {
                String m = on.chartMessage(chartId);
                return m.isEmpty() ? "Loading..." : m;
            }
            case SEARCH: {
                if (searchText.isBlank()) {
                    return "Type a song or artist in the search box.";
                }
                if (on.searchState() == OnlineManager.SearchState.SEARCHING) {
                    return "Searching...";
                }
                if (!on.searchMessage().isEmpty() && searchText.strip().equalsIgnoreCase(on.searchQuery())) {
                    return on.searchMessage();
                }
                return searchText.strip().length() < 2 ? "Type at least 2 letters." : "Searching...";
            }
            default:
                return "";
        }
    }

    private void drawItems(GuiGraphicsExtractor g, Font font, int accent, int mx, int my, boolean clip) {
        ensureItems();
        refreshCurrentKeys();
        int top = listTop();
        int bot = contentBot();
        songScroll = Math.max(0, Math.min(maxItemScroll(), songScroll));
        boolean playing = XFiveLiveClient.MUSIC.isPlaying();
        double time = System.nanoTime() / 1_000_000_000.0;

        if (!gridMode) {
            int ty = contentTop();
            GlassPanelRenderer.text(g, font, "#", x0 + 8, ty + 2, 0x7E8594);
            GlassPanelRenderer.text(g, font, "TITLE", x0 + 44, ty + 2, 0x7E8594);
            String tl = "TIME";
            GlassPanelRenderer.text(g, font, tl, x1 - 8 - GlassPanelRenderer.width(font, tl), ty + 2, 0x7E8594);
        }
        if (items.isEmpty()) {
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, emptyMessage(), x1 - x0 - 8), x0 + 4, top + 10,
                    page == Page.CHARTS && emptyMessage().startsWith("Could not") ? 0xFF6B7B : 0xB4BAC8);
            return;
        }
        if (clip) {
            g.enableScissor(x0 - 2, top, x1 + 2, bot);
        }
        if (gridMode) {
            drawGrid(g, font, accent, mx, my, clip, top, bot, playing, time);
        } else {
            for (int i = 0; i < items.size(); i++) {
                int y = top + (int) Math.round(i * ROW_STEP - songScroll);
                if (y + ROW_H < top || y > bot) {
                    continue;
                }
                if (!clip && (y < top || y + ROW_H > bot)) {
                    continue;
                }
                drawItemRow(g, font, accent, items.get(i), i, y, mx, my, top, bot, playing, time);
            }
        }
        if (clip) {
            g.disableScissor();
        }
        double max = maxItemScroll();
        if (max > 0) {
            int trackH = bot - top;
            int thumbH = Math.max(14, (int) (trackH * (trackH / (trackH + max))));
            int thumbY = top + (int) ((trackH - thumbH) * (songScroll / max));
            g.fill(x1 + 3, thumbY, x1 + 5, thumbY + thumbH, a(0xFFFFFF, 0.35f));
        }
    }

    private void drawItemRow(GuiGraphicsExtractor g, Font font, int accent, Item it, int i, int y, int mx, int my,
                             int top, int bot, boolean playing, double time) {
        boolean cur = isCurrent(it);
        boolean hov = in(mx, my, x0, y, x1 - x0, ROW_H) && my >= top && my < bot;
        if (cur) {
            Glass.roundRect(g, x0, y, x1 - x0, ROW_H, 9, a(accent, 0.22f));
        } else if (hov) {
            Glass.roundRect(g, x0, y, x1 - x0, ROW_H, 9, a(0xFFFFFF, 0.09f));
        }
        int numX = x0 + 8;
        if (cur && playing) {
            for (int b = 0; b < 3; b++) {
                double wave = 0.5 + 0.5 * Math.sin(time * 6 + b * 1.9);
                int bh = 2 + (int) Math.round(wave * 7);
                g.fill(numX + b * 3, y + 16 - bh, numX + b * 3 + 2, y + 16, 0xFF000000 | accent);
            }
        } else {
            GlassPanelRenderer.text(g, font, String.valueOf(i + 1), numX, y + 8, cur ? accent : 0x8F96A6);
        }
        int cover = 18;
        int cx = x0 + 26;
        Glass.roundRect(g, cx, y + (ROW_H - cover) / 2, cover, cover, 5, a(coverColor(it.title), 0.90f));
        GlassPanelRenderer.text(g, font, initial(it.title), cx + (cover - GlassPanelRenderer.width(font, initial(it.title))) / 2,
                y + 8, 0xFFFFFF);
        int textX = x0 + 50;
        int timeW = it.time.isEmpty() ? 0 : GlassPanelRenderer.width(font, it.time);
        int heartW = it.fav == null ? 0 : 18;
        int maxW = Math.max(30, x1 - 12 - timeW - heartW - 6 - textX);
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, it.title, maxW), textX, y + 3,
                cur ? accent : 0xFFFFFF);
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, it.artist, maxW), textX, y + 13, 0x9AA1AF);
        if (timeW > 0) {
            GlassPanelRenderer.text(g, font, it.time, x1 - 8 - timeW, y + 8, 0xB4BAC8);
        }
        if (it.fav != null) {
            int hx = x1 - 8 - timeW - (timeW > 0 ? 8 : 0) - 9;
            icon(g, IC_HEART, hx, y + 8, it.liked ? (0xFF000000 | accent) : 0xFF6A6E78);
        }
    }

    private int heartX(Item it) {
        int timeW = it.time.isEmpty() ? 0 : GlassPanelRenderer.width(GlassPanelRenderer.font(), it.time);
        return x1 - 8 - timeW - (timeW > 0 ? 8 : 0) - 9;
    }

    private void drawGrid(GuiGraphicsExtractor g, Font font, int accent, int mx, int my, boolean clip, int top, int bot,
                          boolean playing, double time) {
        int n = gridCols();
        int cw = gridCardW();
        int ch = gridCardH();
        for (int i = 0; i < items.size(); i++) {
            int col = i % n;
            int row = i / n;
            int x = x0 + col * (cw + 8);
            int y = top + (int) Math.round(row * (ch + 8) - songScroll);
            if (y + ch < top || y > bot) {
                continue;
            }
            if (!clip && (y < top || y + ch > bot)) {
                continue;
            }
            Item it = items.get(i);
            boolean cur = isCurrent(it);
            boolean hov = in(mx, my, x, y, cw, ch) && my >= top && my < bot;
            int base = coverColor(it.title);
            Glass.roundRect(g, x, y, cw, cw, 10, a(Themes.blend(base, 0x000000, hov ? 0.0f : 0.15f), 0.92f),
                    a(Themes.blend(base, 0x000000, 0.45f), 0.92f));
            if (cur) {
                Glass.border(g, x, y, cw, cw, 10, 0xFF000000 | accent, 0xFF000000 | accent);
            }
            String ini = initial(it.title);
            bigText(g, font, ini, x + cw / 2 - GlassPanelRenderer.width(font, ini), y + cw / 2 - 9, 0xFFFFFF, 2f);
            if (cur && playing) {
                for (int b = 0; b < 3; b++) {
                    double wave = 0.5 + 0.5 * Math.sin(time * 6 + b * 1.9);
                    int bh = 3 + (int) Math.round(wave * 8);
                    g.fill(x + 6 + b * 4, y + cw - 6 - bh, x + 8 + b * 4, y + cw - 6, 0xFFFFFFFF);
                }
            }
            if (it.fav != null) {
                icon(g, IC_HEART, x + cw - 14, y + 5, it.liked ? (0xFF000000 | accent) : 0xAAFFFFFF);
            }
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, it.title, cw), x, y + cw + 3,
                    cur ? accent : 0xFFFFFF);
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, it.artist, cw), x, y + cw + 13, 0x9AA1AF);
        }
    }

    /** Indeks lagu yang disentuh pada koordinat layar, atau -1. */
    private int itemAt(double mx, double my) {
        int top = listTop();
        if (my < top || my >= contentBot() || mx < x0 || mx >= x1) {
            return -1;
        }
        if (gridMode) {
            int n = gridCols();
            int cw = gridCardW();
            int ch = gridCardH();
            int colRaw = (int) Math.floor((mx - x0) / (cw + 8));
            double inX = (mx - x0) - colRaw * (cw + 8);
            int row = (int) Math.floor((my - top + songScroll) / (ch + 8));
            double inY = (my - top + songScroll) - row * (ch + 8);
            int idx = row * n + colRaw;
            return colRaw < n && inX <= cw && inY <= ch && row >= 0 && idx < items.size() ? idx : -1;
        }
        int row = (int) Math.floor((my - top + songScroll) / ROW_STEP);
        int local = (int) Math.floor(my - top + songScroll) - row * ROW_STEP;
        return row >= 0 && row < items.size() && local < ROW_H ? row : -1;
    }

    private boolean tapItems(double mx, double my) {
        int idx = itemAt(mx, my);
        if (idx < 0) {
            return false;
        }
        Item it = items.get(idx);
        boolean onHeart;
        if (gridMode) {
            int n = gridCols();
            int cw = gridCardW();
            int x = x0 + (idx % n) * (cw + 8);
            double y = listTop() + (idx / n) * (gridCardH() + 8) - songScroll;
            onHeart = mx >= x + cw - 20 && my < y + 20;
        } else {
            onHeart = mx >= heartX(it) - 5 && mx <= heartX(it) + 15;
        }
        if (it.fav != null && onHeart) {
            it.fav.run();
        } else {
            XFiveLiveClient.playUiSound();
            it.play.run();
        }
        itemsSig = Long.MIN_VALUE;
        return true;
    }

    // =====================================================================
    // Home
    // =====================================================================

    private int homeHeroH() { return ph >= 300 ? 70 : 56; }

    private int[] homeHero(int base) { return new int[] {x0, base, x1 - x0, homeHeroH()}; }

    private int[] homePlay(int base) {
        int[] h = homeHero(base);
        return new int[] {h[0] + 12, h[1] + h[3] - 12 - 18, 74, 18};
    }

    private int homeCols() { return Math.max(2, Math.min(6, (x1 - x0 + 8) / 108)); }
    private int homeCardW() { return (x1 - x0 - (homeCols() - 1) * 8) / homeCols(); }
    private int homeCardH() { return 38; }

    private int[] homeCard(int i, int base) {
        int n = homeCols();
        int cw = homeCardW();
        int labelBottom = base + homeHeroH() + 10 + 14;
        return new int[] {x0 + (i % n) * (cw + 8), labelBottom + (i / n) * (homeCardH() + 8), cw, homeCardH()};
    }

    private double maxHomeScroll() {
        int n = OnlineManager.CHARTS.size();
        int rowsN = (n + homeCols() - 1) / homeCols();
        int total = homeHeroH() + 10 + 14 + rowsN * (homeCardH() + 8);
        return Math.max(0, total - (contentBot() - contentTop()));
    }

    private void drawHome(GuiGraphicsExtractor g, Font font, int accent, int mx, int my, boolean clip) {
        songScroll = Math.max(0, Math.min(maxHomeScroll(), songScroll));
        int base = contentTop() - (int) Math.round(songScroll);
        int top = contentTop();
        int bot = contentBot();
        if (clip) {
            g.enableScissor(x0 - 2, top, x1 + 2, bot);
        }
        int[] h = homeHero(base);
        Glass.roundRect(g, h[0], h[1], h[2], h[3], 14, a(accent, 0.95f),
                a(Themes.blend(accent, 0x000000, 0.50f), 0.95f));
        Glass.border(g, h[0], h[1], h[2], h[3], 14, a(0xFFFFFF, 0.30f), a(0xFFFFFF, 0.08f));
        double time = System.nanoTime() / 1_000_000_000.0;
        boolean playing = XFiveLiveClient.MUSIC.isPlaying();
        for (int b = 0; b < 5; b++) {
            double wave = playing ? 0.5 + 0.5 * Math.sin(time * 5 + b * 1.3) : 0.35;
            int bh = 6 + (int) Math.round(wave * 20);
            g.fill(h[0] + h[2] - 14 - b * 7, h[1] + h[3] - 10 - bh, h[0] + h[2] - 10 - b * 7, h[1] + h[3] - 10, a(0xFFFFFF, 0.30f));
        }
        String hello = "Welcome back, " + userName();
        bigText(g, font, GlassPanelRenderer.fit(font, hello, (int) ((h[2] - 90) / 1.5f)), h[0] + 12, h[1] + 9, 0xFFFFFF, 1.5f);
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, "Hits from Indonesia and the world, no download needed.", h[2] - 90),
                h[0] + 12, h[1] + 28, 0xFFEFF3);
        int[] pr = homePlay(base);
        boolean ph2 = in(mx, my, pr) && my >= top && my < bot;
        Glass.roundRect(g, pr[0], pr[1], pr[2], pr[3], 9, a(0xFFFFFF, ph2 ? 1f : 0.92f));
        triRight(g, pr[0] + 9, pr[1] + 4, 11, 0xFF1F2023);
        GlassPanelRenderer.text(g, font, "Play hits", pr[0] + 24, pr[1] + 5, 0x1F2023);

        int ly = base + homeHeroH() + 10;
        label(g, font, "TOP CHARTS BY COUNTRY", x0 + 2, ly);
        List<OnlineManager.Chart> cs = OnlineManager.CHARTS;
        for (int i = 0; i < cs.size(); i++) {
            int[] r = homeCard(i, base);
            if (r[1] + r[3] < top || r[1] > bot) {
                continue;
            }
            if (!clip && (r[1] < top || r[1] + r[3] > bot)) {
                continue;
            }
            OnlineManager.Chart c = cs.get(i);
            boolean hov = in(mx, my, r) && my >= top && my < bot;
            Glass.roundRect(g, r[0], r[1], r[2], r[3], 10, a(c.color(), hov ? 0.95f : 0.80f),
                    a(Themes.blend(c.color(), 0x000000, 0.5f), hov ? 0.95f : 0.80f));
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, c.title(), r[2] - 12), r[0] + 8, r[1] + 7, 0xFFFFFF);
            GlassPanelRenderer.text(g, font, c.country() + " hits", r[0] + 8, r[1] + 20, 0xE6E8EE);
            triRight(g, r[0] + r[2] - 16, r[1] + r[3] - 17, 9, 0xCCFFFFFF);
        }
        if (clip) {
            g.disableScissor();
        }
    }

    private boolean tapHome(double mx, double my) {
        int base = contentTop() - (int) Math.round(songScroll);
        if (in(mx, my, homePlay(base))) {
            playChart("id");
            return true;
        }
        List<OnlineManager.Chart> cs = OnlineManager.CHARTS;
        for (int i = 0; i < cs.size(); i++) {
            if (in(mx, my, homeCard(i, base))) {
                chartId = cs.get(i).id();
                goTo(Page.CHARTS);
                return true;
            }
        }
        return false;
    }

    /** Memutar seluruh tangga negara dari lagu pertama; lanjut otomatis ke lagu berikutnya. */
    private void playChart(String id) {
        OnlineManager on = XFiveLiveClient.ONLINE;
        List<HitCharts.Hit> hits = on.chartHits(id);
        if (hits.isEmpty()) {
            on.loadChart(id, true);
            XFiveLiveClient.MUSIC.setStatus("Loading the chart... tap again in a moment.");
            return;
        }
        List<OnlineManager.Entry> entries = new ArrayList<>();
        for (HitCharts.Hit h : hits) {
            entries.add(OnlineManager.Entry.of(h));
        }
        XFiveLiveClient.playUiSound();
        on.playEntries(entries, 0);
    }


    // =====================================================================
    // Bar pemutar
    // =====================================================================

    private boolean showVolume() { return x1 - x0 >= 360; }

    /** Kotak kontrol: 0 ulang, 1 sebelumnya, 2 putar, 3 berikutnya, 4 acak, 5 volume, 6 bar progres, 7 hati. */
    private int[][] playerRects() {
        int w = x1 - x0;
        int cy = pcY + 21;
        int infoW = Math.min(110, Math.max(46, w / 4));
        boolean vol = showVolume();
        int rightReserve = vol ? 56 + 18 + 34 : 30;
        int centerMin = x0 + 10 + infoW;
        int centerMax = x1 - 10 - rightReserve;
        int group = 124;
        int start = x0 + (w - group) / 2;
        start = Math.max(centerMin, Math.min(start, centerMax - group));
        int[][] r = new int[8][];
        r[0] = new int[] {start, cy - 8, 16, 16};
        r[1] = new int[] {start + 24, cy - 8, 16, 16};
        r[2] = new int[] {start + 48, cy - 14, 28, 28};
        r[3] = new int[] {start + 84, cy - 8, 16, 16};
        r[4] = new int[] {start + 108, cy - 8, 16, 16};
        r[5] = new int[] {x1 - 10 - 56, cy - 6, 56, 12};
        r[6] = new int[] {x0 + 10 + 28, pcY + PLAYER_H - 14, w - 20 - 56, 12};
        r[7] = new int[] {vol ? r[5][0] - 34 : x1 - 10 - 16, cy - 8, 16, 16};
        return r;
    }

    /** Lagu yang sedang aktif: {judul, artis, kunci lagu, true bila lagu online}; bisa kosong. */
    private Song currentOnlineSong() {
        return XFiveLiveClient.MUSIC.isOnline() ? XFiveLiveClient.ONLINE.nowSong() : null;
    }

    private boolean currentLiked() {
        Song s = currentOnlineSong();
        if (s != null) {
            return FavoriteSongs.contains(XFiveLiveClient.CONFIG.favoriteOnline, s.id());
        }
        PlaylistManager.Track t = XFiveLiveClient.MUSIC.currentTrack();
        return t != null && !XFiveLiveClient.MUSIC.isOnline() && isFavorite(t);
    }

    private void toggleCurrentFavorite() {
        Song s = currentOnlineSong();
        if (s != null) {
            toggleOnlineFavorite(s);
            return;
        }
        PlaylistManager.Track t = XFiveLiveClient.MUSIC.currentTrack();
        if (t != null && !XFiveLiveClient.MUSIC.isOnline()) {
            toggleFavorite(t);
        }
    }

    private void drawPlayer(GuiGraphicsExtractor g, Font font, int accent, int mx, int my) {
        int w = x1 - x0;
        Glass.roundRect(g, x0, pcY, w, PLAYER_H, 14,
                Glass.mix(Glass.argb(0x3C414A, 0.80f), Glass.argb(accent, 0.80f), 0.14f),
                Glass.mix(Glass.argb(0x22262C, 0.88f), Glass.argb(accent, 0.88f), 0.07f));
        Glass.border(g, x0, pcY, w, PLAYER_H, 14, a(0xFFFFFF, 0.18f), a(0xFFFFFF, 0.06f));

        var music = XFiveLiveClient.MUSIC;
        OnlineManager on = XFiveLiveClient.ONLINE;
        long dur = music.durationMs();
        long pos = music.positionMs();
        float prog = dur > 0 ? Math.max(0f, Math.min(1f, (float) pos / dur)) : 0f;
        int[][] r = playerRects();

        PlaylistManager.Track t = music.currentTrack();
        Song liveSong = currentOnlineSong();
        String title;
        String artist;
        if (liveSong != null) {
            title = liveSong.title();
            artist = liveSong.artist();
        } else if (t != null) {
            title = t.title();
            artist = t.artist();
        } else if (on.nowEntry() != null) {
            title = on.nowEntry().title();
            artist = on.message().isEmpty() ? on.nowEntry().artist() : on.message();
        } else {
            title = "Not playing";
            artist = music.status().isBlank() ? "-" : music.status();
        }
        int cover = 28;
        int cx = x0 + 10;
        int cyTop = pcY + 6;
        Glass.roundRect(g, cx, cyTop, cover, cover, 7, a(t == null && on.nowEntry() == null ? 0x4A4D55 : coverColor(title), 0.92f));
        String ini = initial(title);
        GlassPanelRenderer.text(g, font, ini, cx + (cover - GlassPanelRenderer.width(font, ini)) / 2, cyTop + 10, 0xFFFFFF);
        int tx = cx + cover + 8;
        int maxW = Math.max(30, r[0][0] - 8 - tx);
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, title, maxW), tx, pcY + 9, 0xFFFFFF);
        GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, artist, maxW), tx, pcY + 20, 0x9AA1AF);

        // waktu dan garis progres di bawah
        int ty = pcY + PLAYER_H - 12;
        GlassPanelRenderer.text(g, font, formatTime(pos), x0 + 10, ty, 0xB4BAC8);
        String endT = formatTime(dur);
        GlassPanelRenderer.text(g, font, endT, x1 - 10 - GlassPanelRenderer.width(font, endT), ty, 0xB4BAC8);
        int[] pb = r[6];
        int lx = pb[0] + 1;
        int lw = pb[2] - 2;
        int ly = pb[1] + 5;
        g.fill(lx, ly, lx + lw, ly + 2, a(0xFFFFFF, 0.18f));
        int filled = Math.round(lw * prog);
        if (filled > 0) {
            g.fill(lx, ly, lx + filled, ly + 2, 0xFF000000 | accent);
        }
        if (seeking || in(mx, my, pb)) {
            Glass.disc(g, lx + filled, ly + 1, 3, 0xFFFFFFFF);
        }

        // transport
        XFiveConfig c = XFiveLiveClient.CONFIG;
        int white = 0xFFE6E8EE;
        boolean rep = c.repeatMode != 0;
        drawBtnBg(g, r[0], mx, my, "repeat");
        icon(g, IC_REPEAT, r[0][0] + 2, r[0][1] + 4, rep ? (0xFF000000 | accent) : 0xFF8A8E98);
        if (c.repeatMode == 2) {
            GlassPanelRenderer.text(g, font, "1", r[0][0] + 6, r[0][1] + 4, accent);
        }
        drawBtnBg(g, r[1], mx, my, "prev");
        g.fill(r[1][0] + 3, r[1][1] + 3, r[1][0] + 5, r[1][1] + 13, white);
        triLeft(g, r[1][0] + 5, r[1][1] + 3, 10, 7, white);
        boolean pp = pressedKey.equals("play");
        int pcx = r[2][0] + 14;
        int pcy = r[2][1] + 14;
        Glass.disc(g, pcx, pcy, pp ? 12 : 14, a(accent, 1f));
        drawPlayGlyph(g, pcx + (music.isPlaying() ? 0 : 1), pcy, music.isPlaying(), 0xFFFFFFFF);
        drawBtnBg(g, r[3], mx, my, "next");
        triRight(g, r[3][0] + 3, r[3][1] + 3, 10, white);
        g.fill(r[3][0] + 10, r[3][1] + 3, r[3][0] + 12, r[3][1] + 13, white);
        drawBtnBg(g, r[4], mx, my, "shuffle");
        icon(g, IC_SHUFFLE, r[4][0] + 3, r[4][1] + 4, c.shuffle ? (0xFF000000 | accent) : 0xFF8A8E98);

        // hati (suka) lagu yang sedang diputar
        drawBtnBg(g, r[7], mx, my, "heart");
        icon(g, IC_HEART, r[7][0] + 4, r[7][1] + 4, currentLiked() ? (0xFF000000 | accent) : 0xFF8A8E98);

        if (showVolume()) {
            int[] v = r[5];
            icon(g, IC_VOLUME, v[0] - 14, v[1] + 2, 0xFFB4B8C2);
            int vy = v[1] + v[3] / 2 - 1;
            g.fill(v[0], vy, v[0] + v[2], vy + 3, a(0xFFFFFF, 0.20f));
            int vx = v[0] + Math.round(v[2] * c.defaultVolume);
            g.fill(v[0], vy, vx, vy + 3, 0xFF000000 | accent);
            Glass.roundRect(g, vx - 3, vy - 3, 7, 9, 3, 0xFFFFFFFF);
        }
    }

    private void drawBtnBg(GuiGraphicsExtractor g, int[] r, int mx, int my, String key) {
        boolean pressed = pressedKey.equals(key);
        if (pressed) {
            Glass.disc(g, r[0] + r[2] / 2, r[1] + r[3] / 2, 9, a(0xFFFFFF, 0.14f));
        } else if (in(mx, my, r)) {
            Glass.disc(g, r[0] + r[2] / 2, r[1] + r[3] / 2, 9, a(0xFFFFFF, 0.07f));
        }
    }


    // =====================================================================
    // Baris pengaturan
    // =====================================================================

    private void drawRows(GuiGraphicsExtractor g, Font font, int accent, int mx, int my, boolean clip) {
        int top = rowsTop();
        int bot = rowsBot();
        double maxScroll = Math.max(0, rowsH - (bot - top));
        rowsScroll = Math.max(0, Math.min(maxScroll, rowsScroll));
        if (clip) {
            g.enableScissor(rowsX() - 2, top, rowsR() + 2, bot);
        }
        for (Row r : rows) {
            int ax = rowsX() + r.x;
            int ay = top + r.y - (int) Math.round(rowsScroll);
            if (ay + r.h < top || ay > bot) {
                continue;
            }
            if (!clip && (ay < top || ay + r.h > bot)) {
                continue;
            }
            drawRow(g, font, accent, r, ax, ay, mx, my);
        }
        if (clip) {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            int trackH = bot - top;
            int thumbH = Math.max(14, (int) (trackH * (trackH / (trackH + maxScroll))));
            int thumbY = top + (int) ((trackH - thumbH) * (rowsScroll / maxScroll));
            g.fill(rowsR() + 3, thumbY, rowsR() + 5, thumbY + thumbH, a(0xFFFFFF, 0.35f));
        }
    }

    private int sliderX0(Row r, int ax) { return ax + (int) (r.w * 0.42); }
    private int sliderX1(Row r, int ax, Font font) {
        int vw = GlassPanelRenderer.width(font, r.value.get());
        return Math.max(sliderX0(r, ax) + 24, ax + r.w - 10 - vw - 8);
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, int accent, Row r, int ax, int ay, int mx, int my) {
        if (r.kind == Row.NOTE) {
            GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, r.label, r.w - 8), ax + 4, ay + 3,
                    0xB4BAC8);
            return;
        }
        if (r.kind == Row.HEADING) {
            GlassPanelRenderer.text(g, font, r.label.toUpperCase(Locale.ROOT), ax + 2, ay + 5, 0x8F96A6);
            g.fill(ax + 2, ay + r.h - 3, ax + r.w, ay + r.h - 2, a(0xFFFFFF, 0.10f));
            return;
        }
        boolean hov = in(mx, my, ax, ay, r.w, r.h) && my >= rowsTop() && my < rowsBot();
        boolean pressed = pressedRow == r && !dragged;
        if (r.kind == Row.ACTION) {
            Glass.roundRect(g, ax, ay, r.w, r.h, r.h / 2, a(accent, pressed ? 0.60f : (hov ? 0.50f : 0.36f)));
            int w = GlassPanelRenderer.width(font, r.label);
            GlassPanelRenderer.text(g, font, r.label, ax + (r.w - w) / 2, ay + (r.h - 9) / 2, 0xFFFFFF);
            return;
        }
        Glass.roundRect(g, ax, ay, r.w, r.h, 9, a(0xFFFFFF, pressed ? 0.15f : (hov ? 0.12f : 0.08f)));
        int labelY = ay + (r.h - 9) / 2;
        switch (r.kind) {
            case Row.TOGGLE -> {
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, r.label, r.w - 50), ax + 10, labelY,
                        0xFFFFFF);
                boolean on = r.on.getAsBoolean();
                int sx = ax + r.w - 34;
                int sy = ay + (r.h - 12) / 2;
                Glass.roundRect(g, sx, sy, 24, 12, 6, on ? a(accent, 0.95f) : a(0xFFFFFF, 0.20f));
                int kx = on ? sx + 13 : sx + 2;
                Glass.roundRect(g, kx, sy + 2, 9, 8, 4, 0xFFFFFFFF);
            }
            case Row.CYCLE -> {
                String v = r.value.get();
                int vw = GlassPanelRenderer.width(font, v);
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, r.label, r.w - vw - 28), ax + 10,
                        labelY, 0xFFFFFF);
                GlassPanelRenderer.text(g, font, v, ax + r.w - 10 - vw, labelY, accent);
            }
            case Row.SLIDER -> {
                String v = r.value.get();
                int vw = GlassPanelRenderer.width(font, v);
                int sx0 = sliderX0(r, ax);
                int sx1 = sliderX1(r, ax, font);
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, r.label, sx0 - ax - 14), ax + 10,
                        labelY, 0xFFFFFF);
                GlassPanelRenderer.text(g, font, v, ax + r.w - 10 - vw, labelY, accent);
                int ty = ay + r.h / 2 - 1;
                double f = Math.max(0, Math.min(1, r.get.getAsDouble()));
                int kx = sx0 + (int) Math.round((sx1 - sx0) * f);
                if (r.rainbow) {
                    for (int x = sx0; x < sx1; x += 2) {
                        float hue = (float) (x - sx0) / Math.max(1, sx1 - sx0);
                        g.fill(x, ty, Math.min(sx1, x + 2), ty + 3, 0xFF000000 | Themes.hsvToRgb(hue, 0.85f, 1f));
                    }
                } else {
                    g.fill(sx0, ty, sx1, ty + 3, a(0xFFFFFF, 0.20f));
                    g.fill(sx0, ty, kx, ty + 3, 0xFF000000 | accent);
                }
                Glass.roundRect(g, kx - 3, ty - 3, 7, 9, 3, 0xFFFFFFFF);
            }
            case Row.SWATCHES -> {
                GlassPanelRenderer.text(g, font, r.label, ax + 10, ay + 5, 0xB4BAC8);
                int n = swatchCount();
                int size = swatchSize(r);
                int sel = XFiveLiveClient.CONFIG.theme == null ? 0
                        : (Themes.isCustom(XFiveLiveClient.CONFIG.theme) ? n - 1 : Themes.indexOf(XFiveLiveClient.CONFIG.theme));
                for (int i = 0; i < n; i++) {
                    int sx = ax + 10 + i * (size + 4);
                    int sy = ay + 18;
                    int col = i == n - 1 ? Themes.customColor() : Themes.COLORS[i];
                    Glass.roundRect(g, sx, sy, size, 12, 4, 0xFF000000 | col);
                    if (i == n - 1) {
                        g.fill(sx + 2, sy + 5, sx + size - 2, sy + 7, a(0xFFFFFF, 0.55f));
                    }
                    if (i == sel) {
                        Glass.border(g, sx - 2, sy - 2, size + 4, 16, 5, 0xFFFFFFFF, 0xFFFFFFFF);
                    }
                }
            }
            case Row.SAMPLE -> {
                int vw = r.w - 20;
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, "Now Playing - Sample Song 0123", vw),
                        ax + 10, ay + 6, 0xFFFFFF);
                GlassPanelRenderer.text(g, font, GlassPanelRenderer.fit(font, "The quick brown fox jumps over", vw),
                        ax + 10, ay + 19, 0xB4BAC8);
            }
            default -> {
            }
        }
    }

    private int swatchCount() { return Themes.IDS.length + 1; }

    private int swatchSize(Row r) {
        int n = swatchCount();
        return Math.max(12, Math.min(26, (r.w - 20 - 4 * (n - 1)) / n));
    }

    private static final class Row {
        static final int TOGGLE = 0;
        static final int SLIDER = 1;
        static final int CYCLE = 2;
        static final int ACTION = 3;
        static final int SWATCHES = 4;
        static final int SAMPLE = 5;
        static final int NOTE = 6;
        static final int HEADING = 7;

        final int kind;
        final String label;
        Supplier<String> value = () -> "";
        BooleanSupplier on = () -> false;
        Runnable action = () -> { };
        DoubleSupplier get = () -> 0;
        DoubleConsumer set = v -> { };
        boolean rainbow;
        boolean full;
        int h = 22;
        int x;
        int y;
        int w;

        Row(int kind, String label) {
            this.kind = kind;
            this.label = label;
            if (kind == SWATCHES || kind == SAMPLE) {
                full = true;
                h = 34;
            }
            if (kind == NOTE) {
                full = true;
                h = 14;
            }
            if (kind == HEADING) {
                full = true;
                h = 16;
            }
            if (kind == ACTION) {
                h = 20;
            }
        }
    }


    private void layoutRows() {
        int w = rowsR() - rowsX();
        boolean two = w >= 300;
        int colW = two ? (w - 6) / 2 : w;
        int curY = 0;
        int col = 0;
        int lineH = 0;
        for (Row r : rows) {
            if (r.full || !two) {
                if (col == 1) {
                    curY += lineH + 4;
                    col = 0;
                    lineH = 0;
                }
                r.x = 0;
                r.w = w;
                r.y = curY;
                curY += r.h + 4;
            } else {
                r.x = col == 0 ? 0 : colW + 6;
                r.w = colW;
                r.y = curY;
                lineH = Math.max(lineH, r.h);
                if (col == 0) {
                    col = 1;
                } else {
                    col = 0;
                    curY += lineH + 4;
                    lineH = 0;
                }
            }
        }
        if (col == 1) {
            curY += lineH + 4;
        }
        rowsH = curY;
    }

    private Row toggle(String label, BooleanSupplier get, Runnable flip) {
        Row r = new Row(Row.TOGGLE, label);
        r.on = get;
        r.action = () -> {
            flip.run();
            XFiveLiveClient.CONFIG.save();
        };
        return r;
    }

    private Row cycle(String label, Supplier<String> value, Runnable next) {
        Row r = new Row(Row.CYCLE, label);
        r.value = value;
        r.action = () -> {
            next.run();
            XFiveLiveClient.CONFIG.save();
        };
        return r;
    }

    private Row slider(String label, DoubleSupplier get, DoubleConsumer set, Supplier<String> value) {
        Row r = new Row(Row.SLIDER, label);
        r.get = get;
        r.set = set;
        r.value = value;
        return r;
    }

    private Row action(String label, Runnable run) {
        Row r = new Row(Row.ACTION, label);
        r.action = () -> {
            run.run();
            XFiveLiveClient.CONFIG.save();
        };
        return r;
    }

    private Row note(String text) {
        return new Row(Row.NOTE, text);
    }

    private void drawPill(GuiGraphicsExtractor g, Font font, int[] r, String label, int accent, boolean hov,
                          boolean pressed) {
        float alpha = pressed ? 0.40f : (hov ? 0.30f : 0.20f);
        Glass.roundRect(g, r[0], r[1], r[2], r[3], 6, a(accent, alpha));
        int w = GlassPanelRenderer.width(font, label);
        GlassPanelRenderer.text(g, font, label, r[0] + (r[2] - w) / 2, r[1] + (r[3] - 9) / 2, 0xFFFFFF);
    }

    private static void drawPlayGlyph(GuiGraphicsExtractor g, int cx, int cy, boolean playing, int color) {
        if (playing) {
            g.fill(cx - 4, cy - 5, cx - 1, cy + 5, color);
            g.fill(cx + 1, cy - 5, cx + 4, cy + 5, color);
        } else {
            triRight(g, cx - 3, cy - 5, 11, color);
        }
    }

    private static String formatTime(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L);
    }

    private static String colorName() {
        return DynamicIslandHud.COLOR_NAMES[XFiveLiveClient.CONFIG.islandColor];
    }

    private static String repeatLabel() {
        return switch (XFiveLiveClient.CONFIG.repeatMode) {
            case 0 -> "Off";
            case 2 -> "One";
            default -> "All";
        };
    }

    private void beginHudDrag(String key, int[] box, double mx, double my) {
        draggedHudKey = key;
        dragOffsetX = (int) mx - box[0];
        dragOffsetY = (int) my - box[1];
        int[] pos = XFiveLiveClient.CONFIG.hudPositions.get(key);
        if (pos[0] < 0 || pos[1] < 0) {
            pos[0] = box[0];
            pos[1] = box[1];
        }
    }

    // ---------- mode atur posisi HUD ----------

    private int[] doneRect() {
        return new int[] {width / 2 - 70, height - 30, 140, 20};
    }

    private void drawHudEdit(GuiGraphicsExtractor g, Font font, int mx, int my) {
        int accent = accent();
        drawHudPreviews(g, font, accent);
        int[] d = doneRect();
        Glass.roundRect(g, d[0] - 4, d[1] - 16, d[2] + 8, d[3] + 20, 8, a(0x000000, 0.65f));
        GlassPanelRenderer.text(g, font, "Drag the previews to move", d[0] + 2, d[1] - 12, 0xFFFFFF);
        drawPill(g, font, d, "Done", accent, in(mx, my, d), false);
    }

    // =====================================================================
    // Halaman Spesifikasi (animasi: HP tergambar, chip membal, judul glitch, baris diketik)
    // =====================================================================

    private static boolean glTried;

    /** Memilih isi halaman Specs: hasil deteksi perangkat (jika siap dan diaktifkan) atau preset. */
    private static void syncSpecs() {
        if (!glTried) {
            glTried = true;
            try {
                DeviceInfo.setGlRenderer(GL11.glGetString(GL11.GL_RENDERER));
            } catch (Throwable ignored) {
                // tidak ada konteks OpenGL; GPU dibaca dari sistem saja
            }
        }
        DeviceInfo.Result d = XFiveLiveClient.CONFIG.specAuto ? DeviceInfo.detected() : null;
        if (d != null) {
            SpecSheet.use(d.title(), d.rows());
        } else {
            SpecSheet.usePreset();
        }
    }

    private void drawSpecs(GuiGraphicsExtractor g, Font font, int accent) {
        syncSpecs();
        double s = (System.nanoTime() - pageNanos) / 1_000_000_000.0;
        if (!animOn()) {
            s = 99.0;
        }
        int x = rowsX();
        int y = rowsTop();
        int w = rowsR() - x;
        int h = rowsBot() - y;
        int k = h >= 112 ? 2 : 1;
        int phoneW = 26 * k;
        boolean phone = w >= phoneW + 150;
        int tx = x + (phone ? phoneW + 16 : 2);
        int tw = x + w - tx;

        if (phone) {
            drawSpecPhone(g, x + phoneW / 2 + 2, y + h / 2, k, accent, s);
        }

        // judul besar (2x bila muat), diikuti baris spesifikasi
        int w1 = GlassPanelRenderer.width(font, SpecSheet.title());
        int rows = SpecSheet.rows().length;
        int rowH = 13;
        boolean big = tw >= 2 * w1 + 6 && h >= 2 * 9 + 8 + rows * rowH + 6;
        int hs = big ? 2 : 1;
        int totalH = 9 * hs + 8 + rows * rowH;
        int ty = y + Math.max(0, (h - totalH) / 2);

        drawSpecHeading(g, font, tx, ty, hs, accent, s);

        int ry = ty + 9 * hs + 8;
        int labelW = 0;
        for (String[] r : SpecSheet.rows()) {
            labelW = Math.max(labelW, GlassPanelRenderer.width(font, r[0]));
        }
        for (int i = 0; i < rows; i++) {
            float e = com.xfive.live.config.SpecSheet.rowIn(i, s);
            if (e <= 0f) {
                continue;
            }
            String[] r = SpecSheet.rows()[i];
            int dx = Math.round((1f - e) * 22f);
            int yy = ry + i * rowH;
            g.fill(tx + dx, yy + 1, tx + dx + 3, yy + 4, a(accent, e));
            GlassPanelRenderer.textAlpha(g, font, r[0], tx + 8 + dx, yy, 0xA9B2C8, e);
            int n = com.xfive.live.config.SpecSheet.valueChars(r[1].length(), i, s);
            if (n > 0) {
                String shown = r[1].substring(0, n);
                GlassPanelRenderer.textAlpha(g, font, shown, tx + 8 + labelW + 10 + dx, yy, 0xFFFFFF, e);
                if (n < r[1].length()) {
                    int cw = GlassPanelRenderer.width(font, shown);
                    g.fill(tx + 8 + labelW + 10 + dx + cw + 1, yy, tx + 8 + labelW + 10 + dx + cw + 3, yy + 9, a(accent, 1f));
                }
            }
            int lineW = Math.round((tw - 2) * e);
            g.fill(tx, yy + rowH - 2, tx + lineW, yy + rowH - 1, a(0xFFFFFF, 0.10f));
        }
        if (s > com.xfive.live.config.SpecSheet.total()) {
            String hint = "Tap to replay";
            GlassPanelRenderer.textAlpha(g, font, XFiveLiveClient.CONFIG.specAuto && DeviceInfo.detected() != null
                    ? "Read from this device" : "Preset", x, y + h - 9, 0xA9B2C8, 0.55f);
            GlassPanelRenderer.textAlpha(g, font, hint, x + w - GlassPanelRenderer.width(font, hint), y + h - 9,
                    0xA9B2C8, 0.55f);
        }
    }

    private void drawSpecHeading(GuiGraphicsExtractor g, Font font, int x, int y, int hs, int accent, double s) {
        String t = SpecSheet.title();
        int lastSpace = t.lastIndexOf(' ');
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale((float) hs, (float) hs);
        if (s >= com.xfive.live.config.SpecSheet.headingDone()) {
            String head = t.substring(0, lastSpace + 1);
            GlassPanelRenderer.text(g, font, head, 0, 0, 0xFFFFFF);
            GlassPanelRenderer.text(g, font, t.substring(lastSpace + 1), GlassPanelRenderer.width(font, head), 0, accent);
        } else {
            int frame = (int) (System.nanoTime() / 33_000_000L);
            int cx = 0;
            for (int i = 0; i < t.length(); i++) {
                String ch = String.valueOf(t.charAt(i));
                int cwid = GlassPanelRenderer.width(font, ch);
                int st = com.xfive.live.config.SpecSheet.headingState(i, s);
                if (st == 1 && t.charAt(i) != ' ') {
                    String gl = String.valueOf(com.xfive.live.config.SpecSheet.glyph(frame * 131 + i * 17));
                    GlassPanelRenderer.text(g, font, gl, cx + (frame + i) % 3 - 1, (frame * 7 + i) % 3 - 1, accent);
                } else if (st == 2) {
                    GlassPanelRenderer.text(g, font, ch, cx, 0, i > lastSpace ? accent : 0xFFFFFF);
                }
                cx += cwid;
            }
        }
        g.pose().popMatrix();
    }

    /** Ikon HP bergaris: tergambar, berputar masuk, chip membal dengan kaki, dan dua batang memori. */
    private void drawSpecPhone(GuiGraphicsExtractor g, int cx, int cy, int k, int accent, double s) {
        float sc = com.xfive.live.config.SpecSheet.phoneScale(s) * k;
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        g.pose().rotate(com.xfive.live.config.SpecSheet.phoneTurn(s));
        g.pose().scale(sc, sc);

        int white = 0xFFFFFFFF;
        // garis tepi: atas, kanan, bawah, kiri sesuai kemajuan
        float p = com.xfive.live.config.SpecSheet.outline(s);
        int perim = 2 * (26 + 52);
        int left = Math.round(perim * p);
        int[][] seg = {{-13, -26, 26, 1}, {12, -26, 1, 52}, {-13, 25, 26, 1}, {-13, -26, 1, 52}};
        int[] len = {26, 52, 26, 52};
        for (int i = 0; i < 4 && left > 0; i++) {
            int use = Math.min(len[i], left);
            left -= use;
            int[] q = seg[i];
            if (i == 0 || i == 2) {
                int x0 = i == 2 ? q[0] + q[2] - use : q[0];
                g.fill(x0, q[1], x0 + use, q[1] + q[3], white);
            } else {
                int y0 = i == 3 ? q[1] + q[3] - use : q[1];
                g.fill(q[0], y0, q[0] + q[2], y0 + use, white);
            }
        }
        if (p >= 1f) {
            g.fill(-4, -23, 4, -22, a(0xFFFFFF, 0.9f));   // speaker
            g.fill(-4, 22, 4, 23, a(0xFFFFFF, 0.7f));      // home bar
        }

        // chip
        float cp = com.xfive.live.config.SpecSheet.chip(s);
        if (cp > 0f) {
            g.pose().pushMatrix();
            g.pose().translate(0f, -8f);
            g.pose().scale(cp, cp);
            double pulse = 0.5 + 0.5 * Math.sin(Math.max(0.0, s - 1.2) * 4.5);
            if (s > 1.2) {
                g.fill(-8, -8, 8, 8, a(accent, (float) (0.10 + 0.18 * pulse)));
            }
            g.fill(-6, -6, 6, -5, white);
            g.fill(-6, 5, 6, 6, white);
            g.fill(-6, -5, -5, 5, white);
            g.fill(5, -5, 6, 5, white);
            g.fill(-2, -2, 2, 2, a(accent, 1f));
            for (int i = 0; i < 3; i++) {
                int len2 = Math.round(2f * com.xfive.live.config.SpecSheet.pin(s, i));
                int o = -4 + i * 4;
                if (len2 > 0) {
                    g.fill(o, -6 - len2, o + 1, -6, white);
                    g.fill(o, 6, o + 1, 6 + len2, white);
                    g.fill(-6 - len2, o, -6, o + 1, white);
                    g.fill(6, o, 6 + len2, o + 1, white);
                }
            }
            g.pose().popMatrix();
        }
        // dua batang memori (RAM dan ROM)
        for (int i = 0; i < 2; i++) {
            float bp = com.xfive.live.config.SpecSheet.bar(s, i);
            int half = Math.round(8f * Math.max(0f, Math.min(1.1f, bp)));
            if (half > 0) {
                int by = 8 + i * 6;
                g.fill(-half, by, half, by + 3, a(0xFFFFFF, 0.85f));
            }
        }
        g.pose().popMatrix();
    }

    private void drawHudPreviews(GuiGraphicsExtractor g, Font font, int accent) {
        int[] island = DynamicIslandHud.box();
        int[] music = com.xfive.live.hud.MusicHud.box();
        int[] lyrics = com.xfive.live.hud.LyricsHud.box();
        XFiveConfig c = XFiveLiveClient.CONFIG;

        int ix = island[0], iy = island[1], iw = island[2], ih = island[3];
        g.fill(ix, iy, ix + iw, iy + ih, 0xE51B2029);
        g.fill(ix, iy, ix + iw, iy + 2, a(accent, 0.95f));
        GlassPanelRenderer.text(g, font, "DYNAMIC ISLAND PREVIEW", ix + 10, iy + 8, 0xFFFFFF);
        GlassPanelRenderer.text(g, font, "Song title - Artist", ix + 10, iy + 24, 0xB8C0CE);
        g.fill(ix + 10, iy + ih - 8, ix + iw - 10, iy + ih - 6, 0x66FFFFFF);
        g.fill(ix + 10, iy + ih - 8, ix + 10 + Math.max(1, (iw - 20) / 3), iy + ih - 6, 0xFF000000 | accent);
        outline(g, ix, iy, iw, ih, a(accent, 0.95f));

        int mx = music[0], my = music[1], mw = music[2], mh = music[3];
        g.fill(mx, my, mx + mw, my + mh, 0xE51B2029);
        g.fill(mx, my, mx + mw, my + 2, a(GlassPanelRenderer.CYAN, 0.95f));
        GlassPanelRenderer.text(g, font, "MUSIC HUD PREVIEW", mx + 6, my + 5, 0xFFFFFF);
        GlassPanelRenderer.text(g, font, "Song title - Artist", mx + 6, my + 17, 0xB8C0CE);
        outline(g, mx, my, mw, mh, a(GlassPanelRenderer.CYAN, 0.95f));

        int lx = lyrics[0], ly = lyrics[1], lw = lyrics[2], lh = lyrics[3];
        g.fill(lx, ly, lx + lw, ly + lh, 0x55303038);
        String[] lines = {"Previous lyric line", "CURRENT LYRIC PREVIEW", "Next lyric line"};
        int lineH = Math.max(9, lh / 3);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 1 ? c.highlightColor : 0xB8C0CE;
            String line = GlassPanelRenderer.fit(font, lines[i], Math.max(40, lw - 12));
            GlassPanelRenderer.text(g, font, line, lx + (lw - GlassPanelRenderer.width(font, line)) / 2,
                    ly + 3 + i * lineH, color);
        }
        outline(g, lx, ly, lw, lh, a(c.highlightColor, 0.9f));
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x - 2, y - 2, x + w + 2, y, color);
        g.fill(x - 2, y + h, x + w + 2, y + h + 2, color);
        g.fill(x - 2, y, x, y + h, color);
        g.fill(x + w, y, x + w + 2, y + h, color);
    }


    private Row heading(String text) {
        return new Row(Row.HEADING, text);
    }

    private void buildRows() {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        List<Row> out = new ArrayList<>();
        if (page == Page.SETTINGS) {
            switch (tab) {
                case GENERAL -> {
                    out.add(heading("General"));
                    out.add(toggle("Enable XFiveLive", () -> c.enableMod, () -> c.enableMod = !c.enableMod));
                    out.add(toggle("Auto resume music", () -> c.autoResume, () -> c.autoResume = !c.autoResume));
                    out.add(toggle("Auto-download lyrics", () -> c.autoLyrics, () -> c.autoLyrics = !c.autoLyrics));
                    out.add(toggle("Specs: read my device", () -> c.specAuto, () -> c.specAuto = !c.specAuto));
                    out.add(heading("Library"));
                    out.add(action("Rescan music folder", XFiveLiveClient::refreshMusic));
                    out.add(note("Music folder: xfive-music   Lyrics: xfive-lirik"));
                }
                case HUD -> {
                    out.add(heading("Show"));
                    out.add(toggle("Master HUD", () -> c.showHud, () -> c.showHud = !c.showHud));
                    out.add(toggle("Music HUD", () -> c.enableMusic, () -> c.enableMusic = !c.enableMusic));
                    out.add(toggle("Dynamic Island", () -> c.dynamicIsland, () -> c.dynamicIsland = !c.dynamicIsland));
                    out.add(toggle("Lyrics HUD", () -> c.enableLyrics, () -> c.enableLyrics = !c.enableLyrics));
                    out.add(heading("Lyrics"));
                    out.add(cycle("Lyric animation", () -> LyricStyle.NAMES[LyricStyle.indexOf(c.lyricsAnim)],
                            () -> c.lyricsAnim = LyricStyle.next(c.lyricsAnim)));
                    out.add(toggle("Karaoke highlight", () -> c.lyricsKaraoke, () -> c.lyricsKaraoke = !c.lyricsKaraoke));
                    out.add(toggle("Prev/next lines", () -> c.lyricsPreview, () -> c.lyricsPreview = !c.lyricsPreview));
                    out.add(toggle("Instrumental dots", () -> c.lyricsCountdown, () -> c.lyricsCountdown = !c.lyricsCountdown));
                    out.add(cycle("Lyrics position", () -> new String[] {"Top", "Middle", "Bottom"}[c.lyricsPosition],
                            () -> c.lyricsPosition = (c.lyricsPosition + 1) % 3));
                    out.add(slider("Lyrics sync", () -> (c.lyricsOffsetMs + 3000) / 6000.0,
                            v -> c.lyricsOffsetMs = (int) (Math.round((v * 6000.0 - 3000.0) / 50.0) * 50),
                            () -> (c.lyricsOffsetMs > 0 ? "+" : "") + c.lyricsOffsetMs + " ms"));
                    out.add(heading("Size and position"));
                    out.add(slider("HUD opacity", () -> (c.hudOpacity - 0.1) / 0.9,
                            v -> c.hudOpacity = (float) (0.1 + v * 0.9),
                            () -> Math.round(c.hudOpacity * 100) + "%"));
                    out.add(slider("Bar width", () -> c.islandW / 520.0,
                            v -> c.islandW = (int) Math.round(v * 520), () -> c.islandW <= 0 ? "Hidden" : c.islandW + " px"));
                    out.add(slider("Bar height", () -> c.islandH / 160.0,
                            v -> c.islandH = (int) Math.round(v * 160), () -> c.islandH <= 0 ? "Hidden" : c.islandH + " px"));
                    out.add(action("Move HUDs (drag)", () -> hudEditMode = true));
                    out.add(action("Reset HUD positions", c::resetHudPositions));
                    out.add(action("Reset bar size", XFiveLiveClient::resetIslandSize));
                }
                case CONTROLS -> {
                    out.add(heading("Dynamic bar"));
                    out.add(cycle("Bar shape", () -> c.islandCard ? "Card" : "Pill", XFiveLiveClient::toggleIslandCard));
                    out.add(cycle("Bar color", ControlCenterScreen::colorName, XFiveLiveClient::cycleIslandColor));
                    out.add(toggle("Auto-hide pop-up", () -> c.islandAutoHide, () -> c.islandAutoHide = !c.islandAutoHide));
                    out.add(slider("Pop-up time", () -> (c.islandShowSec - 2) / 28.0,
                            v -> c.islandShowSec = 2 + (int) Math.round(v * 28), () -> c.islandShowSec + " s"));
                    out.add(toggle("Show while paused", () -> c.islandShowPaused, XFiveLiveClient::toggleIslandShowPaused));
                    out.add(heading("Playback"));
                    out.add(cycle("Repeat", ControlCenterScreen::repeatLabel, XFiveLiveClient::cycleRepeat));
                    out.add(toggle("Shuffle", () -> c.shuffle, XFiveLiveClient::toggleShuffle));
                }
                case LOOK -> {
                    out.add(heading("Color"));
                    out.add(new Row(Row.SWATCHES, "Theme color (last swatch = your own color)"));
                    Row hue = slider("Hue", () -> c.customHue, v -> setCustom((float) v, c.customSat, c.customVal),
                            () -> Math.round(c.customHue * 360) + " deg");
                    hue.rainbow = true;
                    out.add(hue);
                    out.add(slider("Saturation", () -> c.customSat, v -> setCustom(c.customHue, (float) v, c.customVal),
                            () -> Math.round(c.customSat * 100) + "%"));
                    out.add(slider("Brightness", () -> c.customVal, v -> setCustom(c.customHue, c.customSat, (float) v),
                            () -> Math.round(c.customVal * 100) + "%"));
                    out.add(heading("Style"));
                    out.add(cycle("UI style", () -> "dark_music".equals(c.themeStyle) ? "Dark Music" : "Liquid Glass Lite",
                            () -> c.themeStyle = "dark_music".equals(c.themeStyle) ? "glass" : "dark_music"));
                    out.add(cycle("Font", () -> Fonts.NAMES[Fonts.indexOf(c.uiFont)], () -> c.uiFont = Fonts.next(c.uiFont)));
                    out.add(new Row(Row.SAMPLE, "Sample"));
                    out.add(slider("Panel opacity", () -> (c.customTransparency - 0.1) / 0.9,
                            v -> c.customTransparency = (float) (0.1 + v * 0.9),
                            () -> Math.round(c.customTransparency * 100) + "%"));
                    out.add(toggle("Spring animations", () -> c.animations, () -> c.animations = !c.animations));
                }
                case SOURCES -> {
                    out.add(heading("Online music (no download)"));
                    out.add(note("Audius: full songs, free, played straight away."));
                    out.add(note("Apple previews: 30-second clips when a hit is not on Audius."));
                    out.add(note("Charts: top songs of 12 countries, updated about every 30 minutes."));
                    out.add(heading("Your own music"));
                    out.add(note("Put mp3/m4a/ogg/wav files in the xfive-music folder (Now Playing > Add)."));
                    out.add(heading("Spotify and YouTube Music"));
                    out.add(note("Their songs are copyright-locked, so XFiveLive cannot play them itself."));
                }
                default -> {
                }
            }
        }
        rows = out;
        layoutRows();
    }

    private static void setCustom(float h, float s, float v) {
        XFiveConfig c = XFiveLiveClient.CONFIG;
        c.customHue = h;
        c.customSat = s;
        c.customVal = v;
        c.theme = Themes.CUSTOM;
        c.applyCustomColor();
    }

    // =====================================================================
    // Navigasi
    // =====================================================================

    private void goTo(Page target) {
        goTo(target, false);
    }

    private void goTo(Page target, boolean keepSearchFocus) {
        if (target == page && !keepSearchFocus) {
            return;
        }
        page = target;
        filterText = "";
        songScroll = 0;
        rowsScroll = 0;
        itemsSig = Long.MIN_VALUE;
        pageNanos = System.nanoTime();
        enterPage();
        buildRows();
        refocusSearch = keepSearchFocus;
        rebuildWidgets();
    }

    private void goToTab(Tab target) {
        tab = target;
        page = Page.SETTINGS;
        songScroll = 0;
        rowsScroll = 0;
        pageNanos = System.nanoTime();
        buildRows();
        rebuildWidgets();
    }

    /** Menjalankan pencarian dari kotak di sidebar (setelah jeda mengetik atau saat ikon kaca diketuk). */
    private void submitSearch() {
        searchPendingAt = 0L;
        String q = searchText.strip();
        if (q.length() < 2) {
            return;
        }
        XFiveLiveClient.ONLINE.search(q);
        itemsSig = Long.MIN_VALUE;
        if (page != Page.SEARCH) {
            goTo(Page.SEARCH, true);
        }
    }

    // =====================================================================
    // Input
    // =====================================================================

    private void playOrPause() {
        var m = XFiveLiveClient.MUSIC;
        if (m.currentTrack() == null && m.playlist().size() > 0 && XFiveLiveClient.ONLINE.nowEntry() == null) {
            XFiveLiveClient.playLocal(0);
        } else {
            m.togglePause();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (closing || !settled()) {
            return true; // abaikan ketukan selama animasi pop-in/out
        }
        if (hudEditMode) {
            if (in(mx, my, doneRect())) {
                hudEditMode = false;
                XFiveLiveClient.CONFIG.save();
                return true;
            }
            int[][] boxes = {DynamicIslandHud.box(), com.xfive.live.hud.MusicHud.box(),
                    com.xfive.live.hud.LyricsHud.box()};
            String[] keys = {"dynamicIsland", "musicHud", "lyricsHud"};
            for (int i = 0; i < boxes.length; i++) {
                if (in(mx, my, boxes[i])) {
                    beginHudDrag(keys[i], boxes[i], mx, my);
                    return true;
                }
            }
            return true;
        }
        layout();

        if (in(mx, my, closeRect())) {
            onClose();
            return true;
        }

        // sidebar
        if (!wideSide && in(mx, my, searchRect)) {
            pressedKey = "search";
            goTo(Page.SEARCH, true);
            return true;
        }
        if (wideSide && in(mx, my, searchRect[0], searchRect[1], 18, searchRect[3])) {
            submitSearch();
            return true;
        }
        for (int i = 0; i < NAV.length; i++) {
            if (in(mx, my, navRects[i])) {
                pressedKey = "nav" + i;
                if (NAV[i] == Page.CHARTS || NAV[i] == Page.HOME || NAV[i] == Page.NOW || NAV[i] == Page.FAVORITES) {
                    goTo(NAV[i]);
                }
                return true;
            }
        }
        if (in(mx, my, settingsRect)) {
            goTo(Page.SETTINGS);
            return true;
        }
        if (userRect != null && in(mx, my, userRect)) {
            goToTab(Tab.SOURCES);
            return true;
        }

        // bar pemutar
        int[][] pr = playerRects();
        if (in(mx, my, pr[6])) {
            seeking = true;
            seekAt(mx);
            return true;
        }
        String[] keys = {"repeat", "prev", "play", "next", "shuffle"};
        for (int i = 0; i < 5; i++) {
            if (in(mx, my, pr[i])) {
                pressedKey = keys[i];
                switch (i) {
                    case 0 -> XFiveLiveClient.cycleRepeat();
                    case 1 -> XFiveLiveClient.MUSIC.previous();
                    case 2 -> playOrPause();
                    case 3 -> XFiveLiveClient.MUSIC.next();
                    default -> XFiveLiveClient.toggleShuffle();
                }
                return true;
            }
        }
        if (in(mx, my, pr[7])) {
            pressedKey = "heart";
            toggleCurrentFavorite();
            itemsSig = Long.MIN_VALUE;
            return true;
        }
        if (showVolume() && in(mx, my, pr[5][0] - 4, pr[5][1] - 3, pr[5][2] + 8, pr[5][3] + 6)) {
            volumeDrag = true;
            setVolumeAt(mx);
            return true;
        }

        // judul, tab, chip
        if (isListPage() && in(mx, my, segRect())) {
            gridMode = mx >= segRect()[0] + segRect()[2] / 2;
            songScroll = 0;
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true; // EditBox
        }
        if (page == Page.SETTINGS) {
            int[][] tr = tabRects();
            for (int i = 0; i < tr.length; i++) {
                if (in(mx, my, tr[i])) {
                    goToTab(Tab.values()[i]);
                    return true;
                }
            }
        }
        if (page == Page.CHARTS) {
            int[][] cr = chipRects();
            for (int i = 0; i < cr.length; i++) {
                if (in(mx, my, cr[i])) {
                    chartId = OnlineManager.CHARTS.get(i).id();
                    songScroll = 0;
                    itemsSig = Long.MIN_VALUE;
                    XFiveLiveClient.ONLINE.loadChart(chartId, false);
                    return true;
                }
            }
        }
        if (page == Page.NOW) {
            if (in(mx, my, addRect())) {
                pressedKey = "add";
                String raw = sourceInput == null ? pendingSource : sourceInput.getValue();
                String msg = XFiveLiveClient.addSource(raw);
                if (msg.startsWith("Ditambahkan")) {
                    pendingSource = "";
                    if (sourceInput != null) {
                        sourceInput.setValue("");
                    }
                }
                XFiveLiveClient.MUSIC.setStatus(msg);
                itemsSig = Long.MIN_VALUE;
                return true;
            }
            if (in(mx, my, refreshRect())) {
                pressedKey = "refresh";
                XFiveLiveClient.refreshMusic();
                songScroll = 0;
                itemsSig = Long.MIN_VALUE;
                return true;
            }
        }

        // isi halaman
        if (page == Page.SETTINGS) {
            if (tab == Tab.SPECS) {
                if (in(mx, my, rowsX(), rowsTop(), rowsR() - rowsX(), rowsBot() - rowsTop())) {
                    pageNanos = System.nanoTime(); // ketuk untuk memutar ulang animasi
                    XFiveLiveClient.playUiSound();
                }
                return true;
            }
            return clickRows(mx, my);
        }
        if (in(mx, my, x0, contentTop(), x1 - x0, contentBot() - contentTop())) {
            pressTarget = 1;
            dragged = false;
            pressX = mx;
            pressY = my;
            lastY = my;
            return true;
        }
        return false;
    }

    private Row rowAt(double mx, double my) {
        if (my < rowsTop() || my >= rowsBot()) {
            return null;
        }
        for (Row r : rows) {
            int ax = rowsX() + r.x;
            int ay = rowsTop() + r.y - (int) Math.round(rowsScroll);
            if (in(mx, my, ax, ay, r.w, r.h)) {
                return r;
            }
        }
        return null;
    }

    private boolean clickRows(double mx, double my) {
        Row r = rowAt(mx, my);
        if (r != null && r.kind == Row.SLIDER) {
            int ax = rowsX() + r.x;
            Font font = GlassPanelRenderer.font();
            if (mx >= sliderX0(r, ax) - 6 && mx <= sliderX1(r, ax, font) + 6) {
                activeSlider = r;
                setSlider(r, mx);
                return true;
            }
        }
        pressTarget = 2;
        pressedRow = r;
        dragged = false;
        pressX = mx;
        pressY = my;
        lastY = my;
        return true;
    }

    private void setSlider(Row r, double mx) {
        int ax = rowsX() + r.x;
        int sx0 = sliderX0(r, ax);
        int sx1 = sliderX1(r, ax, GlassPanelRenderer.font());
        double f = Math.max(0, Math.min(1, (mx - sx0) / Math.max(1, sx1 - sx0)));
        r.set.accept(f);
    }

    private void setVolumeAt(double mx) {
        int[] v = playerRects()[5];
        float f = (float) Math.max(0, Math.min(1, (mx - v[0]) / Math.max(1, v[2])));
        XFiveLiveClient.CONFIG.defaultVolume = f;
        XFiveLiveClient.MUSIC.setVolume(f);
    }

    private void seekAt(double mx) {
        long dur = XFiveLiveClient.MUSIC.durationMs();
        if (dur <= 0L) {
            return;
        }
        int[] s = playerRects()[6];
        double f = (mx - (s[0] + 1)) / Math.max(1, s[2] - 2);
        XFiveLiveClient.MUSIC.seekTo((float) Math.max(0, Math.min(1, f)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x();
        double my = event.y();
        if (draggedHudKey != null && hudEditMode) {
            int[] pos = XFiveLiveClient.CONFIG.hudPositions.get(draggedHudKey);
            pos[0] = Math.max(0, Math.min(Math.max(0, width - 40), (int) mx - dragOffsetX));
            pos[1] = Math.max(0, Math.min(Math.max(0, height - 24), (int) my - dragOffsetY));
            return true;
        }
        if (seeking) {
            seekAt(mx);
            return true;
        }
        if (volumeDrag) {
            setVolumeAt(mx);
            return true;
        }
        if (activeSlider != null) {
            setSlider(activeSlider, mx);
            return true;
        }
        if (pressTarget != 0) {
            if (Math.abs(my - pressY) > 4 || Math.abs(mx - pressX) > 4) {
                dragged = true;
            }
            if (dragged) {
                if (pressTarget == 1) {
                    songScroll -= my - lastY;
                } else {
                    rowsScroll -= my - lastY;
                }
            }
            lastY = my;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        pressedKey = "";
        boolean handled = false;
        if (draggedHudKey != null) {
            draggedHudKey = null;
            XFiveLiveClient.CONFIG.save();
            return true;
        }
        if (seeking) {
            seeking = false;
            handled = true;
        }
        if (volumeDrag) {
            volumeDrag = false;
            XFiveLiveClient.CONFIG.save();
            handled = true;
        }
        if (activeSlider != null) {
            activeSlider = null;
            XFiveLiveClient.CONFIG.save();
            handled = true;
        }
        if (pressTarget == 1) {
            if (!dragged) {
                if (page == Page.HOME) {
                    tapHome(pressX, pressY);
                } else {
                    tapItems(pressX, pressY);
                }
            }
            handled = true;
        } else if (pressTarget == 2) {
            if (!dragged && pressedRow != null) {
                activateRow(pressedRow, event.x());
            }
            handled = true;
        }
        pressTarget = 0;
        pressedRow = null;
        dragged = false;
        if (handled) {
            return true;
        }
        return super.mouseReleased(event);
    }

    private void activateRow(Row r, double mx) {
        if (r.kind == Row.SWATCHES) {
            int size = swatchSize(r);
            int ax = rowsX() + r.x;
            int i = (int) Math.floor((mx - (ax + 10)) / (size + 4));
            XFiveConfig c = XFiveLiveClient.CONFIG;
            if (i >= 0 && i < Themes.IDS.length) {
                c.theme = Themes.IDS[i];
                c.save();
            } else if (i == Themes.IDS.length) {
                c.theme = Themes.CUSTOM;
                c.applyCustomColor();
                c.save();
            }
            return;
        }
        if (r.kind == Row.TOGGLE || r.kind == Row.CYCLE || r.kind == Row.ACTION) {
            r.action.run();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean inside = in(mouseX, mouseY, x0, contentTop(), x1 - x0, contentBot() - contentTop());
        if (inside && page != Page.SETTINGS) {
            songScroll -= scrollY * ROW_STEP;
            return true;
        }
        if (inside && page == Page.SETTINGS) {
            rowsScroll -= scrollY * 26;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
