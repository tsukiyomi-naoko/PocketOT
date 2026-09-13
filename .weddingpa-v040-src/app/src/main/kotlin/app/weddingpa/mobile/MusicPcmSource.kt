package app.weddingpa.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Decodes a user-selected playlist with Android's platform codecs, downmixes to mono,
 * and resamples to the PA engine rate. The audio thread only polls a bounded PCM queue.
 */
class MusicPcmSource(
    private val context: Context,
    private val uris: List<Uri>,
    private val targetRate: Int,
    private val onTrackChanged: (Int, String) -> Unit,
    private val onPlaylistEnded: () -> Unit
) {
    private val queue = ArrayBlockingQueue<ShortArray>(160)
    @Volatile private var running = false
    @Volatile private var decodeFinished = false
    @Volatile private var endNotified = false
    @Volatile private var paused = false
    @Volatile private var skipRequested = false
    @Volatile var trackIndex: Int = 0
        private set
    @Volatile var trackName: String = ""
        private set
    @Volatile var lastError: String? = null
        private set

    private var thread: Thread? = null
    private var readChunk: ShortArray? = null
    private var readOffset = 0

    fun start() {
        if (running || uris.isEmpty()) return
        running = true
        decodeFinished = false
        endNotified = false
        paused = false
        thread = Thread({ decodePlaylist() }, "WeddingPA-MusicDecode").also { it.start() }
    }

    fun setPaused(value: Boolean) { paused = value }
    fun isPaused(): Boolean = paused
    fun isRunning(): Boolean = running

    fun next() {
        skipRequested = true
        queue.clear()
        readChunk = null
        readOffset = 0
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Throwable) {}
        queue.clear()
        try { thread?.join(350) } catch (_: Throwable) {}
        thread = null
        readChunk = null
        readOffset = 0
    }

    /** Fill up to count samples. Missing samples are returned as silence. */
    fun readInto(out: ShortArray, count: Int): Int {
        if (!running || paused || count <= 0) {
            java.util.Arrays.fill(out, 0, count.coerceAtMost(out.size), 0.toShort())
            return 0
        }
        var written = 0
        while (written < count) {
            var chunk = readChunk
            if (chunk == null || readOffset >= chunk.size) {
                chunk = queue.poll()
                readChunk = chunk
                readOffset = 0
                if (chunk == null) break
            }
            val n = minOf(count - written, chunk.size - readOffset)
            System.arraycopy(chunk, readOffset, out, written, n)
            written += n
            readOffset += n
        }
        if (written < count) java.util.Arrays.fill(out, written, count.coerceAtMost(out.size), 0.toShort())
        if (decodeFinished && queue.isEmpty() && (readChunk == null || readOffset >= (readChunk?.size ?: 0))) {
            running = false
            if (!endNotified) {
                endNotified = true
                onPlaylistEnded()
            }
        }
        return written
    }

    private fun decodePlaylist() {
        try {
            trackIndex = 0
            while (running && trackIndex < uris.size) {
                skipRequested = false
                trackName = displayName(uris[trackIndex])
                onTrackChanged(trackIndex, trackName)
                try {
                    decodeOne(uris[trackIndex])
                    lastError = null
                } catch (t: Throwable) {
                    if (running) lastError = "${trackName}: ${t.message ?: t.javaClass.simpleName}"
                }
                if (skipRequested) {
                    queue.clear()
                    readChunk = null
                    readOffset = 0
                }
                trackIndex++
            }
        } finally {
            decodeFinished = true
        }
    }

    private fun decodeOne(uri: Uri) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) throw IllegalArgumentException("No decodable audio track")
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Missing audio MIME")
            try { format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) } catch (_: Throwable) {}
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var inputDone = false
            var outputDone = false
            var sourceRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, targetRate)
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var carry = ShortArray(0)

            while (running && !outputDone && !skipRequested) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sourceRate = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceRate)
                        channels = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        pcmEncoding = outFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            val mono = decodeToMono(outBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding)
                            val resampled = if (sourceRate == targetRate) mono else resampleLinear(mono, sourceRate, targetRate)
                            carry = enqueueChunks(carry, resampled)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
            if (carry.isNotEmpty() && running && !skipRequested) offerChunk(carry)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun decodeToMono(buffer: ByteBuffer, channels: Int, encoding: Int): ShortArray {
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / channels
            ShortArray(frames) { frame ->
                var sum = 0.0
                repeat(channels) { ch -> sum += floats.get(frame * channels + ch).toDouble() }
                val v = (sum / channels).coerceIn(-1.0, 1.0)
                (v * Short.MAX_VALUE).toInt().toShort()
            }
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = shorts.remaining() / channels
            ShortArray(frames) { frame ->
                var sum = 0L
                repeat(channels) { ch -> sum += shorts.get(frame * channels + ch).toLong() }
                (sum / channels).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
        }
    }

    private fun resampleLinear(input: ShortArray, sourceRate: Int, destRate: Int): ShortArray {
        if (input.isEmpty() || sourceRate <= 0 || destRate <= 0) return input
        val outSize = ((input.size.toLong() * destRate) / sourceRate).toInt().coerceAtLeast(1)
        val ratio = sourceRate.toDouble() / destRate.toDouble()
        return ShortArray(outSize) { i ->
            val src = i * ratio
            val a = floor(src).toInt().coerceIn(0, input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val f = src - a
            (input[a] * (1.0 - f) + input[b] * f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun enqueueChunks(carry: ShortArray, data: ShortArray): ShortArray {
        if (data.isEmpty()) return carry
        val combined = ShortArray(carry.size + data.size)
        System.arraycopy(carry, 0, combined, 0, carry.size)
        System.arraycopy(data, 0, combined, carry.size, data.size)
        val chunkSize = (targetRate / 100).coerceAtLeast(240) // ~10 ms
        var offset = 0
        while (offset + chunkSize <= combined.size && running && !skipRequested) {
            offerChunk(combined.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
        }
        return if (offset < combined.size) combined.copyOfRange(offset, combined.size) else ShortArray(0)
    }

    private fun offerChunk(chunk: ShortArray) {
        while (running && !skipRequested) {
            try {
                if (queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "Audio track"
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "Audio track"
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        try { if (containsKey(key)) getInteger(key) else default } catch (_: Throwable) { default }
}
