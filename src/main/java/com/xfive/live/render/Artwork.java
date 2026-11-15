package com.xfive.live.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.xfive.live.XFiveLiveClient;
import com.xfive.live.download.Http;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gambar sampul dari internet: diunduh di thread latar, lalu dijadikan tekstur di thread game.
 * Gambar yang gagal tidak dicoba lagi. Maksimal 24 gambar disimpan; yang paling lama tidak dipakai dilepas.
 */
public final class Artwork {
    public record Tex(Identifier id, int w, int h) {
    }

    private static final class Entry {
        volatile int state; // 0 mengunduh, 1 byte siap, 2 tekstur siap, 3 gagal
        volatile byte[] bytes;
        Tex tex;
        long used;
    }

    private static final int MAX_ENTRIES = 24;
    private static final int MAX_BYTES = 3_000_000;
    private static final Map<String, Entry> MAP = new HashMap<>();
    private static int counter = 0;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "xfive-artwork");
        t.setDaemon(true);
        return t;
    });

    private Artwork() {
    }

    /** Tekstur sampul untuk alamat gambar, atau null jika belum siap/gagal/dimatikan. Dari thread render saja. */
    public static Tex get(String url) {
        if (url == null || url.isEmpty() || !XFiveLiveClient.CONFIG.showArtwork) {
            return null;
        }
        Entry e = MAP.get(url);
        if (e == null) {
            evict();
            Entry fresh = new Entry();
            fresh.used = System.nanoTime();
            MAP.put(url, fresh);
            POOL.execute(() -> {
                try {
                    fresh.bytes = Http.getBytes(url, MAX_BYTES);
                    fresh.state = 1;
                } catch (Exception | OutOfMemoryError ex) {
                    fresh.state = 3;
                }
            });
            return null;
        }
        e.used = System.nanoTime();
        if (e.state == 1) {
            byte[] b = e.bytes;
            e.bytes = null;
            NativeImage img = null;
            try {
                img = NativeImage.read(b);
                int w = img.getWidth();
                int h = img.getHeight();
                Identifier id = Identifier.fromNamespaceAndPath("xfive-live", "art/" + (counter++));
                DynamicTexture tex = new DynamicTexture(() -> "xfive-art", img);
                img = null; // DynamicTexture memegang gambar ini dan menutupnya sendiri
                Minecraft.getInstance().getTextureManager().register(id, tex);
                tex.upload();
                e.tex = new Tex(id, w, h);
                e.state = 2;
            } catch (Exception | LinkageError ex) {
                if (img != null) {
                    img.close();
                }
                e.state = 3;
            }
        }
        return e.state == 2 ? e.tex : null;
    }

    private static void evict() {
        if (MAP.size() < MAX_ENTRIES) {
            return;
        }
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> en : MAP.entrySet()) {
            if (en.getValue().used < t && en.getValue().state != 0) {
                t = en.getValue().used;
                oldest = en.getKey();
            }
        }
        if (oldest != null) {
            Entry gone = MAP.remove(oldest);
            if (gone.tex != null) {
                try {
                    Minecraft.getInstance().getTextureManager().release(gone.tex.id());
                } catch (RuntimeException ignored) {
                    // sudah dilepas
                }
            }
        }
    }

    /** Menggambar sampul persegi ukuran size di (x, y). */
    public static void draw(GuiGraphicsExtractor g, Tex t, int x, int y, int size) {
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, size, size, t.w(), t.h(), t.w(), t.h());
    }

    /** Sampul untuk lagu yang sedang diputar (hanya lagu online yang punya sampul). */
    public static Tex forCurrent() {
        return get(XFiveLiveClient.artUrl(XFiveLiveClient.MUSIC.currentTrack()));
    }
}
