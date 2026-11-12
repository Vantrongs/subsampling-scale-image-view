package com.davemorrissey.labs.subscaleview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a view takes the screen to show of it, under ancestors that zoom, pan and clip it: a parent scaled without
 * being resized (the webtoon reader's frame) leaves a part of the view's rectangle on the screen, and a tile outside
 * that part draws nothing there. The image shows once the tiles that draw a pixel of the screen are decoded, those are
 * decoded first, and a change an ancestor makes, of which the view is told nothing, is seen before the next frame.
 * Which tiles draw a pixel is asked of the platform: the same hierarchy drawn into a bitmap, each strip in a colour of
 * its own.
 */
@RunWith(AndroidJUnit4::class)
public class ViewportTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val file = File(context.cacheDir, "viewport").apply { writeBytes(ByteArray(0)) }
	private lateinit var activity: Activity

	/** The reader's frame: it keeps its size and clips [list], which it scales and moves; the view is in the list */
	private lateinit var frame: FrameLayout
	private lateinit var list: FrameLayout
	private lateinit var view: SubsamplingScaleImageView

	private var imageWidth = IMAGE_WIDTH
	private var imageHeight = IMAGE_HEIGHT
	private var atOnce = 1

	@Volatile
	private var isHeld: (Rect) -> Boolean = { false }
	private val gates = ConcurrentHashMap<Rect, CountDownLatch>()
	private val asked = CopyOnWriteArrayList<Rect>()
	private val decoded = CopyOnWriteArrayList<Rect>()

	/** The decoder of the image now open */
	@Volatile
	private var opened: Strips? = null

	/** What was decoded when the image told it was ready, on the main thread */
	@Volatile
	private var decodedAtReady: List<Rect>? = null

	@Before
	public fun setUp() {
		activity = instrumentation.startActivitySync(
			Intent(context, Activity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		)
		onMain {
			frame = FrameLayout(activity)
			list = FrameLayout(activity)
			frame.addView(list, FrameLayout.LayoutParams(FRAME_WIDTH, FRAME_HEIGHT))
			activity.setContentView(
				FrameLayout(activity).apply {
					addView(frame, FrameLayout.LayoutParams(FRAME_WIDTH, FRAME_HEIGHT, Gravity.TOP or Gravity.START))
				},
			)
		}
	}

	@After
	public fun tearDown() {
		close()
		onMain { activity.finish() }
		executor.shutdownNow()
	}

	/**
	 * The reviewer's case of round 6 at a fifth of its size: a page at a place where its first strip ends above the
	 * middle half of the view, one region decoded at a time, the list zoomed to 2 around its middle while the second
	 * strip decodes. The page is ready with that strip, the only one the screen shows.
	 */
	@Test
	public fun aZoomWhileAStripDecodesLeavesLessToWaitFor() {
		isHeld = { true }
		open(ZOOM_SCROLL)
		waitFor({ "asked $asked" }) { asked.size == 1 }
		assertEquals("the strip started first: of the two in the view, the one further down", 1, strip(asked[0]))
		onMain {
			list.scaleX = 2f
			list.scaleY = 2f
		}
		release(1)
		waitFor({ "not ready with the strip the zoomed screen shows; decoded ${decoded.map(::strip)}" }) { onMain { view.isReady } }
		assertEquals(listOf(1), checkNotNull(decodedAtReady).map(::strip))
	}

	/**
	 * The zoom comes once that strip is in and the page waits for the one above it: no decode ends after it, and the
	 * view does not move in its image, yet the page is ready by the next frame.
	 */
	@Test
	public fun aZoomWithTheStripSeenDecodedShowsTheImage() {
		isHeld = { true }
		open(ZOOM_SCROLL)
		waitFor { asked.size == 1 }
		release(1)
		waitFor({ "asked ${asked.map(::strip)}" }) { asked.size == 2 }
		assertEquals(0, strip(asked[1]))
		onMain { }
		assertFalse("ready without the strip at the view's top", onMain { view.isReady })
		onMain {
			list.scaleX = 2f
			list.scaleY = 2f
		}
		waitFor({ "the view did not look again at what the screen shows of it" }) { onMain { view.isReady } }
		assertEquals(listOf(1), decoded.map(::strip))
	}

	/**
	 * The list panned under a zoom, as the frame pans it: the part of the view the screen shows moves over its strips
	 * though the view stands where it stood, and the strip that comes into it is the next decoded, ahead of those
	 * nearer the place the zoom began at.
	 */
	@Test
	public fun aPanUnderAZoomChoosesTheStripItBringsOntoTheScreen() {
		isHeld = { true }
		// Three strips in the view's rectangle would need a view taller than a strip; here the view shows 2 and 3
		val scroll = (2 * STRIP_PX + FRAME_HEIGHT * 0.3f).toInt()
		onMain {
			list.scaleX = 2f
			list.scaleY = 2f
			// The top half of the list on the screen
			list.translationY = FRAME_HEIGHT / 2f
		}
		open(scroll)
		waitFor { asked.size == 1 }
		assertEquals("the strip the screen shows, not the one further down the view", 2, strip(asked[0]))
		// The bottom half
		onMain { list.translationY = -FRAME_HEIGHT / 2f }
		release(2)
		waitFor({ "asked ${asked.map(::strip)}" }) { asked.size == 2 }
		assertEquals(3, strip(asked[1]))
		assertFalse("ready with a strip the screen no longer shows", onMain { view.isReady })
		release(3)
		waitFor { onMain { view.isReady } }
	}

	/**
	 * At every zoom, pan and place: the image is ready exactly with the strips that draw a pixel of the screen, which
	 * are the first decoded. Zooms above 1 (the list scaled around its middle), below 1, whole and fractional, pans
	 * that leave a part or a row of the view in the frame, and places that put a strip's edge beside the edge of what
	 * the screen shows.
	 */
	@Test
	public fun theImageIsReadyWithTheStripsThatDrawOnTheScreen() {
		val moves = listOf(
			Move(1f, 0f, 0f),
			Move(2f, 0f, 0f),
			Move(2f, 37f, -FRAME_HEIGHT / 2f),
			Move(2.5f, -100f, 200f),
			Move(1.37f, 13.5f, -77.3f),
			Move(0.5f, 0f, 0f),
			Move(0.73f, -20f, 31f),
			Move(1f, 0f, FRAME_HEIGHT * 0.6f),
			Move(1f, 0f, 1.4f - FRAME_HEIGHT),
			Move(1f, FRAME_WIDTH - 1f, 0f),
		)
		val edge = STRIP_PX.toInt()
		val scrolls = listOf(0, 1, 130, edge - FRAME_HEIGHT / 4 - 1, edge - FRAME_HEIGHT / 4, edge - FRAME_HEIGHT / 4 + 1, edge - 1, edge, edge + 1, 2 * edge + 3, MAX_SCROLL)
		for (move in moves) {
			onMain { move.applyTo(list) }
			for (scroll in scrolls) {
				assertReadyWithTheStripsOnTheScreen("$move at $scroll", scroll, expected = null)
			}
		}
	}

	/**
	 * A view no part of which reaches the screen is taken whole: panned out of its frame, hidden, or not in a window,
	 * it is ready with the strips its own rectangle shows, as it was before the screen was asked.
	 */
	@Test
	public fun aViewOffTheScreenIsTakenWhole() {
		val scroll = ZOOM_SCROLL
		assertReadyWithTheStripsOnTheScreen("on the screen", scroll, expected = null)
		val whole = checkNotNull(decodedAtReady).map(::strip)
		assertEquals(listOf(1, 0), whole)
		onMain { list.translationY = 3f * FRAME_HEIGHT }
		assertReadyWithTheStripsOnTheScreen("panned out of the frame", scroll, whole)
		onMain {
			list.translationY = 0f
			list.scaleX = 2f
			list.scaleY = 2f
			frame.visibility = View.INVISIBLE
		}
		assertReadyWithTheStripsOnTheScreen("hidden under a zoom", scroll, whole)
		onMain {
			frame.visibility = View.VISIBLE
			(frame.parent as FrameLayout).removeView(frame)
		}
		assertReadyWithTheStripsOnTheScreen("not in a window", scroll, whole)
	}

	/**
	 * An image in more layers than one, zoomed into: the tiles the screen shows are asked for, and when an ancestor
	 * brings more of the view onto the screen, the tiles of that part are, without the view moving in its image; when
	 * the screen shows less of it and then the same again, nothing is dropped and decoded anew.
	 */
	@Test
	public fun tilesAnAncestorBringsOntoTheScreenAreAskedFor() {
		imageWidth = 4 * FRAME_WIDTH
		imageHeight = 4 * FRAME_HEIGHT
		atOnce = 4
		val fit = FRAME_WIDTH / imageWidth.toFloat()
		// Its first draw, which lays the tiles out as large as the canvas takes them, on a canvas that takes a 16th
		// of the image: before it is in the window, whose canvas would take it whole
		val small = object : Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) {
			override fun getMaximumBitmapWidth() = FRAME_WIDTH
			override fun getMaximumBitmapHeight() = FRAME_HEIGHT
		}
		onMain {
			view = newView(fit, 1f)
			view.measure(
				View.MeasureSpec.makeMeasureSpec(FRAME_WIDTH, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(FRAME_HEIGHT, View.MeasureSpec.EXACTLY),
			)
			view.layout(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
			view.setImage(ImageSource.file(file))
		}
		waitFor({ "the base layer did not load: $asked" }) {
			onMain { view.draw(small) }
			onMain { view.isReady } && asked.size == decoded.size
		}
		val base = asked.size
		// The left half of the list is out of the frame: at a scale of 1 the view shows the middle of the image, a
		// quarter of its width, and the screen the right half of that
		val seen = imageWidth / 2 - FRAME_WIDTH / 2
		val onScreen = imageWidth / 2
		onMain {
			list.translationX = -FRAME_WIDTH / 2f
			list.addView(view, FrameLayout.LayoutParams(FRAME_WIDTH, FRAME_HEIGHT))
			view.setScaleAndCenter(1f, PointF(imageWidth / 2f, imageHeight / 2f))
		}
		fun tiles() = asked.drop(base)
		waitFor({ "asked $asked" }) { tiles().isNotEmpty() && asked.size == decoded.size }
		awaitFrame()
		assertTrue("tiles of the part out of the frame were asked for: ${tiles()}", tiles().all { it.right > onScreen && it.left < onScreen + FRAME_WIDTH / 2 })
		onMain { list.translationX = 0f }
		waitFor({ "the tiles brought onto the screen were not asked for: ${tiles()}" }) { tiles().any { it.right <= onScreen } }
		waitFor { asked.size == decoded.size }
		assertTrue("tiles the view does not show were asked for: ${tiles()}", tiles().all { it.right > seen && it.left < seen + FRAME_WIDTH })
		awaitFrame()
		val before = asked.size
		onMain { list.translationX = -FRAME_WIDTH / 2f }
		awaitFrame()
		onMain { list.translationX = 0f }
		awaitFrame()
		assertEquals("decodes after the view left the screen in part and came back", before, asked.size)
	}

	private class Move(val zoom: Float, val x: Float, val y: Float) {

		fun applyTo(list: FrameLayout) {
			list.scaleX = zoom
			list.scaleY = zoom
			list.translationX = x
			list.translationY = y
		}

		override fun toString() = "zoom $zoom moved $x, $y"
	}

	/**
	 * Opens the image at [scroll] under the ancestors as they are, twice. First with nothing held, to ask the platform
	 * which strips draw a pixel of the screen there ([expected], where the screen shows none of the view): the frame
	 * drawn into a bitmap. Then with every other strip held and one region decoded at a time: the image is ready, and
	 * what was decoded by then is those strips and no other, so none of them was left out and none waited behind a
	 * strip the screen does not show.
	 */
	private fun assertReadyWithTheStripsOnTheScreen(case: String, scroll: Int, expected: List<Int>?) {
		isHeld = { false }
		open(scroll)
		waitFor({ "$case: the strips did not all decode" }) { opened?.isRecycled == true }
		val onScreen = expected?.toSet() ?: stripsDrawn(case)
		assertTrue("$case: nothing of the image on the screen", onScreen.isNotEmpty())
		close()

		isHeld = { strip(it) !in onScreen }
		open(scroll)
		try {
			waitFor({ "$case: not ready with strips $onScreen, which the screen shows; decoded ${decoded.map(::strip)}" }) {
				onMain { view.isReady }
			}
			assertEquals("$case: decoded when ready", onScreen, checkNotNull(decodedAtReady).map(::strip).toSet())
		} finally {
			close()
		}
	}

	/** The strips of which the frame, drawn by the platform into a bitmap, shows a pixel */
	private fun stripsDrawn(case: String): Set<Int> {
		val bitmap = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
		onMain { frame.draw(Canvas(bitmap)) }
		val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
		bitmap.getPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
		bitmap.recycle()
		val colors = pixels.toSet() - 0
		val strips = colors.map { color -> COLORS.indexOf(color) }
		assertTrue("$case: a pixel that is no strip's: ${colors.map { it.toUInt().toString(16) }}", strips.none { it < 0 })
		return strips.toSet()
	}

	private fun strip(sRect: Rect) = sRect.top / STRIP_ROWS

	private fun release(strip: Int) {
		gates.entries.filter { strip(it.key) == strip }.forEach { it.value.countDown() }
	}

	private fun newView(min: Float, max: Float) = object : SubsamplingScaleImageView(activity) {
		// Also where the screen shows none of it, so that it loads in strips there too
		override fun isOnScreen() = true

		override fun onReady() {
			decodedAtReady = decoded.toList()
		}
	}.apply {
		backgroundDispatcher = executor.asCoroutineDispatcher()
		regionDecoderFactory = object : DecoderFactory<ImageRegionDecoder> {
			override val bitmapConfig = Bitmap.Config.ARGB_8888
			override fun make() = Strips().also { opened = it }
		}
		minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
		minScale = min
		maxScale = max
	}

	private fun open(scroll: Int) {
		val scale = SCALE
		val centre = PointF(imageWidth / 2f, (FRAME_HEIGHT / 2f + scroll) / SCALE)
		asked.clear()
		decoded.clear()
		gates.clear()
		opened = null
		decodedAtReady = null
		onMain {
			view = newView(scale, scale)
			list.addView(view, FrameLayout.LayoutParams(FRAME_WIDTH, FRAME_HEIGHT))
		}
		// Laid out and in its window before it has an image, as a page of a list is
		waitFor({ "the view was not laid out" }) { onMain { view.width == FRAME_WIDTH && view.height == FRAME_HEIGHT || view.parent?.parent?.parent == null } }
		onMain {
			if (view.width == 0) {
				// Not in a window: no layout comes
				view.measure(
					View.MeasureSpec.makeMeasureSpec(FRAME_WIDTH, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(FRAME_HEIGHT, View.MeasureSpec.EXACTLY),
				)
				view.layout(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
			}
			view.setImage(ImageSource.file(file))
			view.setScaleAndCenter(scale, centre)
		}
		if (!onMain { view.isShown }) {
			// Nothing draws it, and its first draw is what lays its tiles out
			val canvas = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
			waitFor({ "the image did not open" }) {
				onMain { view.draw(canvas) }
				asked.isNotEmpty()
			}
		}
	}

	private fun close() {
		gates.values.forEach { it.countDown() }
		isHeld = { false }
		if (::view.isInitialized) {
			onMain {
				view.recycle()
				list.removeView(view)
			}
			// Its decodes have all returned by then, and tell the next image nothing
			waitFor({ "the decoder was not recycled" }) { opened?.isRecycled != false }
		}
	}

	/** A frame of the window drawn after this call */
	private fun awaitFrame() {
		val drawn = CountDownLatch(1)
		onMain {
			frame.invalidate()
			// A frame's callbacks run before its traversal: the second one comes after the traversal of the first
			Choreographer.getInstance().postFrameCallback {
				Choreographer.getInstance().postFrameCallback { drawn.countDown() }
			}
		}
		assertTrue("no frame was drawn", drawn.await(5, TimeUnit.SECONDS))
		onMain { }
	}

	private inner class Strips : ImageRegionDecoder {

		override fun init(context: Context, uri: Uri): Point = Point(imageWidth, imageHeight)

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
			val rect = Rect(sRect)
			asked += rect
			if (isHeld(rect)) {
				val gate = gates.getOrPut(rect) { CountDownLatch(1) }
				check(gate.await(20, TimeUnit.SECONDS)) { "the test did not release $rect" }
			}
			val bitmap = Bitmap.createBitmap(rect.width() / sampleSize, rect.height() / sampleSize, Bitmap.Config.ARGB_8888)
			bitmap.eraseColor(COLORS[strip(rect) % COLORS.size])
			decoded += rect
			return bitmap
		}

		override val regionsAtOnce: Int
			get() = atOnce

		override val isReady = true

		@Volatile
		var isRecycled = false

		override fun recycle() {
			isRecycled = true
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
			Thread.sleep(5)
		}
	}

	private companion object {
		// A fifth of the POCO X3 Pro's screen and of an 800x15000 page on it: 8 strips of 375 rows, 506.25 px each
		const val FRAME_WIDTH = 216
		const val FRAME_HEIGHT = 480
		const val IMAGE_WIDTH = 160
		const val IMAGE_HEIGHT = 3000
		const val SCALE = FRAME_WIDTH / IMAGE_WIDTH.toFloat()
		const val STRIP_ROWS = 375
		const val STRIP_PX = STRIP_ROWS * SCALE
		const val MAX_SCROLL = 4050 - FRAME_HEIGHT

		// The first edge at a fifth of the view's height: above the middle half, which a zoom of 2 leaves on the screen
		const val ZOOM_SCROLL = 506 - FRAME_HEIGHT / 5
		val COLORS = intArrayOf(
			Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.GRAY,
		).toList()
	}
}
