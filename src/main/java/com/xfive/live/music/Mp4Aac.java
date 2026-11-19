package com.xfive.live.music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pembaca kontainer MP4/M4A khusus untuk mengambil bingkai AAC. Murni Java, tanpa library.
 * Hanya kotak yang dibutuhkan yang dibaca (moov/trak/mdia/minf/stbl: stsd, stsz, stsc, stco/co64).
 * Kotak metadata (udta, meta, ilst, cover) dilewati, jadi tag aneh dari iTunes tidak bisa menyebabkan
 * "error while decoding box". File yang terpotong tetap dibaca sebanyak yang ada.
 */
public final class Mp4Aac {
    /** Hasil: konfigurasi AAC (AudioSpecificConfig) dan posisi tiap bingkai di dalam data. */
    public record Result(byte[] config, int[] offsets, int[] sizes) {
        public int count() {
            return offsets.length;
        }
    }

    private static final int MAX_SAMPLES = 8_000_000;

    private Mp4Aac() {
    }

    /** Membaca jalur audio AAC pertama. Melempar IOException dengan pesan jelas jika bukan AAC atau rusak. */
    public static Result parse(byte[] d) throws IOException {
        int[] moov = find(d, 0, d.length, "moov");
        if (moov == null) {
            throw new IOException("M4A tidak punya kotak moov (file terpotong atau bukan M4A)");
        }
        String unsupported = null;
        int pos = moov[0];
        while (pos + 8 <= moov[1]) {
            long[] box = header(d, pos, moov[1]);
            if (box == null) {
                break;
            }
            if (type(d, pos).equals("trak")) {
                try {
                    Result r = parseTrak(d, (int) box[1], (int) box[2]);
                    if (r != null) {
                        return r;
                    }
                } catch (UnsupportedFormat e) {
                    unsupported = e.getMessage();
                }
            }
            pos = (int) box[2];
        }
        if (unsupported != null) {
            throw new IOException(unsupported);
        }
        throw new IOException("M4A tidak punya jalur audio AAC");
    }

    private static final class UnsupportedFormat extends Exception {
        UnsupportedFormat(String m) {
            super(m);
        }
    }

    /** Mengembalikan null jika trak ini bukan audio. */
    private static Result parseTrak(byte[] d, int s, int e) throws IOException, UnsupportedFormat {
        int[] mdia = find(d, s, e, "mdia");
        if (mdia == null) {
            return null;
        }
        int[] hdlr = find(d, mdia[0], mdia[1], "hdlr");
        if (hdlr != null && hdlr[1] - hdlr[0] >= 12 && !"soun".equals(ascii(d, hdlr[0] + 8))) {
            return null;
        }
        int[] minf = find(d, mdia[0], mdia[1], "minf");
        int[] stbl = minf == null ? null : find(d, minf[0], minf[1], "stbl");
        if (stbl == null) {
            return null;
        }
        int[] stsd = find(d, stbl[0], stbl[1], "stsd");
        int[] stsz = find(d, stbl[0], stbl[1], "stsz");
        int[] stsc = find(d, stbl[0], stbl[1], "stsc");
        int[] stco = find(d, stbl[0], stbl[1], "stco");
        int[] co64 = stco == null ? find(d, stbl[0], stbl[1], "co64") : null;
        if (stsd == null || stsz == null || stsc == null || (stco == null && co64 == null)) {
            throw new IOException("M4A rusak: tabel posisi bingkai tidak lengkap");
        }
        byte[] config = readConfig(d, stsd[0], stsd[1]);

        // ukuran tiap bingkai
        int p = stsz[0] + 4;
        need(d, p + 8, stsz[1]);
        long constSize = u32(d, p);
        long n = u32(d, p + 4);
        if (n <= 0 || n > MAX_SAMPLES) {
            throw new IOException("M4A rusak: jumlah bingkai tidak masuk akal");
        }
        int count = (int) n;
        int[] sizes = new int[count];
        if (constSize != 0) {
            java.util.Arrays.fill(sizes, (int) Math.min(constSize, Integer.MAX_VALUE));
        } else {
            int avail = Math.min(count, Math.max(0, (stsz[1] - (p + 8)) / 4));
            for (int i = 0; i < avail; i++) {
                sizes[i] = (int) Math.min(u32(d, p + 8 + i * 4), Integer.MAX_VALUE);
            }
            count = avail;
        }

        // peta bingkai ke potongan (chunk)
        int q = stsc[0] + 4;
        need(d, q + 4, stsc[1]);
        int runs = (int) Math.min(u32(d, q), (stsc[1] - (q + 4)) / 12);
        long[] firstChunk = new long[runs];
        long[] perChunk = new long[runs];
        for (int i = 0; i < runs; i++) {
            firstChunk[i] = u32(d, q + 4 + i * 12);
            perChunk[i] = u32(d, q + 8 + i * 12);
        }
        int chunkTable = (stco != null ? stco[0] : co64[0]) + 4;
        int entry = stco != null ? 4 : 8;
        int tableEnd = stco != null ? stco[1] : co64[1];
        need(d, chunkTable + 4, tableEnd);
        int chunks = (int) Math.min(u32(d, chunkTable), (tableEnd - (chunkTable + 4)) / entry);

        List<int[]> frames = new ArrayList<>();
        int sample = 0;
        outer:
        for (int c = 0; c < chunks && sample < count; c++) {
            long off = entry == 4 ? u32(d, chunkTable + 4 + c * 4) : u64(d, chunkTable + 4 + c * 8);
            long perThis = 1;
            for (int r = 0; r < runs; r++) {
                if (firstChunk[r] <= c + 1) {
                    perThis = perChunk[r];
                }
            }
            for (long k = 0; k < perThis && sample < count; k++) {
                int sz = sizes[sample];
                if (sz <= 0 || off < 0 || off + sz > d.length) {
                    break outer; // file terpotong: pakai bingkai yang sudah ada
                }
                frames.add(new int[] {(int) off, sz});
                off += sz;
                sample++;
            }
        }
        if (frames.isEmpty()) {
            throw new IOException("M4A tidak berisi bingkai audio yang bisa dibaca");
        }
        int[] offs = new int[frames.size()];
        int[] szs = new int[frames.size()];
        for (int i = 0; i < offs.length; i++) {
            offs[i] = frames.get(i)[0];
            szs[i] = frames.get(i)[1];
        }
        return new Result(config, offs, szs);
    }

