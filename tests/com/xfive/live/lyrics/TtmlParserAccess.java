package com.xfive.live.lyrics;

/** Akses uji ke fungsi paket-privat TtmlParser. */
public final class TtmlParserAccess {
    private TtmlParserAccess() {
    }

    public static long t(String s) {
        return TtmlParser.parseTime(s);
    }
}
