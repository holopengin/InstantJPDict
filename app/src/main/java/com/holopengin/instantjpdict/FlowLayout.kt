package com.holopengin.instantjpdict

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A wrapping row of views: children flow left→right and wrap to a new line at
 * the container's width, sharing a baseline within each row where they have one.
 *
 * Extracted from [OcrOverlayView]'s private nested class (#89) so the shared
 * results renderer ([DictionaryResultView]) and the overlay can use the SAME
 * layout: the dictionary panel's tags, ruby, sense rows and table cells were
 * built on it, and a second copy would be a second wrapping rule to keep in step.
 * The behaviour is unchanged — it is the same measure/layout/baseline code, only
 * its home moved.
 */
internal class FlowLayout(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val maxWidth = width - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var rowBaseline = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidthSpec = if (child.layoutParams.width == LayoutParams.MATCH_PARENT) {
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            }
            child.measure(
                childWidthSpec,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )

            val measuredWidth = if (child.layoutParams.width == LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth

            if (x + measuredWidth > width - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
                rowBaseline = 0
            }
            x += measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
            rowBaseline = maxOf(rowBaseline, child.baseline)
        }

        val calculatedHeight = y + rowHeight + paddingBottom
        val finalHeight = if (heightMode == MeasureSpec.EXACTLY) heightSize else maxOf(calculatedHeight, minimumHeight)
        setMeasuredDimension(width, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val maxWidth = width - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var rowBaseline = 0
        var rowStartIndex = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val measuredWidth = if (child.layoutParams.width == LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth

            if (x + measuredWidth > width - paddingRight && x > paddingLeft) {
                layoutRow(rowStartIndex, i, y, rowHeight, rowBaseline, maxWidth)
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
                rowBaseline = 0
                rowStartIndex = i
            }
            x += measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
            rowBaseline = maxOf(rowBaseline, child.baseline)
        }
        layoutRow(rowStartIndex, childCount, y, rowHeight, rowBaseline, maxWidth)
    }

    private fun layoutRow(start: Int, end: Int, top: Int, rowHeight: Int, rowBaseline: Int, maxWidth: Int) {
        var x = paddingLeft
        for (i in start until end) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childBaseline = child.baseline
            val childTop = if (childBaseline != -1 && rowBaseline != -1) {
                top + rowBaseline - childBaseline
            } else {
                top + rowHeight - child.measuredHeight
            }

            val measuredWidth = if (child.layoutParams.width == LayoutParams.MATCH_PARENT) maxWidth else child.measuredWidth
            child.layout(x, childTop, x + measuredWidth, childTop + child.measuredHeight)
            x += measuredWidth
        }
    }

    override fun getBaseline(): Int {
        if (childCount == 0) return -1
        return getChildAt(0).baseline
    }
}
