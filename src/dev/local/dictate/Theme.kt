package dev.local.dictate

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.EditText

/**
 * Same palette as claude-agents-android/newsdigest-android's own Theme.kt
 * (a snapshot of the desktop's generated scheme), trimmed to just what
 * this app's plain settings screen needs.
 */
object Theme {
    const val bg = 0xFF201A17.toInt()
    const val surface = 0xFF2A221E.toInt()
    const val onBackground = 0xFFECE0DA.toInt()
    const val onSurfaceVariant = 0xFFD7C2B8.toInt()
    const val outlineVariant = 0xFF52443C.toInt()
    const val outline = 0xFF9F8D84.toInt()
    const val primary = 0xFFFFB68A.toInt()
    const val onPrimary = 0xFF522300.toInt()
    const val muted = 0xFF8A7A70.toInt()

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(color: Int, context: Context, radiusDp: Int = 10, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    fun rippleOn(base: GradientDrawable): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(outline and 0x66FFFFFF.toInt()), base, base)

    fun styleEditText(e: EditText, context: Context) {
        e.setTextColor(onBackground)
        e.setHintTextColor(muted)
        e.background = roundedDrawable(surface, context, strokeColor = outlineVariant)
        e.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }

    fun stylePrimaryButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(primary, context))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }

    fun styleGhostButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, strokeColor = outlineVariant))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }
}
