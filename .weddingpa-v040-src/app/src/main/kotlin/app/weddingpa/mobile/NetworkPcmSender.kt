package app.weddingpa.mobile

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort low-latency PCM sender. Network I/O never runs on the audio thread.
 * Frames are deliberately small enough to remain below a normal Ethernet/Wi-Fi MTU.
 */
class NetworkPcmSender {
    private data class Frame(val samples: ShortArray, val sampleRate: Int)

    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Frame>(12)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var address: InetAddress? = null
    @Volatile private var port: Int = DEFAULT_PORT
    @Volatile var packetsSent: Long = 0
        private set
    @Volatile var packetsDropped: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun start(host: String, port: Int = DEFAULT_PORT) {
        stop()
        if (host.isBlank()) return
        val resolved = InetAddress.getByName(host.trim())
        this.address = resolved
        this.port = port
        this.socket = DatagramSocket()
        running.set(true)
        thread = Thread({ sendLoop() }, "WeddingPA-NetTx").also { it.start() }
    }

    fun offer(samples: ShortArray, count: Int, sampleRate: Int) {
        if (!running.get() || count <= 0) return
        val copy = samples.copyOf(count)
        if (!queue.offer(Frame(copy, sampleRate))) {
            queue.poll()
            if (!queue.offer(Frame(copy, sampleRate))) packetsDropped++ else packetsDropped++
        }
    }

    private fun sendLoop() {
        var sequence = 0
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val frame = try { queue.take() } catch (_: InterruptedException) { break }
                val target = address ?: continue
                val bytes = ByteArray(HEADER_BYTES + frame.samples.size * 2)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(MAGIC)
                bb.putInt(sequence++)
                bb.putInt(frame.sampleRate)
                bb.putShort(frame.samples.size.toShort())
                bb.putShort(0) // flags/reserved
                frame.samples.forEach { bb.putShort(it) }
                socket?.send(DatagramPacket(bytes, bytes.size, target, port))
                packetsSent++
                lastError = null
            }
        } catch (t: Throwable) {
            if (running.get()) lastError = t.message ?: t.javaClass.simpleName
        }
    }

    fun stop() {
        running.set(false)
        try { thread?.interrupt() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        try { thread?.join(250) } catch (_: Throwable) {}
        thread = null
        socket = null
        address = null
        queue.clear()
    }

    companion object {
        const val DEFAULT_PORT = 42043
        const val MAGIC = 0x33504157 // bytes: W A P 3 in little-endian; only compared as int
        const val HEADER_BYTES = 16
        const val DISCOVERY_REQUEST = "WEDDINGPA_DISCOVER_V3"
        const val DISCOVERY_RESPONSE_PREFIX = "WEDDINGPA_SATELLITE_V3|"
    }
}
