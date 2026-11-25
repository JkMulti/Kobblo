package com.kobblo.gallery.file.explorer.tools

/**
 * The Hub draws only its top route, so a screen with another opened over it is rebuilt when that one
 * closes. This keeps the little that screen must find again, until it's really left.
 */
internal object RouteMemory {
    private val values = HashMap<String, String>()

    fun get(key: String): String? = values[key]

    fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
