package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A load superseded by a change of [SubsamplingScaleImageView.downSampling] that finishes after the load that replaced
 * it: an off-screen page set to sample 4 and back to 1 while its sample-4 decode still runs. Each decode here is held
 * until the test releases it, so the order is the one the test sets.
 */
@RunWith(AndroidJUnit4::class)
public class SupersededLoadTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val decodes = LinkedBlockingQueue<Decode>()
	private val file = File(context.cacheDir, "page").apply { writeBytes(ByteArray(0)) }
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		onMain { view.recycle() }
		decodes.forEach { it.release() }
		executor.shutdownNow()
	}

	@Test
	public fun wholeImageKeepsTheLoadOfTheCurrentSampling() {
		open(ImageSource.file(file))
		val first = decodes.next(sampleSize = 1, whole = true)
		first.release()
		waitFor { onMain { view.isReady } }

		onMain { view.downSampling = 4 }
		val superseded = decodes.next(sampleSize = 4, whole = true)
		onMain { view.downSampling = 1 }
		val current = decodes.next(sampleSize = 1, whole = true)
		current.release()
		waitFor { shownColor() == current.color }
		superseded.release()
		waitFor { superseded.bitmap.isRecycled || shownColor() == superseded.color }

		assertTrue("the superseded bitmap is recycled", superseded.bitmap.isRecycled)
		assertEquals(current.color, shownColor())
		onMain {
			assertEquals(WIDTH, view.sWidth)
			assertEquals(HEIGHT, view.sHeight)
			assertEquals(1f, view.scale)
		}
		assertFalse(current.bitmap.isRecycled)
	}

	@Test
	public fun tilesKeepTheLoadOfTheCurrentSampling() {
		// A region always opens in tiles
		open(ImageSource.file(file).region(Rect(0, 0, WIDTH, HEIGHT)))
		decodes.next(sampleSize = 1, whole = false).release()
		waitFor { shownColor() != Color.TRANSPARENT }

		onMain { view.downSampling = 4 }
		val superseded = decodes.next(sampleSize = 4, whole = false)
		onMain { view.downSampling = 1 }
		// Loaded again at once, not left waiting for the superseded load
		val current = decodes.next(sampleSize = 1, whole = false)
		current.release()
		waitFor { shownColor() == current.color }
		superseded.release()
		waitFor { superseded.bitmap.isRecycled || shownColor() == superseded.color }

		assertTrue("the superseded tile is recycled", superseded.bitmap.isRecycled)
		assertEquals(current.color, shownColor())
		onMain {
			assertEquals(WIDTH, view.sWidth)
			assertEquals(HEIGHT, view.sHeight)
		}
	}

	/**
	 * The region decoder of a replaced image fails to open after the new image shows: an open that does not answer the
	 * interruption, as BitmapRegionDecoder's native one does not, so the cancelled load resumes with its error.
	 */
	@Test
	public fun initErrorOfAReplacedImageIsNotReported() {
		val opening = HeldInitRegionDecoder()
		val errors = mutableListOf<Throwable>()
		val makes = AtomicInteger()
		// The replaced image opens with the held decoder, the image replacing it with one that opens at once
		open(ImageSource.file(file).region(Rect(0, 0, WIDTH, HEIGHT))) {
			if (makes.getAndIncrement() == 0) opening else HeldRegionDecoder()
		}
		onMain {
			view.addOnImageEventListener(object : DefaultOnImageEventListener {
				override fun onImageLoadError(e: Throwable) {
					errors += e
				}
			})
		}
		assertTrue("the region decoder opens", opening.started.await(10, TimeUnit.SECONDS))
		onMain { view.setImage(ImageSource.file(file)) }
		val current = decodes.next(sampleSize = 1, whole = true)
		current.release()
		waitFor { shownColor() == current.color }

		opening.fail.countDown()
		waitFor { opening.isRecycled }
		onMain { } // what the main thread had queued by then has run

		assertEquals(emptyList<Throwable>(), errors)
		assertEquals(current.color, shownColor())
		assertFalse(current.bitmap.isRecycled)
	}

	private fun open(
		source: ImageSource,
		regionDecoder: () -> ImageRegionDecoder = { HeldRegionDecoder() },
	) = onMain {
		view = SubsamplingScaleImageView(context).apply {
			backgroundDispatcher = executor.asCoroutineDispatcher()
			bitmapDecoderFactory = factory { HeldDecoder() }
			regionDecoderFactory = factory(regionDecoder)
			// The width-fit scale of a webtoon page, at which the whole page is needed at full resolution
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
			minScale = 1f
			maxScale = 1f
			measure(
				View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, WIDTH, VIEW_HEIGHT)
			setImage(source)
		}
	}

	/** The colour at the middle of the view drawn now; drawing also starts the first load once the size is known */
	private fun shownColor(): Int = onMain {
		val bitmap = Bitmap.createBitmap(WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		view.draw(Canvas(bitmap))
		bitmap.getPixel(WIDTH / 2, VIEW_HEIGHT / 2).also { bitmap.recycle() }
	}

	private fun LinkedBlockingQueue<Decode>.next(sampleSize: Int, whole: Boolean): Decode {
		var decode: Decode? = null
		waitFor {
			shownColor() // a draw starts the first load
			decode = poll(50, TimeUnit.MILLISECONDS)
			decode != null
		}
		val next = checkNotNull(decode)
		assertEquals("sample size", sampleSize, next.sampleSize)
		assertEquals("whole image", whole, next.isWhole)
		return next
	}

	private inner class HeldDecoder : ImageDecoder {
		override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap =
			Decode(sampleSize, isWhole = true, WIDTH / sampleSize, HEIGHT / sampleSize).await()
	}

	private inner class HeldRegionDecoder : ImageRegionDecoder {
		override fun init(context: Context, uri: Uri) = Point(WIDTH, HEIGHT)

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
			Decode(sampleSize, isWhole = false, sRect.width() / sampleSize, sRect.height() / sampleSize).await()

		// As a pool with a decoder free beside the one a superseded load still holds
		override val regionsAtOnce: Int = 2

		override val isReady = true

		override fun recycle() = Unit
	}

	/** Opens only when the test lets it, then fails, whatever interrupts it meanwhile */
	private class HeldInitRegionDecoder : ImageRegionDecoder {
		val started = CountDownLatch(1)
		val fail = CountDownLatch(1)
		@Volatile
		var isRecycled = false

		override fun init(context: Context, uri: Uri): Point {
			started.countDown()
			while (true) {
				try {
					fail.await()
					break
				} catch (_: InterruptedException) {
				}
			}
			throw IOException("cannot open the replaced image")
		}

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = error("never opened")

		override val isReady = false

		override fun recycle() {
			isRecycled = true
		}
	}

	private inner class Decode(val sampleSize: Int, val isWhole: Boolean, width: Int, height: Int) {
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
