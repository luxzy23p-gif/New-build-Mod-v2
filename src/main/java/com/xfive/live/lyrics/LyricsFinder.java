package com.xfive.live.lyrics;

/**
 * Mencari lirik bersinkron dari beberapa sumber, berurutan: LRCLIB (pencocokan durasi paling ketat), lalu Better Lyrics.
 * Satu jalur yang sama dipakai untuk lagu offline (file sendiri) maupun lagu online.
 */
public final class LyricsFinder {
    private LyricsFinder() {
    }

    public static LrclibFetcher.Result find(String artist, String title, long durationMs, boolean useBetterLyrics) {
        LrclibFetcher.Result first = LrclibFetcher.fetchDetailed(artist, title, durationMs);
        if (first.text() != null || !useBetterLyrics || durationMs <= 0L || title == null || title.isBlank()) {
            return first;
        }
        String clean = LrclibFetcher.cleanTitle(title);
        LrclibFetcher.Result second = BetterLyricsFetcher.fetch(artist, clean, durationMs);
        if (second.text() != null) {
            return second;
        }
        String why = first.reason();
        if (!second.reason().isEmpty() && !why.isEmpty()) {
            why = why + " " + second.reason();
        } else if (why.isEmpty()) {
            why = second.reason();
        }
        return new LrclibFetcher.Result(null, why);
    }
}
