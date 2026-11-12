package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * Where the strips of a tall image are drawn: each on whole pixels of the view, its edges the same numbers as those of
 * the strips above and below it, wherever the view stands, at the scales of both phones' screens and at a sampling;
 * and without anti-aliasing, which on a canvas a parent has scaled would blend two strips that meet with what is
 * under them. A strip drawn through a matrix from its corners ended a fraction of a pixel off (1875 rows drawn
 * 2530 px tall, at 2530.0002), which the GPU draws apart from its neighbours and by another program.
 */
@RunWith(AndroidJUnit4::class)
public class StripEdgesTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val file = File(context.cacheDir, "edges").apply { writeBytes(ByteArray(0)) }
	private val recycledDecoders = AtomicInteger()
	private var imageWidth = 0
	private var imageHeight = 0
	private var bitmapConfig = Bitmap.Config.ALPHA_8
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		onMain { view.recycle() }
		executor.shutdownNow()
	}

	/** The screen of the POCO X3 Pro and the page of round 3's phone run: 8 strips of 1875 rows, 2531.25 px each */
	@Test
	public fun stripsOfAPageFittedToAPhonesWidthAreDrawnOnWholePixels(): Unit = assertStripsOnWholePixels(1080, 2400, 800, sampling = 1)

	/** The POCO F6's */
	@Test
	public fun stripsOfAPageFittedToAnotherWidthAreDrawnOnWholePixels(): Unit = assertStripsOnWholePixels(1220, 2712, 800, sampling = 1)

	/** A width whose scale, in single precision, puts the page's right edge a fraction inside the view's: 1219.9999 */
	@Test
	public fun stripsOfAPageWhoseEdgeComesOutShortCoverTheView(): Unit = assertStripsOnWholePixels(1220, 2712, 1110, sampling = 1)

	/** Decoded at half the resolution, as «Reduce memory consumption» asks of a page */
	@Test
	public fun stripsAtASamplingAreDrawnOnWholePixels(): Unit = assertStripsOnWholePixels(1080, 2400, 800, sampling = 2)

	private fun assertStripsOnWholePixels(viewWidth: Int, viewHeight: Int, pageWidth: Int, sampling: Int) {
		imageWidth = pageWidth
		imageHeight = 15000 * pageWidth / 800
		val scale = viewWidth / imageWidth.toFloat()
		open(viewWidth, viewHeight, scale, sampling)
		val canvas = RecordingCanvas()
		waitFor {
			onMain { view.draw(canvas) }
			recycledDecoders.get() == 1
		}
		val range = (imageHeight * scale).toInt() - viewHeight
		var strips = 0
		var antiAliased = 0
		for (y in (0..range step 13) + range) {
			canvas.drawn.clear()
			onMain {
				view.setScaleAndCenter(scale, PointF(imageWidth / 2f, (viewHeight / 2f + y) / scale))
				view.draw(canvas)
			}
			val drawn = canvas.drawn.sortedBy { it.rect.top }
			strips = drawn.size
			for ((index, strip) in drawn.withIndex()) {
				val at = "at $y, strip $index of ${strip.rows} rows drawn ${strip.rect}"
				assertTrue("$at: an edge off a whole pixel", strip.rect.isOnWholePixels())
				assertEquals("$at: its left edge", 0f, strip.rect.left)
				assertEquals("$at: its right edge", viewWidth.toFloat(), strip.rect.right)
				if (index > 0) {
					assertEquals("$at: its top against the bottom of the strip above", drawn[index - 1].rect.bottom, strip.rect.top)
				}
				if (strip.isAntiAliased) {
					antiAliased++
				}
			}
			assertEquals("at $y: the page's top", (-y).toFloat(), drawn.first().rect.top)
			assertEquals("at $y: the page's bottom", (range + viewHeight - y).toFloat(), drawn.last().rect.bottom)
		}
		assertTrue("strips drawn: $strips", strips > 2)
		assertEquals("strips drawn anti-aliased", 0, antiAliased)
	}

	/**
	 * A parent that zooms draws the view on a scaled canvas (the webtoon reader's frame), where no edge of a strip is
	 * on a whole pixel: the strips still meet without a line of what is under them between them.
	 */
	@Test
	public fun stripsMeetWithoutASeamOnAScaledCanvas() {
		imageWidth = 80
		imageHeight = 1500
		bitmapConfig = Bitmap.Config.ARGB_8888
		val viewWidth = 108
		val viewHeight = 240
		val scale = viewWidth / imageWidth.toFloat()
		open(viewWidth, viewHeight, scale, sampling = 1)
		val bitmap = Bitmap.createBitmap((viewWidth * ZOOM).toInt(), (viewHeight * ZOOM).toInt(), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		canvas.scale(ZOOM, ZOOM)
		waitFor {
			onMain { view.draw(canvas) }
			recycledDecoders.get() == 1
		}
		val range = (imageHeight * scale).toInt() - viewHeight
		for (y in 0..range step 7) {
			onMain {
				bitmap.eraseColor(UNDER)
				view.setScaleAndCenter(scale, PointF(imageWidth / 2f, (viewHeight / 2f + y) / scale))
				view.draw(canvas)
			}
			// But the canvas's last row, which the view's own bottom edge may share with what is under it
			for (row in 0 until bitmap.height - 1) {
				assertEquals("at $y, row $row", STRIP.toUInt().toString(16), bitmap.getPixel(bitmap.width / 2, row).toUInt().toString(16))
			}
		}
	}

	private fun RectF.isOnWholePixels() = left == floor(left) && top == floor(top) && right == floor(right) && bottom == floor(bottom)

	private fun open(viewWidth: Int, viewHeight: Int, scale: Float, sampling: Int) = onMain {
		view = object : SubsamplingScaleImageView(context) {
			override fun isOnScreen() = true
		}.apply {
			backgroundDispatcher = executor.asCoroutineDispatcher()
			regionDecoderFactory = object : DecoderFactory<ImageRegionDecoder> {
				override val bitmapConfig = Bitmap.Config.ARGB_8888
				override fun make() = Strips()
			}
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
			minScale = scale
			maxScale = scale
			downSampling = sampling
			measure(
				View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, viewWidth, viewHeight)
			setImage(ImageSource.file(file))
			setScaleAndCenter(scale, PointF(imageWidth / 2f, viewHeight / 2f / scale))
		}
	}

	private inner class Strips : ImageRegionDecoder {

		override fun init(context: Context, uri: Uri): Point = Point(imageWidth, imageHeight)

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
			Bitmap.createBitmap(sRect.width() / sampleSize, sRect.height() / sampleSize, bitmapConfig).apply { eraseColor(STRIP) }

		override val regionsAtOnce: Int = 4

		override val isReady = true

		override fun recycle() {
			recycledDecoders.incrementAndGet()
		}
	}

	private class Drawn(val rows: Int, val rect: RectF, val isAntiAliased: Boolean)

	/** Records where each bitmap is drawn, as the canvas is asked to draw it, without drawing it */
	private class RecordingCanvas : Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) {

		val drawn = ArrayList<Drawn>()

		override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
			val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
			matrix.mapRect(rect)
			drawn += Drawn(bitmap.height, rect, paint?.isAntiAlias == true)
		}

		override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
			check(src == null) { "a part of a strip's bitmap" }
			drawn += Drawn(bitmap.height, RectF(dst), paint?.isAntiAlias == true)
		}
	}

	private fun <T> onMain(block: () -> T): T {
		var result: Result<T>? = null
		instrumentation.runOnMainSync { result = runCatching(block) }
		return checkNotNull(result).getOrThrow()
	}

	private fun waitFor(condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (!condition()) {
			check(System.nanoTime() < deadline) { "timed out" }
			Thread.sleep(10)
		}
	}

	private companion object {
		const val ZOOM = 1.3f
		const val STRIP = Color.RED
		const val UNDER = Color.BLUE
	}
}
