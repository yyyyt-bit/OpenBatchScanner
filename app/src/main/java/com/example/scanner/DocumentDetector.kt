package com.example.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object DocumentDetector {

    /** Converte o plano Y do frame em Mat cinza, já girado para a orientação da tela. */
    fun gray(img: ImageProxy): Mat {
        val plane = img.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val full = Mat(img.height, plane.rowStride, CvType.CV_8UC1)
        full.put(0, 0, bytes)
        val crop = full.submat(0, img.height, 0, img.width)
        val out = Mat()
        when (img.imageInfo.rotationDegrees) {
            90 -> Core.rotate(crop, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(crop, out, Core.ROTATE_180)
            270 -> Core.rotate(crop, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> crop.copyTo(out)
        }
        crop.release(); full.release()
        return out
    }

    /** Retorna 8 floats normalizados (0..1): TL, TR, BR, BL. Null se não achar documento. */
    fun detect(gray: Mat): FloatArray? {
        val scale = 480.0 / gray.cols()
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(small, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val minArea = small.rows() * small.cols() * 0.25
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < minArea || area <= bestArea) continue
            val c2f = MatOfPoint2f(*c.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                best = approx.toArray(); bestArea = area
            }
        }
        val w = small.cols().toDouble(); val h = small.rows().toDouble()
        small.release(); edges.release()
        val p = best ?: return null
        val tl = p.minBy { it.x + it.y }; val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }; val bl = p.maxBy { it.y - it.x }
        return floatArrayOf(
            (tl.x / w).toFloat(), (tl.y / h).toFloat(), (tr.x / w).toFloat(), (tr.y / h).toFloat(),
            (br.x / w).toFloat(), (br.y / h).toFloat(), (bl.x / w).toFloat(), (bl.y / h).toFloat()
        )
    }

    fun signature(gray: Mat): Mat = Mat().also { Imgproc.resize(gray, it, Size(32.0, 32.0)) }

    fun difference(a: Mat, b: Mat): Double {
        val d = Mat(); Core.absdiff(a, b, d)
        val m = Core.mean(d).`val`[0]; d.release(); return m
    }

    /** Corrige a perspectiva; se bw=true aplica filtro preto e branco. */
    fun warp(bmp: Bitmap, q: FloatArray, bw: Boolean): Bitmap {
        val src = Mat(); Utils.bitmapToMat(bmp, src)
        val pts = Array(4) { Point(q[it * 2] * src.cols().toDouble(), q[it * 2 + 1] * src.rows().toDouble()) }
        fun d(a: Int, b: Int) = hypot(pts[a].x - pts[b].x, pts[a].y - pts[b].y)
        val w = max(d(0, 1), d(3, 2)); val h = max(d(0, 3), d(1, 2))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        val m = Imgproc.getPerspectiveTransform(MatOfPoint2f(*pts), dst)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w, h))
        if (bw) {
            Imgproc.cvtColor(out, out, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(out, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 31, 15.0)
        }
        val res = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, res)
        src.release(); out.release(); m.release()
        return res
    }
}
