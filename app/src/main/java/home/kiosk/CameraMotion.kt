package home.kiosk

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Elapsed-realtime of the last camera motion, so the in-app screensaver can wake too. 0 = none yet. */
val lastMotion = MutableStateFlow(0L)

/**
 * Motion from the tablet's camera by frame differencing: once a second the preview's luma is averaged into a coarse grid
 * and compared with the previous grid. Nothing is stored or sent; only the "motion" verdict leaves this class.
 * The old Camera API on purpose: these MediaTek tablets only expose camera2 in LEGACY mode, which wraps it anyway.
 */
@Suppress("DEPRECATION")
class CameraMotion(private val facing: Int, private val threshold: () -> Float, private val onMotion: () -> Unit) {
    private var camera: Camera? = null
    private val texture = SurfaceTexture(0) // preview needs a target; never drawn
    private var prev: IntArray? = null
    private var lastCheck = 0L

    /** The camera facing [facing] if there is one, else the first. False when there's no usable camera. */
    fun start(): Boolean = runCatching {
        val info = Camera.CameraInfo()
        val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info); info.facing == facing
        } ?: 0
        val cam = Camera.open(id)
        camera = cam
        val p = cam.parameters
        val size = p.supportedPreviewSizes.minBy { it.width * it.height }
        p.setPreviewSize(size.width, size.height)
        p.supportedPreviewFpsRange?.minByOrNull { it[0] }?.let { p.setPreviewFpsRange(it[0], it[1]) } // least CPU and heat
        cam.parameters = p
        cam.addCallbackBuffer(ByteArray(size.width * size.height * 3 / 2))
        cam.setPreviewCallbackWithBuffer { data, c ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck >= 1_000) {
                lastCheck = now
                val cur = lumaGrid(data, size.width, size.height)
                prev?.let { if (motionScore(it, cur) >= threshold()) onMotion() }
                prev = cur
            }
            c.addCallbackBuffer(data)
        }
        cam.setPreviewTexture(texture)
        cam.startPreview()
    }.onFailure { stop() }.isSuccess

    fun stop() {
        camera?.runCatching { setPreviewCallbackWithBuffer(null); stopPreview(); release() }
        camera = null
        prev = null
    }
}

const val GRID_W = 32
const val GRID_H = 24
/** Brightness change (0..255) for a grid cell to count as moved; above sensor noise, below a person walking past. */
const val CELL_DELTA = 12

/** Mean luma of each cell of a [GRID_W]x[GRID_H] grid over an NV21 frame's Y plane (every other pixel is plenty). */
fun lumaGrid(y: ByteArray, w: Int, h: Int): IntArray {
    val sum = IntArray(GRID_W * GRID_H)
    val n = IntArray(sum.size)
    for (r in 0 until h step 2) for (c in 0 until w step 2) {
        val i = (r * GRID_H / h) * GRID_W + c * GRID_W / w
        sum[i] += y[r * w + c].toInt() and 0xFF
        n[i]++
    }
    return IntArray(sum.size) { sum[it] / maxOf(n[it], 1) }
}

/** Fraction of grid cells whose brightness moved by more than [CELL_DELTA]. */
fun motionScore(a: IntArray, b: IntArray): Float = a.indices.count { abs(a[it] - b[it]) > CELL_DELTA }.toFloat() / a.size
