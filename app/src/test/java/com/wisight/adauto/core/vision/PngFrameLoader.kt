package com.wisight.adauto.core.vision

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * 语料加载：把 PNG 解成 ARGB `IntArray`。
 *
 * 为什么自己解而不用 `javax.imageio`：Android 单测的编译/运行 classpath 里没有
 * `java.desktop`，`ImageIO` 不可用。这里只依赖 `java.util.zip.Inflater`（Android 自带），
 * 支持 8bit 真彩 / 真彩+alpha、非隔行 —— 覆盖所有本仓库语料以及 `adb screencap` 的输出。
 *
 * 支持全部 5 种行过滤器，所以真机截图可以直接丢进语料目录。
 */
internal object PngFrameLoader {

    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun read(file: File): Image {
        val bytes = file.readBytes()
        require(bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            "${file.name} 不是 PNG"
        }
        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= bytes.size) {
            val len = beInt(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = beInt(bytes, data)
                    height = beInt(bytes, data + 4)
                    bitDepth = bytes[data + 8].toInt() and 0xFF
                    colorType = bytes[data + 9].toInt() and 0xFF
                    interlace = bytes[data + 12].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, data, len)
            }
            pos = data + len + 4
        }
        require(bitDepth == 8 && interlace == 0 && (colorType == 2 || colorType == 6)) {
            "${file.name}: 仅支持 8bit 真彩/真彩+alpha 非隔行 PNG（depth=$bitDepth type=$colorType interlace=$interlace）"
        }
        val bpp = if (colorType == 6) 4 else 3
        val raw = inflate(idat.toByteArray(), width * height * bpp + height)
        val stride = width * bpp
        val argb = IntArray(width * height)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var rp = 0
        for (y in 0 until height) {
            val filter = raw[rp++].toInt() and 0xFF
            System.arraycopy(raw, rp, cur, 0, stride)
            rp += stride
            unfilter(filter, cur, prev, bpp)
            val base = y * width
            for (x in 0 until width) {
                val i = x * bpp
                val r = cur[i].toInt() and 0xFF
                val g = cur[i + 1].toInt() and 0xFF
                val b = cur[i + 2].toInt() and 0xFF
                argb[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return Image(width, height, argb)
    }

    private fun beInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArray(expected)
        var n = 0
        while (n < expected && !inflater.finished()) {
            val r = inflater.inflate(out, n, expected - n)
            if (r == 0 && inflater.needsInput()) break
            n += r
        }
        inflater.end()
        require(n == expected) { "PNG 数据长度异常：$n/$expected" }
        return out
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            0 -> Unit
            1 -> for (i in bpp until cur.size) {
                cur[i] = (cur[i] + cur[i - bpp]).toByte()
            }
            2 -> for (i in cur.indices) {
                cur[i] = (cur[i] + prev[i]).toByte()
            }
            3 -> for (i in cur.indices) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                cur[i] = (cur[i] + ((left + up) ushr 1)).toByte()
            }
            4 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + paeth(a, b, c)).toByte()
            }
            else -> error("未知 PNG 行过滤器 $filter")
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
