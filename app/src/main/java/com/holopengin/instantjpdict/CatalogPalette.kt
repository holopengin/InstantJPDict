package com.holopengin.instantjpdict

/**
 * #71 follow-up: the catalog dialog's text colours, per day/night.
 *
 * The dialog's detail text was hardcoded `#444444`. On the app's `DayNight`
 * theme the dialog surface is near-black at night, so the description, the
 * size/licence/source line and the progress text were dark grey on dark grey —
 * unreadable. The four semantic colours had the same problem in reverse: they
 * were picked for a white surface and go dim on a dark one.
 *
 * Kept Android-free (a boolean in, a colour int out) so the day/night
 * relationship is asserted in unit tests rather than eyeballed on one device in
 * one mode — which is how this shipped broken in the first place.
 *
 * The values are chosen so each night variant is clearly lighter than its day
 * variant, and each is legible against the surface it will sit on: Material's
 * dark dialog surface is ~#1E1E1E, the light one ~#FFFFFF.
 */
data class CatalogPalette private constructor(
    /** Body/detail text: descriptions, metadata, progress, the intro paragraph. */
    val neutral: Int,    /** A completed/installed state. */
    val ok: Int,
    /** A recoverable warning, e.g. download unavailable. */
    val warn: Int,
    /** A failure the user must act on. */
    val err: Int,
) {
    companion object {
        /** Detail text on a light surface: mid grey, comfortably past AA. */
        const val NEUTRAL_DAY = 0xFF555555.toInt()
        /** Detail text on the dark dialog surface. */
        const val NEUTRAL_NIGHT = 0xFFB3B3B3.toInt()

        const val OK_DAY = 0xFF1B7F3B.toInt()
        const val OK_NIGHT = 0xFF7DD88F.toInt()

        const val WARN_DAY = 0xFFB45409.toInt()
        const val WARN_NIGHT = 0xFFE8A45C.toInt()

        const val ERR_DAY = 0xFFB01C1C.toInt()
        const val ERR_NIGHT = 0xFFFF8A80.toInt()

        /**
         * True when the device is in night mode.
         *
         * Read from the configuration rather than from a theme attribute so the
         * choice is explicit here and assertable; the dialog's surface colour and
         * this must agree, and the theme is `DayNight` so they do.
         */
        fun isNight(context: android.content.Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        /** The palette for the current configuration. */
        fun of(context: android.content.Context): CatalogPalette = forNight(isNight(context))

        /** The palette for an explicit mode — the seam the tests drive. */
        fun forNight(night: Boolean): CatalogPalette =
            if (night) {
                CatalogPalette(NEUTRAL_NIGHT, OK_NIGHT, WARN_NIGHT, ERR_NIGHT)
            } else {
                CatalogPalette(NEUTRAL_DAY, OK_DAY, WARN_DAY, ERR_DAY)
            }
    }
}
