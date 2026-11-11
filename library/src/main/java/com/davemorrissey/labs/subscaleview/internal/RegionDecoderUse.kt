package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder

/**
 * The region decoder of one image of a view, from its opening to its end. The view ends it without waiting
 * ([retire]): the decodes under way, which nothing interrupts in native code, keep it until the last of them returns,
 * and that one recycles it, on its thread; ended while none runs, it is recycled at once. No decode starts in a
 * decoder ended, so a decoder is never recycled under a decode and never used after.
 */
internal class RegionDecoderUse(private val decoder: ImageRegionDecoder) {

	private var decoding = 0
	private var isRetired = false

	val decodesRegionsApart: Boolean
		get() = decoder.decodesRegionsApart

	/** The region decoded, the caller's from then on; null from a decoder ended, or not ready */
	@WorkerThread
	fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap? {
		synchronized(this) {
			if (isRetired) {
				return null
			}
			decoding++
		}
		try {
			return if (decoder.isReady) decoder.decodeRegion(sRect, sampleSize) else null
		} finally {
			val isLast = synchronized(this) { --decoding == 0 && isRetired }
			if (isLast) {
				decoder.recycle()
			}
		}
	}

	/** Ends the decoder: recycled now when nothing decodes in it, otherwise as its last decode returns */
	fun retire() {
		val isIdle = synchronized(this) {
			if (isRetired) {
				return
			}
			isRetired = true
			decoding == 0
		}
		if (isIdle) {
			decoder.recycle()
		}
	}
}
