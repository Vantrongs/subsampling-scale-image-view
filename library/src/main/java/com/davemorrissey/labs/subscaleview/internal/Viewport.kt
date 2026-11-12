package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * What of a view reaches the screen, as it was when last [read]: the pixels of its window that the view's own
 * rectangle covers once its ancestors have moved, scaled and clipped it, and the way from the view's pixels to them.
 * A view is not its rectangle on the screen: a parent that zooms scales it without resizing it (the webtoon reader's
 * frame), so only a part of that rectangle is seen, and a list scrolls a part of it past its own edge. Told from the
 * whole rectangle, a tile that drew nothing on the screen counted as shown, and an image waited for its decode.
 *
 * A view no part of which reaches the screen (detached, hidden, scrolled or zoomed out of every ancestor) has no such
 * part to tell, and is taken whole, as if it filled its window: where it will be shown once it is.
 *
 * The main thread's, like the view it reads.
 */
internal class Viewport {

	// The view's pixels to its window's
	private val toWindow = Matrix()
	private val toView = Matrix()

	// The window's pixels the view's rectangle reaches, and that rectangle
	private val visible = Rect()
	private val bounds = Rect()
	private val clipped = Rect()
	private val mapped = RectF()

	fun read(view: View): Viewport {
		bounds.set(0, 0, view.width, view.height)
		toWindow.reset()
		// The framework clips the rectangle by every ancestor the way its drawing does, a transformed one included,
		// and rounds outwards; it gives no way back to the view's pixels (getLocalVisibleRect only shifts the window's
		// by the view's corner, which under a scale is another rectangle), so the same chain is walked here for it
		if (view.isShown && view.getGlobalVisibleRect(visible)) {
			var child = view
			while (true) {
				toWindow.postConcat(child.matrix)
				toWindow.postTranslate(child.left.toFloat(), child.top.toFloat())
				val parent = child.parent as? View ?: break
				toWindow.postTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
				child = parent
			}
		} else {
			visible.set(bounds)
		}
		return this
	}

	/**
	 * Whether a tile drawn at [vRect] of the view draws a pixel of the screen: the part of it inside the view, which
	 * the canvas clips it to, covers the centre of a pixel that the view reaches.
	 */
	fun shows(vRect: Rect): Boolean {
		if (!clipped.setIntersect(vRect, bounds)) {
			return false
		}
		mapped.set(clipped)
		toWindow.mapRect(mapped)
		return max(Math.round(mapped.left), visible.left) < min(Math.round(mapped.right), visible.right) &&
			max(Math.round(mapped.top), visible.top) < min(Math.round(mapped.bottom), visible.bottom)
	}

	/**
	 * How far a tile drawn at [vRect] of the view is from what the screen shows of the view, in the screen's pixels:
	 * under 0 for a tile that shows, else the gap between them, which is 0 for the tiles beside it.
	 */
	fun gapTo(vRect: Rect): Float {
		if (shows(vRect)) {
			return -1f
		}
		mapped.set(vRect)
		toWindow.mapRect(mapped)
		return maxOf(mapped.left - visible.right, visible.left - mapped.right, 0f) +
			maxOf(mapped.top - visible.bottom, visible.top - mapped.bottom, 0f)
	}

	/** The part of the view's rectangle that reaches the screen, in the view's pixels, into [target] */
	fun exposure(target: RectF) {
		target.set(visible)
		if (!toWindow.invert(toView)) {
			target.set(bounds)
			return
		}
		toView.mapRect(target)
		if (!target.intersect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())) {
			target.setEmpty()
		}
	}
}
