package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tall image needed at full resolution on a view on screen loads in strips no shorter than the view, the ones the
 * view shows first, and shows once those are decoded, wherever the view has been asked to stand by then. The strips
 * still waiting follow the view: each one that starts is the nearest to where the view stands then, so a place the
 * view moves to is the next decoded. Nothing else is decoded for the image, and its decoder ends with its last strip.
 * Each decode is held until the test releases it.
 */
@RunWith(AndroidJUnit4::class)
public class StripsTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context: Context = instrumentation.targetContext
	private val executor = Executors.newCachedThreadPool()
	private val asked = CopyOnWriteArrayList<Decode>()
	private val file = File(context.cacheDir, "page").apply { writeBytes(ByteArray(0)) }
	private val recycledDecoders = AtomicInteger()
	private val openedDecoders = AtomicInteger()
	private val loaded = AtomicInteger()
	private var decodesRegionsApart = true
	private var ignoresInterruption = false
	private var imageHeight = HEIGHT
	private lateinit var view: SubsamplingScaleImageView

	@After
	public fun tearDown() {
		// The decodes first: one that ignores interruption would keep whatever waits for it
		asked.forEach { it.release() }
		onMain { view.recycle() }
		executor.shutdownNow()
	}

	@Test
	public fun theStripTheViewOpensAtShowsBeforeTheRest() {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		assertFalse("ready before anything is decoded", onMain { view.isReady })

		first.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == first.color }
		assertTrue(onMain { view.isReady })
		assertEquals("image loaded events", 1, loaded.get())

		val rest = (1 until STRIPS).map { decode(it).also(Decode::release) }
		for ((index, decode) in rest.withIndex()) {
			val top = (index + 1) * VIEW_HEIGHT
			onMain { view.setScaleAndCenter(1f, PointF(WIDTH / 2f, top + VIEW_HEIGHT / 2f)) }
			waitFor { colorAt(VIEW_HEIGHT / 2) == decode.color }
			assertEquals(decode.color, colorAt(0))
			assertEquals(decode.color, colorAt(VIEW_HEIGHT - 1))
		}
		// Every strip is there: the decoder ends with the last of them, no place the view goes to decodes anything, and
		// nothing was decoded but the strips
		waitFor { recycledDecoders.get() == 1 }
		for (y in 0..HEIGHT - VIEW_HEIGHT step VIEW_HEIGHT / 3) {
			onMain { view.setScaleAndCenter(1f, PointF(WIDTH / 2f, y + VIEW_HEIGHT / 2f)) }
			colorAt(0)
		}
		onMain { }
		assertEquals("decodes", STRIPS, asked.size)
		assertTrue("every decode is a strip at full resolution", asked.all { it.rect != null && it.sampleSize == 1 })
		assertEquals("image loaded events", 1, loaded.get())
		assertEquals("region decoders opened", 1, openedDecoders.get())
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
		assertEquals("the strips stay drawn without their decoder", rest.last().color, colorAt(VIEW_HEIGHT / 2))

		onMain { view.recycle() }
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
		assertTrue("the strips are recycled with the image", asked.all { it.bitmap.isRecycled })
	}

	/** A view as tall as no whole part of the image: the strips are no shorter than it, so its top shows with one */
	@Test
	public fun aViewAtTheTopShowsWithItsFirstStrip() {
		imageHeight = STRIPS * VIEW_HEIGHT - VIEW_HEIGHT / 10
		open(isOnScreen = true, configure = at(0))
		var first: Decode? = null
		waitFor {
			colorAt(0)
			first = asked.firstOrNull { it.rect?.top == 0 }
			first != null
		}
		val top = checkNotNull(first)
		assertTrue("a strip shorter than the view", checkNotNull(top.rect).height() >= VIEW_HEIGHT)
		top.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == top.color }
		assertEquals(top.color, colorAt(VIEW_HEIGHT - 1))
		assertEquals("image loaded events", 1, loaded.get())
	}

	/**
	 * The view's owner destroyed, as a reader closed, while a strip decodes in a decoder that does not end for it: the
	 * main thread goes on at once, and the decoder and the strip are released when the decode returns.
	 */
	@Test
	public fun anOwnerDestroyedWhileAStripDecodesDoesNotWaitForIt() {
		ignoresInterruption = true
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		val destroyed = CountDownLatch(1)
		Handler(Looper.getMainLooper()).post {
			view.onOwnerDestroyed()
			destroyed.countDown()
		}
		assertTrue("the main thread waits for the decode", destroyed.await(2, TimeUnit.SECONDS))
		onMain { }
		assertEquals("a decoder recycled under its decode", 0, recycledDecoders.get())
		assertFalse(first.bitmap.isRecycled)
		assertFalse(first.isTaken)
		asked.forEach { it.release() }
		waitFor { recycledDecoders.get() == 1 }
		waitFor { asked.all { it.bitmap.isRecycled } }
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
		assertEquals("image loaded events", 0, loaded.get())
	}

	/** The same for an image replaced: its decoder ends when its decode returns, and the new image loads meanwhile */
	@Test
	public fun anImageReplacedWhileAStripDecodesDoesNotWaitForIt() {
		ignoresInterruption = true
		open(isOnScreen = true, configure = at(0))
		val old = (0 until TILE_LOADS).map(::decode)
		val replaced = CountDownLatch(1)
		Handler(Looper.getMainLooper()).post {
			view.setImage(ImageSource.file(file))
			view.setScaleAndCenter(1f, PointF(WIDTH / 2f, VIEW_HEIGHT / 2f))
			replaced.countDown()
		}
		assertTrue("the main thread waits for the decode", replaced.await(2, TimeUnit.SECONDS))
		waitFor {
			colorAt(0)
			openedDecoders.get() == 2
		}
		assertEquals("a decoder recycled under its decodes", 0, recycledDecoders.get())
		// The decodes of the image dropped still hold the threads strips load on: no more start beside them
		Thread.sleep(HANDOVER_MS)
		colorAt(0)
		assertEquals("decodes asked for", TILE_LOADS, asked.size)
		old.first().release()
		waitFor {
			colorAt(0)
			asked.size == TILE_LOADS + 1
		}
		val first = asked.last()
		assertEquals(strip(0), first.rect)
		old.drop(1).forEach { it.release() }
		waitFor { recycledDecoders.get() == 1 }
		waitFor { old.all { it.bitmap.isRecycled } }
		first.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == first.color }
	}

	/** The view's owner destroyed, as a reader closed: what was decoded and the decoder go with it, and what still decodes */
	@Test
	public fun theStripsAndTheirDecoderGoWithTheViewsOwner() {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		first.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == first.color }
		val second = decode(1)
		onMain {
			second.release()
			second.awaitReturned()
			Thread.sleep(HANDOVER_MS)
			view.onOwnerDestroyed()
		}
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
		assertTrue("the strip shown is recycled", first.bitmap.isRecycled)
		waitFor { second.bitmap.isRecycled }
	}

	/** At another sampling every strip loads again at once, not each as the view comes to it */
	@Test
	public fun everyStripLoadsAgainAtAnotherSampling() {
		open(isOnScreen = true, configure = at(0))
		(0 until STRIPS).forEach { decode(it).release() }
		waitFor { colorAt(VIEW_HEIGHT / 2) == decode(0).color }
		waitFor { asked.size == STRIPS && onMain { asked.all { it.isTaken } } }
		waitFor { recycledDecoders.get() == 1 }
		onMain { view.downSampling = 2 }
		waitFor {
			asked.forEach { it.release() }
			asked.size == 2 * STRIPS
		}
		assertEquals((0 until STRIPS).map(::strip).toSet(), asked.filter { it.sampleSize == 2 }.map { it.rect }.toSet())
		// Their decoder was opened for them, and ends with the last of them as the first did
		assertEquals("region decoders opened", 2, openedDecoders.get())
		waitFor { recycledDecoders.get() == 2 }
		assertEquals("image loaded events", 1, loaded.get())
	}

	/** A sampling changed again while the decoder opens for it: one decoder, and the strips at the sampling last set */
	@Test
	public fun aSamplingChangedTwiceOpensTheDecoderOnce() {
		open(isOnScreen = true, configure = at(0))
		(0 until STRIPS).forEach { decode(it).release() }
		waitFor { recycledDecoders.get() == 1 }
		onMain {
			view.downSampling = 2
			view.downSampling = 4
		}
		waitFor {
			asked.forEach { it.release() }
			asked.size == 2 * STRIPS
		}
		assertEquals((0 until STRIPS).map(::strip).toSet(), asked.filter { it.sampleSize == 4 }.map { it.rect }.toSet())
		assertEquals("region decoders opened", 2, openedDecoders.get())
		waitFor { recycledDecoders.get() == 2 }
	}

	/** A sampling changed while strips decode: those are dropped, and the decoder ends when the last of either returns */
	@Test
	public fun aSamplingChangedWhileStripsDecodeEndsTheDecoderAfterBoth() {
		ignoresInterruption = true
		open(isOnScreen = true, configure = at(0))
		val old = (0 until TILE_LOADS).map(::decode)
		onMain { view.downSampling = 2 }
		old.drop(1).forEach { it.release() }
		waitFor {
			asked.filter { it.sampleSize == 2 }.forEach { it.release() }
			asked.count { it.sampleSize == 2 } == STRIPS && onMain { asked.all { it === old.first() || it.isTaken } }
		}
		waitFor { colorAt(VIEW_HEIGHT / 2) == asked.first { it.sampleSize == 2 && it.rect == strip(0) }.color }
		Thread.sleep(HANDOVER_MS)
		assertEquals("a decoder recycled under a decode", 0, recycledDecoders.get())
		old.first().release()
		waitFor { recycledDecoders.get() == 1 }
		waitFor { old.all { it.bitmap.isRecycled } }
		assertEquals("region decoders opened", 1, openedDecoders.get())
	}

	/** The strips nearest to where the view opens are asked for first, as many as load at a time, the rest after them */
	@Test
	public fun theStripsNearestTheViewAreAskedForFirst() {
		open(isOnScreen = true, configure = at(HEIGHT - VIEW_HEIGHT))
		val nearest = (STRIPS - TILE_LOADS until STRIPS).map(::decode)
		assertEquals("decodes asked for at once", TILE_LOADS, asked.size)
		nearest.last().release()
		decode(STRIPS - TILE_LOADS - 1)
		assertEquals("decodes asked for", TILE_LOADS + 1, asked.size)
	}

	/** A view that opens over two strips shows once both are there */
	@Test
	public fun aViewOverTwoStripsShowsOnceBothAreThere() {
		open(isOnScreen = true, configure = at(2 * VIEW_HEIGHT + VIEW_HEIGHT / 2))
		val upper = decode(2)
		val lower = decode(3)
		upper.release()
		upper.awaitDelivered()
		assertFalse("ready with half of what it shows", onMain { view.isReady })
		assertEquals(0, colorAt(VIEW_HEIGHT / 4))
		assertEquals(0, colorAt(3 * VIEW_HEIGHT / 4))
		lower.release()
		waitFor { colorAt(VIEW_HEIGHT / 4) == upper.color }
		assertEquals(lower.color, colorAt(3 * VIEW_HEIGHT / 4))
		assertEquals("image loaded events", 1, loaded.get())
	}

	/** Moved while its first strips decode, the view shows once the strips of the place it was moved to are there */
	@Test
	public fun aViewMovedBeforeItsFirstStripShowsWhereItWasMovedTo() {
		open(isOnScreen = true, configure = at(0))
		decode(0)
		onMain { view.setScaleAndCenter(1f, PointF(WIDTH / 2f, 4 * VIEW_HEIGHT + VIEW_HEIGHT / 2f)) }
		for (index in 0 until 4) {
			val decode = decode(index)
			decode.release()
			decode.awaitDelivered()
			assertFalse("ready at a place nothing is decoded for", onMain { view.isReady })
			assertEquals("nothing is drawn before the image shows", 0, colorAt(VIEW_HEIGHT / 2))
		}
		assertEquals("image loaded events", 0, loaded.get())
		val target = decode(4)
		target.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == target.color }
		assertEquals("image loaded events", 1, loaded.get())
	}

	/** Moved once shown to where its strip still decodes: what is decoded shows, the rest when it comes */
	@Test
	public fun aViewMovedOnceShownShowsEachStripAsItComes() {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		first.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == first.color }

		// Nine tenths of a screen down: the strip shown stays where the view still shows it
		moveTo(0.9f)
		assertEquals(first.color, colorAt(VIEW_HEIGHT / 20))
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		val second = decode(1)
		second.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == second.color }
		assertEquals(first.color, colorAt(VIEW_HEIGHT / 20))
		assertEquals("image loaded events", 1, loaded.get())
	}

	/**
	 * Moved once shown, with the decodes under way held: the strip under the place it was moved to is the next to
	 * start, then the ones beside it, ahead of the order of the opening.
	 */
	@Test
	public fun theStripsWaitingFollowTheView() {
		imageHeight = MANY_STRIPS * VIEW_HEIGHT
		val first = openShownAtTheTop()
		moveTo(9f)
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		decode(1).release()
		val under = decode(9)
		decode(2).release()
		decode(10)
		decode(3).release()
		decode(8)
		assertStartedAfterTheOpening(9, 10, 8)
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		under.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == under.color }
		assertEquals(under.color, colorAt(0))
		assertEquals(under.color, colorAt(VIEW_HEIGHT - 1))
		// Back where it opened, what was decoded there is still there
		moveTo(0f)
		assertEquals(first.color, colorAt(VIEW_HEIGHT / 2))
		assertEquals("image loaded events", 1, loaded.get())
	}

	/** Flung across the image while it decodes: each strip that starts is the one under the view by then */
	@Test
	public fun aViewFlungAcrossTheImageDecodesWhereItIs() {
		imageHeight = MANY_STRIPS * VIEW_HEIGHT
		openShownAtTheTop()
		for ((released, place) in listOf(1 to 6, 2 to 8, 3 to MANY_STRIPS - 1)) {
			moveTo(place.toFloat())
			decode(released).release()
			decode(place)
		}
		assertStartedAfterTheOpening(6, 8, MANY_STRIPS - 1)
		val last = decode(MANY_STRIPS - 1)
		last.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == last.color }
		// The rest follow, each once, and the decoder ends with the last
		waitFor {
			asked.forEach { it.release() }
			asked.size == MANY_STRIPS && recycledDecoders.get() == 1
		}
		assertEquals((0 until MANY_STRIPS).toSet(), askedStrips().toSet())
		for (index in 0 until MANY_STRIPS) {
			moveTo(index.toFloat())
			assertEquals(decode(index).color, colorAt(VIEW_HEIGHT / 2))
		}
		assertEquals("decodes", MANY_STRIPS, asked.size)
		assertEquals("image loaded events", 1, loaded.get())
	}

	/** A jump to the far end once shown: the last strip is the next to start, before every strip between */
	@Test
	public fun aJumpToTheLastStripDecodesItNext() {
		imageHeight = MANY_STRIPS * VIEW_HEIGHT
		openShownAtTheTop()
		moveTo(MANY_STRIPS - 1f)
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		decode(1).release()
		val last = decode(MANY_STRIPS - 1)
		assertStartedAfterTheOpening(MANY_STRIPS - 1)
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		last.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == last.color }
		// And then the one beside it, not the opening's next
		decode(MANY_STRIPS - 2)
		assertStartedAfterTheOpening(MANY_STRIPS - 1, MANY_STRIPS - 2)
	}

	@Test
	public fun stripsOfAnImageReplacedMeanwhileAreNotShown() {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		onMain { view.recycle() }
		assertEquals("region decoders recycled", 1, recycledDecoders.get())
		// Its decode, interrupted or not, gives the image nothing
		first.release()
		Thread.sleep(HANDOVER_MS)
		assertEquals(0, colorAt(VIEW_HEIGHT / 2))
		assertFalse(onMain { view.isReady })
		assertEquals("image loaded events", 0, loaded.get())
	}

	/**
	 * A strip decoded for an image that is replaced before the main thread takes it is recycled: the decode ends while
	 * the main thread is busy, which then drops the image.
	 */
	@Test
	public fun aStripDecodedForAnImageDroppedBeforeItIsTakenIsRecycled() {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		onMain {
			first.release()
			first.awaitReturned()
			// Time for the worker to hand the bitmap over to this thread, which is held here
			Thread.sleep(HANDOVER_MS)
			view.recycle()
		}
		waitFor { first.bitmap.isRecycled }
		assertEquals("image loaded events", 0, loaded.get())
	}

	/** The same for an image decoded whole */
	@Test
	public fun aWholeImageDecodedForAViewRecycledBeforeItIsTakenIsRecycled() {
		open(isOnScreen = false, configure = at(0))
		val whole = decode(null)
		onMain {
			whole.release()
			whole.awaitReturned()
			Thread.sleep(HANDOVER_MS)
			view.recycle()
		}
		waitFor { whole.bitmap.isRecycled }
		assertEquals("image loaded events", 0, loaded.get())
	}

	@Test
	public fun aViewOffScreenLoadsTheWholeImageOnly() {
		open(isOnScreen = false, configure = at(0))
		assertWholeImageOnly()
	}

	@Test
	public fun anImageUnderTwoScreensLoadsWholeOnly() {
		imageHeight = 2 * VIEW_HEIGHT - 1
		open(isOnScreen = true, configure = at(0))
		assertWholeImageOnly()
	}

	@Test
	public fun aDecoderWhoseRegionsCostTheImageLoadsItWholeOnly() {
		decodesRegionsApart = false
		open(isOnScreen = true, configure = at(0))
		assertWholeImageOnly()
	}

	private fun assertWholeImageOnly() {
		val whole = decode(null)
		waitFor { recycledDecoders.get() == 1 }
		whole.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == whole.color }
		assertEquals("decodes", 1, asked.size)
	}

	private fun at(top: Int): SubsamplingScaleImageView.() -> Unit = {
		setScaleAndCenter(1f, PointF(WIDTH / 2f, top + VIEW_HEIGHT / 2f))
	}

	private fun strip(index: Int) = Rect(0, index * VIEW_HEIGHT, WIDTH, (index + 1) * VIEW_HEIGHT)

	/** The strips in the order their decodes started; those started together, in any order */
	private fun askedStrips(): List<Int> = asked.map { checkNotNull(it.rect).top / VIEW_HEIGHT }

	/** After [openShownAtTheTop], whose strips started as it says, [strips] started, in that order, and no other */
	private fun assertStartedAfterTheOpening(vararg strips: Int) {
		val started = askedStrips()
		assertEquals("the strips of the opening", (0 until TILE_LOADS).toSet(), started.take(TILE_LOADS).toSet())
		assertEquals("the order strips started in", listOf(TILE_LOADS) + strips.toList(), started.drop(TILE_LOADS))
	}

	/** Puts the view's top [screens] of its height down the image */
	private fun moveTo(screens: Float) = onMain {
		view.setScaleAndCenter(1f, PointF(WIDTH / 2f, screens * VIEW_HEIGHT + VIEW_HEIGHT / 2f))
	}

	/**
	 * An image shown at its top by its first strip, given back, with as many strips decoding, held, as load at a
	 * time: the opening's next three and, in the place the first left, the fourth
	 */
	private fun openShownAtTheTop(): Decode {
		open(isOnScreen = true, configure = at(0))
		val first = decode(0)
		(1 until TILE_LOADS).forEach(::decode)
		first.release()
		waitFor { colorAt(VIEW_HEIGHT / 2) == first.color }
		decode(TILE_LOADS)
		assertEquals("decodes asked for", TILE_LOADS + 1, asked.size)
		return first
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

	/** The colour at row [y] of the view drawn now, 0 where it draws nothing; drawing also starts the first load */
	private fun colorAt(y: Int): Int = onMain {
		val bitmap = Bitmap.createBitmap(WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
		view.draw(Canvas(bitmap))
		bitmap.getPixel(WIDTH / 2, y).also { bitmap.recycle() }
	}

	/** The decode of the strip at [index], of the whole image for null, once it is asked for */
	private fun decode(index: Int?): Decode {
		val rect = index?.let(::strip)
		var decode: Decode? = null
		waitFor {
			colorAt(0) // a draw starts the first load
			decode = asked.firstOrNull { it.rect == rect }
			decode != null
		}
		return checkNotNull(decode)
	}

	private inner class HeldDecoder : ImageDecoder {
		override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap =
			Decode(null, sampleSize, WIDTH, imageHeight).await()
	}

	private inner class HeldRegionDecoder : ImageRegionDecoder {
		override fun init(context: Context, uri: Uri): Point {
			openedDecoders.incrementAndGet()
			return Point(WIDTH, imageHeight)
		}

		override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
			Decode(Rect(sRect), sampleSize, sRect.width(), sRect.height()).await()

		override val decodesRegionsApart: Boolean
			get() = this@StripsTest.decodesRegionsApart

		override val isReady = true

		override fun recycle() {
			recycledDecoders.incrementAndGet()
		}
	}

	/** A decode of [rect] of the image, of all of it when null */
	private inner class Decode(val rect: Rect?, val sampleSize: Int, width: Int, height: Int) {
		val color = COLORS[colorIndex.getAndIncrement() % COLORS.size]
		val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
		private val gate = CountDownLatch(1)
		private val returned = CountDownLatch(1)

		fun await(): Bitmap {
			asked.add(this)
			if (ignoresInterruption) {
				// As a native decode: it runs to its end, and its thread is still interrupted then
				var isInterrupted = false
				while (true) {
					try {
						gate.await()
						break
					} catch (e: InterruptedException) {
						isInterrupted = true
					}
				}
				if (isInterrupted) {
					Thread.currentThread().interrupt()
				}
			} else {
				gate.await()
			}
			returned.countDown()
			return bitmap
		}

		fun release() = gate.countDown()

		/** Whether the view has the bitmap or, having dropped the decode, has recycled it */
		val isTaken: Boolean
			get() = returned.count == 0L

		fun awaitReturned() = check(returned.await(10, TimeUnit.SECONDS)) { "the decode did not end" }

		/** Waits until the main thread has taken the bitmap, released before: what it queued for it has run */
		fun awaitDelivered() {
			awaitReturned()
			Thread.sleep(HANDOVER_MS)
			onMain { }
		}
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
		const val VIEW_HEIGHT = 1000
		const val STRIPS = 6
		const val MANY_STRIPS = 12
		const val HEIGHT = STRIPS * VIEW_HEIGHT
		const val TILE_LOADS = 4
		const val HANDOVER_MS = 200L
		val COLORS = intArrayOf(
			Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY, Color.DKGRAY,
		)
		val colorIndex = AtomicInteger()
	}
}
