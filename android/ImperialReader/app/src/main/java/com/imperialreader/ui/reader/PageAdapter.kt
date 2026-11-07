package com.imperialreader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.AppCompatImageView
import coil.load
import coil.size.Size
import coil.transform.Transformation
import com.imperialreader.data.source.MangaFirePageTransform
import com.imperialreader.util.dp
import kotlin.math.min

class ZoomablePageImageView(context: Context, private val webtoonMode: Boolean) : AppCompatImageView(context) {
    private val pageMatrix = Matrix()
    private val imageBounds = RectF()
    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (drawable == null) return false
            if (userScale > MIN_SCALE) {
                fitPage()
            } else {
                applyScale(2f, event.x, event.y)
            }
            return true
        }
    })

    private var userScale = MIN_SCALE
    private var lastX = 0f
    private var lastY = 0f
    var onZoomChanged: ((Boolean) -> Unit)? = null

    init {
        scaleType = if (webtoonMode) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.MATRIX
    }

    fun fitPage() {
        val page = drawable ?: return
        val pageWidth = page.intrinsicWidth.toFloat()
        val pageHeight = page.intrinsicHeight.toFloat()
        if (width <= 0 || height <= 0 || pageWidth <= 0 || pageHeight <= 0) return
        userScale = MIN_SCALE
        if (webtoonMode) {
            scaleX = MIN_SCALE
            scaleY = MIN_SCALE
            translationX = 0f
            translationY = 0f
            onZoomChanged?.invoke(false)
            return
        }
        val fit = min(width / pageWidth, height / pageHeight)
        val x = (width - pageWidth * fit) / 2f
        val y = (height - pageHeight * fit) / 2f
        pageMatrix.setScale(fit, fit)
        pageMatrix.postTranslate(x, y)
        setImageMatrix(pageMatrix)
        onZoomChanged?.invoke(false)
    }

    fun resetZoom() {
        if (userScale > MIN_SCALE) fitPage()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fitPage()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gestureHandled = doubleTapDetector.onTouchEvent(event)
        val zoomed = userScale > MIN_SCALE
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x
            lastY = event.y
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && zoomed && event.pointerCount == 1) {
            if (webtoonMode) {
                translationX += event.x - lastX
                translationY += event.y - lastY
                clampViewTransform()
            } else {
                pageMatrix.postTranslate(event.x - lastX, event.y - lastY)
                clampImage()
                setImageMatrix(pageMatrix)
            }
            lastX = event.x
            lastY = event.y
        }

        if (zoomed) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        val handled = super.onTouchEvent(event)
        return handled || gestureHandled || zoomed
    }

    private fun applyScale(nextScale: Float, focusX: Float, focusY: Float) {
        val boundedScale = nextScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = boundedScale / userScale
        if (factor == 1f) return
        if (webtoonMode) {
            translationX += (userScale - boundedScale) * (focusX - width / 2f)
            translationY += (userScale - boundedScale) * (focusY - height / 2f)
            scaleX = boundedScale
            scaleY = boundedScale
        } else {
            pageMatrix.postScale(factor, factor, focusX, focusY)
        }
        userScale = boundedScale
        if (webtoonMode) clampViewTransform() else {
            clampImage()
            setImageMatrix(pageMatrix)
        }
        onZoomChanged?.invoke(userScale > MIN_SCALE)
    }

    private fun clampImage() {
        val page = drawable ?: return
        imageBounds.set(0f, 0f, page.intrinsicWidth.toFloat(), page.intrinsicHeight.toFloat())
        pageMatrix.mapRect(imageBounds)
        val dx = when {
            imageBounds.width() <= width -> width / 2f - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        val dy = when {
            imageBounds.height() <= height -> height / 2f - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
        pageMatrix.postTranslate(dx, dy)
    }

    private fun clampViewTransform() {
        val page = drawable ?: return
        val pageWidth = page.intrinsicWidth.toFloat()
        val pageHeight = page.intrinsicHeight.toFloat()
        if (pageWidth <= 0f || pageHeight <= 0f || width <= 0 || height <= 0) return
        val fit = min(width / pageWidth, height / pageHeight)
        val left = (width - pageWidth * fit) / 2f
        val top = (height - pageHeight * fit) / 2f
        val pivotX = width / 2f
        val pivotY = height / 2f
        imageBounds.set(
            pivotX + (left - pivotX) * userScale + translationX,
            pivotY + (top - pivotY) * userScale + translationY,
            pivotX + (left + pageWidth * fit - pivotX) * userScale + translationX,
            pivotY + (top + pageHeight * fit - pivotY) * userScale + translationY
        )
        translationX += when {
            imageBounds.width() <= width -> pivotX - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        translationY += when {
            imageBounds.height() <= height -> pivotY - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}

/**
 * One adapter for both reader styles.
 * - paged modes (LTR/RTL): each page fills the ViewPager2
 * - webtoon mode: full-width images stacked vertically
 */
class PageAdapter(
    private val headers: Map<String, String>,
    private val webtoon: Boolean,
    private val onSavePage: (ImageView, String) -> Unit,
    private val onZoomChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    private val boundImages = mutableMapOf<Int, ZoomablePageImageView>()
    private val boundHolders = mutableMapOf<Int, VH>()
    private var activePage = 0

    var pages: List<String> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(
        val root: FrameLayout,
        val image: ZoomablePageImageView,
        val video: VideoView,
        val mediaController: MediaController,
        val videoControlsButton: TextView,
        val progress: ProgressBar,
        val error: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(match, if (webtoon) wrap else match)
            if (webtoon) minimumHeight = ctx.dp(320)
        }
        val image = ZoomablePageImageView(ctx, webtoon).apply {
            layoutParams = FrameLayout.LayoutParams(match, if (webtoon) wrap else match)
            if (webtoon) adjustViewBounds = true
        }
        val video = VideoView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(match, if (webtoon) ctx.dp(320) else match)
            visibility = View.GONE
        }
        val mediaController = MediaController(ctx).apply { setAnchorView(root) }
        video.setMediaController(mediaController)
        val videoControlsButton = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = ctx.dp(16)
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC202020.toInt())
                cornerRadius = ctx.dp(20).toFloat()
            }
            text = "VIDEO  |  TAP FOR CONTROLS"
            contentDescription = "Video. Tap to show playback controls"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val progress = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(ctx.dp(36), ctx.dp(36), android.view.Gravity.CENTER)
        }
        val error = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(wrap, wrap, android.view.Gravity.CENTER)
            setTextColor(0xFFA0A0A0.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            visibility = View.GONE
        }
        root.addView(image)
        root.addView(video)
        root.addView(videoControlsButton)
        root.addView(progress)
        root.addView(error)
        return VH(root, image, video, mediaController, videoControlsButton, progress, error)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.image.resetZoom()
        boundHolders[position] = h
        load(h, position)
    }

    fun setActivePage(position: Int) {
        if (position == activePage) return
        val previous = activePage
        activePage = position
        if (previous in pages.indices && isPawchiveVideo(pages[previous])) {
            boundHolders[previous]?.let { holder ->
                holder.video.stopPlayback()
                holder.video.visibility = View.GONE
                holder.mediaController.hide()
                holder.videoControlsButton.visibility = View.GONE
                holder.videoControlsButton.setOnClickListener(null)
                holder.video.setOnClickListener(null)
                holder.progress.visibility = View.GONE
            }
        }
        listOf(previous, position)
            .filter { it in pages.indices && isPawchiveVideo(pages[it]) }
            .distinct()
            .forEach(::notifyItemChanged)
    }

    fun pausePawchiveVideo() {
        boundHolders[activePage]?.takeIf {
            pages.getOrNull(activePage)?.let(::isPawchiveVideo) == true &&
                it.video.visibility == View.VISIBLE
        }?.video?.pause()
    }

    fun resumePawchiveVideo() {
        boundHolders[activePage]?.takeIf {
            pages.getOrNull(activePage)?.let(::isPawchiveVideo) == true &&
                it.video.visibility == View.VISIBLE
        }?.video?.start()
    }

    private fun load(h: VH, position: Int) {
        val url = pages[position]
        boundImages.entries.removeAll { it.value === h.image }
        boundImages[position] = h.image
        h.image.onZoomChanged = { zoomed -> onZoomChanged(position, zoomed) }
        h.error.visibility = View.GONE
        if (isPawchiveVideo(url) && position != activePage) {
            h.video.stopPlayback()
            h.video.setOnPreparedListener(null)
            h.video.setOnErrorListener(null)
            h.mediaController.hide()
            h.video.visibility = View.GONE
            h.videoControlsButton.visibility = View.GONE
            h.videoControlsButton.setOnClickListener(null)
            h.video.setOnClickListener(null)
            h.image.load(null)
            h.image.visibility = View.GONE
            h.image.setOnLongClickListener(null)
            h.progress.visibility = View.GONE
            return
        }
        h.progress.visibility = View.VISIBLE
        if (isVideoPage(h.root.context, url)) {
            h.video.stopPlayback()
            h.image.load(null)
            h.image.visibility = View.GONE
            h.image.setImageDrawable(null)
            h.image.setOnLongClickListener(null)
            h.video.visibility = View.VISIBLE
            h.videoControlsButton.visibility =
                if (isPawchiveVideo(url)) View.VISIBLE else View.GONE
            h.videoControlsButton.text = "VIDEO  |  TAP FOR CONTROLS"
            h.videoControlsButton.contentDescription = "Video. Tap to show playback controls"
            h.videoControlsButton.setOnClickListener {
                if (h.mediaController.isShowing) {
                    h.mediaController.hide()
                    h.videoControlsButton.text = "VIDEO  |  TAP FOR CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to show playback controls"
                } else {
                    h.mediaController.show(0)
                    h.videoControlsButton.text = "VIDEO  |  HIDE CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to hide playback controls"
                }
            }
            h.video.setOnClickListener {
                if (!h.mediaController.isShowing) {
                    h.mediaController.show(0)
                    h.videoControlsButton.text = "VIDEO  |  HIDE CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to hide playback controls"
                }
            }
            h.video.setOnPreparedListener { player ->
                if (h.bindingAdapterPosition != position) return@setOnPreparedListener
                if (isPawchiveVideo(url) && position != activePage) {
                    h.video.stopPlayback()
                    h.video.visibility = View.GONE
                    h.progress.visibility = View.GONE
                    return@setOnPreparedListener
                }
                h.progress.visibility = View.GONE
                h.root.minimumHeight = 0
                player.isLooping = true
            }
            h.video.setOnErrorListener { _, _, _ ->
                if (h.bindingAdapterPosition == position) {
                    h.progress.visibility = View.GONE
                    h.error.text = "Video failed to load\nTap to retry"
                    h.error.visibility = View.VISIBLE
                    h.error.setOnClickListener { load(h, position) }
                }
                true
            }
            h.video.setVideoURI(Uri.parse(url))
            return
        }
        h.video.stopPlayback()
        h.video.setOnPreparedListener(null)
        h.video.setOnErrorListener(null)
        h.mediaController.hide()
        h.video.visibility = View.GONE
        h.videoControlsButton.visibility = View.GONE
        h.videoControlsButton.setOnClickListener(null)
        h.video.setOnClickListener(null)
        h.image.visibility = View.VISIBLE
        h.image.setOnLongClickListener {
            onSavePage(h.image, url)
            true
        }
        h.image.load(url) {
            headers.forEach { (k, v) -> addHeader(k, v) }
            MangaFirePageTransform.offset(url)?.let { transformations(MangaFireImageTransformation(it)) }
            listener(
                onSuccess = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.root.minimumHeight = 0
                    h.image.fitPage()
                },
                onError = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.error.text = "Page ${position + 1} failed to load\nTap to retry"
                    h.error.visibility = View.VISIBLE
                    h.error.setOnClickListener { load(h, position) }
                }
            )
        }
    }

    private class MangaFireImageTransformation(private val offset: Int) : Transformation {
        override val cacheKey = "mangafire-scramble-$offset"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap =
            MangaFirePageTransform.unscramble(input, offset)
    }

    private fun isVideoPage(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val name = uri.lastPathSegment.orEmpty().substringBefore('?')
        if (name.endsWith(".mp4", ignoreCase = true)) return true
        return uri.scheme == "content" &&
            context.contentResolver.getType(uri)?.substringBefore(';')
                ?.equals("video/mp4", ignoreCase = true) == true
    }

    private fun isPawchiveVideo(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return (host.equals("pawchive.pw", ignoreCase = true) ||
            host.endsWith(".pawchive.pw", ignoreCase = true)) &&
            uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".mp4", ignoreCase = true)
    }

    override fun onViewRecycled(holder: VH) {
        boundImages.entries.removeAll { it.value === holder.image }
        boundHolders.entries.removeAll { it.value === holder }
        holder.image.resetZoom()
        holder.image.onZoomChanged = null
        holder.image.setImageDrawable(null)
        holder.video.stopPlayback()
        holder.video.setOnPreparedListener(null)
        holder.video.setOnErrorListener(null)
        holder.mediaController.hide()
        holder.video.visibility = View.GONE
        holder.videoControlsButton.visibility = View.GONE
        holder.videoControlsButton.setOnClickListener(null)
        holder.video.setOnClickListener(null)
        if (webtoon) holder.root.minimumHeight = holder.root.context.dp(320)
        super.onViewRecycled(holder)
    }

    fun resetZoom(position: Int) {
        boundImages[position]?.resetZoom()
    }
}
