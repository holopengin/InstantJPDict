package com.holopengin.instantjpdict

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * ui-deepseek design 1 ("Harbour"): Material You for the whole process.
 *
 * Applying dynamic colour on the Application, before any activity is created,
 * means the main screen, the viewfinder and the OCR share surface all resolve the
 * same tonal palette — a wallpaper-driven scheme applied to only one of them
 * would leave the camera chrome looking like a different app.
 *
 * On API < 31 (and on ROMs without the Monet palette provider) the call is a
 * no-op and the hand-picked roles in `values/colors.xml` stand. That is why those
 * values exist rather than a bare `Theme.Material3` default: the fallback is a
 * designed palette, not a placeholder.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Before any other app code runs, so an early-startup crash is captured
        // too. See CrashReporter for the report/hand-off flow.
        CrashReporter.install(this)
    }
}
