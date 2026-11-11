package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tall image that opens whole on a view on screen shows the part the view opens at as soon as that part is decoded,
 * ahead of the whole image, which replaces it. Each decode is held until the test releases it.
 */
@RunWith(AndroidJUnit4::class)
public class FirstRegionTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val decodes = LinkedBlockingQueue<Decode>()
	private val file = File(context.cacheDir, "page").apply { writeBytes(ByteArray(0)) }
	private val recycledDecoders = AtomicInteger()
	private val openedDecoders = AtomicInteger()
	private val loaded = AtomicInteger()
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		onMain { view.recycle() }
		decodes.forEach { it.release() }
		executor.shutdownNow()
	}

	@Test
	public fun thePartShownFirstShowsBeforeTheWholeImage() {
		open(isOnScreen = true, configure = atTheTop)
		val (region, whole) = firstDecodes()
		assertEquals(Rect(0, 0, WIDTH, VIEW_HEIGHT), region.rect)
		assertFalse("ready before anything is decoded", onMain { view.isReady })

		region.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == region.color }
		assertTrue(onMain { view.isReady })
		assertEquals("image loaded events", 1, loaded.get())
		waitFor { recycledDecoders.get() == 1 }

		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		assertTrue("the region's bitmap is recycled", region.bitmap.isRecycled)
		assertFalse(whole.bitmap.isRecycled)
		assertEquals("image loaded events", 1, loaded.get())
		assertEquals("region decoders opened", 1, openedDecoders.get())
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
	}

	@Test
	public fun thePartIsTheOneTheViewOpensAt() {
		open(isOnScreen = true) { setScaleAndCenter(1f, PointF(WIDTH / 2f, 300f + VIEW_HEIGHT / 2f)) }
		val (region, whole) = firstDecodes()
		assertEquals(Rect(0, 300, WIDTH, 300 + VIEW_HEIGHT), region.rect)
		region.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == region.color }
		// The view stands where it was asked to: the region fills it
		assertEquals(region.color, colorAt(0))
		assertEquals(region.color, colorAt(VIEW_HEIGHT - 1))
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
	}

	@Test
	public fun aPartThatComesAfterTheWholeImageIsNotShown() {
		open(isOnScreen = true, configure = atTheTop)
		val (region, whole) = firstDecodes()
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		region.release()
		waitFor { region.bitmap.isRecycled }
		assertEquals(whole.color, colorAt(VIEW_HEIGHT / 2))
		assertEquals("image loaded events", 1, loaded.get())
		waitFor { recycledDecoders.get() == 1 }
	}

	@Test
	public fun aPartOfAnImageReplacedMeanwhileIsNotShown() {
		open(isOnScreen = true, configure = atTheTop)
		val (region, _) = firstDecodes()
		onMain { view.recycle() }
		region.release()
		waitFor { recycledDecoders.get() == 1 }
		onMain { } // what the main thread had queued by then has run
		assertFalse(onMain { view.isReady })
		assertEquals("image loaded events", 0, loaded.get())
	}

	@Test
	public fun aViewOffScreenLoadsTheWholeImageOnly() {
		open(isOnScreen = false, configure = atTheTop)
		val whole = next()
		assertNull("the whole image", whole.rect)
		waitFor { recycledDecoders.get() == 1 }
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		assertEquals("decodes left", 0, decodes.size)
	}

	@Test
	public fun aViewOpenedNearTheImagesEndLoadsTheWholeImageOnly() {
		// Rows down to 2000 of 3000 would be read for the part: more than half of the whole decode
		open(isOnScreen = true) { setScaleAndCenter(1f, PointF(WIDTH / 2f, 1000f + VIEW_HEIGHT / 2f)) }
		val whole = next()
		assertNull("the whole image", whole.rect)
		waitFor { recycledDecoders.get() == 1 }
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		assertEquals("decodes left", 0, decodes.size)
	}

	/** A view left where it first stands shows the middle of the image: the rows down to 2000 of 3000 */
	@Test
	public fun aViewLeftAtTheImagesMiddleLoadsTheWholeImageOnly() {
		open(isOnScreen = true)
		val whole = next()
		assertNull("the whole image", whole.rect)
		waitFor { recycledDecoders.get() == 1 }
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		assertEquals("decodes left", 0, decodes.size)
	}

	private val atTheTop: SubsamplingScaleImageView.() -> Unit = {
		setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
	}

	/** The region's decode and the whole image's, which start together */
	private fun firstDecodes(): Pair<Decode, Decode> {
		val a = next()
		val b = next()
		val region = listOf(a, b).single { it.rect != null }
		val whole = listOf(a, b).single { it.rect == null }
		return region to whole
	}

	private fun open(isOnScreen: Boolean, configure: SubsamplingScaleImageView.() -> Unit = {}) = onMain {
		view = object : SubsamplingScaleImageView(context) {
			override fun isOnScreen() = isOnScreen
		}.apply {
			backgroundDispatcher = executor.asCoroutineDispatcher()
			bitmapDecoderFactory = factory<ImageDecoder> { HeldDecoder() }
			regionDecoderFactory = factory<ImageRegionDecoder> { HeldRegionDecoder() }
			// The width-fit scale of a webtoon page, at which the whole page is needed at full resolution
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
			minScale = 1f
			maxScale = 1f
			addOnImageEventListener(object : DefaultOnImageEventListener {
				override fun onImageLoaded() {
					loaded.incrementAndGet()
				}
			})
			measure(
				View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, WIDTH, VIEW_HEIGHT)
			setImage(ImageSource.file(file))
			configure()
		}
	}

	/** The colour at row [y] of the view drawn now; drawing also starts the first load once the size is known */
	private fun colorAt(y: Int): Int = onMain {
		val bitmap = Bitmap.createBitmap(WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		view.draw(Canvas(bitmap))
		bitmap.getPixel(WIDTH / 2, y).also { bitmap.recycle() }
	}

	private fun next(): Decode {
		var decode: Decode? = null
		waitFor {
			colorAt(0) // a draw starts the first load
			decode = decodes.poll(50, TimeUnit.MILLISECONDS)
			decode != null
		}
		return checkNotNull(decode)
	}

	private inner class HeldDecoder : ImageDecoder {
		override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap = Decode(null, WIDTH, HEIGHT).await()
	}

	private inner class HeldRegionDecoder : ImageRegionDecoder {
		override fun init(context: Context, uri: Uri): Point {
			openedDecoders.incrementAndGet()
			return Point(WIDTH, HEIGHT)
		}

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
			Decode(Rect(sRect), sRect.width(), sRect.height()).await()

		override val isReady = true

		override fun recycle() {
			recycledDecoders.incrementAndGet()
		}
	}

	/** A decode of [rect] of the image, of all of it when null */
	private inner class Decode(val rect: Rect?, width: Int, height: Int) {
		val color = COLORS[colorIndex++ % COLORS.size]
		val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
		private val gate = CountDownLatch(1)

		fun await(): Bitmap {
			decodes.add(this)
			gate.await()
			return bitmap
		}

		fun release() = gate.countDown()
	}

	private fun <T> factory(make: () -> T) = object : DecoderFactory<T> {
		override val bitmapConfig = Bitmap.Config.ARGB_8888
		override fun make() = make()
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
		const val WIDTH = 800
		const val HEIGHT = 3000
		const val VIEW_HEIGHT = 1000
		val COLORS = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
		var colorIndex = 0
	}
}
