package com.kobblo.gallery.file.explorer

// Both are saved by their constants' names.

/** Whether the app is light or dark, or as the phone is. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What lies behind everything in the dark: near black, or black for screens that switch those pixels off. */
enum class DarkSurfaceStyle { STANDARD, AMOLED }

/** Where the tint picked in Settings is saved, by its name. The file picker reads it too. */
const val TintPref = "tint_color"
