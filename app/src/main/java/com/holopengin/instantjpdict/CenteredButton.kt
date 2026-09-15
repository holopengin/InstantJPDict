package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/**
 * A Button that centers its text precisely within its bounds using the actual
 * rendered text's bounding box.
 *
 * Extends [AppCompatButton], not the platform `Button` (F2/#86): the platform
 * subclass is what lint's AppCompatCustomView error flags, because an app
 * running with an AppCompat theme must let AppCompat back the widget (tint,
 * background, text appearance). Construction and the `onDraw` draw are
 * unchanged — every call site sets its own background anyway.
 */
class CenteredButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val bounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val textStr = text.toString()
        if (textStr.isEmpty()) return

        // Get the bounding box of the actual rendered characters in the text
        paint.getTextBounds(textStr, 0, textStr.length, bounds)

        // Center vertically based on the exact visual bounds of the rendered glyphs.
        val baselineOffsetY = (height / 2f) - (bounds.top + bounds.bottom) / 2f

        // Center horizontally based on the exact visual bounds of the rendered glyphs.
        val x = (width / 2f) - (bounds.left + bounds.right) / 2f

        paint.color = currentTextColor
        canvas.drawText(textStr, x, baselineOffsetY, paint)
    }
}
