package com.example.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object PdfExporter {
    private const val MAX_SIDE = 1600

    fun export(ctx: Context, files: List<File>): Uri? {
        val doc = PdfDocument()
        var n = 0
        for (f in files) {
            var bmp = BitmapFactory.decodeFile(f.path) ?: continue
            val s = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            if (s < 1f) {
                val small = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                bmp.recycle(); bmp = small
            }
            val page = doc.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, ++n).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
            bmp.recycle()
        }
        if (n == 0) { doc.close(); return null }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        return try {
            ctx.contentResolver.openOutputStream(uri ?: return null)?.use { doc.writeTo(it) }
            uri
        } catch (e: Exception) {
            uri?.let { ctx.contentResolver.delete(it, null, null) }
            null
        } finally {
            doc.close()
        }
    }
}
