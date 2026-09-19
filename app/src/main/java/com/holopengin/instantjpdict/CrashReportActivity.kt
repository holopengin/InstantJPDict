package com.holopengin.instantjpdict

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import java.io.File

/**
 * The crash screen: what happened, and the choice to send it. Started by
 * [CrashReporter] in a fresh process after the crashing one died; the report
 * file path arrives in the intent.
 *
 * The attachment is the user's call (the checkbox, on by default): the email's
 * body always carries the version/device block, the stack trace only rides
 * along when they leave it checked. "Close" sends nothing and leaves the log in
 * the cache for the next crash's pruning.
 */
class CrashReportActivity : AppCompatActivity() {

    private var crashFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        crashFile = intent.getStringExtra(EXTRA_CRASH_FILE)?.let { File(it) }?.takeIf { it.isFile }
        val trace = try {
            crashFile?.readText()
        } catch (e: Exception) {
            null
        } ?: "No crash log was found."

        val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val root = CoordinatorLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "Crash report"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        val appBar = AppBarLayout(this).apply {
            elevation = 0f
            addView(
                toolbar,
                AppBarLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(appBar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun paragraph(text: String, size: Float = 15f, colorValue: Int = onSurface): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(colorValue)
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

        content.addView(
            paragraph(
                "InstantJPDict closed unexpectedly. The details below help fix " +
                    "it — send them to the developer?",
                size = 16f,
            )
        )

        val attach = MaterialCheckBox(this).apply {
            text = "Attach crash log"
            isChecked = true
        }
        content.addView(attach)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        actions.addView(
            MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                text = "Email crash report"
                isAllCaps = false
                setOnClickListener { sendReport(attach.isChecked) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        actions.addView(
            MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Close"
                isAllCaps = false
                setTextColor(color(com.google.android.material.R.attr.colorPrimary))
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
        )
        content.addView(actions)

        content.addView(
            paragraph("Crash log", size = 14f, colorValue = onSurfaceVariant)
        )
        content.addView(
            TextView(this).apply {
                text = trace
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(onSurfaceVariant)
                setTextIsSelectable(true)
            }
        )

        val scroll = NestedScrollView(this).apply {
            clipToPadding = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { behavior = AppBarLayout.ScrollingViewBehavior() }
        }
        root.addView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.setPadding(0, bars.top, 0, 0)
            scroll.setPadding(dp(20), dp(8), dp(20), dp(24) + bars.bottom)
            insets
        }

        setContentView(root)
    }

    private fun sendReport(includeAttachment: Boolean) {
        val body = buildString {
            appendLine("InstantJPDict crash report")
            appendLine(Feedback.appVersion(this@CrashReportActivity))
            appendLine(Feedback.deviceInfo())
            appendLine()
            appendLine("What were you doing when it crashed?")
            if (includeAttachment && crashFile != null) {
                appendLine()
                appendLine("(The crash log is attached.)")
            }
        }
        val attachment = crashFile?.takeIf { includeAttachment }
        try {
            startActivity(
                Feedback.emailIntent(
                    this,
                    subject = "InstantJPDict crash report",
                    body = body,
                    attachment = attachment,
                )
            )
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No email app found to send the report", Toast.LENGTH_LONG).show()
        }
    }

    private fun color(attr: Int): Int =
        MaterialColors.getColor(this, attr, android.graphics.Color.GRAY)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_CRASH_FILE = "crash_file"

        fun intent(context: Context, crashFile: File): Intent =
            Intent(context, CrashReportActivity::class.java)
                .putExtra(EXTRA_CRASH_FILE, crashFile.absolutePath)
    }
}
