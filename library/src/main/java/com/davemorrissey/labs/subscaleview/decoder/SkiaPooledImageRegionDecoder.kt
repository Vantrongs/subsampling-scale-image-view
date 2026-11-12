package com.davemorrissey.labs.subscaleview.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 *
 *
 * An implementation of [ImageRegionDecoder] using a pool of [BitmapRegionDecoder]s,
 * to provide true parallel loading of tiles. This is only effective if parallel loading has been
 * enabled in the view by changing [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.backgroundDispatcher]
 * with a multi-threaded [CoroutineDispatcher] instance.
 *
 *
 * One decoder is initialised when the class is initialised. This is enough to decode base layer tiles.
 * Additional decoders are initialised when a subregion of the image is first requested, which indicates
 * interaction with the view. Creation of additional encoders stops when [.allowAdditionalDecoder]
 * returns false. The default implementation takes into account the file size, number of CPU cores,
 * low memory status and a hard limit of 4. Extend this class to customise this.
 *
 *
 * **WARNING:** This class is highly experimental and not proven to be stable on a wide range of
 * devices. You are advised to test it thoroughly on all available devices, and code your app to use
 * [SkiaImageRegionDecoder] on old or low powered devices you could not test.
 *
 */
public open class SkiaPooledImageRegionDecoder @JvmOverloads constructor(
	private val bitmapConfig: Bitmap.Config = Bitmap.Config.RGB_565,
) : ImageRegionDecoder {

	// One pool from the decoder's making to its end, never replaced or cleared: its end is its own state
	// ([DecoderPool.isRecycled]), so no thread can lose the pool between checking it and using it
	private val pool = DecoderPool()
	private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

	// What the decoders are opened from; each function reads it once
	@Volatile
	private var source: Source? = null

	@Volatile
	private var fileLength = Long.MAX_VALUE
	private val imageDimensions = Point(0, 0)
	private val isLazyInited = AtomicBoolean(false)

	@Volatile
	private var onRegionsAtOnceGrown: (() -> Unit)? = null

	/**
	 * Initialises the decoder pool. This method creates one decoder on the current thread and uses
	 * it to decode the bounds. An independent thread populates the pool with up to three more once a
	 * subregion is first requested; it ends when [.recycle] is called.
	 */
	@Throws(Exception::class)
	override fun init(context: Context, uri: Uri): Point {
		val source = Source(context, uri)
		this.source = source
		addDecoder(source)
		return imageDimensions
	}

	/**
	 * Initialises extra decoders for as long as [.allowAdditionalDecoder] returns
	 * true and the pool has not been recycled.
	 */
	private fun lazyInit() {
		if (isLazyInited.compareAndSet(false, true) && fileLength < Long.MAX_VALUE) {
			val source = source ?: return
			debug("Starting lazy init of additional decoders")
			thread(start = true) { fill(source) }
		}
	}

	/**
	 * The thread that fills the pool. It works on the pool and on the [source] it was started with, so [recycle]
	 * on another thread, at any point of it, takes nothing from under it: all it does once the pool is recycled is
	 * recycle the decoder it was making ([addDecoder]) and end. It ends too when a decoder fails to open: the pool
	 * has the one [init] made, and a source that stopped opening (a file deleted) would fail every further turn.
	 */
	@WorkerThread
	private fun fill(source: Source) {
		while (!pool.isRecycled && allowAdditionalDecoder(pool.size, fileLength)) {
			try {
				val start = System.currentTimeMillis()
				debug("Starting decoder")
				addDecoder(source)
				val end = System.currentTimeMillis()
				debug("Started decoder, took " + (end - start) + "ms")
			} catch (e: Exception) {
				debug("Failed to start decoder: " + e.message)
				return
			}
		}
	}

	/**
	 * Initialises a new [BitmapRegionDecoder] and adds it to the pool; one made for a pool recycled meanwhile has no
	 * other owner and is recycled here.
	 */
	@Throws(Exception::class)
	@WorkerThread
	private fun addDecoder(source: Source) {
		val decoder = open(source)
		onDecoderOpened(decoder)
		if (pool.add(decoder)) {
			onRegionsAtOnceGrown?.invoke()
		} else {
			decoder.recycle()
		}
	}

	/**
	 * The decoders in the pool: one from [init] on, and only that one for a source whose length is not known (an entry
	 * of a ZIP), while the pool is still being filled, where a decoder failed to open or [allowAdditionalDecoder]
	 * refused another. A region asked for beyond them waits for a decoder in the order it came.
	 */
	override val regionsAtOnce: Int
		get() = pool.size

	override fun setOnRegionsAtOnceGrown(listener: (() -> Unit)?) {
		onRegionsAtOnceGrown = listener
	}

	/** Called with each decoder made, by the thread that made it, before the pool is offered it. For tests. */
	@WorkerThread
	internal open fun onDecoderOpened(decoder: BitmapRegionDecoder): Unit = Unit

	/** Called by a thread that has taken a decoder of the pool for [sRect], before it decodes. For tests. */
	@WorkerThread
	internal open fun onRegionDecoding(sRect: Rect): Unit = Unit

	@SuppressLint("DiscouragedApi")
	@Throws(Exception::class)
	@WorkerThread
	private fun open(source: Source): BitmapRegionDecoder {
		val uri = source.uri
		val context = source.context
		var fileLength = Long.MAX_VALUE
		val decoder: BitmapRegionDecoder = when (uri.scheme) {
			URI_SCHEME_RES -> {
				val packageName = uri.authority
				val res = if (packageName == null || context.packageName == packageName) {
					context.resources
				} else {
					context.packageManager.getResourcesForApplication(packageName)
				}
				var id = 0
				val segments = uri.pathSegments
				val size = segments.size
				if (size == 2 && segments[0] == "drawable") {
					val resName = segments[1]
					id = res.getIdentifier(resName, "drawable", packageName)
				} else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
					try {
						id = segments[0].toInt()
					} catch (ignored: NumberFormatException) {
					}
				}
				try {
					val descriptor = context.resources.openRawResourceFd(id)
					fileLength = descriptor.length
				} catch (e: Exception) {
					// Pooling disabled
				}
				res.openRawResource(id).use { BitmapRegionDecoder(it, context, uri) }
			}

			URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { file ->
				val entry = requireNotNull(file.getEntry(uri.fragment)) {
					"Entry ${uri.fragment} not found in the zip"
				}
				file.getInputStream(entry).use { input ->
					BitmapRegionDecoder(input, context, uri)
				}
			}

			URI_SCHEME_FILE -> {
				val path = uri.schemeSpecificPart
				if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
					val assetName = path.substring(URI_PATH_ASSET.length)
					try {
						val descriptor = context.assets.openFd(assetName)
						fileLength = descriptor.length
					} catch (e: Exception) {
						// Pooling disabled
					}
					context.assets.open(assetName, AssetManager.ACCESS_RANDOM)
						.use { BitmapRegionDecoder(it, context, uri) }
				} else {
					val d = BitmapRegionDecoder(path, context, uri)
					try {
						val file = File(path)
						if (file.exists()) {
							fileLength = file.length()
						}
					} catch (e: Exception) {
						// Pooling disabled
					}
					d
				}
			}

			URI_SCHEME_CONTENT -> {
				val contentResolver = context.contentResolver
				val d = contentResolver.openInputStream(uri)?.use { BitmapRegionDecoder(it, context, uri) }
				try {
					contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
						fileLength = descriptor.length
					}
				} catch (e: Exception) {
					// Stick with MAX_LENGTH
				}
				d
			}

			else -> throw UnsupportedUriException(uri.toString())
		} ?: throw ImageDecodeException.create(context, uri)
		this.fileLength = fileLength
		imageDimensions[decoder.width] = decoder.height
		return decoder
	}

	/**
	 * Acquire a read lock to prevent decoding overlapping with recycling, then check the pool still
	 * exists and acquire a decoder to load the requested region. There is no check whether the pool
	 * currently has decoders, because it's guaranteed to have one decoder after [.init]
	 * is called and none once [.recycle] is called. In practice the view can't call this
	 * method until after [.init], so there will be no blocking on an empty pool.
	 */
	@WorkerThread
	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		debug("Decode region " + sRect + " on thread " + Thread.currentThread().name)
		if (sRect.width() < imageDimensions.x || sRect.height() < imageDimensions.y) {
			lazyInit()
		}
		val source = source
		decoderLock.readLock().lock()
		try {
			// Under the read lock the pool is not recycled meanwhile, so the decoder acquired stays whole
			if (!pool.isRecycled) {
				val decoder = pool.acquire()
				try {
					// Decoder can't be null or recycled in practice
					if (decoder != null && !decoder.isRecycled) {
						val options = BitmapFactory.Options()
						options.inSampleSize = sampleSize
						options.inPreferredConfig = bitmapConfig
						onRegionDecoding(sRect)
						return decoder.decodeRegion(sRect, options)
							?: throw ImageDecodeException.create(source?.context, source?.uri)
					}
				} finally {
					if (decoder != null) {
						pool.release(decoder)
					}
				}
			}
			throw IllegalStateException("Cannot decode region after decoder has been recycled")
		} finally {
			decoderLock.readLock().unlock()
		}
	}

	/** Whether the pool has at least one decoder, which a pool recycled has not. */
	override val isReady: Boolean
		get() = !pool.isEmpty

	/**
	 * Wait until all read locks held by [.decodeRegion] are released, then recycle the pool. The thread that fills
	 * it is not waited for: the pool refuses what it makes from then on.
	 */
	override fun recycle() {
		decoderLock.writeLock().lock()
		try {
			pool.recycle()
			source = null
			onRegionsAtOnceGrown = null
		} finally {
			decoderLock.writeLock().unlock()
		}
	}

	/**
	 * Called before creating a new decoder. Based on number of CPU cores, available memory, and the
	 * size of the image file, determines whether another decoder can be created. Subclasses can
	 * override and customise this.
	 * @param numberOfDecoders the number of decoders that have been created so far
	 * @param fileLength the size of the image file in bytes. Creating another decoder will use approximately this much native memory.
	 * @return true if another decoder can be created.
	 */
	protected open fun allowAdditionalDecoder(numberOfDecoders: Int, fileLength: Long): Boolean {
		if (numberOfDecoders >= 4) {
			debug("No additional decoders allowed, reached hard limit (4)")
			return false
		} else if (numberOfDecoders * fileLength > 20 * 1024 * 1024) {
			debug("No additional encoders allowed, reached hard memory limit (20Mb)")
			return false
		} else if (numberOfDecoders >= numberOfCores) {
			debug("No additional encoders allowed, limited by CPU cores ($numberOfCores)")
			return false
		} else if (source?.context?.isLowMemory() != false) {
			debug("No additional encoders allowed, memory is low")
			return false
		}
		debug(
			"Additional decoder allowed, current count is " + numberOfDecoders + ", estimated native memory " + fileLength * numberOfDecoders / (1024 * 1024) + "Mb",
		)
		return true
	}

	private val numberOfCores: Int
		get() = Runtime.getRuntime().availableProcessors()

	private class Source(val context: Context, val uri: Uri)

	private fun debug(message: String) {
		if (isDebug) {
			Log.d(TAG, message)
		}
	}

	public class Factory @JvmOverloads constructor(
		override val bitmapConfig: Bitmap.Config = Bitmap.Config.RGB_565,
	) : DecoderFactory<SkiaPooledImageRegionDecoder> {

		override fun make(): SkiaPooledImageRegionDecoder {
			return SkiaPooledImageRegionDecoder(bitmapConfig)
		}
	}

	public companion object {

		private const val TAG = "SkiaPooledDecoder"

		/**
		 * Controls logging of debug messages. All instances are affected.
		 * @param debug true to enable debug logging, false to disable.
		 */
		@Keep
		@set:JvmName("setDebug")
		public var isDebug: Boolean = false
	}
}
