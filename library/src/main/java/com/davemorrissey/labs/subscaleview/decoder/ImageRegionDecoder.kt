package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.ImageSource
import org.jetbrains.annotations.Blocking

/**
 * Interface for image decoding classes, allowing the default [android.graphics.BitmapRegionDecoder]
 * based on the Skia library to be replaced with a custom class.
 */
public interface ImageRegionDecoder {

	/**
	 * Initialise the decoder. When possible, perform initial setup work once in this method. The
	 * dimensions of the image must be returned. The URI can be in one of the following formats:
	 * <br></br>
	 * File: `file:///scard/picture.jpg`
	 * <br></br>
	 * Asset: `file:///android_asset/picture.png`
	 * <br></br>
	 * Resource: `android.resource://com.example.app/drawable/picture`
	 * @param context Application context. A reference may be held, but must be cleared on recycle.
	 * @param uri URI of the image.
	 * @return Dimensions of the image.
	 * @throws Exception if initialisation fails.
	 */
	@Throws(Exception::class)
	@WorkerThread
	@Blocking
	public fun init(context: Context, uri: Uri): Point

	@Throws(Exception::class)
	@WorkerThread
	@Blocking
	public fun init(context: Context, source: ImageSource): Point = init(context, source.toUri(context))

	/**
	 *
	 *
	 * Decode a region of the image with the given sample size. This method is called off the UI
	 * thread so it can safely load the image on the current thread. It is called from
	 * [android.os.AsyncTask]s running in an executor that may have multiple threads, so
	 * implementations must be thread safe. Adding `synchronized` to the method signature
	 * is the simplest way to achieve this, but bear in mind the [.recycle] method can be
	 * called concurrently.
	 *
	 *
	 * See [SkiaImageRegionDecoder] and [SkiaPooledImageRegionDecoder] for examples of
	 * internal locking and synchronization.
	 *
	 * @param sRect Source image rectangle to decode.
	 * @param sampleSize Sample size.
	 * @return The decoded region. It is safe to return null if decoding fails.
	 */
	@WorkerThread
	@Blocking
	public fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap

	/**
	 * Whether a region decodes for less than the whole image does, known once [init] returned. The view shows a tall
	 * image in strips, the ones on screen first, only then: a decoder that decodes the whole image for its first
	 * region would show nothing sooner and keep the image twice.
	 */
	public val decodesRegionsApart: Boolean
		get() = true

	/**
	 * How many regions the decoder decodes at the same time now, known once [init] returned and at least 1 while it is
	 * ready: a [decodeRegion] called beyond that waits inside the decoder for one under way, in an order its caller
	 * cannot change. The view starts no more decodes than this at a time and keeps the rest waiting itself, so the
	 * next to start is always the one it needs most by then. It may grow while the decoder is used (a pool being
	 * filled), which [setOnRegionsAtOnceGrown] tells, and does not shrink. A decoder that says nothing decodes one
	 * region at a time, as one [android.graphics.BitmapRegionDecoder] does.
	 */
	public val regionsAtOnce: Int
		get() = 1

	/**
	 * Sets what is called, on any thread, each time [regionsAtOnce] has grown; a decoder whose number never changes
	 * calls nothing. A decoder that wraps another passes both on.
	 */
	public fun setOnRegionsAtOnceGrown(listener: (() -> Unit)?): Unit = Unit

	/**
	 * Status check. Should return false before initialisation and after recycle.
	 * @return true if the decoder is ready to be used.
	 */
	public val isReady: Boolean

	/**
	 * This method will be called when the decoder is no longer required. It should clean up any resources still in use.
	 */
	public fun recycle()
}
