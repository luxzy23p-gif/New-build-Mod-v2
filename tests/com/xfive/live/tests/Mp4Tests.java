package com.xfive.live.tests;

import com.xfive.live.music.Mp4Aac;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/** Uji pembaca M4A: file MP4 dibuat sendiri, termasuk kotak metadata rusak yang membuat JAAD gagal. */
final class Mp4Tests {
    private final BiConsumer<String, Boolean> check;

    Mp4Tests(BiConsumer<String, Boolean> check) {
        this.check = check;
    }

    void run() throws IOException {
        byte[][] frames = new byte[10][];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new byte[20 + i * 3];
            java.util.Arrays.fill(frames[i], (byte) (i + 1));
        }
        byte[] cfg = {0x12, 0x10};

        for (boolean moovFirst : new boolean[] {true, false}) {
            for (boolean co64 : new boolean[] {false, true}) {
                String tag = (moovFirst ? "moov di depan" : "moov di belakang") + (co64 ? ", co64" : ", stco");
                byte[] file = build(frames, cfg, moovFirst, co64, false, "mp4a", 0);
                Mp4Aac.Result r = Mp4Aac.parse(file);
                check.accept("mp4 " + tag + ": jumlah bingkai", r.count() == 10);
                check.accept("mp4 " + tag + ": konfigurasi AAC", r.config().length == 2 && r.config()[0] == 0x12 && r.config()[1] == 0x10);
                boolean same = true;
                for (int i = 0; i < r.count(); i++) {
                    same &= r.sizes()[i] == frames[i].length;
                    for (int k = 0; k < frames[i].length && same; k++) {
                        same &= file[r.offsets()[i] + k] == frames[i][k];
                    }
                }
                check.accept("mp4 " + tag + ": isi bingkai sama", same);
            }
        }

        // metadata rusak (ukuran kotak nol / lebih kecil dari header / melewati akhir file) tidak boleh menghentikan
        byte[] junk = build(frames, cfg, false, false, true, "mp4a", 0);
        check.accept("mp4 metadata rusak tetap terbaca", Mp4Aac.parse(junk).count() == 10);

        // ukuran bingkai tetap (stsz bukan tabel)
        byte[] constant = build(frames, cfg, true, false, false, "mp4a", 25);
        Mp4Aac.Result c = Mp4Aac.parse(constant);
        check.accept("mp4 ukuran tetap: ukuran 25", c.count() > 0 && c.sizes()[0] == 25);

