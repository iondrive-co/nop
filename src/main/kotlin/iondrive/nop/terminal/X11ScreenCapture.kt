package iondrive.nop.terminal

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.Locale

internal data class CaptureResult(
    val image: BufferedImage,
    val bounds: Rectangle,
    val overlayBounds: List<Rectangle>,
)

/**
 * Grabs workspace windows via X11 XGetImage.
 *
 * In seamless Xpra environments, top-level windows are off-screen redirected via XComposite,
 * so root window captures produce uniform black. This captures individual viewable client
 * windows directly and paints them into one composite image matching their desktop layout.
 */
internal object X11ScreenCapture {

    @Structure.FieldOrder(
        "x", "y", "width", "height", "border_width", "depth",
        "visual", "root", "c_class", "bit_gravity", "win_gravity",
        "backing_store", "backing_planes", "backing_pixel", "save_under",
        "colormap", "map_installed", "map_state", "all_event_masks",
        "your_event_mask", "do_not_propagate_mask", "override_redirect", "screen",
    )
    class XWindowAttributes : Structure() {
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var border_width: Int = 0
        @JvmField var depth: Int = 0
        @JvmField var visual: Pointer? = null
        @JvmField var root: NativeLong = NativeLong(0)
        @JvmField var c_class: Int = 0
        @JvmField var bit_gravity: Int = 0
        @JvmField var win_gravity: Int = 0
        @JvmField var backing_store: Int = 0
        @JvmField var backing_planes: NativeLong = NativeLong(0)
        @JvmField var backing_pixel: NativeLong = NativeLong(0)
        @JvmField var save_under: Int = 0
        @JvmField var colormap: NativeLong = NativeLong(0)
        @JvmField var map_installed: Int = 0
        @JvmField var map_state: Int = 0
        @JvmField var all_event_masks: NativeLong = NativeLong(0)
        @JvmField var your_event_mask: NativeLong = NativeLong(0)
        @JvmField var do_not_propagate_mask: NativeLong = NativeLong(0)
        @JvmField var override_redirect: Int = 0
        @JvmField var screen: Pointer? = null
    }

    @Structure.FieldOrder(
        "width", "height", "xoffset", "format", "data",
        "byte_order", "bitmap_unit", "bitmap_bit_order", "bitmap_pad",
        "depth", "bytes_per_line", "bits_per_pixel",
        "red_mask", "green_mask", "blue_mask",
    )
    class XImage(p: Pointer? = null) : Structure(p) {
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var xoffset: Int = 0
        @JvmField var format: Int = 0
        @JvmField var data: Pointer? = null
        @JvmField var byte_order: Int = 0
        @JvmField var bitmap_unit: Int = 0
        @JvmField var bitmap_bit_order: Int = 0
        @JvmField var bitmap_pad: Int = 0
        @JvmField var depth: Int = 0
        @JvmField var bytes_per_line: Int = 0
        @JvmField var bits_per_pixel: Int = 0
        @JvmField var red_mask: NativeLong = NativeLong(0)
        @JvmField var green_mask: NativeLong = NativeLong(0)
        @JvmField var blue_mask: NativeLong = NativeLong(0)

        init {
            p?.let { read() }
        }
    }

    fun interface XErrorHandler : Callback {
        fun callback(display: Pointer?, errorEvent: Pointer?): Int
    }

    private val IGNORE_ERRORS = XErrorHandler { _, _ -> 0 }

