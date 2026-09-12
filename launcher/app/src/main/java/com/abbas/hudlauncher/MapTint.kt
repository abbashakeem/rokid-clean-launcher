package com.abbas.hudlauncher

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Recolours the phone's navigation map to the HUD's green ramp, the way Rokid-Maps converts dark
 * tiles to shades of green for a monochrome display.
 *
 * Luminance is taken from the source, contrast is stretched so the dark map background falls to
 * black (fully transparent on the waveguide) and roads stay bright, then the result is multiplied
 * into our ink colour. A ColorMatrix is used so the ImageView does it on the GPU each frame.
 */
object MapTint {
    /** Ink colour channels as fractions of full white (#40FF5E). */
    private const val R_F = 0x40 / 255f
    private const val G_F = 0xFF / 255f
    private const val B_F = 0x5E / 255f

    /** Contrast stretch: out = CONTRAST * luminance + BLACK_POINT. */
    private const val CONTRAST = 1.7f
    private const val BLACK_POINT = -0.28f      // in 0..1, applied before scaling to 0..255

    // Rec. 601 luma weights, matching what the eye reads as brightness on a dark map.
    private const val LR = 0.299f
    private const val LG = 0.587f
    private const val LB = 0.114f

    val colorFilter: ColorMatrixColorFilter by lazy {
        val o = BLACK_POINT * 255f
        ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            LR * CONTRAST * R_F, LG * CONTRAST * R_F, LB * CONTRAST * R_F, 0f, o * R_F,
            LR * CONTRAST * G_F, LG * CONTRAST * G_F, LB * CONTRAST * G_F, 0f, o * G_F,
            LR * CONTRAST * B_F, LG * CONTRAST * B_F, LB * CONTRAST * B_F, 0f, o * B_F,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
}
