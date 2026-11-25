package com.kobblo.gallery.file.explorer.transfer.web

import android.content.Context
import android.icu.text.PluralRules
import android.text.TextUtils
import android.view.View
import androidx.annotation.PluralsRes
import com.kobblo.gallery.file.explorer.R

/**
 * What Web Share says to whoever is at the browser, in the phone's language: the page's words, and what the server
 * answers when something can't be done. The server has no Context, and is tested without one, so it's all read
 * here, once, when Web Share starts.
 */
data class WebShareText(
    /** A BCP 47 tag, for the page's `lang`, and for choosing a plural's form in the browser. */
    val language: String,
    val rightToLeft: Boolean,
    /** Plain words for the page's `__NAME__` places, by name. The page escapes them. */
    val page: Map<String, String>,
    /** Words for the page's script, by name: a string, or a plural's forms by CLDR category. */
    val script: Map<String, Any>,
    /** What a browser is told when something it asked for can't happen. The page shows it, and History keeps it. */
    val replies: Map<Reply, String>,
    /** A browser this phone can't name. */
    val someBrowser: String,
    /** A browser, and the system it's on: `%1$s on %2$s`. */
    val browserOn: String,
    /** The zip of everything, for the device's name, before `.zip`. */
    val zipName: String,
    /** The list in a zip of what couldn't be read, before `.txt`. */
    val unreadableName: String
) {
    enum class Reply {
        FAILED, NOT_LET_IN, ACCEPT_FIRST, GONE, NOT_ACCEPTED, NOTE_TOO_LONG, NO_SPACE, NO_FOLDER, RAN_OUT, STOPPED,
        INTERRUPTED, DAMAGED, CANT_SAVE, NOTE_CUT_SHORT
    }

    fun reply(reply: Reply): String = replies[reply] ?: reply.name

    companion object {
        /** Every category a whole number can fall in, in any language, turns up below this. */
        private const val PLURAL_SAMPLES = 200
        private val textPlace = Regex("%(?:(\\d+)\\$)?s")

        /**
         * Fills a template's `%1$s` places, and plain `%s` ones in turn, as the page's script does. Unlike
         * String.format, a translation with a stray `%` in it can't stop the page or a download from being served.
         */
        fun fill(template: String, vararg args: String): String {
            var next = 0
            return textPlace.replace(template) { found ->
                val index = found.groupValues[1].toIntOrNull()?.minus(1) ?: next++
                args.getOrNull(index) ?: found.value
            }
        }

        fun from(context: Context): WebShareText {
            val resources = context.resources
            val locale = resources.configuration.locales[0]
            val rules = PluralRules.forLocale(locale)
            fun word(id: Int) = context.getString(id)

            // A count is only known in the browser, so it gets every form this language has and picks one there.
            fun forms(@PluralsRes id: Int): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                for (n in 0 until PLURAL_SAMPLES) {
                    val category = rules.select(n.toDouble())
                    if (category !in out) out[category] = resources.getQuantityString(id, n)
                }
                out.getOrPut("other") { out.values.last() }
                return out
            }

            return WebShareText(
                language = locale.toLanguageTag(),
                rightToLeft = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL,
                page = mapOf(
                    "LOADING" to word(R.string.share_web_loading),
                    "FILES" to word(R.string.transfer_files_title),
                    "DOWNLOAD_ALL" to word(R.string.share_web_download_all),
                    "CHANGED_MIND" to word(R.string.share_web_changed_mind),
                    "SEND_TO_PHONE" to word(R.string.share_web_send_to_phone),
                    "CHOOSE_FILES" to word(R.string.share_web_choose_files),
                    "DROP" to word(R.string.share_web_drop),
                    "CHOOSE_FOLDER" to word(R.string.share_web_choose_folder),
                    "ASKED_FIRST" to word(R.string.share_web_asked_first),
                    "SEND_NOTE" to word(R.string.share_web_send_note),
                    "NOTE_PLACEHOLDER" to word(R.string.share_web_note_placeholder),
                    "SEND" to word(R.string.transfer_send),
                    "UNENCRYPTED" to word(R.string.share_web_unencrypted),
                    "WAITING" to word(R.string.share_web_waiting),
                    "TRY_AGAIN" to word(R.string.transfer_try_again),
                    "REJECT" to word(R.string.transfer_reject),
                    "ACCEPT" to word(R.string.transfer_accept)
                ),
                script = mapOf(
                    "waiting" to word(R.string.share_web_waiting),
                    "letIn" to word(R.string.share_web_let_in),
                    "notLetIn" to word(R.string.share_web_not_let_in),
                    "rejected" to word(R.string.share_web_rejected),
                    "unanswered" to word(R.string.share_web_unanswered),
                    "withdrawn" to word(R.string.share_web_withdrawn),
                    "cooling" to word(R.string.share_web_cooling),
                    "unreachable" to word(R.string.share_web_unreachable),
                    "linkStopped" to word(R.string.share_web_link_stopped),
                    "download" to word(R.string.share_web_download),
                    "nothingAccepted" to word(R.string.share_web_nothing_accepted),
                    "canStillSend" to word(R.string.share_web_can_still_send),
                    "files" to forms(R.plurals.transfer_incoming_summary),
                    "youRejected" to forms(R.plurals.share_web_you_rejected),
                    "offerTitle" to word(R.string.share_web_offer_title),
                    "offerNotes" to forms(R.plurals.share_web_offer_notes),
                    "offerBoth" to word(R.string.share_web_offer_both),
                    "namesMore" to word(R.string.transfer_names_more),
                    "tabOffer" to word(R.string.share_web_tab_offer),
                    "waitingAccept" to word(R.string.share_web_waiting_accept),
                    "cancel" to word(R.string.cancel),
                    "tryAgain" to word(R.string.transfer_try_again),
                    "savedAs" to word(R.string.share_web_saved_as),
                    "failedStatus" to word(R.string.share_web_failed_status),
                    "connectionLost" to word(R.string.share_web_connection_lost),
                    "connectionLostNote" to word(R.string.share_web_connection_lost_note),
                    "noteSent" to word(R.string.share_web_note_sent),
                    "noteFailed" to word(R.string.share_web_note_failed)
                ),
                replies = mapOf(
                    Reply.FAILED to word(R.string.transfer_failed_unknown),
                    Reply.NOT_LET_IN to word(R.string.share_web_reply_not_let_in),
                    Reply.ACCEPT_FIRST to word(R.string.share_web_reply_accept_first),
                    Reply.GONE to word(R.string.share_web_reply_gone),
                    Reply.NOT_ACCEPTED to word(R.string.share_web_reply_not_accepted),
                    Reply.NOTE_TOO_LONG to word(R.string.share_web_reply_note_too_long),
                    Reply.NO_SPACE to word(R.string.share_web_reply_no_space),
                    Reply.NO_FOLDER to word(R.string.share_web_reply_no_folder),
                    Reply.RAN_OUT to word(R.string.share_web_reply_ran_out),
                    Reply.STOPPED to word(R.string.share_web_reply_stopped),
                    Reply.INTERRUPTED to word(R.string.share_web_reply_interrupted),
                    Reply.DAMAGED to word(R.string.share_web_reply_damaged),
                    Reply.CANT_SAVE to word(R.string.share_web_reply_cant_save),
                    Reply.NOTE_CUT_SHORT to word(R.string.share_web_reply_note_cut_short)
                ),
                someBrowser = word(R.string.share_web_browser),
                browserOn = word(R.string.share_web_browser_on),
                zipName = word(R.string.share_web_zip_name),
                unreadableName = word(R.string.share_web_zip_unreadable)
            )
        }
    }
}
