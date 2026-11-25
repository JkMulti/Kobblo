package com.kobblo.gallery.file.explorer.data.core

/** Runs [block] and turns anything it throws into a failed [Result], for callers that report a failure rather than handle one. */
internal inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    Result.failure(e)
}
