package com.kobblo.gallery.file.explorer.fileops

import android.content.Context
import com.kobblo.gallery.file.explorer.R

/** Turns what happened into what to tell the person: how many, and why. */
object FileOpText {
    internal fun reason(context: Context, reason: FailReason): String = context.getString(
        when (reason) {
            FailReason.MISSING -> R.string.safety_reason_missing
            FailReason.NO_SPACE -> R.string.safety_reason_no_space
            FailReason.INTO_ITSELF -> R.string.safety_reason_into_itself
            FailReason.DENIED -> R.string.safety_reason_denied
            FailReason.NAME_TOO_LONG -> R.string.safety_reason_name_too_long
            FailReason.CHECK_FAILED -> R.string.safety_reason_check_failed
            FailReason.NOT_POSSIBLE -> R.string.safety_reason_not_possible
            FailReason.UNKNOWN -> R.string.safety_reason_unknown
        }
    )

    /** With many failures one reason usually covers most of them, and it's the one worth saying. */
    private fun commonReason(context: Context, reasons: List<FailReason>): String =
        reason(context, reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: FailReason.UNKNOWN)

    fun deleteFailed(context: Context, removed: Int, failed: List<BinFailure>): String =
        context.resources.getQuantityString(
            R.plurals.safety_delete_failed,
            failed.size,
            failed.size,
            removed + failed.size,
            commonReason(context, failed.map { it.reason })
        )

    /**
     * What a paste needs to say beyond the usual, whether that's a problem or only a name that changed.
     * Blank when there's nothing to add.
     */
    fun transferProblem(context: Context, report: TransferReport, move: Boolean): String {
        val parts = ArrayList<String>()
        if (report.cancelled) {
            parts += context.resources.getQuantityString(
                if (move) R.plurals.safety_move_stopped else R.plurals.safety_copy_stopped,
                report.done,
                report.done
            )
        }
        if (report.failed.isNotEmpty()) {
            parts += context.resources.getQuantityString(
                R.plurals.safety_transfer_failed,
                report.failed.size,
                report.failed.size,
                report.done + report.failed.size,
                commonReason(context, report.failed.map { it.reason })
            )
        }
        if (report.renamed > 0) {
            parts += context.resources.getQuantityString(R.plurals.safety_transfer_renamed, report.renamed, report.renamed)
        }
        if (report.linksSkipped > 0) {
            parts += context.resources.getQuantityString(R.plurals.safety_links_skipped, report.linksSkipped, report.linksSkipped)
        }
        return parts.joinToString(" ")
    }

    fun restored(context: Context, report: BinRestoreReport): String {
        val parts = ArrayList<String>()
        if (report.restored.isNotEmpty()) {
            parts += context.resources.getQuantityString(R.plurals.safety_restored, report.restored.size, report.restored.size)
        }
        if (report.renamed > 0) {
            parts += context.resources.getQuantityString(R.plurals.safety_restored_renamed, report.renamed, report.renamed)
        }
        if (report.relocated > 0) {
            parts += context.resources.getQuantityString(R.plurals.safety_restored_elsewhere, report.relocated, report.relocated)
        }
        if (report.failed.isNotEmpty()) {
            parts += context.resources.getQuantityString(
                R.plurals.safety_restore_failed,
                report.failed.size,
                report.failed.size,
                commonReason(context, report.failed.map { it.reason })
            )
        }
        return parts.joinToString(" ")
    }

    fun binDeleteFailed(context: Context, report: BinDeleteReport): String =
        context.resources.getQuantityString(R.plurals.safety_bin_delete_failed, report.failed, report.failed)

    fun renameFailed(context: Context, problem: RenameProblem, name: String): String = when (problem) {
        RenameProblem.NAME_TAKEN -> context.getString(R.string.safety_rename_taken, name)
        RenameProblem.BAD_NAME -> context.getString(R.string.safety_rename_bad_name)
        RenameProblem.FAILED -> context.getString(R.string.safety_rename_failed)
    }
}
