package se.r56.video

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
        row.setOnClickListener {
            closeDrawerFrom(row)
            onClick()
        }
        return row
    }

    class DrawerTag(val close: () -> Unit)

    class Shell(val root: FrameLayout, val sidebar: LinearLayout, val content: FrameLayout, val title: TextView, val subtitle: TextView) {
        var isTv = true
        var drawerOpen = false
        var openDrawer: () -> Unit = {}
        var closeDrawer: () -> Unit = {}

        /** Phone only: closes the menu drawer if it is open. Returns true if the back press was consumed. */
        fun handleBack(): Boolean {
            if (!isTv && drawerOpen) {
                closeDrawer()
                return true
            }
            return false
        }
    }

    /** Chromecast / Android TV (or any device without touchscreen) keeps the original TV layout. */
    fun isTv(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (um != null && um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun closeDrawerFrom(v: View) {
        var cur: View? = v
        while (cur != null) {
            val t = cur.tag
            if (t is DrawerTag) {
                t.close()
                return
            }
            cur = cur.parent as? View
        }
    }

    /**
     * Standard screen: top bar (logo, title, clock), left menu, content area.
     * TV: menu is always visible on the left. Phone: menu is a drawer opened with the button in the top bar.
     */
    fun shell(ctx: Context, titleText: String): Shell {
        val tv = isTv(ctx)
        val root = FrameLayout(ctx)
        root.background = background()

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // top bar
        val top = FrameLayout(ctx)
        val logo = text(ctx, "R56 video", if (tv) 26f else 22f)
        logo.setTypeface(logo.typeface, android.graphics.Typeface.BOLD)
        val logoLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START)
        logoLp.leftMargin = dp(ctx, if (tv) 28 else 60)
        top.addView(logo, logoLp)
        val clock = TextClock(ctx)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = if (tv) 24f else 18f
        clock.setTextColor(TEXT)
        val clockLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END)
        clockLp.rightMargin = dp(ctx, if (tv) 32 else 16)
        top.addView(clock, clockLp)
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, if (tv) 64 else 56)))

        // body
        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.HORIZONTAL
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val sidebar = LinearLayout(ctx)
        sidebar.orientation = LinearLayout.VERTICAL
        sidebar.setPadding(0, dp(ctx, 12), 0, 0)
        if (tv) {
            sidebar.setBackgroundColor(0x66000000)
            body.addView(sidebar, LinearLayout.LayoutParams(dp(ctx, 250), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val right = LinearLayout(ctx)
        right.orientation = LinearLayout.VERTICAL
        val padH = if (tv) 28 else 16
        right.setPadding(dp(ctx, padH), dp(ctx, 8), dp(ctx, padH), dp(ctx, 8))
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val title = text(ctx, titleText, if (tv) 26f else 22f)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        right.addView(title)
        val subtitle = text(ctx, "", 14f, TEXT_DIM)
        subtitle.maxLines = 1
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        right.addView(subtitle)

        val content = FrameLayout(ctx)
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        clp.topMargin = dp(ctx, 10)
        right.addView(content, clp)

        val shell = Shell(root, sidebar, content, title, subtitle)
        shell.isTv = tv
        if (!tv) setupDrawer(ctx, shell, top)
        return shell
    }

    private fun setupDrawer(ctx: Context, shell: Shell, top: FrameLayout) {
        val root = shell.root
        val sidebar = shell.sidebar

        val overlay = FrameLayout(ctx)
        overlay.visibility = View.GONE
        val scrim = View(ctx)
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.isClickable = true
        overlay.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val width = minOf(dp(ctx, 290), (ctx.resources.displayMetrics.widthPixels * 0.82f).toInt())
        sidebar.setBackgroundColor(0xFF111B26.toInt())
        sidebar.isClickable = true
        sidebar.tag = DrawerTag { shell.closeDrawer() }
        overlay.addView(sidebar, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        shell.openDrawer = {
            shell.drawerOpen = true
            overlay.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(160).start()
            sidebar.translationX = -width.toFloat()
            sidebar.animate().translationX(0f).setDuration(180).start()
            if (sidebar.childCount > 0) sidebar.getChildAt(0).requestFocus()
        }
        shell.closeDrawer = {
            shell.drawerOpen = false
            scrim.animate().alpha(0f).setDuration(140).start()
            sidebar.animate().translationX(-width.toFloat()).setDuration(160).withEndAction {
                if (!shell.drawerOpen) overlay.visibility = View.GONE
            }.start()
        }
        scrim.setOnClickListener { shell.closeDrawer() }

        val burger = icon(ctx, R.drawable.ic_menu, 28)
        burger.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        burger.isFocusable = true
        burger.isClickable = true
        burger.background = focusBg()
        burger.contentDescription = "Menu"
        burger.setOnClickListener { if (shell.drawerOpen) shell.closeDrawer() else shell.openDrawer() }
        top.addView(burger, FrameLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48), Gravity.CENTER_VERTICAL or Gravity.START).also { it.leftMargin = dp(ctx, 6) })
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
