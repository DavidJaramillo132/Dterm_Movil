package com.example.dterm.ui.theme

import androidx.compose.ui.graphics.Color

// The terminal emulator already paints up to 256 ANSI colours straight from the
// remote machine. Any hue in the chrome would compete with that output and make
// it ambiguous whether a colour came from the host or from us, so the chrome is
// achromatic and Rust is spent only on failure.
val Ink = Color(0xFF14120F)   // app ground; deliberately not pure black, so ANSI black stays visible against it
val Ink2 = Color(0xFF1D1A16)  // raised surface: key row, input bar
val Ink3 = Color(0xFF2A2621)  // hairlines, borders, dividers
val Bone = Color(0xFFE8E3DA)  // primary text and terminal foreground
val Bone2 = Color(0xFF9A938A) // labels, secondary text
val Bone3 = Color(0xFF635C54) // hints, disabled
val Rust = Color(0xFFC2593A)  // failure only; never decorative
