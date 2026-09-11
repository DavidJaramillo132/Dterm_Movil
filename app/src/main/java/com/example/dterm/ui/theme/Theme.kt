package com.example.dterm.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// A terminal is read against its own background for long stretches; there is no
// light variant because a bright ground would wash out the dim half of the ANSI
// palette. primary is Bone rather than a hue so that any component we have not
// styled by hand still tints achromatically instead of inventing an accent.
private val DarkColorScheme = darkColorScheme(
    primary = Bone,
    onPrimary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Ink,
    onSurface = Bone,
    surfaceVariant = Ink2,
    onSurfaceVariant = Bone2,
    outline = Ink3,
    outlineVariant = Ink3,
    error = Rust,
    onError = Ink
)

@Composable
fun DtermTheme(
    // Off by default: on Android 12+ dynamic colour rewrites the scheme from the
    // user's wallpaper, which would put arbitrary hues next to the emulator's
    // ANSI output and make the source of a colour unreadable.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
