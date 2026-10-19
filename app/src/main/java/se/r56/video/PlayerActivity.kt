package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: TextView
    private lateinit var osd: LinearLayout
    private lateinit var btnPlay: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var toast: TextView
    private lateinit var loading: TextView

    private var osdVisible = false
    private var seeking = false
    private var pendingSeekMs = 0L
    private var tick = 0
    private var destroyed = false
    private var finishing = false
    private lateinit var progressKey: String
    private var startMs = 0L
    private var title = ""

    private val hideOsdRunnable = Runnable { hideOsd() }
    private val hideToastRunnable = Runnable { toast.visibility = View.GONE }
    private val doSeekRunnable = Runnable {
        player?.seekTo(pendingSeekMs)
        seeking = false
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            tick++
            if (tick % 10 == 0) saveProgress(false)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val src = Store.source(intent.getStringExtra("sourceId") ?: "")
        val path = intent.getStringExtra("path")
        if (src == null || path == null) {
            finish()
            return
        }
        val isRar = intent.getBooleanExtra("rar", false)
        title = intent.getStringExtra("title") ?: path.substringAfterLast('/')
        startMs = intent.getLongExtra("startMs", 0L)
        progressKey = Store.fileKey(src, path)

        buildUi()
        setContentView(buildRoot())
        hideSystemUi()

        Thread {
            try {
                val dir = path.substringBeforeLast('/', "")
                val fileName = path.substringAfterLast('/')
                val entries = try {
                    Smb.list(src, dir)
                } catch (e: Exception) {
                    emptyList()
                }
                val uri: Uri
                val subBase: String
                if (isRar) {
                    val res = RarSupport.resolve(src, path)
                    uri = SmbDataSource.buildUri(SmbDataSource.SCHEME_RAR, src.id, "$path/${res.fileName}")
                    subBase = RarArchive.baseName(fileName)
                } else {
                    uri = SmbDataSource.buildUri(SmbDataSource.SCHEME_SMB, src.id, path)
                    subBase = fileName.substringBeforeLast('.')
                }
                val subs = findSubtitles(src, dir, entries, subBase)
                runOnUiThread { if (!destroyed) startPlayer(uri, subs) }
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                runOnUiThread { if (!destroyed) showError(msg) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- UI

    private lateinit var rootView: FrameLayout

    private fun ctrlButton(res: Int, onClick: () -> Unit): ImageView {
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.setColorFilter(Color.WHITE)
        val pad = Ui.dp(this, 12)
        iv.setPadding(pad, pad, pad, pad)
        iv.isFocusable = true
        iv.isClickable = true
        iv.background = Ui.focusBg()
        iv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56))
        lp.leftMargin = Ui.dp(this, 6)
        lp.rightMargin = Ui.dp(this, 6)
        iv.layoutParams = lp
        return iv
    }

    private fun buildUi() {
        rootView = FrameLayout(this)
        rootView.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        rootView.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        loading = Ui.text(this, "Opening…", 22f)
        rootView.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        topBar = Ui.text(this, title, 22f)
        topBar.setPadding(Ui.dp(this, 32), Ui.dp(this, 20), Ui.dp(this, 32), Ui.dp(this, 32))
        topBar.maxLines = 1
        topBar.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        topBar.visibility = View.GONE
        rootView.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        toast = Ui.text(this, "", 28f)
        toast.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12))
        toast.setBackgroundColor(0xAA000000.toInt())
        toast.visibility = View.GONE
        val tlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        tlp.topMargin = Ui.dp(this, 60)
        rootView.addView(toast, tlp)

        osd = LinearLayout(this)
        osd.orientation = LinearLayout.VERTICAL
        osd.setPadding(Ui.dp(this, 40), Ui.dp(this, 36), Ui.dp(this, 40), Ui.dp(this, 20))
        osd.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt()))
        osd.visibility = View.GONE

        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        timeRow.gravity = Gravity.CENTER_VERTICAL
        timeCur = Ui.text(this, "0:00", 18f)
        timeTotal = Ui.text(this, "0:00", 18f)
        seekBar = SeekBar(this)
        seekBar.max = 1000
        seekBar.keyProgressIncrement = 5
        seekBar.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        seekBar.thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        seekBar.isFocusable = true
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = player?.duration ?: return
                if (dur <= 0 || dur == C.TIME_UNSET) return
                seeking = true
                pendingSeekMs = dur * progress / 1000
                timeCur.text = Ui.fmtTime(pendingSeekMs)
                handler.removeCallbacks(doSeekRunnable)
                handler.postDelayed(doSeekRunnable, 600)
                scheduleHideOsd()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        timeRow.addView(timeCur, LinearLayout.LayoutParams(Ui.dp(this, 80), ViewGroup.LayoutParams.WRAP_CONTENT))
        timeRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeTotal.gravity = Gravity.END
        timeRow.addView(timeTotal, LinearLayout.LayoutParams(Ui.dp(this, 80), ViewGroup.LayoutParams.WRAP_CONTENT))
        osd.addView(timeRow)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        buttons.addView(ctrlButton(R.drawable.ic_rew) { seekBy(-10_000) })
        btnPlay = ctrlButton(R.drawable.ic_pause) { togglePlay() }
        buttons.addView(btnPlay)
        buttons.addView(ctrlButton(R.drawable.ic_stop) { stopAndExit() })
        buttons.addView(ctrlButton(R.drawable.ic_ff) { seekBy(30_000) })
        val gap = View(this)
        buttons.addView(gap, LinearLayout.LayoutParams(Ui.dp(this, 40), 1))
        buttons.addView(ctrlButton(R.drawable.ic_audio) { showTrackDialog(C.TRACK_TYPE_AUDIO, "Audio track") })
        buttons.addView(ctrlButton(R.drawable.ic_subs) { showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles") })
        osd.addView(buttons)

        rootView.addView(osd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun buildRoot(): View = rootView

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun showOsd() {
        osdVisible = true
        osd.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        if (!osd.hasFocus()) btnPlay.requestFocus()
        updateUi()
        scheduleHideOsd()
    }

    private fun hideOsd() {
        osdVisible = false
        osd.visibility = View.GONE
        topBar.visibility = View.GONE
        handler.removeCallbacks(hideOsdRunnable)
    }

    private fun scheduleHideOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        if (player?.isPlaying == true) handler.postDelayed(hideOsdRunnable, 6000)
    }

    private fun showToast(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, 1500)
    }

    private fun updateUi() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0 && dur != C.TIME_UNSET) {
            timeTotal.text = Ui.fmtTime(dur)
            if (!seeking) {
                timeCur.text = Ui.fmtTime(pos)
                seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            }
        }
        btnPlay.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---------------------------------------------------------------- playback

    private fun findSubtitles(src: Source, dir: String, entries: List<Entry>, base: String): List<MediaItem.SubtitleConfiguration> {
        val out = ArrayList<MediaItem.SubtitleConfiguration>()
        for (e in entries) {
            if (e.isDir) continue
            val ext = e.name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> null
            } ?: continue
            if (!e.name.startsWith(base, ignoreCase = true)) continue
            val lang = e.name.substring(base.length, e.name.length - ext.length - 1).trim('.', ' ', '_', '-')
            val uri = SmbDataSource.buildUri(SmbDataSource.SCHEME_SMB, src.id, if (dir.isEmpty()) e.name else "$dir/${e.name}")
            val b = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(if (lang.isEmpty()) "External ${ext.uppercase()}" else lang)
            if (lang.isNotEmpty()) b.setLanguage(lang)
            out.add(b.build())
        }
        return out
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveProgress(true)
                finishPlayback()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateUi()
            if (isPlaying) scheduleHideOsd() else handler.removeCallbacks(hideOsdRunnable)
        }

        override fun onPlayerError(error: PlaybackException) {
            val detail = error.cause?.message ?: error.message ?: error.errorCodeName
            showError(detail)
        }
    }

    private fun startPlayer(uri: Uri, subs: List<MediaItem.SubtitleConfiguration>) {
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory()))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        p.addListener(listener)
        playerView.player = p
        val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subs).build()
        p.setMediaItem(item, startMs)
        p.prepare()
        p.playWhenReady = true
        player = p
        loading.visibility = View.GONE
        handler.post(ticker)
    }

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        updateUi()
        if (osdVisible) scheduleHideOsd()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var target = p.currentPosition + deltaMs
        if (target < 0) target = 0
        if (dur > 0 && dur != C.TIME_UNSET && target > dur - 1000) target = dur - 1000
        p.seekTo(target)
        val sign = if (deltaMs > 0) "+" else "-"
        showToast("$sign${Math.abs(deltaMs) / 1000}s   ${Ui.fmtTime(target)}")
        if (osdVisible) scheduleHideOsd()
    }

    private fun saveProgress(ended: Boolean) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val pos = p.currentPosition
        when {
            ended || pos >= dur * 0.95 -> Store.setProgress(progressKey, 0, dur, true)
            pos >= 15_000 -> {
                val old = Store.getProgress(progressKey)
                Store.setProgress(progressKey, pos, dur, old?.watched == true)
            }
        }
    }

    private fun finishPlayback() {
        if (finishing) return
        finishing = true
        finish()
    }

    private fun stopAndExit() {
        saveProgress(false)
        player?.stop()
        finishPlayback()
    }

    private fun showError(msg: String) {
        if (destroyed) return
        loading.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("Playback error")
            .setMessage(msg)
            .setPositiveButton("Close") { _, _ -> finishPlayback() }
            .setOnCancelListener { finishPlayback() }
            .show()
    }

    // ---------------------------------------------------------------- track selection

    private class Choice(val label: String, val selected: Boolean, val action: () -> Unit)

    private fun langName(l: String?): String? {
        if (l.isNullOrEmpty() || l == C.LANGUAGE_UNDETERMINED) return null
        val n = Locale.forLanguageTag(l).getDisplayLanguage(Locale.ENGLISH)
        return if (n.isNullOrEmpty()) l else n.replaceFirstChar { it.uppercase() }
    }

    private fun codecName(mime: String?): String? = when {
        mime == null -> null
        mime == "audio/eac3-joc" -> "E-AC3 Atmos"
        mime == "audio/eac3" -> "E-AC3"
        mime == "audio/ac3" -> "AC3"
        mime.startsWith("audio/vnd.dts") -> "DTS"
        mime == "audio/true-hd" -> "TrueHD"
        mime == "audio/mp4a-latm" -> "AAC"
        mime == "audio/opus" -> "Opus"
        mime == "audio/vorbis" -> "Vorbis"
        mime == "audio/flac" -> "FLAC"
        mime == "audio/mpeg" -> "MP3"
        mime == "application/x-subrip" -> "SRT"
        mime == "text/x-ssa" -> "ASS"
        mime == "application/pgs" -> "PGS"
        mime == "application/vobsub" -> "VobSub"
        mime == "text/vtt" -> "VTT"
        mime == "application/dvbsubs" -> "DVB"
        else -> null
    }

    private fun channels(c: Int): String? = when {
        c <= 0 || c == Format.NO_VALUE -> null
        c == 1 -> "Mono"
        c == 2 -> "Stereo"
        c == 6 -> "5.1"
        c == 8 -> "7.1"
        else -> "${c}ch"
    }

    private fun describe(f: Format, audio: Boolean): String {
        val parts = ArrayList<String>()
        langName(f.language)?.let { parts.add(it) }
        val lbl = f.label
        if (!lbl.isNullOrEmpty() && parts.none { it.equals(lbl, ignoreCase = true) }) parts.add(lbl)
        codecName(f.sampleMimeType)?.let { parts.add(it) }
        if (audio) channels(f.channelCount)?.let { parts.add(it) }
        if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts.add("forced")
        return if (parts.isEmpty()) "Track" else parts.joinToString(" · ")
    }

    private fun showTrackDialog(type: Int, dialogTitle: String) {
        val p = player ?: return
        val audio = type == C.TRACK_TYPE_AUDIO
        val choices = ArrayList<Choice>()
        if (!audio) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            choices.add(Choice("Off", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        val anyDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                n++
                val f = g.getTrackFormat(i)
                var label = describe(f, audio)
                if (!g.isTrackSupported(i)) label += "  (unsupported)"
                val isSel = g.isTrackSelected(i) && !anyDisabled
                val group = g.mediaTrackGroup
                val idx = i
                choices.add(Choice(label, isSel) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group, idx))
                        .build()
                })
            }
        }
        if (n == 0) {
            Toast.makeText(this, if (audio) "No audio tracks found" else "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }
        var checked = choices.indexOfFirst { it.selected }
        if (checked < 0) checked = 0
        handler.removeCallbacks(hideOsdRunnable)
        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), checked) { d, which ->
                choices[which].action()
                d.dismiss()
            }
            .setOnDismissListener { scheduleHideOsd() }
            .show()
    }

    // ---------------------------------------------------------------- keys & lifecycle

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (osdVisible && event.action == KeyEvent.ACTION_DOWN) scheduleHideOsd()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                p.playWhenReady = true
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                p.playWhenReady = false
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopAndExit()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                seekBy(30_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                seekBy(-10_000)
                return true
            }
        }
        if (!osdVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekBy(-10_000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekBy(30_000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOsd()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (osdVisible) hideOsd() else stopAndExit()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
        saveProgress(false)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        saveProgress(false)
        player?.release()
        player = null
        super.onDestroy()
    }
}
