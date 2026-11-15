package com.xfive.live.download;

/** Mengenali jenis file audio dari byte awal (bukan dari nama), agar halaman web tidak tersimpan sebagai lagu. */
public final class AudioSniffer {
    private AudioSniffer() {
    }

    /** Ekstensi ("mp3", "m4a", "ogg", "wav") atau null jika bukan audio yang didukung. */
    public static String sniff(byte[] b, int len) {
        if (b == null || len < 4) {
            return null;
        }
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return "mp3";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
            return "mp3";
        }
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return "ogg";
        }
        if (len >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
            return "wav";
        }
        if (len >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "m4a";
        }
        return null;
    }

    /** Apakah awal data tampak seperti halaman HTML/teks (bukan audio). */
    public static boolean looksLikeHtml(byte[] b, int len) {
        int n = Math.min(len, 256);
        String head = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).stripLeading().toLowerCase();
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")
                || head.startsWith("<?xml") || head.startsWith("{") || head.startsWith("<body");
    }
}
