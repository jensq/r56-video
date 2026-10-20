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
    private lateinit var sizeValue: TextView
    private lateinit var backValue: TextView
    private lateinit var fwdValue: TextView
    private lateinit var audioValue: TextView

    private val sizes = listOf(
        "Extra small" to 12, "Small" to 16, "Medium" to 20, "Large" to 24, "Extra large" to 28, "Huge" to 34
    )
    private val steps = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 300)

    private fun sizeName(sp: Int) = sizes.firstOrNull { it.second == sp }?.first ?: "$sp sp"
    private fun secLabel(s: Int) = if (s % 60 == 0 && s >= 60) "${s / 60} min" else "$s seconds"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = Ui.shell(this, "Settings")
        shell.subtitle.text = "Changes are saved automatically"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        shell.content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val r1 = row("List text size (files and folders)") { chooseSize() }
        sizeValue = r1.second
        col.addView(r1.first)
        val r2 = row("Skip backward") { chooseStep(true) }
        backValue = r2.second
        col.addView(r2.first)
        val r3 = row("Skip forward") { chooseStep(false) }
        fwdValue = r3.second
        col.addView(r3.first)
        val r4 = row("Audio decoder") { chooseAudio() }
        audioValue = r4.second
        col.addView(r4.first)

        preview = Ui.text(this, "", 20f)
        preview.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), 0, 0)
        col.addView(preview)
        val hint = Ui.text(this, "Skip steps are used by the rewind/forward buttons and by the left/right keys during playback.", 14f, Ui.TEXT_DIM)
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), 0)
        col.addView(hint)

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_home, "Back") { finish() })
        setContentView(shell.root)
        refresh()
        r1.first.requestFocus()
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
        sizeValue.text = sizeName(Store.textSizeSp)
        backValue.text = secLabel(Store.seekBackSec)
        fwdValue.text = secLabel(Store.seekForwardSec)
        audioValue.text = if (Store.softwareAudioFirst) "Software first" else "Automatic"
        preview.text = "Preview: Example Folder Name"
        preview.textSize = Store.textSizeSp.toFloat()
    }

    private fun chooseAudio() {
        val items = arrayOf("Automatic (device decoder, software as backup)", "Software first (FFmpeg: AC3, E-AC3, DTS, TrueHD…)")
        AlertDialog.Builder(this)
            .setTitle("Audio decoder")
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
            .setTitle("List text size")
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
            .setTitle(if (back) "Skip backward" else "Skip forward")
            .setSingleChoiceItems(steps.map { secLabel(it) }.toTypedArray(), cur) { d, which ->
                if (back) Store.seekBackSec = steps[which] else Store.seekForwardSec = steps[which]
                d.dismiss()
                refresh()
            }
            .show()
    }
}
