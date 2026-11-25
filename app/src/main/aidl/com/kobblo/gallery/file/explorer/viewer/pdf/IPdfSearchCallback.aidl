package com.kobblo.gallery.file.explorer.viewer.pdf;

/** Search results as they're found. One way, so a slow screen never holds up the scan. */
oneway interface IPdfSearchCallback {
    /**
     * The matches on one page. rects holds left, top, right, bottom in points, top-left origin, and
     * rectsPerMatch says how many of those belong to each match, as a match can wrap across lines.
     */
    void onMatches(int searchId, int page, in float[] rects, in int[] rectsPerMatch);

    void onProgress(int searchId, int pagesScanned);

    void onFinished(int searchId, boolean complete);
}
