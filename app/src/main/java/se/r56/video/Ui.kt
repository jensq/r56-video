package se.r56.video

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** Kodi (Estuary) inspired look: dark blue background, left menu, cyan focus highlight. */
object Ui {
    const val ACCENT = 0xFF12A0C7.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xFF9FB0BF.toInt()
    const val GREEN = 0xFF8BC34A.toInt()
    const val AMBER = 0xFFFFC107.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun background(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF0D131B.toInt(), 0xFF1B2836.toInt())
    )

    /** Highlight shown for the focused view (accent) and, for lists, the remembered row when unfocused. */
    fun focusBg(dimWhenUnfocused: Boolean = false): StateListDrawable {
        val d = StateListDrawable()
        d.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(ACCENT))
        d.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(ACCENT))
        d.addState(intArrayOf(), ColorDrawable(if (dimWhenUnfocused) 0x2612A0C7 else Color.TRANSPARENT))
        return d
    }

    fun icon(ctx: Context, res: Int, sizeDp: Int = 24, color: Int = TEXT): ImageView {
        val iv = ImageView(ctx)
        iv.setImageResource(res)
        iv.setColorFilter(color)
        iv.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        return iv
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int = TEXT): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        return t
    }

    /** Focusable menu entry for the left side panel. */
    fun menuButton(ctx: Context, iconRes: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 16), 0, dp(ctx, 12), 0)
        row.isFocusable = true
        row.isClickable = true
        row.background = focusBg()
        val ic = icon(ctx, iconRes, 22)
        row.addView(ic)
        val t = text(ctx, label, 18f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(ctx, 14)
        row.addView(t, lp)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52))
        row.setOnClickListener { onClick() }
        return row
    }

    class Shell(val root: FrameLayout, val sidebar: LinearLayout, val content: FrameLayout, val title: TextView, val subtitle: TextView)

    /** Standard screen: top bar (logo, title, clock), left menu, content area. */
    fun shell(ctx: Context, titleText: String): Shell {
        val root = FrameLayout(ctx)
        root.background = background()

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // top bar
        val top = FrameLayout(ctx)
        val logo = text(ctx, "R56 video", 26f)
        logo.setTypeface(logo.typeface, android.graphics.Typeface.BOLD)
        val logoLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START)
        logoLp.leftMargin = dp(ctx, 28)
        top.addView(logo, logoLp)
        val clock = TextClock(ctx)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 24f
        clock.setTextColor(TEXT)
        val clockLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END)
        clockLp.rightMargin = dp(ctx, 32)
        top.addView(clock, clockLp)
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 64)))

        // body
        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.HORIZONTAL
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val sidebar = LinearLayout(ctx)
        sidebar.orientation = LinearLayout.VERTICAL
        sidebar.setBackgroundColor(0x66000000)
        sidebar.setPadding(0, dp(ctx, 12), 0, 0)
        body.addView(sidebar, LinearLayout.LayoutParams(dp(ctx, 250), ViewGroup.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(ctx)
        right.orientation = LinearLayout.VERTICAL
        right.setPadding(dp(ctx, 28), dp(ctx, 8), dp(ctx, 28), dp(ctx, 8))
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val title = text(ctx, titleText, 26f)
        right.addView(title)
        val subtitle = text(ctx, "", 14f, TEXT_DIM)
        subtitle.maxLines = 1
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        right.addView(subtitle)

        val content = FrameLayout(ctx)
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        clp.topMargin = dp(ctx, 10)
        right.addView(content, clp)

        return Shell(root, sidebar, content, title, subtitle)
    }

    fun addMenu(shell: Shell, view: View) = shell.sidebar.addView(view)

    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    fun fmtSize(b: Long): String {
        if (b <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$b B" else String.format("%.1f %s", v, units[i])
    }
}

/** Applies the Kodi-like list look (cyan focus bar, dim bar for the remembered row, thin dividers). */
fun styleKodiList(list: android.widget.ListView) {
    list.selector = Ui.focusBg(true)
    list.setDrawSelectorOnTop(false)
    list.cacheColorHint = Color.TRANSPARENT
    list.divider = ColorDrawable(0x22FFFFFF)
    list.dividerHeight = 1
    list.itemsCanFocus = false
    list.isVerticalScrollBarEnabled = true
    list.isFocusable = true
    list.overScrollMode = View.OVER_SCROLL_NEVER
}
