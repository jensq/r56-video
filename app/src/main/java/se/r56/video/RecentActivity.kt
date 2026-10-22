package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** The 10 most recently watched videos. Long press (or hold OK) on one to jump to its folder. */
class RecentActivity : Activity() {
    private class Row(val r: Recent, val src: Source)

    private lateinit var shell: Ui.Shell
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var rows: List<Row> = emptyList()
    private var selected = 0
    private var dialogOpen = false
    private var longHandled = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@RecentActivity
            val row = rows[position]
            val ts = Store.textSizeSp.toFloat()
            val line = LinearLayout(ctx)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 16), 0)
            line.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, (Store.textSizeSp * 3.4f).toInt()))
            line.addView(Ui.icon(ctx, if (row.r.rar) R.drawable.ic_archive else R.drawable.ic_video, 26))

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val title = Ui.text(ctx, row.r.title, ts)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            col.addView(title)
            val dir = row.r.path.substringBeforeLast('/', "")
            val where = Ui.text(ctx, row.src.share + if (dir.isEmpty()) "" else "/$dir", ts * 0.65f, Ui.TEXT_DIM)
            where.maxLines = 1
            where.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            col.addView(where)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = Ui.dp(ctx, 14)
            line.addView(col, lp)

            val pr = Store.getProgress(row.r.key)
            if (pr != null && pr.pos > 0) {
                line.addView(Ui.text(ctx, "${Ui.fmtTime(pr.pos)} / ${Ui.fmtTime(pr.dur)}", ts * 0.75f, Ui.AMBER))
            } else if (pr != null && pr.watched) {
                line.addView(Ui.icon(ctx, R.drawable.ic_check, 24, Ui.GREEN))
            }
            return line
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = Ui.shell(this, L.t("Recently watched"))
        shell.subtitle.text = L.t("The 10 most recently watched videos")

        list = ListView(this)
        styleKodiList(list)
        list.adapter = adapter
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.let { play(it) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { showMenu(it) }
            true
        }
        shell.content.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = Ui.text(this, L.t("No videos watched yet"), 20f, Ui.TEXT_DIM)
        status.gravity = Gravity.CENTER
        shell.content.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_home, L.t("Home")) { finish() })
        setContentView(shell.root)
    }

    override fun onResume() {
        super.onResume()
        // Skip entries whose saved location has been removed from the favorites.
        rows = Store.recents().mapNotNull { r -> Store.source(r.sourceId)?.let { Row(r, it) } }
        adapter.notifyDataSetChanged()
        val has = rows.isNotEmpty()
        list.visibility = if (has) View.VISIBLE else View.GONE
        status.visibility = if (has) View.GONE else View.VISIBLE
        if (has) {
            selected = selected.coerceIn(0, rows.size - 1)
            list.setSelection(selected)
            list.requestFocus()
        } else {
            shell.sidebar.getChildAt(0)?.requestFocus()
        }
    }

    private fun play(row: Row) {
        val pr = Store.getProgress(row.r.key)
        if (pr != null && pr.pos > 15_000) {
            AlertDialog.Builder(this)
                .setTitle(row.r.title)
                .setMessage(L.t("You have watched part of this video."))
                .setPositiveButton(L.f("Resume from %s", Ui.fmtTime(pr.pos))) { _, _ -> start(row, pr.pos) }
                .setNegativeButton(L.t("Play from beginning")) { _, _ -> start(row, 0) }
                .show()
        } else {
            start(row, 0)
        }
    }

    private fun start(row: Row, startMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("sourceId", row.src.id)
                .putExtra("path", row.r.path)
                .putExtra("rar", row.r.rar)
                .putExtra("title", row.r.title)
                .putExtra("startMs", startMs)
        )
    }

    private fun showMenu(row: Row) {
        if (dialogOpen) return
        dialogOpen = true
        AlertDialog.Builder(this)
            .setTitle(row.r.title)
            .setItems(arrayOf(L.t("Go to folder"))) { _, _ ->
                val dir = row.r.path.substringBeforeLast('/', "")
                startActivity(
                    Intent(this, BrowserActivity::class.java)
                        .putExtra("sourceId", row.src.id)
                        .putExtra("folder", dir)
                        .putExtra("select", row.r.path)
                )
            }
            .setNegativeButton(L.t("Cancel"), null)
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    /** TV remote: holding OK/Enter on a row opens the same menu as a long press on a touch screen. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) longHandled = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount >= 1 && !longHandled && list.hasFocus()) {
                longHandled = true
                rows.getOrNull(selected)?.let { showMenu(it) }
                return true
            }
            if (longHandled) {
                if (e.action == KeyEvent.ACTION_UP) longHandled = false
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!shell.handleBack()) super.onBackPressed()
    }
}
