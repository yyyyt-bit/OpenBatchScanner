package com.example.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object PdfExporter {
    /** Junta as páginas em um PDF e salva em Downloads. */
    fun export(ctx: Context, files: List<File>): Uri? {
        val doc = PdfDocument()
        files.forEachIndexed { i, f ->
            val bmp = BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
            val page = doc.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create())
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
            bmp.recycle()
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let { u -> ctx.contentResolver.openOutputStream(u)?.use { doc.writeTo(it) } }
        doc.close()
        return uri
    }
}
