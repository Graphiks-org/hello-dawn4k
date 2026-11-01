package org.graphiks.hellodawn4k

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal PNG writer: RGBA8, no scanline filtering, deflate-compressed IDAT. */
object Png {
    fun encode(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rgba.size / 2)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8) // bit depth
            .put(6) // color type: truecolor with alpha
            .put(0) // compression: deflate
            .put(0) // filter method
            .put(0) // interlace: none
        chunk(out, "IHDR", header.array())

        val raw = ByteArray(height * (width * 4 + 1))
        var source = 0
        var target = 0
        for (y in 0 until height) {
            raw[target++] = 0 // filter type: none
            System.arraycopy(rgba, source, raw, target, width * 4)
            source += width * 4
            target += width * 4
        }
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(1 shl 16)
        val compressed = ByteArrayOutputStream(data.size / 4)
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return compressed.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val crc = CRC32().apply {
            update(type.toByteArray(Charsets.US_ASCII))
            update(data)
        }
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