    /** Mencari konfigurasi AAC di dalam kotak stsd (kotak esds, boleh bersarang di dalam wave). */
    private static byte[] readConfig(byte[] d, int s, int e) throws IOException, UnsupportedFormat {
        int entryAt = s + 8; // versi+flag (4) dan jumlah entri (4)
        need(d, entryAt + 8, e);
        String fmt = ascii(d, entryAt + 4);
        if (!fmt.equals("mp4a")) {
            throw new UnsupportedFormat("Audio ini berformat \"" + fmt.trim()
                    + "\" (bukan AAC, mungkin Apple Lossless). Pakai AAC, MP3, OGG, atau WAV.");
        }
        int entryEnd = (int) Math.min(e, entryAt + Math.max(8, u32(d, entryAt)));
        int esds = indexOf(d, "esds", entryAt + 8, entryEnd);
        if (esds < 0) {
            throw new IOException("M4A rusak: tidak ada konfigurasi AAC (esds)");
        }
        int p = esds + 4 + 4; // nama kotak + versi/flag
        while (p + 2 <= entryEnd) {
            int tag = d[p++] & 0xFF;
            int len = 0;
            for (int i = 0; i < 4 && p < entryEnd; i++) {
                int b = d[p++] & 0xFF;
                len = (len << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (tag == 0x03) {
                p += 2;
                int flags = p < entryEnd ? d[p] & 0xFF : 0;
                p += 1;
                if ((flags & 0x80) != 0) {
                    p += 2;
                }
                if ((flags & 0x40) != 0 && p < entryEnd) {
                    p += 1 + (d[p] & 0xFF);
                }
                if ((flags & 0x20) != 0) {
                    p += 2;
                }
            } else if (tag == 0x04) {
                int oti = p < entryEnd ? d[p] & 0xFF : 0;
                if (oti != 0x40 && oti != 0x66 && oti != 0x67 && oti != 0x68) {
                    throw new UnsupportedFormat("Audio ini bukan AAC (kode " + oti + "). Pakai AAC, MP3, OGG, atau WAV.");
                }
                p += 13;
            } else if (tag == 0x05) {
                if (len < 2 || p + len > entryEnd) {
                    throw new IOException("M4A rusak: konfigurasi AAC terpotong");
                }
                byte[] cfg = new byte[len];
                System.arraycopy(d, p, cfg, 0, len);
                return cfg;
            } else {
                p += len;
            }
        }
        throw new IOException("M4A rusak: konfigurasi AAC tidak ditemukan");
    }

    // ------------------------------------------------------------------ kotak dasar

    /** Isi kotak pertama bertipe type di antara [s, e): {awal isi, akhir kotak}. Null jika tidak ada. */
    private static int[] find(byte[] d, int s, int e, String type) {
        int pos = s;
        while (pos + 8 <= e) {
            long[] box = header(d, pos, e);
            if (box == null) {
                return null;
            }
            if (type(d, pos).equals(type)) {
                return new int[] {(int) box[1], (int) box[2]};
            }
            pos = (int) box[2];
        }
        return null;
    }

    /** {ukuran, awal isi, akhir kotak} dengan batas dijepit ke e; null jika kotak tidak masuk akal. */
    private static long[] header(byte[] d, int pos, int e) {
        long size = u32(d, pos);
        int hdr = 8;
        if (size == 1) {
            if (pos + 16 > e) {
                return null;
            }
            size = u64(d, pos + 8);
            hdr = 16;
        } else if (size == 0) {
            size = e - pos;
        }
        if (size < hdr) {
            return null;
        }
        long end = Math.min((long) e, pos + size);
        if (end <= pos) {
            return null;
        }
        return new long[] {size, pos + hdr, end};
    }

    private static String type(byte[] d, int pos) {
        return ascii(d, pos + 4);
    }

    private static String ascii(byte[] d, int pos) {
        return new String(d, pos, 4, StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] d, String name, int s, int e) {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = s; i + 4 <= e; i++) {
            if (d[i] == n[0] && d[i + 1] == n[1] && d[i + 2] == n[2] && d[i + 3] == n[3]) {
                return i;
            }
        }
        return -1;
    }

    private static void need(byte[] d, int upTo, int limit) throws IOException {
        if (upTo > limit || upTo > d.length) {
            throw new IOException("M4A rusak: tabel terpotong");
        }
    }

    private static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static long u64(byte[] d, int p) {
        return (u32(d, p) << 32) | u32(d, p + 4);
    }
}
