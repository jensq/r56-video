package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Settings: list text size and the skip steps used when seeking in a video. */
class SettingsActivity : Activity() {
    private lateinit var shell: Ui.Shell
    private lateinit var preview: TextView
    private lateinit var langValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var backValue: TextView
    private lateinit var fwdValue: TextView
    private lateinit var audioValue: TextView

    private val sizes = listOf(
        L.t("Extra small") to 12, L.t("Small") to 16, L.t("Medium") to 20, L.t("Large") to 24, L.t("Extra large") to 28, L.t("Huge") to 34
    )
    private val steps = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 300)

    private fun sizeName(sp: Int) = sizes.firstOrNull { it.second == sp }?.first ?: "$sp sp"
    private fun secLabel(s: Int) = if (s % 60 == 0 && s >= 60) L.f("%d min", s / 60) else L.f("%d seconds", s)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = Ui.shell(this, L.t("Settings"))
        shell.subtitle.text = L.t("Changes are saved automatically")

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        shell.content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val r0 = row(L.t("Language")) { chooseLanguage() }
        langValue = r0.second
        col.addView(r0.first)
        val r1 = row(L.t("List text size (files and folders)")) { chooseSize() }
        sizeValue = r1.second
        col.addView(r1.first)
        val r2 = row(L.t("Skip backward")) { chooseStep(true) }
        backValue = r2.second
        col.addView(r2.first)
        val r3 = row(L.t("Skip forward")) { chooseStep(false) }
        fwdValue = r3.second
        col.addView(r3.first)
        val r4 = row(L.t("Audio decoder")) { chooseAudio() }
        audioValue = r4.second
        col.addView(r4.first)

        preview = Ui.text(this, "", 20f)
        preview.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), 0, 0)
        col.addView(preview)
        val hint = Ui.text(this, L.t("Skip steps are used by the rewind/forward buttons and by the left/right keys during playback."), 14f, Ui.TEXT_DIM)
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), 0)
        col.addView(hint)

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_home, L.t("Back")) { finish() })
        setContentView(shell.root)
        refresh()
        r0.first.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!shell.handleBack()) super.onBackPressed()
    }

    private fun row(title: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        r.isFocusable = true
        r.isClickable = true
        r.background = Ui.focusBg()
        r.minimumHeight = Ui.dp(this, 60)
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val t = Ui.text(this, title, 20f)
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = Ui.text(this, "", 20f, Ui.AMBER)
        v.setPadding(Ui.dp(this, 12), 0, 0, 0)
        r.addView(v)
        r.setOnClickListener { onClick() }
        return Pair(r, v)
    }

    private fun refresh() {
        langValue.text = if (Store.language == "sv") "Svenska" else "English"
        sizeValue.text = sizeName(Store.textSizeSp)
        backValue.text = secLabel(Store.seekBackSec)
        fwdValue.text = secLabel(Store.seekForwardSec)
        audioValue.text = if (Store.softwareAudioFirst) L.t("Software first") else L.t("Automatic")
        preview.text = L.t("Preview: Example Folder Name")
        preview.textSize = Store.textSizeSp.toFloat()
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this)
            .setTitle(L.t("Language"))
            .setSingleChoiceItems(arrayOf("English", "Svenska"), if (Store.language == "sv") 1 else 0) { d, which ->
                val code = if (which == 1) "sv" else "en"
                d.dismiss()
                if (code != Store.language) {
                    Store.language = code
                    recreate()
                }
            }
            .show()
    }

    private fun chooseAudio() {
        val items = arrayOf(L.t("Automatic (device decoder, software as backup)"), L.t("Software first (FFmpeg: AC3, E-AC3, DTS, TrueHD…)"))
        AlertDialog.Builder(this)
            .setTitle(L.t("Audio decoder"))
            .setSingleChoiceItems(items, if (Store.softwareAudioFirst) 1 else 0) { d, which ->
                Store.softwareAudioFirst = which == 1
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun chooseSize() {
        val cur = sizes.indexOfFirst { it.second == Store.textSizeSp }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(L.t("List text size"))
            .setSingleChoiceItems(sizes.map { it.first }.toTypedArray(), cur) { d, which ->
                Store.textSizeSp = sizes[which].second
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun chooseStep(back: Boolean) {
        val curVal = if (back) Store.seekBackSec else Store.seekForwardSec
        val cur = steps.indexOf(curVal).let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle(if (back) L.t("Skip backward") else L.t("Skip forward"))
            .setSingleChoiceItems(steps.map { secLabel(it) }.toTypedArray(), cur) { d, which ->
                if (back) Store.seekBackSec = steps[which] else Store.seekForwardSec = steps[which]
                d.dismiss()
                refresh()
            }
            .show()
    }
}
