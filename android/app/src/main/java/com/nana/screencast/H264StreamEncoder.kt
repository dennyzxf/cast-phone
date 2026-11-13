package com.nana.screencast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

data class H264Config(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val description: String
)

/**
 * 把 MediaCodec 输出的 Annex-B H.264 流转成 WebCodecs 需要的 AVCC 格式。
 *
 * 每个视频帧的二进制布局：
 *   [1 字节] 1 = 关键帧，0 = 非关键帧
 *   [4 字节大端长度 + NALU] * N
 *
 * SPS / PPS 单独通过 JSON 文本消息发送（AVCDecoderConfigurationRecord，base64）。
 */
class H264StreamEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onConfig: (H264Config) -> Unit,
    private val onFrame: (isKey: Boolean, data: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastConfigAt = 0L

    val surface: Surface
        get() = inputSurface ?: error("编码器尚未启动")

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        running = true
        worker = Thread(this::drainLoop, "h264-drain").also { it.start() }
        Log.i(TAG, "编码器启动 ${width}x${height} @${fps}fps / ${bitrate / 1000}kbps")
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val c = codec ?: return
        while (running) {
            try {
                val index = c.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readCsd(c.outputFormat)
                    continue
                }
                if (index < 0) continue
                val buffer = c.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes)
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    handleAnnexB(bytes, isKey, isConfig)
                }
                c.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "编码器状态异常，结束读取", e)
                break
            } catch (e: Exception) {
                Log.w(TAG, "编码循环异常", e)
            }
        }
    }

    private fun readCsd(format: MediaFormat) {
        for (key in arrayOf("csd-0", "csd-1")) {
            if (!format.containsKey(key)) continue
            val bb: ByteBuffer = format.getByteBuffer(key) ?: continue
            val arr = ByteArray(bb.remaining())
            bb.duplicate().get(arr)
            if (absorbParameterSets(arr)) sendConfigIfReady(force = true)
        }
    }

    /** 从一段数据里抽出 SPS(7) / PPS(8)，返回是否发生了变化 */
    private fun absorbParameterSets(data: ByteArray): Boolean {
        val list = splitAnnexB(data).ifEmpty { listOf(data) }
        var changed = false
        for (nalu in list) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                7 -> { sps = nalu; changed = true }
                8 -> { pps = nalu; changed = true }
            }
        }
        return changed
    }

    private fun handleAnnexB(data: ByteArray, isKey: Boolean, isConfig: Boolean) {
        val nalus = splitAnnexB(data).filter { it.isNotEmpty() }
        val source = nalus.ifEmpty { listOf(data) }

        var changed = false
        var hasIdr = false
        val payload = ArrayList<ByteArray>(4)
        var payloadSize = 0

        for (nalu in source) {
            val type = nalu[0].toInt() and 0x1F
            when (type) {
                7 -> { sps = nalu; changed = true }
                8 -> { pps = nalu; changed = true }
                else -> {
                    if (type == 5) hasIdr = true
                    payload.add(nalu)
                    payloadSize += 4 + nalu.size
                }
            }
        }

        if (changed) sendConfigIfReady(force = true)
        if (isConfig || payload.isEmpty()) return

        val key = isKey || hasIdr
        // 每个关键帧补一次配置，接收端中途连上也能立刻解码
        if (key) sendConfigIfReady(force = false)

        val frame = ByteArray(1 + payloadSize)
        frame[0] = if (key) 1 else 0
        var offset = 1
        for (nalu in payload) {
            val len = nalu.size
            frame[offset] = ((len ushr 24) and 0xFF).toByte()
            frame[offset + 1] = ((len ushr 16) and 0xFF).toByte()
            frame[offset + 2] = ((len ushr 8) and 0xFF).toByte()
            frame[offset + 3] = (len and 0xFF).toByte()
            System.arraycopy(nalu, 0, frame, offset + 4, len)
            offset += 4 + len
        }
        onFrame(key, frame)
    }

    private fun sendConfigIfReady(force: Boolean) {
        val s = sps ?: return
        val p = pps ?: return
        if (s.size < 4 || p.isEmpty()) return
        if (!force && System.currentTimeMillis() - lastConfigAt < 900) return

        // AVCDecoderConfigurationRecord
        val record = ByteArray(11 + s.size + p.size)
        var o = 0
        record[o++] = 1                                  // configurationVersion
        record[o++] = s[1]                               // AVCProfileIndication
        record[o++] = s[2]                               // profile_compatibility
        record[o++] = s[3]                               // AVCLevelIndication
        record[o++] = 0xFF.toByte()                      // 0xFC | lengthSizeMinusOne=3
        record[o++] = 0xE1.toByte()                      // 0xE0 | numOfSequenceParameterSets=1
        record[o++] = ((s.size ushr 8) and 0xFF).toByte()
        record[o++] = (s.size and 0xFF).toByte()
        System.arraycopy(s, 0, record, o, s.size); o += s.size
        record[o++] = 1                                  // numOfPictureParameterSets
        record[o++] = ((p.size ushr 8) and 0xFF).toByte()
        record[o++] = (p.size and 0xFF).toByte()
        System.arraycopy(p, 0, record, o, p.size)

        val codecName = "avc1." + hex(s[1]) + hex(s[2]) + hex(s[3])
        lastConfigAt = System.currentTimeMillis()
        onConfig(
            H264Config(
                codec = codecName,
                width = width,
                height = height,
                fps = fps,
                description = Base64.encodeToString(record, Base64.NO_WRAP)
            )
        )
    }

    private fun hex(b: Byte): String = "%02x".format(b.toInt() and 0xFF)

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var naluStart = -1
        var i = 0
        while (i < data.size) {
            val is4 = i + 3 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3 = i + 2 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            if (is4 || is3) {
                val header = if (is4) 4 else 3
                if (naluStart >= 0 && i > naluStart) out.add(data.copyOfRange(naluStart, i))
                naluStart = i + header
                i += header
            } else {
                i++
            }
        }
        if (naluStart >= 0 && naluStart < data.size) {
            out.add(data.copyOfRange(naluStart, data.size))
        }
        return out
    }

    fun stop() {
        running = false
        try { worker?.join(1200) } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputSurface = null
        worker = null
    }
}
