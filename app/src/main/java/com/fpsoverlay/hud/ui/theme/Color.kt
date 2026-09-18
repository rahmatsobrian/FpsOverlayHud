package com.fpsoverlay.hud.ui.theme

import androidx.compose.ui.graphics.Color

// Fallback seed palette for Android 10/11 (no Dynamic Color / wallpaper-based
// theming available before Android 12). Also used if the user turns dynamic
// color off, or picks a custom accent.
val SeedGreen = Color(0xFF2ECC71)   // GPU / MEM label
val SeedBlue = Color(0xFF3B9EFF)    // CPU / RAM label
val SeedPink = Color(0xFFF6B8CC)    // API/D3D11 label
val SeedOrange = Color(0xFFFF9E2C)  // metric values

val md_theme_light_primary = Color(0xFF3B6939)
val md_theme_light_secondary = Color(0xFF53634F)
val md_theme_light_tertiary = Color(0xFF386569)
val md_theme_light_background = Color(0xFFFCFDF6)
val md_theme_light_surface = Color(0xFFFCFDF6)

val md_theme_dark_primary = Color(0xFFA0D39B)
val md_theme_dark_secondary = Color(0xFFBBCBB4)
val md_theme_dark_tertiary = Color(0xFFA1CED2)
val md_theme_dark_background = Color(0xFF1A1C18)
val md_theme_dark_surface = Color(0xFF1A1C18)

// HUD row label colors, matching the reference overlay design
object HudColors {
    val LabelGpu = SeedGreen
    val LabelMem = SeedGreen
    val LabelCpu = SeedBlue
    val LabelRam = SeedBlue
    val LabelApi = SeedPink
    val ValueOrange = SeedOrange
    val ValueWhite = Color.White
    val ValueDim = Color(0xFF9E9E9E) // "N/A" / device-limited chips
    val Outline = Color.Black
}
