package com.example.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

enum class St { SEARCHING, CAPTURING, WAIT_SWAP }

class QuadOverlay(c: Context) : View(c) {
    var quad: FloatArray? = null
    var color = Color.YELLOW
    private val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
    override fun onDraw(cv: Canvas) {
        val q = quad ?: return
        // O preview é 3:4 (retrato) centralizado (FIT_CENTER)
        val u = minOf(width / 3f, height / 4f)
        val w = u * 3; val h = u * 4
        val ox = (width - w) / 2; val oy = (height - h) / 2
        val path = Path()
        for (i in 0..3) {
            val x = ox + q[i * 2] * w; val y = oy + q[i * 2 + 1] * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); paint.color = color; cv.drawPath(path, paint)
    }
}

class MainActivity : ComponentActivity() {
    private companion object { const val NEED = 12 }   // frames estáveis para disparar

    private lateinit var preview: PreviewView
    private lateinit var overlay: QuadOverlay
    private lateinit var status: TextView
    private lateinit var autoBtn: Button
    private lateinit var bwBtn: Button
    private lateinit var imageCapture: ImageCapture
    private lateinit var dir: File
    private val exec = Executors.newSingleThreadExecutor()
    private val pages = mutableListOf<File>()

    @Volatile private var state = St.SEARCHING
    @Volatile private var auto = true
    @Volatile private var bw = false
    @Volatile private var lastQuad: FloatArray? = null
    private var prev: FloatArray? = null
    private var stable = 0
    private var missing = 0
    private var armed = false
    private var lastSig: Mat? = null
    private var msg = ""

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        dir = File(filesDir, "session").apply { mkdirs() }
        pages.addAll(dir.listFiles()?.sortedBy { it.name }.orEmpty())   // retoma lote
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = QuadOverlay(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24); textSize = 16f
        }
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { f(this) }
        }
        autoBtn = btn("Auto ON") { auto = !auto; it.text = if (auto) "Auto ON" else "Auto OFF"; resetSearch() }
        bwBtn = btn("Cor") { bw = !bw; it.text = if (bw) "P&B" else "Cor" }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0x88000000.toInt())
            addView(autoBtn); addView(bwBtn)
            addView(btn("Foto") { if (state != St.CAPTURING) { state = St.CAPTURING; lastSig = null; capture(lastQuad ?: floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)) } })
            addView(btn("Desfazer") { pages.removeLastOrNull()?.delete(); setStatus("Página removida") })
            addView(btn("PDF") { exportPdf() })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        setStatus("Iniciando…")
    }

    private fun setStatus(m: String) { msg = m; status.text = "Páginas: ${pages.size}  •  $m" }
    private fun resetSearch() { stable = 0; prev = null; missing = 0; armed = false; state = St.SEARCHING }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img -> analyze(img) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val gray = DocumentDetector.gray(img)
            try { onFrame(gray, DocumentDetector.detect(gray)) } finally { gray.release() }
        } finally { img.close() }
    }

    private fun delta(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }

    /** Máquina de estados: SEARCHING -> CAPTURING -> WAIT_SWAP -> SEARCHING */
    private fun onFrame(gray: Mat, q: FloatArray?) {
        lastQuad = q
        var text = ""
        var color = Color.YELLOW
        when (state) {
            St.SEARCHING -> {
                val p = prev
                stable = if (q != null && p != null && delta(p, q) < 0.02f) stable + 1 else 0
                prev = q
                text = if (q == null) "Procurando documento…" else "Estabilizando $stable/$NEED"
                if (auto && q != null && stable >= NEED) {
                    lastSig?.release(); lastSig = DocumentDetector.signature(gray)
                    state = St.CAPTURING; color = Color.GREEN
                    runOnUiThread { capture(q) }
                }
            }
            St.WAIT_SWAP -> {
                text = "Troque a página"; color = Color.CYAN
                missing = if (q == null) missing + 1 else 0
                val sig = lastSig
                val changed = sig != null && DocumentDetector.difference(DocumentDetector.signature(gray), sig) > 30
                if (missing >= 6 || changed) armed = true      // página saiu ou mudou
                if (armed && q != null) resetSearch()
            }
            St.CAPTURING -> { text = "Capturando…"; color = Color.GREEN }
        }
        runOnUiThread { overlay.quad = q; overlay.color = color; overlay.invalidate(); setStatus(text) }
    }

    private fun capture(q: FloatArray) {
        val raw = File(cacheDir, "raw_${System.nanoTime()}.jpg")
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    missing = 0; armed = false; state = St.WAIT_SWAP
                    lifecycleScope.launch(Dispatchers.Default) { process(raw, q) }
                }
                override fun onError(e: ImageCaptureException) { state = St.SEARCHING }
            })
    }

    private suspend fun process(raw: File, q: FloatArray) {
        val rot = when (ExifInterface(raw.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        var bmp = BitmapFactory.decodeFile(raw.path)
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val out = DocumentDetector.warp(bmp, q, bw)
        val f = File(dir, "page_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle(); out.recycle(); raw.delete()
        withContext(Dispatchers.Main) { pages.add(f); setStatus(msg) }
    }

    private fun exportPdf() {
        val files = pages.toList()
        if (files.isEmpty()) { Toast.makeText(this, "Nenhuma página", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { PdfExporter.export(this@MainActivity, files) }
            if (uri != null) {
                files.forEach { it.delete() }; pages.clear()
                Toast.makeText(this@MainActivity, "PDF salvo em Downloads", Toast.LENGTH_LONG).show()
            } else Toast.makeText(this@MainActivity, "Falha ao salvar", Toast.LENGTH_LONG).show()
            setStatus("Pronto")
        }
    }
}
