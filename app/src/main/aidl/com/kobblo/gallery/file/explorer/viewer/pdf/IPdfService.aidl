package com.kobblo.gallery.file.explorer.viewer.pdf;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.kobblo.gallery.file.explorer.viewer.pdf.IPdfSearchCallback;
import com.kobblo.gallery.file.explorer.viewer.pdf.PdfBitmap;

/** What the viewer asks of the process that parses PDFs. See PdfService for why it's a separate one. */
interface IPdfService {
    /** A session number, or one of the negative codes in PdfOpenResult. */
    int open(in ParcelFileDescriptor file, String password);

    int pageCount(int session);

    /** Width and height in points, in pairs, for up to count pages from the first asked for. */
    float[] pageSizes(int session, int from, int count);

    /** Draws a page into a new bitmap. matrix is the six values of an affine transform from points to pixels. */
    PdfBitmap render(int session, int page, int width, int height, in float[] matrix);

    /** The page's text, or an empty string if it has none. */
    String pageText(int session, int page);

    /** Characters and where they are on a page, for selecting: text in "text", rects in "rects", four floats per character. */
    Bundle pageChars(int session, int page);

    /** Where tapping goes on a page: "rects" four floats per link in points, "pages" the page each jumps to or -1, "uris" the address of those that don't. */
    Bundle pageLinks(int session, int page);

    /** Starts looking for query from startPage, wrapping round. A new search on the session replaces the last. */
    void search(int session, int searchId, String query, int startPage, IPdfSearchCallback callback);

    void cancelSearch(int session);

    /** Outline and document information. Parsed on first ask, so call it off the main thread. */
    Bundle info(int session);

    void close(int session);

    /** The first page, fitted inside these limits, for a list's thumbnail. Null if it can't be drawn. */
    PdfBitmap thumbnail(in ParcelFileDescriptor file, int maxWidth, int maxHeight);

    /** A copy of a locked document with its lock removed, for handing to the print system. Deleted when the session closes. */
    String unlockedCopy(int session);

    /** So that a stuck process can be ended from outside. */
    int pid();
}
