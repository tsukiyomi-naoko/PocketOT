package app.weddingpa.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/** Second-phone companion: receives WeddingPA PCM over LAN and plays it on the phone's system media route. */
class NetworkAudioReceiverService : Service() {
    private lateinit var notificationManager: NotificationManager
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var playThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val jitter = TreeMap<Int, ShortArray>()
    private val jitterLock = Object()

    @Volatile private var packetsReceived = 0L
    @Volatile private var packetsLost = 0L
    @Volatile private var lastSequence: Int? = null
    @Volatile private var connectedMaster = "Waiting for master"
    @Volatile private var lastError: String? = null
    @Volatile private var sampleRate = 48_000
    @Volatile private var bufferMs = 100

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bufferMs = intent.getIntExtra(EXTRA_BUFFER_MS, bufferMs).coerceIn(40, 500)
                if (!running.get()) startReceiver()
            }
            ACTION_STOP -> stopReceiver()
            ACTION_QUERY -> {
                broadcastStatus()
                if (!running.get()) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startReceiver() {
        lastError = null
        running.set(true)
        startForegroundCompat(buildNotification())
        acquireWakeLock()
        try {
            socket = DatagramSocket(NetworkPcmSender.DEFAULT_PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
            }
            buildTrack(sampleRate)
            receiveThread = Thread({ receiveLoop() }, "WeddingPA-NetRx").also { it.start() }
            playThread = Thread({ playLoop() }, "WeddingPA-NetPlay").also { it.start() }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            running.set(false)
            broadcastStatus()
            stopSelf()
        }
        broadcastStatus()
    }

    private fun buildTrack(rate: Int) {
        try { audioTrack?.release() } catch (_: Throwable) {}
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = maxOf(min * 3, rate * 2 / 2)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        sampleRate = rate
    }

    private fun receiveLoop() {
        val buffer = ByteArray(1500)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket?.receive(packet) ?: break
                if (packet.length <= 0) continue
                val text = if (packet.length <= 96) {
                    try { String(packet.data, packet.offset, packet.length, Charsets.UTF_8) } catch (_: Throwable) { "" }
                } else ""
                if (text == NetworkPcmSender.DISCOVERY_REQUEST) {
                    val response = "${NetworkPcmSender.DISCOVERY_RESPONSE_PREFIX}${Build.MODEL}|${NetworkPcmSender.DEFAULT_PORT}"
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    socket?.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                    continue
                }
                if (packet.length < NetworkPcmSender.HEADER_BYTES) continue
                val bb = ByteBuffer.wrap(packet.data, packet.offset, packet.length).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.int != NetworkPcmSender.MAGIC) continue
                val seq = bb.int
                val rate = bb.int
                val count = bb.short.toInt() and 0xffff
                bb.short // flags
                if (count <= 0 || count * 2 > bb.remaining()) continue
                if (rate != sampleRate && rate in 8_000..96_000) {
                    synchronized(jitterLock) { jitter.clear() }
                    buildTrack(rate)
                }
                val samples = ShortArray(count) { bb.short }
                synchronized(jitterLock) {
                    if (jitter.size > 80) jitter.pollFirstEntry()
                    jitter[seq] = samples
                    jitterLock.notifyAll()
                }
                connectedMaster = packet.address.hostAddress ?: "Master"
                packetsReceived++
                val prev = lastSequence
                if (prev != null && seq > prev + 1) packetsLost += (seq - prev - 1).toLong()
                if (prev == null || seq > prev) lastSequence = seq
                if (packetsReceived % 30L == 0L) broadcastStatus()
            } catch (_: java.net.SocketTimeoutException) {
                broadcastStatus()
            } catch (t: Throwable) {
                if (running.get()) lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var expected: Int? = null
        var primed = false
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var frame: ShortArray? = null
            synchronized(jitterLock) {
                val targetFrames = (bufferMs / 10).coerceAtLeast(4)
                if (!primed) {
                    if (jitter.size < targetFrames) {
                        try { jitterLock.wait(20) } catch (_: InterruptedException) { break }
                        continue
                    }
                    expected = jitter.firstKey()
                    primed = true
                }
                val e = expected
                if (e != null) {
                    frame = jitter.remove(e)
                    if (frame != null) {
                        expected = e + 1
                    } else if (jitter.isNotEmpty() && jitter.firstKey() > e && jitter.size >= 3) {
                        // A packet was lost (or arrived too late). Preserve the audio clock with one silence frame.
                        packetsLost++
                        expected = e + 1
                        frame = ShortArray((sampleRate / 100).coerceAtLeast(240))
                    }
                }
            }
            if (frame == null) {
                Thread.sleep(3)
                continue
            }
            try {
                audioTrack?.write(frame!!, 0, frame!!.size, AudioTrack.WRITE_BLOCKING)
            } catch (t: Throwable) {
                lastError = "Playback: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun stopReceiver() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        synchronized(jitterLock) { jitterLock.notifyAll() }
        try { receiveThread?.interrupt() } catch (_: Throwable) {}
        try { playThread?.interrupt() } catch (_: Throwable) {}
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        socket = null
        synchronized(jitterLock) { jitter.clear() }
        releaseWakeLock()
        broadcastStatus(stopped = true)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun broadcastStatus(stopped: Boolean = false) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running.get() && !stopped)
            putExtra(EXTRA_HEADLINE, when {
                stopped -> "SATELLITE STOPPED"
                lastError != null -> "SATELLITE NEEDS ATTENTION"
                packetsReceived > 0 -> "SATELLITE RECEIVING"
                else -> "SATELLITE READY"
            })
            putExtra(EXTRA_DETAIL, buildString {
                lastError?.let { append(it).append('\n') }
                append("Listen: ").append(localIpv4()).append(':').append(NetworkPcmSender.DEFAULT_PORT)
                append("\nMaster: ").append(connectedMaster)
                append("\nBuffer: ").append(bufferMs).append(" ms")
                append(" • packets ").append(packetsReceived)
                if (packetsLost > 0) append(" • estimated loss ").append(packetsLost)
                append("\nOutput: Android system media route (connect this phone to speaker #2)")
            })
        })
        if (running.get()) notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun localIpv4(): String {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress ?: "0.0.0.0"
        } catch (_: Throwable) { "0.0.0.0" }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wedding PA Satellite", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 12, Intent(this, NetworkAudioReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Wedding PA — satellite")
            .setContentText(if (packetsReceived > 0) "Receiving from $connectedMaster" else "Waiting for master on Wi-Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeddingPA::Satellite").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    override fun onDestroy() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        synchronized(jitterLock) { jitterLock.notifyAll() }
        try { receiveThread?.interrupt() } catch (_: Throwable) {}
        try { playThread?.interrupt() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.weddingpa.mobile.SATELLITE_START"
        const val ACTION_STOP = "app.weddingpa.mobile.SATELLITE_STOP"
        const val ACTION_QUERY = "app.weddingpa.mobile.SATELLITE_QUERY"
        const val ACTION_STATUS = "app.weddingpa.mobile.SATELLITE_STATUS"
        const val EXTRA_BUFFER_MS = "satellite_buffer_ms"
        const val EXTRA_RUNNING = "satellite_running"
        const val EXTRA_HEADLINE = "satellite_headline"
        const val EXTRA_DETAIL = "satellite_detail"
        private const val CHANNEL_ID = "wedding_pa_satellite"
        private const val NOTIFICATION_ID = 4202
    }
}
