package com.imperialreader.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.VideoOption
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Dedicated player for anime sources (Source.isAnime). ReaderActivity hands anime episodes over to it; manga, comic
 * and novel reading is untouched.
 *
 * Controls: Media3 PlayerView (play/pause, seek bar, ±10 s, speed/quality/audio/subtitle settings), plus a top bar
 * with SUB / DUB, server picker and previous / next episode. Progress is saved in seconds in ChapterEntity.lastPageRead.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AnimePlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPTER = "chapter_id"
        fun intent(ctx: Context, chapterId: Long): Intent =
            Intent(ctx, AnimePlayerActivity::class.java).putExtra(EXTRA_CHAPTER, chapterId)
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootView: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var message: TextView
    private lateinit var btnSub: TextView
    private lateinit var btnDub: TextView

    private var chapterId = 0L
    private var episodeWasRead = false
    private var episodes: List<ChapterEntity> = emptyList() // oldest -> newest
    private var index = 0
    private var options: List<VideoOption> = emptyList()
    private var optionIndex = 0
    private var pendingStartMs = 0L
    private var webPlayer: WebView? = null
    private var loadJob: Job? = null
    private var playJob: Job? = null

    private val prefs by lazy { getSharedPreferences("anime_player", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saver = object : Runnable {
        override fun run() {
            saveProgress(false)
            handler.postDelayed(this, 10_000)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        loadEpisode(intent.getLongExtra(EXTRA_CHAPTER, 0L), null)
    }

    override fun onStart() {
        super.onStart()
        webPlayer?.onResume()
        handler.postDelayed(saver, 10_000)
    }

    override fun onStop() {
        handler.removeCallbacks(saver)
        saveProgress(false)
        player?.pause()
        webPlayer?.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        releasePlayer()
        webPlayer?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webPlayer = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ UI

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0x66000000)
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rootView = root

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3500
            setShowPreviousButton(false)
            setShowNextButton(false)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        btnSub = chip("SUB") { switchLanguage(false) }
        btnDub = chip("DUB") { switchLanguage(true) }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
            addView(chip("←") { finish() }.also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(6)
            })
            addView(btnSub)
            addView(btnDub)
            addView(chip("Server") { showServerPicker() })
            addView(chip("◀ Ep") { openEpisode(-1) })
            addView(chip("Ep ▶") { openEpisode(1) })
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(16), dp(32), dp(16))
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(16))
            insets
        }
        return root
    }

    private fun showLoading() {
        webPlayer?.visibility = View.GONE
        webPlayer?.onPause()
        spinner.visibility = View.VISIBLE
        message.visibility = View.GONE
    }

    private fun showError(text: String, onTap: () -> Unit) {
        spinner.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
        message.setOnClickListener { onTap() }
        playerView.showController()
    }

    private fun updateButtons() {
        val dub = options.getOrNull(optionIndex)?.dub == true
        val hasSub = options.any { !it.dub }
        val hasDub = options.any { it.dub }
        btnSub.alpha = if (!dub) 1f else 0.45f
        btnDub.alpha = if (dub) 1f else 0.45f
        btnSub.visibility = if (hasSub || hasDub) View.VISIBLE else View.GONE
        btnDub.visibility = if (hasDub) View.VISIBLE else View.GONE
        btnSub.text = if (hasSub) "SUB" else "SUB ✕"
    }

    // ------------------------------------------------------------ loading

    private fun loadEpisode(id: Long, startSeconds: Int?) {
        saveProgress(false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showLoading()
            try {
                val dao = app.db.chapterDao()
                val ch = dao.getById(id) ?: throw IOException("Episode not found")
                val m = app.db.mangaDao().getById(ch.mangaId) ?: throw IOException("Title not found")
                val source = app.sources.get(m.source) ?: throw IOException("Source '${m.source}' is not available")
                chapterId = id
                episodeWasRead = ch.read
                episodes = dao.getAll(m.id).sortedByDescending { it.sourceOrder }
                index = episodes.indexOfFirst { it.id == id }.coerceAtLeast(0)
                titleView.text = "${m.title}  •  ${ch.name}"

                val episode = SChapter(url = ch.url, name = ch.name)
                var list = source.getVideoOptions(episode)
                if (list.isEmpty()) {
                    list = listOf(VideoOption("Default", false) {
                        source.loadPages(SManga(url = m.url, title = m.title), episode)
                    })
                }
                options = list
                val preferDub = prefs.getBoolean("dub", false)
                val pick = list.indexOfFirst { it.dub == preferDub }.takeIf { it >= 0 } ?: 0
                app.repo.recordHistory(id)
                pendingStartMs = startSeconds?.let { it * 1000L } ?: if (ch.read) 0L else ch.lastPageRead * 1000L
                playOption(pick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Couldn't load this episode:\n${e.message}\n\nTap to retry") { loadEpisode(id, startSeconds) }
            }
        }
    }

    private fun playOption(i: Int) {
        if (i !in options.indices) return
        optionIndex = i
        updateButtons()
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            showLoading()
            try {
                val pages = options[i].resolve()
                val url = pages.urls.firstOrNull() ?: throw IOException("The server returned no stream")
                if (options[i].useWebView) {
                    openWebPlayer(url, pages.headers)
                    pendingStartMs = 0L
                    return@launch
                }
                webPlayer?.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                val p = buildPlayer(pages.headers)
                p.setMediaItem(MediaItem.fromUri(url))
                p.prepare()
                if (pendingStartMs > 0) p.seekTo(pendingStartMs)
                pendingStartMs = 0L
                p.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("${options[i].label} failed:\n${e.message}\n\nTap to try another server") { nextServer() }
            }
        }
    }

    private fun nextServer() = playOption(if (options.size > 1) (optionIndex + 1) % options.size else optionIndex)

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        releasePlayer()
        val factory = OkHttpDataSource.Factory(app.network.client).setDefaultRequestProperties(headers)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> spinner.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        spinner.visibility = View.GONE
                        message.visibility = View.GONE
                    }
                    Player.STATE_ENDED -> {
                        saveProgress(true)
                        if (index + 1 in episodes.indices) openEpisode(1)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showError("Playback failed (${error.errorCodeName}).\n\nTap to try another server") { nextServer() }
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun openWebPlayer(url: String, headers: Map<String, String>) {
        releasePlayer()
        val web = webPlayer ?: WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webPlayer = this
        }
        if (web.parent == null) {
            rootView.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        }
        playerView.visibility = View.GONE
        web.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        message.visibility = View.GONE
        web.loadUrl(url, headers)
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ actions

    private fun switchLanguage(dub: Boolean) {
        val target = options.indexOfFirst { it.dub == dub }
        if (target < 0) {
            toast(if (dub) "No dub for this episode" else "No sub for this episode")
            return
        }
        if (target == optionIndex) return
        prefs.edit().putBoolean("dub", dub).apply()
        pendingStartMs = player?.currentPosition ?: 0L
        playOption(target)
    }

    private fun showServerPicker() {
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Server")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), optionIndex) { dialog, which ->
                dialog.dismiss()
                if (which != optionIndex) {
                    pendingStartMs = player?.currentPosition ?: 0L
                    playOption(which)
                }
            }
            .show()
    }

    private fun openEpisode(delta: Int) {
        val target = episodes.getOrNull(index + delta)
        if (target == null) {
            toast(if (delta > 0) "No next episode" else "No previous episode")
            return
        }
        loadEpisode(target.id, null)
    }

    private fun saveProgress(finished: Boolean) {
        val p = player ?: return
        val id = chapterId
        if (id == 0L) return
        val duration = p.duration
        if (duration <= 0L) return
        val position = p.currentPosition
        val done = finished || position >= duration * 0.9
        val read = done || episodeWasRead
        val seconds = if (done) 0 else (position / 1000).toInt()
        if (read) episodeWasRead = true
        ioScope.launch { app.db.chapterDao().saveProgress(id, seconds, read) }
    }
}
