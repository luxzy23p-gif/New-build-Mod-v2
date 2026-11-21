package com.xfive.live.lyrics;

/**
 * Mencari lirik bersinkron dari beberapa sumber, berurutan: LRCLIB (pencocokan durasi paling ketat), Better Lyrics,
 * lalu NetEase dan Kugou (opsional).
 * Satu jalur yang sama dipakai untuk lagu offline (file sendiri) maupun lagu online.
 */
public final class LyricsFinder {
    private LyricsFinder() {
    }

    public static LrclibFetcher.Result find(String artist, String title, long durationMs, boolean useBetterLyrics) {
        return find(artist, title, durationMs, useBetterLyrics, false);
    }

    /** moreSources = ikut mencari di NetEase dan Kugou kalau LRCLIB (dan Better Lyrics) tidak punya. */
    public static LrclibFetcher.Result find(String artist, String title, long durationMs, boolean useBetterLyrics,
                                            boolean moreSources) {
        LrclibFetcher.Result first = LrclibFetcher.fetchDetailed(artist, title, durationMs);
        if (first.text() != null || durationMs <= 0L || title == null || title.isBlank()) {
            return first;
        }
        String why = first.reason();
        String clean = LrclibFetcher.cleanTitle(title);
        if (useBetterLyrics) {
            LrclibFetcher.Result second = BetterLyricsFetcher.fetch(artist, clean, durationMs);
            if (second.text() != null) {
                return second;
            }
            if (!second.reason().isEmpty() && !why.isEmpty()) {
                why = why + " " + second.reason();
            } else if (why.isEmpty()) {
                why = second.reason();
            }
        }
        if (moreSources) {
            LrclibFetcher.Result third = NeteaseFetcher.fetch(artist, title, durationMs);
            if (third.text() != null) {
                return third;
            }
            LrclibFetcher.Result fourth = KugouFetcher.fetch(artist, title, durationMs);
            if (fourth.text() != null) {
                return fourth;
            }
        }
        return new LrclibFetcher.Result(null, why);
    }
}
