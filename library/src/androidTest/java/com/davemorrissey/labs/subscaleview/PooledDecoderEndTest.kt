package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pooled region decoder ended while the thread that fills its pool is at work: by the view, with the last strip
 * of a tall image, with an image that replaces it, with the owner's end; and by itself, over and over. Whatever point
 * the filling is at, no thread throws, the main thread does not wait for it, and every decoder made is recycled.
 */
@RunWith(AndroidJUnit4::class)
public class PooledDecoderEndTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val uncaught = CopyOnWriteArrayList<Throwable>()
	private val errors = CopyOnWriteArrayList<Throwable>()
	private val made = CopyOnWriteArrayList<BitmapRegionDecoder>()
	private val fillers = CopyOnWriteArraySet<Thread>()
	private val ended = AtomicInteger()
	private val atHold = CountDownLatch(1)
	private val release = CountDownLatch(1)
	private var hold: Hold? = null
	private var previousHandler: Thread.UncaughtExceptionHandler? = null
	private var view: SubsamplingScaleImageView? = null
	private val tall: File by lazy { page(HEIGHT) }

	private enum class Hold {
		/** The filling has found its pool alive and not yet opened the decoder to add */
		BEFORE_OPENING,

		/** It has opened the decoder and not yet offered it to the pool */
		AFTER_OPENING,
	}

	@Before
	public fun setUp() {
		previousHandler = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
	}

	@After
	public fun tearDown() {
		release.countDown()
		onMain { view?.recycle() }
		executor.shutdownNow()
		fillers.forEach { it.join(TIMEOUT_MS) }
		Thread.setDefaultUncaughtExceptionHandler(previousHandler)
	}

	@Test
	public fun theLastStripEndsADecoderAboutToOpenAnother(): Unit = endedWithTheLastStrip(Hold.BEFORE_OPENING)

	@Test
	public fun theLastStripEndsADecoderThatHasOpenedAnother(): Unit = endedWithTheLastStrip(Hold.AFTER_OPENING)

	@Test
	public fun anImageReplacedEndsADecoderAboutToOpenAnother(): Unit = endedByTheView(Hold.BEFORE_OPENING) {
		setImage(ImageSource.file(tall))
		setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
	}

	@Test
	public fun anImageReplacedEndsADecoderThatHasOpenedAnother(): Unit = endedByTheView(Hold.AFTER_OPENING) {
		setImage(ImageSource.file(tall))
		setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
	}

	@Test
	public fun anOwnerDestroyedEndsADecoderAboutToOpenAnother(): Unit = endedByTheView(Hold.BEFORE_OPENING) {
		onOwnerDestroyed()
	}

	@Test
	public fun anOwnerDestroyedEndsADecoderThatHasOpenedAnother(): Unit = endedByTheView(Hold.AFTER_OPENING) {
		onOwnerDestroyed()
	}

	/**
	 * A decoder recycled at whatever point its filling has reached, many times over, its file gone by then so that
	 * the filling's turns are short: the filling ends, at its first decoder that fails to open, and throws nothing.
	 */
	@Test
	public fun aDecoderRecycledWhileItsPoolFillsThrowsNothing() {
		repeat(RACES) { race ->
			val file = page(SMALL, "race")
			val decoder = Pooled()
			decoder.init(context, Uri.fromFile(file))
			check(file.delete())
			decoder.decodeRegion(Rect(0, 0, SMALL / 2, SMALL / 2), 1).recycle()
			// The filling is somewhere else each time
			val until = System.nanoTime() + race % 50 * 20_000L
			while (System.nanoTime() < until) {
				Thread.yield()
			}
			decoder.recycle()
			assertFalse(decoder.isReady)
		}
		assertTrue("the fillings started", fillers.size > RACES / 2)
		fillers.forEach {
			it.join(TIMEOUT_MS)
			assertFalse("a filling still runs", it.isAlive)
		}
		assertEquals("thrown on a thread", emptyList<Throwable>(), uncaught)
		assertEveryDecoderIsRecycled()
	}

	/**
	 * A tall image on screen loads in strips through the pool's one decoder while the filling is held; its last strip
	 * ends the decoder, and the filling goes on only then.
	 */
	private fun endedWithTheLastStrip(hold: Hold) {
		open(hold)
		waitFor {
			draw()
			atHold.count == 0L
		}
		waitFor {
			draw()
			ended.get() == 1
		}
		assertEquals("image load errors", emptyList<Throwable>(), errors)
		release.countDown()
		assertTheFillingsEndedCleanly()
	}

	private fun endedByTheView(hold: Hold, end: SubsamplingScaleImageView.() -> Unit) {
		open(hold)
		waitFor {
			draw()
			atHold.count == 0L
		}
		val isEnded = CountDownLatch(1)
		instrumentation.runOnMainSync {
			checkNotNull(view).end()
			isEnded.countDown()
		}
		assertTrue("the main thread waits for the filling", isEnded.await(2, TimeUnit.SECONDS))
		// By the last of its decodes under way, as it returns
		waitFor { ended.get() >= 1 }
		release.countDown()
		onMain { checkNotNull(view).recycle() }
		assertTheFillingsEndedCleanly()
	}

	private fun assertTheFillingsEndedCleanly() {
		fillers.forEach {
			it.join(TIMEOUT_MS)
			assertFalse("a filling still runs", it.isAlive)
		}
		assertEquals("thrown on a thread", emptyList<Throwable>(), uncaught)
		// The one the image opened with and the one the held filling made, at least
		assertTrue("decoders made: ${made.size}", made.size >= 2)
		assertEveryDecoderIsRecycled()
	}

	private fun assertEveryDecoderIsRecycled() {
		waitFor { made.all { it.isRecycled } }
		assertEquals("decoders recycled, of those made", made.size, made.count { it.isRecycled })
	}

	private fun open(hold: Hold) {
		this.hold = hold
		// Written here, not by the main thread
		tall.length()
		onMain { openOnMain() }
	}

	private fun openOnMain() {
		view = object : SubsamplingScaleImageView(context) {
			override fun isOnScreen() = true
		}.apply {
			backgroundDispatcher = executor.asCoroutineDispatcher()
			regionDecoderFactory = object : DecoderFactory<ImageRegionDecoder> {
				override val bitmapConfig = Bitmap.Config.ARGB_8888
				override fun make() = Pooled()
			}
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
			minScale = 1f
			maxScale = 1f
			addOnImageEventListener(object : DefaultOnImageEventListener {
				override fun onImageLoadError(e: Throwable) {
					errors += e
				}

				override fun onTileLoadError(e: Throwable) {
					errors += e
				}
			})
			measure(
				View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, WIDTH, VIEW_HEIGHT)
			setImage(ImageSource.file(tall))
			setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
		}
	}

	/** The real pooled decoder: its filling, up to four decoders whatever the device, stops where the test holds it */
	private inner class Pooled : SkiaPooledImageRegionDecoder(Bitmap.Config.ARGB_8888) {

		override fun allowAdditionalDecoder(numberOfDecoders: Int, fileLength: Long): Boolean {
			fillers += Thread.currentThread()
			val isAllowed = numberOfDecoders < POOL
			if (isAllowed && hold == Hold.BEFORE_OPENING) {
				pause()
			}
			return isAllowed
		}

		override fun onDecoderOpened(decoder: BitmapRegionDecoder) {
			made += decoder
			if (hold == Hold.AFTER_OPENING && Thread.currentThread() in fillers) {
				pause()
			}
		}

		override fun recycle() {
			super.recycle()
			ended.incrementAndGet()
		}

		private fun pause() {
			atHold.countDown()
			check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the test did not let the filling go on" }
		}
	}

	private fun page(height: Int, name: String = "pooled-page"): File {
		val file = File(context.cacheDir, "$name-$height.jpg")
		val bitmap = Bitmap.createBitmap(if (height == SMALL) SMALL else WIDTH, height, Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(Color.BLUE)
		file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
		bitmap.recycle()
		return file
	}

	private fun draw() = onMain {
		val bitmap = Bitmap.createBitmap(WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		checkNotNull(view).draw(Canvas(bitmap))
		bitmap.recycle()
	}

	private fun <T> onMain(block: () -> T): T {
		var result: Result<T>? = null
		instrumentation.runOnMainSync { result = runCatching(block) }
		return checkNotNull(result).getOrThrow()
	}

	private fun waitFor(condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
		while (!condition()) {
			check(System.nanoTime() < deadline) { "timed out" }
			Thread.sleep(10)
		}
	}

	private companion object {
		const val WIDTH = 800
		const val VIEW_HEIGHT = 1000
		const val HEIGHT = 6 * VIEW_HEIGHT
		const val SMALL = 64
		const val POOL = 4
		const val RACES = 400
		const val TIMEOUT_MS = 10_000L
	}
}
