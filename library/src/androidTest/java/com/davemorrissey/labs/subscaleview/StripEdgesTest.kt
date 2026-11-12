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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
	private var atOnce = 4

	/** The regions whose decode waits for [release], and those decoded without waiting */
	@Volatile
	private var isHeld: (Rect) -> Boolean = { false }
	private val decoded = CopyOnWriteArrayList<Rect>()

	@Volatile
	private var release = CountDownLatch(1)
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		release.countDown()
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

	/**
	 * A page resumed where the edge between its first two strips stands a quarter of a pixel inside the view (800x15000
	 * on the POCO X3 Pro's screen, 1875 rows a strip, 2531.25 px, at scroll 2531): the edge is drawn at 0, so the first
	 * strip draws nothing of the view and the second covers it. Told from the source rows, the first counted as shown
	 * and the page waited for its decode too.
	 */
	@Test
	public fun aStripThatDrawsNothingOfTheViewIsNotWaitedFor(): Unit = assertShownWithTheStripsDrawn(1080, 2400, 2531)

	/** Around that edge and the next, a pixel at a time, and on the POCO F6's screen (2859.375 px a strip) */
	@Test
	public fun aPageShowsOnceTheStripsDrawnInTheViewAreIn() {
		for (y in (2520..2540) + (5055..5070) + listOf(0, 131, 2400, 17850)) {
			assertShownWithTheStripsDrawn(1080, 2400, y)
		}
		for (y in (2850..2870) + listOf(0, 147, 5718, 20163)) {
			assertShownWithTheStripsDrawn(1220, 2712, y)
		}
	}

	/**
	 * Opens the page at [y] with a decoder that decodes one region at a time and holds every strip but those that
	 * draw a pixel of the view there: the page is ready, and what it draws covers the view. A strip chosen though it
	 * draws nothing there would hold the decoder, and one waited for though it draws nothing would keep the page blank.
	 */
	private fun assertShownWithTheStripsDrawn(viewWidth: Int, viewHeight: Int, y: Int) {
		imageWidth = 800
		imageHeight = 15000
		atOnce = 1
		val scale = viewWidth / imageWidth.toFloat()
		val rows = imageHeight / (imageHeight / kotlin.math.ceil(viewHeight / scale).toInt())
		// The strips' edges as the platform rounds them at this place
		fun edge(strip: Int) = Math.round(minOf(strip * rows, imageHeight) * scale - y)
		isHeld = { sRect ->
			val index = sRect.top / rows
			!(edge(index) < viewHeight && edge(index + 1) > 0)
		}
		decoded.clear()
		open(viewWidth, viewHeight, scale, sampling = 1, y)
		val canvas = RecordingCanvas()
		try {
			waitFor({ "at $y on $viewWidth x $viewHeight: not shown with strips ${decoded.map { it.top / rows }} decoded" }) {
				canvas.drawn.clear()
				onMain {
					view.draw(canvas)
					view.isReady
				}
			}
			val drawn = canvas.drawn.sortedBy { it.rect.top }
			val at = "at $y on $viewWidth x $viewHeight, drawn ${drawn.map { it.rect }}"
			assertEquals("$at: strips decoded", drawn.size, decoded.size)
			assertTrue("$at: the view's top", drawn.first().rect.top <= 0f)
			assertTrue("$at: the view's bottom", drawn.last().rect.bottom >= viewHeight)
			for ((above, below) in drawn.zipWithNext()) {
				assertEquals("$at: where two strips meet", above.rect.bottom, below.rect.top)
			}
			for (strip in drawn) {
				assertTrue("$at: a strip that draws nothing of the view", strip.rect.top < viewHeight && strip.rect.bottom > 0f)
			}
		} finally {
			release.countDown()
			onMain { view.recycle() }
			release = CountDownLatch(1)
			isHeld = { false }
		}
	}

	private fun RectF.isOnWholePixels() = left == floor(left) && top == floor(top) && right == floor(right) && bottom == floor(bottom)

	private fun open(viewWidth: Int, viewHeight: Int, scale: Float, sampling: Int, y: Int = 0) = onMain {
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
			setScaleAndCenter(scale, PointF(imageWidth / 2f, (viewHeight / 2f + y) / scale))
		}
	}

	private inner class Strips : ImageRegionDecoder {

		override fun init(context: Context, uri: Uri): Point = Point(imageWidth, imageHeight)

		private val release = this@StripEdgesTest.release

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
			if (isHeld(sRect)) {
				check(release.await(20, TimeUnit.SECONDS)) { "the test did not release $sRect" }
			} else {
				decoded += Rect(sRect)
			}
			return Bitmap.createBitmap(sRect.width() / sampleSize, sRect.height() / sampleSize, bitmapConfig).apply { eraseColor(STRIP) }
		}

		override val regionsAtOnce: Int = atOnce

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

	private fun waitFor(what: () -> String = { "timed out" }, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (!condition()) {
			check(System.nanoTime() < deadline, what)
			Thread.sleep(10)
		}
	}

	private companion object {
		const val ZOOM = 1.3f
		const val STRIP = Color.RED
		const val UNDER = Color.BLUE
	}
}
