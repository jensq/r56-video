package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home screen: list of favorite network locations. */
class MainActivity : Activity() {
    private lateinit var shell: Ui.Shell
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var sources: List<Source> = emptyList()
    private var selected = 0
    private val langAtCreate = Store.language

    private val adapter = object : BaseAdapter() {
        override fun getCount() = sources.size
        override fun getItem(position: Int): Any = sources[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = sources[position]
            val ctx = this@MainActivity
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), 0)
            row.layoutParams = AbsListViewLp(Ui.dp(ctx, Store.textSizeSp * 34 / 10))
            row.addView(Ui.icon(ctx, R.drawable.ic_star, 28))
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(ctx, s.name, Store.textSizeSp.toFloat()))
            col.addView(Ui.text(ctx, s.address, Store.textSizeSp * 0.65f, Ui.TEXT_DIM).also { it.maxLines = 1 })
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = Ui.dp(ctx, 16)
            row.addView(col, lp)
            return row
        }
    }

    @Suppress("FunctionName")
    private fun AbsListViewLp(h: Int) = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = Ui.shell(this, L.t("Favorites"))
        shell.subtitle.text = L.t("Saved network locations")

        list = ListView(this)
        styleKodiList(list)
        list.adapter = adapter
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val s = sources.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("sourceId", s.id))
        }
        shell.content.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        empty = Ui.text(this, if (shell.isTv) L.t("No favorites yet.\nChoose \"Add location\" in the menu on the left\nto add a network folder (SMB).") else L.t("No favorites yet.\nOpen the menu (top left) and choose\n\"Add location\" to add a network folder (SMB)."), 20f, Ui.TEXT_DIM)
        empty.gravity = Gravity.CENTER
        shell.content.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_add, L.t("Add location")) { showEditor(null) })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_edit, L.t("Edit selected")) {
            sources.getOrNull(selected)?.let { showEditor(it) }
        })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_delete, L.t("Remove selected")) { confirmRemove() })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_settings, L.t("Settings")) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_refresh, L.t("Check for updates")) { Updater.check(this, false) })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_exit, L.t("Exit")) { finishAffinity() })

        val ver = Ui.text(this, L.f("Version %s", Updater.currentName(this)), 13f, Ui.TEXT_DIM)
        ver.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), 0, 0)
        shell.sidebar.addView(ver)

        setContentView(shell.root)
        if (savedInstanceState == null) Updater.check(this, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!shell.handleBack()) super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (Store.language != langAtCreate) {
            recreate()
            return
        }
        reload()
    }

    private fun reload() {
        sources = Store.sources()
        if (selected >= sources.size) selected = maxOf(0, sources.size - 1)
        adapter.notifyDataSetChanged()
        val has = sources.isNotEmpty()
        list.visibility = if (has) View.VISIBLE else View.GONE
        empty.visibility = if (has) View.GONE else View.VISIBLE
        if (has) {
            list.setSelection(selected)
            list.requestFocus()
        } else {
            shell.sidebar.getChildAt(0).requestFocus()
        }
    }

    private fun confirmRemove() {
        val s = sources.getOrNull(selected) ?: return
        AlertDialog.Builder(this)
            .setTitle(L.f("Remove \"%s\"?", s.name))
            .setMessage(L.t("The location is removed from the favorites. Nothing is deleted on the network."))
            .setPositiveButton(L.t("Remove")) { _, _ ->
                Store.remove(s.id)
                reload()
            }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    private fun showEditor(existing: Source?) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8))

        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine(true) // must come first: it would otherwise reset the password masking
            e.inputType = type
            form.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }

        val name = field(L.t("Name (e.g. Movies)"), existing?.name ?: "", InputType.TYPE_CLASS_TEXT)
        val addr = field(
            L.t("Address: smb://server/share/folder"), existing?.address ?: "smb://",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val userText = existing?.let { if (it.domain.isNotEmpty()) "${it.domain}\\${it.user}" else it.user } ?: ""
        val user = field(L.t("Username (empty = guest)"), userText, InputType.TYPE_CLASS_TEXT)
        val pass = field(
            L.t("Password"), existing?.pass ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val scroll = ScrollView(this)
        scroll.addView(form)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) L.t("Add location") else L.t("Edit location"))
            .setView(scroll)
            .setPositiveButton(L.t("Save"), null)
            .setNegativeButton(L.t("Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = Source.parseAddress(addr.text.toString())
            if (parsed == null) {
                Toast.makeText(this, L.t("Address must contain server and share, e.g. smb://192.168.1.10/media"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (host, share, path) = parsed
            val (domain, userName) = Source.splitUser(user.text.toString())
            var title = name.text.toString().trim()
            if (title.isEmpty()) title = if (path.isNotEmpty()) path.substringAfterLast('/') else share
            Store.upsert(Source(existing?.id ?: Store.newId(), title, host, share, path, userName, pass.text.toString(), domain))
            Smb.reset(Source("", "", host, share, "", userName, "", domain))
            dialog.dismiss()
            reload()
        }
    }
}
