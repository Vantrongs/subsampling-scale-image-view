package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The strips of a tall image in the real pooled decoder, whose pool holds fewer decoders than the view loads tiles at
 * a time: one for an image inside a ZIP, for a pool refused more and for one whose next decoder failed to open, two
 * for a pool allowed two, and one while the second is still being opened. A strip is given to the decoder only when
 * one of its decoders is free to decode it, so none waits inside the pool in the order it came, and a place the view
 * jumps to is the next region a decoder takes: it waits for one decode under way, not for every strip asked for
 * before it. Each decode is held, a decoder of the pool in hand, until the test releases it.
 */
@RunWith(AndroidJUnit4::class)
public class StripOrderInThePoolTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()

	/** The regions given to the decoder, in the order they were */
	private val given = CopyOnWriteArrayList<Rect>()

	/** The regions a decoder of the pool was taken for, in the order they were */
	private val taken = CopyOnWriteArrayList<Rect>()
	private val gates = ConcurrentHashMap<Int, CountDownLatch>()
	private val opened = AtomicInteger()
	private val ended = AtomicInteger()
	private val loaded = AtomicInteger()
	private val errors = CopyOnWriteArrayList<Throwable>()
	private val fillingHeld = CountDownLatch(1)
	private val fillingReleased = CountDownLatch(1)
	private var pool = 4
	private var holdsFilling = false
	private var fileToDelete: File? = null

	@Volatile
	private var isAllReleased = false
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		releaseAll()
		onMain { view.recycle() }
		executor.shutdownNow()
	}

	/** An entry of a ZIP, as a downloaded chapter's page: its length is not known, so the pool keeps its one decoder */
	@Test
	public fun aJumpInAnImageInsideAZipIsTheNextRegionDecoded() {
		val zip = File(context.cacheDir, "strips.zip")
		ZipOutputStream(zip.outputStream()).use {
			it.putNextEntry(ZipEntry(ENTRY))
			it.write(page().readBytes())
			it.closeEntry()
		}
		aJumpWithOneDecoderIsTheNextRegionDecoded(ImageSource.zipEntry(zip, ENTRY))
	}

	/** A pool refused another decoder, as on low memory */
	@Test
	public fun aJumpInAPoolRefusedMoreIsTheNextRegionDecoded() {
		pool = 1
		aJumpWithOneDecoderIsTheNextRegionDecoded(ImageSource.file(page()))
	}

	/** A pool whose filling ended at a decoder that failed to open: the file is gone once the first is open */
	@Test
	public fun aJumpInAPoolWhoseNextDecoderFailedToOpenIsTheNextRegionDecoded() {
		val page = page("gone")
		fileToDelete = page
		aJumpWithOneDecoderIsTheNextRegionDecoded(ImageSource.file(page))
		assertEquals("decoders opened", 1, opened.get())
	}

	private fun aJumpWithOneDecoderIsTheNextRegionDecoded(source: ImageSource) {
		open(source)
		awaitTaken(1)
		assertEquals(listOf(strip(0)), taken)
		release(0)
		waitFor { loaded.get() == 1 }
		// The next decode is under way, held, when the view jumps to the far end
		awaitTaken(2)
		assertEquals(strip(1), taken[1])
		awaitHandover()
		moveTo(STRIPS - 1)
		release(1)
		awaitTaken(3)
		assertEquals("the region decoded after the jump", strip(STRIPS - 1), taken[2])
		assertNothingWaitsInThePool()
		assertEveryStripDecodesOnce()
	}

	@Test
	public fun aJumpInAPoolOfTwoIsTheNextRegionDecoded() {
		pool = 2
		open(ImageSource.file(page()))
		// The second as soon as the pool has its second decoder, without a decode returning
		awaitTaken(2)
		assertEquals(setOf(strip(0), strip(1)), taken.toSet())
		release(0)
		waitFor { loaded.get() == 1 }
		awaitTaken(3)
		assertEquals(strip(2), taken[2])
		awaitHandover()
		moveTo(STRIPS - 1)
		release(1)
		awaitTaken(4)
		assertEquals("the region decoded after the jump", strip(STRIPS - 1), taken[3])
		assertNothingWaitsInThePool()
		assertEquals("decoders opened", 2, opened.get())
		assertEveryStripDecodesOnce()
	}

	/** While the pool's second decoder is being opened no strip waits for it: the one it takes is chosen once it is in */
	@Test
	public fun aJumpWhileThePoolFillsIsTheRegionItsNextDecoderTakes() {
		pool = 2
		holdsFilling = true
		open(ImageSource.file(page()))
		awaitTaken(1)
		check(fillingHeld.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the pool did not start to fill" }
		awaitHandover()
		moveTo(STRIPS - 1)
		fillingReleased.countDown()
		awaitTaken(2)
		assertEquals("the region the second decoder took", strip(STRIPS - 1), taken[1])
		assertNothingWaitsInThePool()
		assertEveryStripDecodesOnce()
	}

	/** What the main thread had queued has run, and what it started has reached the decoder */
	private fun awaitHandover() {
		Thread.sleep(HANDOVER_MS)
		onMain { }
	}

	/** Every region given to the decoder has one of its decoders */
	private fun assertNothingWaitsInThePool() {
		awaitHandover()
		assertEquals("regions given to the decoder: $given, of which it decodes", taken.size, given.size)
	}

	private fun assertEveryStripDecodesOnce() {
		releaseAll()
		waitFor {
			draw()
			ended.get() == 1
		}
		assertEquals("image load errors", emptyList<Throwable>(), errors)
		assertEquals((0 until STRIPS).map(::strip).toSet(), taken.toSet())
		assertEquals("decodes", STRIPS, taken.size)
		assertEquals("regions given to the decoder", STRIPS, given.size)
	}

	private fun awaitTaken(count: Int) = waitFor {
		draw() // a draw starts the first load
		taken.size >= count
	}

	private fun strip(index: Int) = Rect(0, index * VIEW_HEIGHT, WIDTH, (index + 1) * VIEW_HEIGHT)

	private fun gate(strip: Int) = gates.getOrPut(strip) { CountDownLatch(1) }.also { if (isAllReleased) it.countDown() }

	private fun release(strip: Int) = gate(strip).countDown()

	private fun releaseAll() {
		isAllReleased = true
		fillingReleased.countDown()
		gates.values.forEach { it.countDown() }
	}

	private fun moveTo(strip: Int) = onMain {
		view.setScaleAndCenter(1f, PointF(WIDTH / 2f, strip * VIEW_HEIGHT + VIEW_HEIGHT / 2f))
	}

	private fun open(source: ImageSource) = onMain {
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
				override fun onImageLoaded() {
					loaded.incrementAndGet()
				}

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
			setImage(source)
			setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
		}
	}

	/** The real pooled decoder with a pool of [pool] at most; each decode waits, its decoder in hand, for the test */
	private inner class Pooled : SkiaPooledImageRegionDecoder(Bitmap.Config.ARGB_8888) {

		override fun allowAdditionalDecoder(numberOfDecoders: Int, fileLength: Long): Boolean = numberOfDecoders < pool

		override fun onDecoderOpened(decoder: BitmapRegionDecoder) {
			if (opened.incrementAndGet() == 1) {
				fileToDelete?.let { check(it.delete()) }
			} else if (holdsFilling) {
				fillingHeld.countDown()
				check(fillingReleased.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the test did not let the filling go on" }
			}
		}

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
			given += Rect(sRect)
			return super.decodeRegion(sRect, sampleSize)
		}

		override fun onRegionDecoding(sRect: Rect) {
			taken += Rect(sRect)
			check(gate(sRect.top / VIEW_HEIGHT).await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				"the test did not release the decode of $sRect"
			}
		}

		override fun recycle() {
			super.recycle()
			ended.incrementAndGet()
		}
	}

	private fun page(name: String = "page"): File {
		val file = File(context.cacheDir, "strip-order-$name.jpg")
		val bitmap = Bitmap.createBitmap(WIDTH, STRIPS * VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(Color.BLUE)
		file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
		bitmap.recycle()
		return file
	}

	private fun draw() = onMain {
		val bitmap = Bitmap.createBitmap(WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		view.draw(Canvas(bitmap))
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
			check(System.nanoTime() < deadline) { "timed out: given $given, taken $taken" }
			Thread.sleep(10)
		}
	}

	private companion object {
		const val WIDTH = 200
		const val VIEW_HEIGHT = 300
		const val STRIPS = 8
		const val ENTRY = "page.jpg"
		const val HANDOVER_MS = 200L
		const val TIMEOUT_MS = 10_000L
	}
}