    private interface LibX11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XQueryTree(
            display: Pointer,
            window: NativeLong,
            rootReturn: NativeLongByReference,
            parentReturn: NativeLongByReference,
            childrenReturn: PointerByReference,
            nchildrenReturn: IntByReference,
        ): Int
        fun XGetWindowAttributes(display: Pointer, window: NativeLong, attributesReturn: XWindowAttributes): Int
        fun XTranslateCoordinates(
            display: Pointer,
            srcW: NativeLong,
            destW: NativeLong,
            srcX: Int,
            srcY: Int,
            destXReturn: IntArray,
            destYReturn: IntArray,
            childReturn: NativeLongByReference,
        ): Int
        fun XGetImage(
            display: Pointer,
            drawable: NativeLong,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            planeMask: NativeLong,
            format: Int,
        ): Pointer?
        fun XDestroyImage(image: Pointer): Int
        fun XGetPixel(image: Pointer, x: Int, y: Int): NativeLong
        fun XFree(data: Pointer): Int
        fun XInternAtom(display: Pointer, atomName: String, onlyIfExists: Boolean): NativeLong
        fun XGetWindowProperty(
            display: Pointer,
            window: NativeLong,
            property: NativeLong,
            longOffset: Long,
            longLength: Long,
            delete: Boolean,
            reqType: NativeLong,
            actualTypeReturn: NativeLongByReference,
            actualFormatReturn: IntArray,
            nitemsReturn: NativeLongByReference,
            bytesAfterReturn: NativeLongByReference,
            propReturn: PointerByReference,
        ): Int
        fun XFetchName(display: Pointer, window: NativeLong, nameReturn: PointerByReference): Int
        fun XSetErrorHandler(handler: XErrorHandler?): Pointer?
        fun XSetErrorHandler(handler: Pointer?): Pointer?
        fun XSync(display: Pointer, discard: Boolean): Int
    }

    private val x11: LibX11? by lazy {
        if (!isLinux()) null
        else runCatching { Native.load("X11", LibX11::class.java) }.getOrNull()
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("linux") == true

    /**
     * Whether the current X display is managed by Xpra.
     */
    fun isXpra(): Boolean {
        val lib = x11 ?: return false
        return runCatching {
            val dpy = lib.XOpenDisplay(null) ?: return false
            try {
                hasXpraWindows(lib, dpy)
            } finally {
                lib.XCloseDisplay(dpy)
            }
        }.getOrDefault(false)
    }

    /**
     * Checks if an image consists entirely of a single uniform colour (such as black).
     */
    fun isUniformColor(image: BufferedImage): Boolean {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return true
        val first = image.getRGB(0, 0)
        val stepX = maxOf(1, w / 32)
        val stepY = maxOf(1, h / 32)
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                if (image.getRGB(x, y) != first) return false
            }
        }
        return image.getRGB(w / 2, h / 2) == first
    }

    private fun hasXpraWindows(lib: LibX11, dpy: Pointer): Boolean {
        val root = lib.XDefaultRootWindow(dpy)
        val rootRet = NativeLongByReference()
        val parentRet = NativeLongByReference()
        val childrenRet = PointerByReference()
        val nchildrenRet = IntByReference()

        if (lib.XQueryTree(dpy, root, rootRet, parentRet, childrenRet, nchildrenRet) == 0) return false
        val count = nchildrenRet.value
        val childrenPtr = childrenRet.value ?: return false
        try {
            val netWmNameAtom = lib.XInternAtom(dpy, "_NET_WM_NAME", false)
            val supportingWmAtom = lib.XInternAtom(dpy, "_NET_SUPPORTING_WM_CHECK", false)

            val wmWin = getWindowPropertyWindow(lib, dpy, root, supportingWmAtom)
            if (wmWin != null) {
                val wmName = getWindowName(lib, dpy, wmWin, netWmNameAtom)
                if (wmName.equals("Xpra", ignoreCase = true)) return true
            }

            val childIds = getWindowArray(childrenPtr, count)
            for (id in childIds) {
                val name = getWindowName(lib, dpy, id, netWmNameAtom)
                if (name.startsWith("Xpra-CorralWindow-")) return true
            }
            return false
        } finally {
            lib.XFree(childrenPtr)
        }
    }

    /**
     * Captures viewable workspace windows from bottom to top and paints them into one image.
     */
    fun capture(): CaptureResult? {
        val lib = x11 ?: return null
        return runCatching {
            val dpy = lib.XOpenDisplay(null) ?: return null
            try {
                captureFromDisplay(lib, dpy)
            } finally {
                lib.XCloseDisplay(dpy)
            }
        }.getOrNull()
    }

    private fun captureFromDisplay(lib: LibX11, dpy: Pointer): CaptureResult? {
        val prevHandler = lib.XSetErrorHandler(IGNORE_ERRORS)
        try {
            val root = lib.XDefaultRootWindow(dpy)
            val rootRet = NativeLongByReference()
            val parentRet = NativeLongByReference()
            val childrenRet = PointerByReference()
            val nchildrenRet = IntByReference()

            if (lib.XQueryTree(dpy, root, rootRet, parentRet, childrenRet, nchildrenRet) == 0) return null
            val count = nchildrenRet.value
            val childrenPtr = childrenRet.value ?: return null

            val children = try {
                getWindowArray(childrenPtr, count)
            } finally {
                lib.XFree(childrenPtr)
            }

            data class WinGrab(val window: NativeLong, val bounds: Rectangle)
            val toGrab = mutableListOf<WinGrab>()

            for (wid in children) {
                val attr = XWindowAttributes()
                if (lib.XGetWindowAttributes(dpy, wid, attr) == 0) continue
                if (attr.map_state != 2 || attr.width <= 0 || attr.height <= 0) continue

                var targetWid = wid
                var targetW = attr.width
                var targetH = attr.height

                val cRoot = NativeLongByReference()
                val cParent = NativeLongByReference()
                val cChildren = PointerByReference()
                val cNchildren = IntByReference()
                if (lib.XQueryTree(dpy, wid, cRoot, cParent, cChildren, cNchildren) != 0) {
                    val subPtr = cChildren.value
                    val subCount = cNchildren.value
                    if (subPtr != null) {
                        try {
                            val subIds = getWindowArray(subPtr, subCount)
                            for (subId in subIds) {
                                val subAttr = XWindowAttributes()
                                if (lib.XGetWindowAttributes(dpy, subId, subAttr) != 0 &&
                                    subAttr.map_state == 2 && subAttr.width > 0 && subAttr.height > 0
                                ) {
                                    targetWid = subId
                                    targetW = subAttr.width
                                    targetH = subAttr.height
                                    break
                                }
                            }
                        } finally {
                            lib.XFree(subPtr)
                        }
                    }
                }

                val destX = IntArray(1)
                val destY = IntArray(1)
                val childRet = NativeLongByReference()
                if (lib.XTranslateCoordinates(dpy, targetWid, root, 0, 0, destX, destY, childRet) == 0) continue

                toGrab.add(WinGrab(targetWid, Rectangle(destX[0], destY[0], targetW, targetH)))
            }

            if (toGrab.isEmpty()) return null

            val minX = toGrab.minOf { it.bounds.x }
            val minY = toGrab.minOf { it.bounds.y }
            val maxX = toGrab.maxOf { it.bounds.x + it.bounds.width }
            val maxY = toGrab.maxOf { it.bounds.y + it.bounds.height }
            val totalBounds = Rectangle(minX, minY, maxX - minX, maxY - minY)
            if (totalBounds.width <= 0 || totalBounds.height <= 0) return null

            val composite = BufferedImage(totalBounds.width, totalBounds.height, BufferedImage.TYPE_INT_RGB)
            val g = composite.createGraphics()
            try {
                for (win in toGrab) {
                    val img = captureWindow(lib, dpy, win.window, win.bounds.width, win.bounds.height) ?: continue
                    g.drawImage(img, win.bounds.x - totalBounds.x, win.bounds.y - totalBounds.y, null)
                }
            } finally {
                g.dispose()
            }

            val appWindows = toGrab.filter { it.bounds.width >= 50 && it.bounds.height >= 50 }.map { it.bounds }
            val overlayBounds = if (appWindows.isNotEmpty()) {
                mergeOverlapping(appWindows)
            } else {
                listOf(totalBounds)
            }

            return CaptureResult(composite, totalBounds, overlayBounds)
        } finally {
            lib.XSync(dpy, false)
            lib.XSetErrorHandler(prevHandler)
        }
    }

    private fun captureWindow(lib: LibX11, dpy: Pointer, window: NativeLong, width: Int, height: Int): BufferedImage? {
        val ximgPtr = lib.XGetImage(
            dpy,
            window,
            0,
            0,
            width,
            height,
            NativeLong(-1L),
            2,
        ) ?: return null

        try {
            val ximage = XImage(ximgPtr)
            val w = ximage.width
            val h = ximage.height
            if (w <= 0 || h <= 0) return null

            val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val dataBuffer = (result.raster.dataBuffer as DataBufferInt).data
            val dataPtr = ximage.data ?: return null

            if (ximage.bits_per_pixel == 32) {
                val bpl = ximage.bytes_per_line
                val stride = bpl / 4
                if (stride == w) {
                    dataPtr.read(0, dataBuffer, 0, w * h)
                } else {
                    for (y in 0 until h) {
                        dataPtr.read(y.toLong() * bpl, dataBuffer, y * w, w)
                    }
                }
            } else {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val pixel = lib.XGetPixel(ximgPtr, x, y).toLong().toInt()
                        result.setRGB(x, y, pixel)
                    }
                }
            }
            return result
        } finally {
            lib.XDestroyImage(ximgPtr)
        }
    }

    private fun getWindowArray(ptr: Pointer, count: Int): List<NativeLong> {
        if (count <= 0) return emptyList()
        val longSize = NativeLong.SIZE
        return (0 until count).map { i ->
            if (longSize == 8) NativeLong(ptr.getLong(i.toLong() * 8))
            else NativeLong(ptr.getInt(i.toLong() * 4).toLong())
        }
    }

    private fun getWindowName(lib: LibX11, dpy: Pointer, window: NativeLong, netWmNameAtom: NativeLong): String {
        val netName = getWindowStringProperty(lib, dpy, window, netWmNameAtom)
        if (!netName.isNullOrEmpty()) return netName

        val namePtrRef = PointerByReference()
        if (lib.XFetchName(dpy, window, namePtrRef) != 0) {
            val namePtr = namePtrRef.value
            if (namePtr != null) {
                try {
                    return namePtr.getString(0, "UTF-8")
                } finally {
                    lib.XFree(namePtr)
                }
            }
        }
        return ""
    }

    private fun getWindowStringProperty(lib: LibX11, dpy: Pointer, window: NativeLong, property: NativeLong): String? {
        val actualType = NativeLongByReference()
        val actualFormat = IntArray(1)
        val nitems = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val propRef = PointerByReference()

        val ret = lib.XGetWindowProperty(
            dpy,
            window,
            property,
            0,
            1024,
            false,
            NativeLong(0),
            actualType,
            actualFormat,
            nitems,
            bytesAfter,
            propRef,
        )
        if (ret != 0) return null
        val prop = propRef.value ?: return null
        return try {
            val length = nitems.value.toInt()
            if (length > 0) prop.getString(0, "UTF-8") else null
        } finally {
            lib.XFree(prop)
        }
    }

    private fun getWindowPropertyWindow(lib: LibX11, dpy: Pointer, window: NativeLong, property: NativeLong): NativeLong? {
        val actualType = NativeLongByReference()
        val actualFormat = IntArray(1)
        val nitems = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val propRef = PointerByReference()

        val ret = lib.XGetWindowProperty(
            dpy,
            window,
            property,
            0,
            1,
            false,
            NativeLong(0),
            actualType,
            actualFormat,
            nitems,
            bytesAfter,
            propRef,
        )
        if (ret != 0) return null
        val prop = propRef.value ?: return null
        return try {
            if (nitems.value.toLong() > 0) {
                if (NativeLong.SIZE == 8) NativeLong(prop.getLong(0))
                else NativeLong(prop.getInt(0).toLong())
            } else null
        } finally {
            lib.XFree(prop)
        }
    }

    internal fun mergeOverlapping(rects: List<Rectangle>): List<Rectangle> {
        val merged = rects.map { Rectangle(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < merged.size) {
                var j = i + 1
                while (j < merged.size) {
                    if (merged[i].intersects(merged[j])) {
                        merged[i] = merged[i].union(merged[j])
                        merged.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return merged
    }
}
