package com.example.dterm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.dterm.R

// Two voices, never mixed. Anything the machine produced or that the user must
// retype into a machine (host, port, secret, session name, key caps) is set in
// Mono, so a lowercase l never has to be told apart from a 1. Anything written
// for a human to read as a sentence is set in UiText, which is narrower and
// cheaper to scan on a phone.

/** Machine data: terminal output, host, port, secret, session name, key caps. */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.W400),
    Font(R.font.jetbrains_mono_bold, FontWeight.W700)
)

/** Human language: labels, buttons, status sentences. */
val UiText = FontFamily(
    Font(R.font.red_hat_text_regular, FontWeight.W400),
    Font(R.font.red_hat_text_medium, FontWeight.W500)
)

/** Spent once, on the app name. A second use would make it decoration. */
val Display = FontFamily(
    Font(R.font.red_hat_display_bold, FontWeight.W700)
)

val Typography = Typography(
    // The app name only. Negative tracking because display sizes look loose at
    // the letter spacing that reads correctly at body size.
    displaySmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.W700,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    // Section headers: card titles, dialog titles.
    titleMedium = TextStyle(
        fontFamily = UiText,
        fontWeight = FontWeight.W500,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = UiText,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = UiText,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    // Buttons and field labels are short strings with no sentence rhythm to
    // carry them, so they are tracked wider than running text to stay legible.
    labelLarge = TextStyle(
        fontFamily = UiText,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(
        fontFamily = UiText,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp
    ),
    // Machine values shown inline. Line height is generous because monospaced
    // text sits in dense blocks where rows otherwise collide.
    bodySmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    )
)
