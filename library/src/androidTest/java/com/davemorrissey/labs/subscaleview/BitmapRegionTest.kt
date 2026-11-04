package com.davemorrissey.labs.subscaleview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [SubsamplingScaleImageView.setImage] with a bitmap and a region shows that region of the bitmap */
@RunWith(AndroidJUnit4::class)
public class BitmapRegionTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()

	@Test
	public fun showsTheRegionOfTheBitmap() {
		val source = gradient()
		val region = Rect(10, 100, 60, 250)
		val shown = show(ImageSource.bitmap(source).region(region)) { view ->
			assertEquals(region.width(), view.sWidth)
			assertEquals(region.height(), view.sHeight)
		}
		for (y in 0 until region.height()) {
			for (x in 0 until region.width()) {
				assertEquals("pixel $x,$y", colorAt(region.left + x, region.top + y), shown.getPixel(x, y))
			}
		}
		assertTrue("the bitmap the view does not show is recycled", source.isRecycled)
	}

	@Test
	public fun keepsACachedBitmapWhetherItCutsItOrNot() {
		val source = gradient()
		show(ImageSource.cachedBitmap(source).region(Rect(10, 100, 60, 250))) {}
		assertFalse(source.isRecycled)

		val immutable = gradient().copy(Bitmap.Config.ARGB_8888, false)
		show(ImageSource.cachedBitmap(immutable).region(Rect(0, 0, WIDTH, HEIGHT))) {}
		assertFalse(immutable.isRecycled)
	}

	/** The view's bitmap after [source] is set, copied before the view is recycled */
	private fun show(source: ImageSource, check: (SubsamplingScaleImageView) -> Unit): Bitmap {
		var shown: Bitmap? = null
		instrumentation.runOnMainSync {
			val view = SubsamplingScaleImageView(instrumentation.targetContext)
			view.setImage(source)
			check(view)
			shown = view.snapshot()
			view.recycle()
		}
		return checkNotNull(shown)
	}

	/** Every pixel a colour of its own */
	private fun gradient() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				setPixel(x, y, colorAt(x, y))
			}
		}
	}

	private fun colorAt(x: Int, y: Int) = Color.rgb(x, y and 0xff, y shr 8)

	private companion object {
		const val WIDTH = 100
		const val HEIGHT = 300
	}
}
