package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Folder browser for one SMB location. */
class BrowserActivity : Activity() {
    class Item(val type: Int, val label: String, val path: String, val size: Long)

    companion object {
        const val UP = 0
        const val DIR = 1
        const val VIDEO = 2
        const val RAR = 3
    }

    private lateinit var src: Source
    private lateinit var shell: Ui.Shell
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var path = ""
    private var items: List<Item> = emptyList()
    private var selected = 0
    private var token = 0
    private val selMemory = HashMap<String, Int>()
    private val io = Executors.newSingleThreadExecutor()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@BrowserActivity
            val it = items[position]
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 16), 0)
            row.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 54))

            val iconRes = when (it.type) {
                UP -> R.drawable.ic_up
                DIR -> R.drawable.ic_folder
                RAR -> R.drawable.ic_archive
                else -> R.drawable.ic_video
            }
            row.addView(Ui.icon(ctx, iconRes, 26))

            val label = Ui.text(ctx, it.label, 20f)
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = Ui.dp(ctx, 14)
            row.addView(label, lp)

            if (it.type == VIDEO || it.type == RAR) {
                val pr = Store.getProgress(Store.fileKey(src, it.path))
                if (pr != null && pr.pos > 0) {
                    val t = Ui.text(ctx, "${Ui.fmtTime(pr.pos)} / ${Ui.fmtTime(pr.dur)}", 15f, Ui.AMBER)
                    val tl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    tl.leftMargin = Ui.dp(ctx, 12)
                    row.addView(t, tl)
                } else if (pr != null && pr.watched) {
                    val c = Ui.icon(ctx, R.drawable.ic_check, 24, Ui.GREEN)
                    (c.layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(ctx, 12)
                    row.addView(c)
                }
                val sz = if (it.type == RAR) "RAR" else Ui.fmtSize(it.size)
                val st = Ui.text(ctx, sz, 15f, Ui.TEXT_DIM)
                st.gravity = Gravity.END
                val sl = LinearLayout.LayoutParams(Ui.dp(ctx, 84), ViewGroup.LayoutParams.WRAP_CONTENT)
                sl.leftMargin = Ui.dp(ctx, 8)
                row.addView(st, sl)
            }
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Store.source(intent.getStringExtra("sourceId") ?: "")
        if (s == null) {
            finish()
            return
        }
        src = s
        shell = Ui.shell(this, src.name)

        list = ListView(this)
        styleKodiList(list)
        list.adapter = adapter
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, position, _ -> onItem(position) }
        shell.content.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = Ui.text(this, "", 20f, Ui.TEXT_DIM)
        status.gravity = Gravity.CENTER
        shell.content.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_star, "Add folder to favorites") { addFavorite() })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_eye, "Toggle watched") { toggleWatched() })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_refresh, "Refresh") { load(path, selected) })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_home, "Home") { finish() })

        setContentView(shell.root)
        load(src.path, 0)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun parent(p: String) = p.substringBeforeLast('/', "")
    private fun join(p: String, n: String) = if (p.isEmpty()) n else "$p/$n"

    private fun load(p: String, select: Int) {
        val my = ++token
        path = p
        shell.subtitle.text = "smb://${src.host}/${src.share}" + if (p.isEmpty()) "" else "/$p"
        status.text = "Loading…"
        status.visibility = View.VISIBLE
        list.visibility = View.INVISIBLE
        io.execute {
            try {
                val entries = Smb.list(src, p)
                val built = build(p, entries)
                runOnUiThread { if (my == token && !isDestroyed) show(built, select) }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                runOnUiThread { if (my == token && !isDestroyed) showError(msg) }
            }
        }
    }

    private fun build(p: String, entries: List<Entry>): List<Item> {
        val names = entries.map { it.name }
        val out = ArrayList<Item>()
        if (p.isNotEmpty()) out.add(Item(UP, "..", parent(p), 0))
        entries.filter { it.isDir }.sortedBy { it.name.lowercase() }.forEach {
            out.add(Item(DIR, it.name, join(p, it.name), 0))
        }
        entries.filter { !it.isDir }.sortedBy { it.name.lowercase() }.forEach {
            if (RarArchive.isVideoName(it.name)) {
                out.add(Item(VIDEO, it.name.substringBeforeLast('.'), join(p, it.name), it.size))
            } else if (RarArchive.isFirstVolumeName(it.name, names)) {
                out.add(Item(RAR, RarArchive.baseName(it.name), join(p, it.name), it.size))
            }
        }
        return out
    }

    private fun show(built: List<Item>, select: Int) {
        items = built
        adapter.notifyDataSetChanged()
        val hasContent = built.any { it.type != UP }
        if (!hasContent) {
            status.text = "No folders or video files here"
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
        list.visibility = View.VISIBLE
        val idx = select.coerceIn(0, maxOf(0, built.size - 1))
        selected = idx
        list.setSelection(idx)
        if (built.isNotEmpty()) list.requestFocus() else shell.sidebar.getChildAt(0).requestFocus()
    }

    private fun showError(msg: String) {
        items = emptyList()
        adapter.notifyDataSetChanged()
        status.text = "Could not open this location:\n$msg"
        status.visibility = View.VISIBLE
        list.visibility = View.INVISIBLE
        shell.sidebar.getChildAt(0).requestFocus()
    }

    private fun onItem(position: Int) {
        val it = items.getOrNull(position) ?: return
        when (it.type) {
            UP -> load(it.path, selMemory[it.path] ?: 0)
            DIR -> {
                selMemory[path] = position
                load(it.path, 0)
            }
            else -> play(it)
        }
    }

    private fun play(item: Item) {
        val pr = Store.getProgress(Store.fileKey(src, item.path))
        if (pr != null && pr.pos > 15_000) {
            AlertDialog.Builder(this)
                .setTitle(item.label)
                .setMessage("You have watched part of this video.")
                .setPositiveButton("Resume from ${Ui.fmtTime(pr.pos)}") { _, _ -> start(item, pr.pos) }
                .setNegativeButton("Play from beginning") { _, _ -> start(item, 0) }
                .show()
        } else {
            start(item, 0)
        }
    }

    private fun start(item: Item, startMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("sourceId", src.id)
                .putExtra("path", item.path)
                .putExtra("rar", item.type == RAR)
                .putExtra("title", item.label)
                .putExtra("startMs", startMs)
        )
    }

    private fun toggleWatched() {
        val it = items.getOrNull(selected)
        if (it == null || (it.type != VIDEO && it.type != RAR)) {
            Toast.makeText(this, "Select a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val key = Store.fileKey(src, it.path)
        if (Store.getProgress(key) != null) {
            Store.clearProgress(key)
            Toast.makeText(this, "Marked as unwatched", Toast.LENGTH_SHORT).show()
        } else {
            Store.setProgress(key, 0, 0, true)
            Toast.makeText(this, "Marked as watched", Toast.LENGTH_SHORT).show()
        }
        adapter.notifyDataSetChanged()
    }

    private fun addFavorite() {
        val dup = Store.sources().any {
            it.host == src.host && it.share == src.share && it.path == path && it.user == src.user && it.domain == src.domain
        }
        if (dup) {
            Toast.makeText(this, "This folder is already in your favorites", Toast.LENGTH_SHORT).show()
            return
        }
        val edit = EditText(this)
        edit.setSingleLine(true)
        edit.setText(if (path.isNotEmpty()) path.substringAfterLast('/') else src.share)
        val box = LinearLayout(this)
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0)
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Add to favorites")
            .setMessage("Name for this folder:")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = edit.text.toString().trim().ifEmpty { "Folder" }
                Store.upsert(Source(Store.newId(), n, src.host, src.share, path, src.user, src.pass, src.domain))
                Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (path.isEmpty() || path == src.path) {
            super.onBackPressed()
        } else {
            val up = parent(path)
            load(up, selMemory[up] ?: 0)
        }
    }
}