        // file terpotong di tengah data: bingkai yang utuh tetap dipakai
        byte[] full = build(frames, cfg, true, false, false, "mp4a", 0);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 60);
        int n = Mp4Aac.parse(cut).count();
        check.accept("mp4 terpotong: sebagian bingkai dipakai (" + n + ")", n > 0 && n < 10);

        // Apple Lossless: pesan jelas
        try {
            Mp4Aac.parse(build(frames, cfg, true, false, false, "alac", 0));
            check.accept("mp4 alac ditolak", false);
        } catch (IOException e) {
            check.accept("mp4 alac: pesan jelas", e.getMessage().contains("bukan AAC"));
        }

        // bukan M4A sama sekali
        try {
            Mp4Aac.parse("ini bukan file m4a, hanya teks biasa yang panjang".getBytes(StandardCharsets.UTF_8));
            check.accept("mp4 sampah ditolak", false);
        } catch (IOException e) {
            check.accept("mp4 sampah ditolak dengan pesan", e.getMessage() != null && !e.getMessage().isEmpty());
        }
        // tidak boleh melempar selain IOException untuk file kecil/terpotong
        boolean onlyIo = true;
        for (int len = 0; len < full.length; len += 7) {
            try {
                Mp4Aac.parse(java.util.Arrays.copyOf(full, len));
            } catch (IOException e) {
                // wajar
            } catch (RuntimeException e) {
                onlyIo = false;
            }
        }
        check.accept("mp4 file terpotong di mana pun hanya memberi IOException", onlyIo);
    }

    // ------------------------------------------------------------------ pembangun MP4

    private static byte[] build(byte[][] frames, byte[] cfg, boolean moovFirst, boolean co64, boolean junk,
                                String codec, int constSize) throws IOException {
        byte[] ftyp = box("ftyp", cat("M4A ".getBytes(StandardCharsets.ISO_8859_1), u32(0), "isom".getBytes(StandardCharsets.ISO_8859_1)));
        int dataLen = 0;
        for (byte[] f : frames) {
            dataLen += f.length;
        }
        if (constSize > 0) {
            dataLen = constSize * frames.length;
        }
        // panjang moov tidak bergantung pada nilai offset, jadi dibangun dua kali
        byte[] moovProbe = moov(frames, cfg, co64, junk, codec, constSize, 0);
        int mdatStart = ftyp.length + (moovFirst ? moovProbe.length : 0) + 8;
        byte[] moov = moov(frames, cfg, co64, junk, codec, constSize, mdatStart);
        ByteArrayOutputStream mdatBody = new ByteArrayOutputStream();
        if (constSize > 0) {
            for (int i = 0; i < frames.length; i++) {
                byte[] f = new byte[constSize];
                java.util.Arrays.fill(f, (byte) (i + 1));
                mdatBody.write(f);
            }
        } else {
            for (byte[] f : frames) {
                mdatBody.write(f);
            }
        }
        byte[] mdat = box("mdat", mdatBody.toByteArray());
        return moovFirst ? cat(ftyp, moov, mdat) : cat(ftyp, mdat, moov);
    }

    private static byte[] moov(byte[][] frames, byte[] cfg, boolean co64, boolean junk, String codec, int constSize,
                               int firstOffset) throws IOException {
        byte[] esds = box("esds", cat(u32(0),
                new byte[] {0x03, (byte) (23 + cfg.length), 0, 1, 0},
                new byte[] {0x04, (byte) (15 + cfg.length), 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new byte[] {0x05, (byte) cfg.length}, cfg, new byte[] {0x06, 1, 2}));
        byte[] entry = box(codec, cat(new byte[6], new byte[] {0, 1}, new byte[8], new byte[] {0, 2, 0, 16, 0, 0, 0, 0, (byte) 0xAC, 0x44, 0, 0},
                esds));
        byte[] stsd = box("stsd", cat(u32(0), u32(1), entry));
        byte[] stts = box("stts", cat(u32(0), u32(1), u32(frames.length), u32(1024)));
        byte[] stsc = box("stsc", cat(u32(0), u32(3), u32(1), u32(3), u32(1), u32(2), u32(2), u32(1), u32(5), u32(1), u32(1)));
        // 10 bingkai dalam 5 potongan: 3 + 2 + 2 + 2 + 1
        int[] chunkSizes = {3, 2, 2, 2, 1};
        byte[] stsz;
        if (constSize > 0) {
            stsz = box("stsz", cat(u32(0), u32(constSize), u32(frames.length)));
        } else {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            z.write(u32(0));
            z.write(u32(0));
            z.write(u32(frames.length));
            for (byte[] f : frames) {
                z.write(u32(f.length));
            }
            stsz = box("stsz", z.toByteArray());
        }
        // offset tiap potongan
        ByteArrayOutputStream co = new ByteArrayOutputStream();
        co.write(u32(0));
        co.write(u32(chunkSizes.length));
        long off = firstOffset;
        int sample = 0;
        for (int c : chunkSizes) {
            co.write(co64 ? u64(off) : u32((int) off));
            for (int k = 0; k < c; k++) {
                off += constSize > 0 ? constSize : frames[sample].length;
                sample++;
            }
        }
        byte[] stco = box(co64 ? "co64" : "stco", co.toByteArray());
        byte[] stbl = box("stbl", cat(stsd, stts, stsc, stsz, stco));
        byte[] minf = box("minf", stbl);
        byte[] hdlr = box("hdlr", cat(u32(0), u32(0), "soun".getBytes(StandardCharsets.ISO_8859_1), new byte[12], new byte[] {0}));
        byte[] mdia = box("mdia", cat(hdlr, minf));
        byte[] trak = box("trak", cat(box("tkhd", new byte[84]), mdia));
        byte[] udta = new byte[0];
        if (junk) {
            // meta dengan anak yang ukurannya 0, 3 (lebih kecil dari header), dan melewati akhir
            byte[] bad = cat(u32(0), "free".getBytes(StandardCharsets.ISO_8859_1), new byte[4],
                    u32(3), "ilst".getBytes(StandardCharsets.ISO_8859_1),
                    u32(99999), "covr".getBytes(StandardCharsets.ISO_8859_1), new byte[10]);
            udta = box("udta", box("meta", cat(u32(0), bad)));
        }
        return box("moov", cat(box("mvhd", new byte[100]), udta, trak));
    }

    private static byte[] box(String type, byte[] body) {
        return cat(u32(body.length + 8), type.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u64(long v) {
        return cat(u32((int) (v >>> 32)), u32((int) v));
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
