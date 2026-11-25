package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.web.WebShareText
import com.kobblo.gallery.file.explorer.transfer.web.WebShareText.Reply

/**
 * Web Share's words as the English resources have them, for tests that run without Android. The app reads them with
 * WebShareText.from(context). Only what these tests look at is here: the page's own words aren't.
 */
internal val englishWebShareText = WebShareText(
    language = "en",
    rightToLeft = false,
    page = emptyMap(),
    script = emptyMap(),
    replies = mapOf(
        Reply.FAILED to "Something went wrong",
        Reply.NOT_LET_IN to "The phone hasn't let this browser in",
        Reply.ACCEPT_FIRST to "Accept this on the page first",
        Reply.GONE to "This file is no longer available",
        Reply.NOT_ACCEPTED to "The phone hasn't accepted this",
        Reply.NOTE_TOO_LONG to "That's too long for a note. Send it as a file instead.",
        Reply.NO_SPACE to "Not enough space on the phone",
        Reply.NO_FOLDER to "Can't open the Received folder",
        Reply.RAN_OUT to "The phone ran out of space",
        Reply.STOPPED to "Web Share stopped",
        Reply.INTERRUPTED to "The upload was interrupted",
        Reply.DAMAGED to "The upload was damaged",
        Reply.CANT_SAVE to "Couldn't save this file",
        Reply.NOTE_CUT_SHORT to "The note was cut short"
    ),
    someBrowser = "Browser",
    browserOn = "%1\$s on %2\$s",
    zipName = "%1\$s files",
    unreadableName = "Files that could not be read"
)
