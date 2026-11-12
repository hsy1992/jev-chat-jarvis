package com.dianming.phoneapp.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.dianming.phoneapp.capture.ocr.MlKitOcr
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

internal object SharedImageImport {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_EDGE = 2560
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun read(context: Context, id: Long, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        worker.execute {
            val bitmap = runCatching {
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buf)
                        if (read < 0) break
                        require(out.size() + read <= MAX_BYTES)
                        out.write(buf, 0, read)
                    }
                    out.toByteArray()
                } ?: error("Unreadable image")
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000)
                    val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(width, height))
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            if (bitmap == null) {
                main.post { ShareTasks.imageReady(id, "", null, "无法读取截图，请分享一张不超过 16 MB 的图片。") }
                return@execute
            }

            val scale = minOf(1f, 600f / maxOf(bitmap.width, bitmap.height))
            val thumbnail = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            MlKitOcr().recognize(bitmap, null) { lines ->
                if (thumbnail !== bitmap) bitmap.recycle()
                val text = lines.joinToString("\n") { it.text }
                when {
                    text.isBlank() -> ShareTasks.imageReady(id, "", thumbnail, "没有识别到文字，可以手动输入。")
                    text.length > ShareTasks.MAX_TEXT ->
                        ShareTasks.imageReady(id, "", thumbnail, "识别内容太长，请裁剪截图后重新分享。")
                    else -> ShareTasks.imageReady(id, text, thumbnail, null)
                }
            }
        }
    }
}
