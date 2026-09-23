package io.github.kunkai2002.splashclean.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer

data class OcrLine(val text: String, val confidence: Float, val box: Rect)

/**
 * PaddleOCR (PP-OCRv4 mobile, Apache-2.0) running on ONNX Runtime.
 * Detection (DB) + recognition (CTC), axis-aligned boxes only — enough for "跳过" buttons.
 * Pre/post-processing follows PaddleOCR defaults: BGR input, ImageNet mean/std for detection,
 * (x/255-0.5)/0.5 for recognition, threshold 0.3, box score 0.5, unclip ratio 1.5.
 */
class PaddleOcr private constructor(
    private val env: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val chars: List<String>,
) {
    companion object {
        @Volatile
        private var instance: PaddleOcr? = null

        fun get(context: Context): PaddleOcr = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): PaddleOcr {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            fun load(name: String) = context.assets.open("ocr/$name").use { it.readBytes() }
            val det = env.createSession(load("ch_PP-OCRv4_det_mobile.onnx"), opts)
            val rec = env.createSession(load("ch_PP-OCRv4_rec_mobile.onnx"), opts)
            val dict = context.assets.open("ocr/ppocr_keys_v1.txt").bufferedReader(Charsets.UTF_8).readLines()
            val chars = ArrayList<String>(dict.size + 2).apply {
                add("") // CTC blank
                addAll(dict.take(6623))
                add(" ")
            }
            return PaddleOcr(env, det, rec, chars)
        }

        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    /** Runs both models once on a blank image so the first real call is fast. */
    fun warmUp() {
        val b = Bitmap.createBitmap(320, 96, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.WHITE)
        recognize(b, Rect(0, 0, 320, 96))
        recognizeBox(b, Rect(0, 24, 160, 72))
        b.recycle()
    }

    /**
     * Runs OCR on [region] of [bitmap] (screen coordinates). Returned boxes are in bitmap coordinates.
     * [maxSide] limits the detection input size.
     */
    @Synchronized
    fun recognize(bitmap: Bitmap, region: Rect, maxSide: Int = 960, recFilter: (Rect) -> Boolean = { true }): List<OcrLine> {
        val w = region.width()
        val h = region.height()
        if (w < 16 || h < 16) return emptyList()
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val nw = maxOf(32, Math.round(w * scale / 32f) * 32)
        val nh = maxOf(32, Math.round(h * scale / 32f) * 32)
        val src = Bitmap.createBitmap(bitmap, region.left, region.top, w, h)
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (src !== bitmap && src !== resized) src.recycle()
        val input = FloatBuffer.allocate(3 * nw * nh)
        val px = IntArray(nw * nh)
        resized.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (resized !== bitmap) resized.recycle()
        val plane = nw * nh
        for (i in 0 until plane) {
            val c = px[i]
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            // BGR order, as PaddleOCR reads images with OpenCV
            input.put(i, (b - DET_MEAN[0]) / DET_STD[0])
            input.put(plane + i, (g - DET_MEAN[1]) / DET_STD[1])
            input.put(2 * plane + i, (r - DET_MEAN[2]) / DET_STD[2])
        }
        val prob: FloatArray
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out ->
                val tensor = out[0] as OnnxTensor
                val fb = tensor.floatBuffer
                prob = FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        val boxes = findBoxes(prob, nw, nh, w.toFloat() / nw, h.toFloat() / nh, region)
        return boxes.filter(recFilter).mapNotNull { box -> recognizeBox(bitmap, box) }
    }

    private fun findBoxes(prob: FloatArray, nw: Int, nh: Int, sx: Float, sy: Float, region: Rect): List<Rect> {
        val seen = BooleanArray(prob.size)
        val stack = IntArray(prob.size)
        val result = ArrayList<Rect>()
        for (start in prob.indices) {
            if (seen[start] || prob[start] <= 0.3f) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = nw; var minY = nh; var maxX = -1; var maxY = -1
            var sum = 0f; var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % nw
                val y = p / nw
                sum += prob[p]; count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val q = p - 1; if (!seen[q] && prob[q] > 0.3f) { seen[q] = true; stack[sp++] = q } }
                if (x < nw - 1) { val q = p + 1; if (!seen[q] && prob[q] > 0.3f) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - nw; if (!seen[q] && prob[q] > 0.3f) { seen[q] = true; stack[sp++] = q } }
                if (y < nh - 1) { val q = p + nw; if (!seen[q] && prob[q] > 0.3f) { seen[q] = true; stack[sp++] = q } }
            }
            if (count < 10 || sum / count < 0.5f) continue
            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat()
            if (minOf(bw, bh) < 3) continue
            val d = bw * bh * 1.5f / (2f * (bw + bh))
            val l = ((minX - d) * sx).coerceAtLeast(0f)
            val t = ((minY - d) * sy).coerceAtLeast(0f)
            val r = ((maxX + 1 + d) * sx).coerceAtMost(region.width().toFloat())
            val b = ((maxY + 1 + d) * sy).coerceAtMost(region.height().toFloat())
            result.add(Rect(region.left + l.toInt(), region.top + t.toInt(), region.left + r.toInt(), region.top + b.toInt()))
        }
        return result
    }

    private fun recognizeBox(bitmap: Bitmap, box: Rect): OcrLine? {
        val bw = box.width()
        val bh = box.height()
        if (bw < 4 || bh < 4) return null
        val rw = (48f * bw / bh).toInt().coerceIn(8, 640)
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, bw, bh)
        val scaled = Bitmap.createScaledBitmap(crop, rw, 48, true)
        if (crop !== bitmap && crop !== scaled) crop.recycle()
        val px = IntArray(rw * 48)
        scaled.getPixels(px, 0, rw, 0, 0, rw, 48)
        if (scaled !== bitmap) scaled.recycle()
        val plane = rw * 48
        val input = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, ((c and 0xff) / 255f - 0.5f) / 0.5f)
            input.put(plane + i, (((c shr 8) and 0xff) / 255f - 0.5f) / 0.5f)
            input.put(2 * plane + i, (((c shr 16) and 0xff) / 255f - 0.5f) / 0.5f)
        }
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 48, rw.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { out ->
                val tensor = out[0] as OnnxTensor
                val shape = tensor.info.shape // [1, T, C]
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val fb = tensor.floatBuffer
                val sb = StringBuilder()
                var confSum = 0f
                var confCount = 0
                var last = 0
                for (s in 0 until steps) {
                    var best = 0
                    var bestV = -1f
                    val base = s * classes
                    for (k in 0 until classes) {
                        val v = fb.get(base + k)
                        if (v > bestV) { bestV = v; best = k }
                    }
                    if (best != 0 && best != last && best < chars.size) {
                        sb.append(chars[best])
                        confSum += bestV
                        confCount++
                    }
                    last = best
                }
                if (sb.isEmpty()) return null
                return OcrLine(sb.toString(), confSum / confCount, box)
            }
        }
    }
}
