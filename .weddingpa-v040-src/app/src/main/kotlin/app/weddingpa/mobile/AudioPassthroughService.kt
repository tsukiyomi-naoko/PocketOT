package app.weddingpa.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.net.Uri
import java.util.ArrayDeque
import java.util.Locale

class AudioPassthroughService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var controlThread: HandlerThread
    private lateinit var controlHandler: Handler

    @Volatile private var armed = false
    @Volatile private var talking = false
    @Volatile private var preampDb = 6.0f
    @Volatile private var duckMusic = true
    @Volatile private var highPass = true
    @Volatile private var voiceEnhance = true
    @Volatile private var noiseGate = true
    @Volatile private var lastGainReductionDb = 0f
    @Volatile private var lastLimiterHits = 0
    @Volatile private var requestedInputId = DEVICE_AUTO
    @Volatile private var requestedOutputId = DEVICE_AUTO
    @Volatile private var multiOutputMode = MULTI_OUTPUT_SINGLE
    @Volatile private var networkHost = ""
    @Volatile private var localDelayMs = 100
    @Volatile private var musicVolume = 1.0f
    @Volatile private var musicDuckLevel = 0.28f
    @Volatile private var musicRequestedPlaying = false

    @Volatile private var engineRunning = false
    @Volatile private var sampleRate = 0
    @Volatile private var lastLevel = 0
    @Volatile private var lastError: String? = null
    @Volatile private var inputLabel = "Not connected"
    @Volatile private var outputLabel = "System media route"
    @Volatile private var focusStatus = "Focus idle"
    @Volatile private var musicStatus = "Internal player stopped"
    @Volatile private var musicTrackLabel = "No internal playlist"

    private var lockedAutoInputId: Int? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRoutedOutputId: Int? = null
    private var musicSource: MusicPcmSource? = null
    private var musicUris: List<Uri> = emptyList()
    private var networkSender: NetworkPcmSender? = null
    private var networkSenderHost: String = ""
    private var syncSignal: ShortArray? = null
    private var syncCursor = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedIds = removedDevices.map { it.id }.toSet()
            val recordId = try { audioRecord?.routedDevice?.id } catch (_: Throwable) { null }
            val selectedInput = if (requestedInputId == DEVICE_AUTO) lockedAutoInputId else requestedInputId
            val routedOutputNow = try { audioTrack?.routedDevice?.id } catch (_: Throwable) { null }
            val routedOutputBefore = lastRoutedOutputId

            if ((selectedInput != null && selectedInput in removedIds) || (recordId != null && recordId in removedIds)) {
                controlHandler.post {
                    talking = false
                    abandonFocus()
                    stopEngine()
                    lastError = "Microphone disconnected — PA muted. Reconnect it, then disarm/re-arm."
                    broadcastStatus()
                    updateNotification()
                }
                return
            }

            if ((routedOutputNow != null && routedOutputNow in removedIds) ||
                (routedOutputBefore != null && routedOutputBefore in removedIds)) {
                controlHandler.post {
                    talking = false
                    abandonFocus()
                    lastError = "Speaker/output disconnected — microphone muted to prevent accidental phone-speaker feedback."
                    lastLevel = 0
                    broadcastStatus()
                    updateNotification()
                }
            }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            broadcastStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        controlThread = HandlerThread("WeddingPA-Control", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        controlHandler = Handler(controlThread.looper)
        createNotificationChannel()
        audioManager.registerAudioDeviceCallback(deviceCallback, controlHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                readConfig(intent)
                if (!hasRecordPermission()) {
                    lastError = "Microphone permission is missing."
                    broadcastStatus()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!armed) {
                    armed = true
                    talking = false
                    lastError = null
                    lockedAutoInputId = if (requestedInputId == DEVICE_AUTO) chooseAutoInput()?.id else null
                    startForegroundCompat(buildNotification("Arming audio…"))
                    acquireWakeLock()
                    controlHandler.post {
                        restartEngine()
                        broadcastStatus()
                        updateNotification()
                    }
                } else {
                    controlHandler.post { restartEngine() }
                }
            }

            ACTION_CONFIG -> {
                val oldInput = requestedInputId
                val oldOutput = requestedOutputId
                val oldDuck = duckMusic
                readConfig(intent)

                if (armed && (oldInput != requestedInputId || oldOutput != requestedOutputId)) {
                    if (requestedInputId == DEVICE_AUTO && oldInput != DEVICE_AUTO) {
                        lockedAutoInputId = chooseAutoInput()?.id
                    } else if (requestedInputId != DEVICE_AUTO) {
                        lockedAutoInputId = null
                    }
                    controlHandler.post { restartEngine() }
                }

                controlHandler.post {
                    configureNetworkSender()
                    if (armed && talking && oldDuck != duckMusic) {
                        if (duckMusic && !internalMusicActive()) requestDuckFocus() else abandonFocus()
                    }
                    broadcastStatus()
                }
            }

            ACTION_TALK_START -> if (armed) {
                controlHandler.post {
                    if (!engineRunning) {
                        lastError = lastError ?: "Audio engine is not ready."
                        talking = false
                    } else {
                        lastError = null
                        if (duckMusic && !internalMusicActive()) requestDuckFocus()
                        else focusStatus = if (internalMusicActive()) "Internal music ducking" else "Music ducking off"
                        talking = true
                    }
                    broadcastStatus()
                    updateNotification()
                }
            }

            ACTION_TALK_STOP -> controlHandler.post {
                talking = false
                lastLevel = 0
                abandonFocus()
                broadcastStatus()
                updateNotification()
            }

            ACTION_SYNC_TEST -> if (armed) {
                controlHandler.post { scheduleSyncTest() }
            }

            ACTION_MUSIC_PLAY -> if (armed) {
                val uriStrings = intent.getStringArrayListExtra(EXTRA_MUSIC_URIS)
                if (!uriStrings.isNullOrEmpty()) musicUris = uriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                musicRequestedPlaying = true
                controlHandler.post { startOrResumeMusic() }
            }

            ACTION_MUSIC_PAUSE -> controlHandler.post {
                musicSource?.setPaused(true)
                musicRequestedPlaying = false
                musicStatus = "Internal player paused"
                broadcastStatus()
            }

            ACTION_MUSIC_NEXT -> controlHandler.post {
                musicSource?.next()
                musicStatus = "Skipping to next track…"
                broadcastStatus()
            }

            ACTION_MUSIC_STOP -> controlHandler.post {
                stopMusic()
                musicUris = emptyList()
                musicRequestedPlaying = false
                musicStatus = "Internal player stopped"
                musicTrackLabel = "No internal playlist"
                broadcastStatus()
            }

            ACTION_DISARM -> controlHandler.post { disarmAndStop() }
            ACTION_QUERY -> {
                broadcastStatus()
                if (!armed) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun readConfig(intent: Intent) {
        requestedInputId = intent.getIntExtra(EXTRA_INPUT_ID, requestedInputId)
        requestedOutputId = intent.getIntExtra(EXTRA_OUTPUT_ID, requestedOutputId)
        preampDb = when {
            intent.hasExtra(EXTRA_PREAMP_DB) -> intent.getFloatExtra(EXTRA_PREAMP_DB, preampDb)
            intent.hasExtra(EXTRA_GAIN) -> {
                val legacyGain = intent.getFloatExtra(EXTRA_GAIN, 1.0f).coerceAtLeast(0.01f)
                (20.0 * kotlin.math.log10(legacyGain.toDouble())).toFloat()
            }
            else -> preampDb
        }.coerceIn(-6f, 24f)
        duckMusic = intent.getBooleanExtra(EXTRA_DUCK, duckMusic)
        highPass = intent.getBooleanExtra(EXTRA_HIGH_PASS, highPass)
        voiceEnhance = intent.getBooleanExtra(EXTRA_VOICE_ENHANCE, voiceEnhance)
        noiseGate = intent.getBooleanExtra(EXTRA_NOISE_GATE, noiseGate)
        multiOutputMode = intent.getIntExtra(EXTRA_MULTI_OUTPUT_MODE, multiOutputMode)
        networkHost = intent.getStringExtra(EXTRA_NETWORK_HOST)?.trim() ?: networkHost
        localDelayMs = intent.getIntExtra(EXTRA_LOCAL_DELAY_MS, localDelayMs).coerceIn(0, 500)
        musicVolume = intent.getFloatExtra(EXTRA_MUSIC_VOLUME, musicVolume).coerceIn(0f, 1.25f)
        musicDuckLevel = intent.getFloatExtra(EXTRA_MUSIC_DUCK_LEVEL, musicDuckLevel).coerceIn(0.05f, 1.0f)
        if (multiOutputMode == MULTI_OUTPUT_LE_SHARE || multiOutputMode == MULTI_OUTPUT_WIFI_MASTER) requestedOutputId = DEVICE_AUTO
    }

    private fun restartEngine() {
        val wasTalking = talking
        talking = false
        lastLevel = 0
        abandonFocus()
        stopEngine()
        lastError = null

        try {
            startEngine()
            configureNetworkSender()
            if (musicRequestedPlaying && musicUris.isNotEmpty()) startOrResumeMusic()
            if (wasTalking) {
                if (duckMusic && !internalMusicActive()) requestDuckFocus()
                talking = true
            }
        } catch (t: Throwable) {
            engineRunning = false
            talking = false
            lastError = t.message ?: t.javaClass.simpleName
        }
        broadcastStatus()
        updateNotification()
    }

    private fun startEngine() {
        if (!hasRecordPermission()) throw IllegalStateException("Microphone permission is missing")

        val input = resolveInputDevice()
        val output = resolveOutputDevice()

        val built = buildCompatibleAudioPair(input, output)
        audioRecord = built.first
        audioTrack = built.second
        sampleRate = built.third

        inputLabel = input?.let { deviceLabel(it) } ?: "Android default microphone"
        outputLabel = output?.let { deviceLabel(it) } ?: "System media route"

        audioTrack!!.play()
        audioRecord!!.startRecording()
        if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Android could not start the selected microphone")
        }

        engineRunning = true
        startAudioLoop(sampleRate)
    }

    private fun buildCompatibleAudioPair(
        input: AudioDeviceInfo?,
        output: AudioDeviceInfo?
    ): Triple<AudioRecord, AudioTrack, Int> {
        var lastFailure: Throwable? = null
        for (rate in intArrayOf(48_000, 44_100, 32_000)) {
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val recordMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val trackMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (recordMin <= 0 || trackMin <= 0) continue

                val recordBuffer = maxOf(recordMin * 2, rate / 5 * 2)
                val trackBuffer = maxOf(trackMin * 2, rate / 5 * 2)
                val formatIn = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(formatIn)
                    .setBufferSizeInBytes(recordBuffer)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone initialization failed at $rate Hz")
                if (input != null && !record.setPreferredDevice(input)) {
                    throw IllegalStateException("Android refused the selected microphone: ${deviceLabel(input)}")
                }

                val speechAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val formatOut = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(speechAttributes)
                    .setAudioFormat(formatOut)
                    .setBufferSizeInBytes(trackBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= 26) trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                track = trackBuilder.build()

                if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("Speaker output initialization failed at $rate Hz")
                if (output != null && !track.setPreferredDevice(output)) {
                    throw IllegalStateException("Android refused the selected output: ${deviceLabel(output)}")
                }

                return Triple(record, track, rate)
            } catch (t: Throwable) {
                lastFailure = t
                try { record?.release() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }
        throw IllegalStateException(lastFailure?.message ?: "No compatible microphone/speaker audio format found")
    }

    private fun startAudioLoop(rate: Int) {
        val record = audioRecord ?: return
        val track = audioTrack ?: return
        val frameSamples = (rate / 100).coerceAtLeast(256) // ~10 ms
        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val micBuffer = ShortArray(frameSamples)
            val musicBuffer = ShortArray(frameSamples)
            val mixedBuffer = ShortArray(frameSamples)
            val processor = PcmProcessor(rate)
            val localDelayQueue = ArrayDeque<ShortArray>()
            var lastMeterAt = 0L
            var lastAppliedDelayFrames = -1

            while (engineRunning && !Thread.currentThread().isInterrupted) {
                val read = try {
                    record.read(micBuffer, 0, micBuffer.size, AudioRecord.READ_BLOCKING)
                } catch (_: Throwable) {
                    break
                }
                if (read <= 0) continue

                val music = musicSource
                val musicSamples = if (music != null && music.isRunning() && !music.isPaused()) {
                    music.readInto(musicBuffer, read)
                } else {
                    java.util.Arrays.fill(musicBuffer, 0, read, 0.toShort())
                    0
                }

                var micLevel = 0
                if (talking) {
                    val stats = processor.process(
                        buffer = micBuffer,
                        count = read,
                        preampDb = preampDb,
                        highPass = highPass,
                        voiceEnhance = voiceEnhance,
                        noiseControl = noiseGate
                    )
                    micLevel = stats.levelPercent
                    lastGainReductionDb = stats.gainReductionDb
                    lastLimiterHits = stats.limitedSamples
                } else {
                    java.util.Arrays.fill(micBuffer, 0, read, 0.toShort())
                    lastGainReductionDb = 0f
                    lastLimiterHits = 0
                }

                val duck = if (talking && duckMusic) musicDuckLevel else 1.0f
                var signal = syncSignal
                var cursor = syncCursor
                var nonSilent = false
                for (i in 0 until read) {
                    val musicSample = musicBuffer[i].toInt() * musicVolume * duck
                    val micSample = micBuffer[i].toInt().toFloat()
                    val syncSample = if (signal != null && cursor < signal.size) signal[cursor++].toInt().toFloat() else 0f
                    var v = (musicSample + micSample + syncSample).toInt()
                    // Final-mix headroom/limiter. Mic has already passed the speech limiter.
                    if (v > 30000) v = 30000 + ((v - 30000) / 5)
                    if (v < -30000) v = -30000 + ((v + 30000) / 5)
                    v = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    mixedBuffer[i] = v.toShort()
                    if (v != 0) nonSilent = true
                }
                if (signal != null) {
                    syncCursor = cursor
                    if (cursor >= signal.size) {
                        syncSignal = null
                        syncCursor = 0
                        if (!talking && !internalMusicActive()) abandonFocus()
                    }
                }

                if (multiOutputMode == MULTI_OUTPUT_WIFI_MASTER) {
                    networkSender?.offer(mixedBuffer, read, rate)
                }

                // Delay the master's local speaker to compensate for Wi-Fi receiver buffering.
                val targetDelayFrames = if (multiOutputMode == MULTI_OUTPUT_WIFI_MASTER) (localDelayMs / 10).coerceAtLeast(0) else 0
                if (targetDelayFrames != lastAppliedDelayFrames) {
                    localDelayQueue.clear()
                    lastAppliedDelayFrames = targetDelayFrames
                }
                localDelayQueue.addLast(mixedBuffer.copyOf(read))
                val localFrame = if (localDelayQueue.size > targetDelayFrames) localDelayQueue.removeFirst() else null
                if (localFrame != null && (nonSilent || multiOutputMode == MULTI_OUTPUT_WIFI_MASTER || internalMusicActive())) {
                    try {
                        var offset = 0
                        while (offset < localFrame.size && engineRunning) {
                            val written = track.write(localFrame, offset, localFrame.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) break
                            offset += written
                        }
                        try {
                            val routed = track.routedDevice
                            val previousRouteId = lastRoutedOutputId
                            if (previousRouteId != null && routed != null && routed.id != previousRouteId &&
                                routed.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && talking) {
                                controlHandler.post {
                                    if (talking) {
                                        talking = false
                                        abandonFocus()
                                        lastLevel = 0
                                        lastError = "Audio route fell back to the phone speaker — microphone muted for feedback safety."
                                        broadcastStatus()
                                        updateNotification()
                                    }
                                }
                            }
                            lastRoutedOutputId = routed?.id
                        } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastMeterAt >= 90) {
                    lastLevel = if (talking) micLevel else 0
                    lastMeterAt = now
                    broadcastStatus()
                }
            }
        }, "WeddingPA-Audio").also { it.start() }
    }

    private fun stopEngine() {
        engineRunning = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioThread?.interrupt() } catch (_: Throwable) {}
        try { audioThread?.join(350) } catch (_: Throwable) {}
        audioThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioRecord = null
        audioTrack = null
        stopMusic()
        networkSender?.stop()
        networkSender = null
        networkSenderHost = ""
        syncSignal = null
        syncCursor = 0
        sampleRate = 0
        lastRoutedOutputId = null
    }

    private fun resolveInputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        if (requestedInputId != DEVICE_AUTO) {
            return devices.firstOrNull { it.id == requestedInputId }
                ?: throw IllegalStateException("Selected microphone is no longer connected")
        }

        val lockedId = lockedAutoInputId
        if (lockedId != null) {
            return devices.firstOrNull { it.id == lockedId }
                ?: throw IllegalStateException("The microphone chosen when PA was armed is no longer connected")
        }

        val chosen = chooseAutoInput()
        lockedAutoInputId = chosen?.id
        return chosen
    }

    private fun chooseAutoInput(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        return devices.minWithOrNull(compareBy<AudioDeviceInfo> { inputPriority(it.type) }.thenBy { it.id })
    }

    private fun resolveOutputDevice(): AudioDeviceInfo? {
        if (requestedOutputId == DEVICE_AUTO) return null // Follow the exact same Android media route as Spotify/music.
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.id == requestedOutputId }
            ?: throw IllegalStateException("Selected speaker/output is no longer connected")
    }

    private fun inputPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 3
        else -> 4
    }

    private fun scheduleSyncTest() {
        if (!armed || !engineRunning) {
            lastError = "Audio engine is not ready for the sync test."
            broadcastStatus()
            return
        }
        if (talking) {
            lastError = "Stop the live microphone before running the sync test."
            broadcastStatus()
            return
        }
        val rate = sampleRate.takeIf { it > 0 } ?: 48_000
        val pulseSamples = (rate * 0.055).toInt().coerceAtLeast(256)
        val gapSamples = (rate * 0.22).toInt().coerceAtLeast(512)
        val total = pulseSamples * 3 + gapSamples * 2
        val signal = ShortArray(total)
        var offset = 0
        repeat(3) { pulseIndex ->
            for (i in 0 until pulseSamples) {
                val envelope = when {
                    i < pulseSamples / 8 -> i.toDouble() / (pulseSamples / 8).coerceAtLeast(1)
                    i > pulseSamples * 7 / 8 -> (pulseSamples - i).toDouble() / (pulseSamples / 8).coerceAtLeast(1)
                    else -> 1.0
                }
                val sample = kotlin.math.sin(2.0 * Math.PI * 1200.0 * i / rate) * 0.32 * envelope
                signal[offset + i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            offset += pulseSamples
            if (pulseIndex < 2) offset += gapSamples
        }
        syncSignal = signal
        syncCursor = 0
        lastError = null
        if (duckMusic && !internalMusicActive()) requestDuckFocus()
        broadcastStatus()
    }

    private fun configureNetworkSender() {
        if (!armed || multiOutputMode != MULTI_OUTPUT_WIFI_MASTER || networkHost.isBlank()) {
            networkSender?.stop()
            networkSender = null
            networkSenderHost = ""
            return
        }
        val current = networkSender
        if (current == null || networkSenderHost != networkHost) {
            current?.stop()
            try {
                networkSender = NetworkPcmSender().also { it.start(networkHost) }
                networkSenderHost = networkHost
            } catch (t: Throwable) {
                lastError = "Wi-Fi satellite: ${t.message ?: t.javaClass.simpleName}"
                networkSender = null
                networkSenderHost = ""
            }
        }
    }

    private fun internalMusicActive(): Boolean = musicSource?.let { it.isRunning() && !it.isPaused() } == true

    private fun startOrResumeMusic() {
        if (!armed || !engineRunning || musicUris.isEmpty()) {
            if (musicUris.isEmpty()) musicStatus = "No internal playlist selected"
            broadcastStatus()
            return
        }
        val existing = musicSource
        if (existing != null && existing.isRunning()) {
            existing.setPaused(false)
            musicRequestedPlaying = true
            musicStatus = "Internal player playing"
            broadcastStatus()
            return
        }
        stopMusic()
        musicSource = MusicPcmSource(
            context = this,
            uris = musicUris,
            targetRate = sampleRate,
            onTrackChanged = { index, name ->
                musicTrackLabel = "${index + 1}/${musicUris.size} • $name"
                musicStatus = "Internal player playing"
                controlHandler.post { broadcastStatus() }
            },
            onPlaylistEnded = {
                musicRequestedPlaying = false
                musicStatus = "Playlist finished"
                controlHandler.post { broadcastStatus() }
            }
        ).also { it.start() }
        musicRequestedPlaying = true
        musicStatus = "Internal player starting…"
        broadcastStatus()
    }

    private fun stopMusic() {
        try { musicSource?.stop() } catch (_: Throwable) {}
        musicSource = null
    }

    private fun routedOutputDevices(): List<AudioDeviceInfo> {
        val track = audioTrack ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                track.routedDevices.filter { it.isSink }.distinctBy { it.id }
            } else {
                listOfNotNull(track.routedDevice).filter { it.isSink }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun routeTransportLabel(devices: List<AudioDeviceInfo>): String {
        if (devices.isEmpty()) return "System media route"
        val kinds = devices.map { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Classic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                else -> "System audio"
            }
        }.distinct()
        return kinds.joinToString(" + ")
    }

    private fun routeSummary(devices: List<AudioDeviceInfo>): String =
        if (devices.isEmpty()) "System media route" else devices.joinToString(" | ") { deviceLabel(it) }

    private fun requestDuckFocus() {
        if (!duckMusic) return
        if (focusRequest == null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    focusStatus = when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> "Duck focus granted"
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "Focus: duck requested"
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "Focus temporarily lost"
                        AudioManager.AUDIOFOCUS_LOSS -> "Focus lost"
                        else -> "Focus change $change"
                    }
                }
                .build()
        }
        val result = audioManager.requestAudioFocus(focusRequest!!)
        focusStatus = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Music duck requested"
        } else {
            "Duck request failed — mic remains live"
        }
    }

    private fun abandonFocus() {
        focusRequest?.let {
            try { audioManager.abandonAudioFocusRequest(it) } catch (_: Throwable) {}
        }
        focusStatus = "Focus idle"
    }

    private fun broadcastStatus() {
        val status = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_ARMED, armed)
            putExtra(EXTRA_TALKING, talking)
            putExtra(EXTRA_LEVEL, lastLevel)
            putExtra(EXTRA_HEADLINE, when {
                lastError != null -> "PA NEEDS ATTENTION"
                talking -> "LIVE MICROPHONE"
                armed && engineRunning -> "ARMED — READY"
                armed -> "ARMING…"
                else -> "DISARMED"
            })

            val routedOutputs = routedOutputDevices()
            val route = routedOutputs.firstOrNull()?.let { deviceLabel(it) }
            if (route != null) {
                outputLabel = if (requestedOutputId == DEVICE_AUTO) "$route (system media route)" else route
            }
            putExtra(EXTRA_ROUTE_COUNT, routedOutputs.size)
            putExtra(EXTRA_ROUTE_SUMMARY, routeSummary(routedOutputs))

            val detail = buildString {
                lastError?.let {
                    append(it)
                    return@buildString
                }
                append("Mic: ").append(inputLabel)
                append("\nOut: ").append(outputLabel)
                if (sampleRate > 0) append(" • ").append(sampleRate / 1000.0).append(" kHz")
                append("\nTransport: ").append(routeTransportLabel(routedOutputs))
                if (multiOutputMode == MULTI_OUTPUT_LE_SHARE) append(" • LE Share/system multi-output mode")
                if (routedOutputs.size > 1) append(" • ").append(routedOutputs.size).append(" simultaneous outputs")
                append("\n").append(if (duckMusic) focusStatus else "Music ducking disabled")
                if (talking) {
                    append("\nVoice: ")
                    append(if (voiceEnhance) "Enhanced" else "Clean")
                    append(" • Preamp ").append(String.format(Locale.US, "%+.0f dB", preampDb))
                    if (voiceEnhance && lastGainReductionDb >= 0.5f) {
                        append(" • GR ").append(String.format(Locale.US, "%.1f dB", lastGainReductionDb))
                    }
                    if (lastLimiterHits > 0) append(" • peak-safe")
                }
            }
            putExtra(EXTRA_DETAIL, detail)
        }
        sendBroadcast(status)
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Classic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Audio device"
        }
        val name = try { device.productName?.toString()?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
        return if (name != null) "$kind — $name" else "$kind #${device.id}"
    }

    private fun hasRecordPermission(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeddingPA::Audio").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun disarmAndStop() {
        talking = false
        armed = false
        lastLevel = 0
        abandonFocus()
        stopEngine()
        lockedAutoInputId = null
        releaseWakeLock()
        lastError = null
        broadcastStatus()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wedding PA", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the wedding microphone audio engine alive"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun buildNotification(overrideText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioPassthroughService::class.java).setAction(ACTION_TALK_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AudioPassthroughService::class.java).setAction(ACTION_DISARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (talking) "Wedding PA — MICROPHONE LIVE" else "Wedding PA — armed")
            .setContentText(overrideText ?: if (talking) "Tap Mute immediately if needed" else "Ready for announcements")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(0, "Mute", muteIntent).build())
            .addAction(Notification.Action.Builder(0, "Disarm", stopIntent).build())
            .build()
    }

    private fun updateNotification() {
        if (!armed) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Throwable) {}
        controlHandler.post {
            talking = false
            armed = false
            abandonFocus()
            stopEngine()
            releaseWakeLock()
        }
        controlThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val DEVICE_AUTO = -1

        const val ACTION_ARM = "app.weddingpa.mobile.ARM"
        const val ACTION_DISARM = "app.weddingpa.mobile.DISARM"
        const val ACTION_TALK_START = "app.weddingpa.mobile.TALK_START"
        const val ACTION_TALK_STOP = "app.weddingpa.mobile.TALK_STOP"
        const val ACTION_CONFIG = "app.weddingpa.mobile.CONFIG"
        const val ACTION_QUERY = "app.weddingpa.mobile.QUERY"
        const val ACTION_SYNC_TEST = "app.weddingpa.mobile.SYNC_TEST"
        const val ACTION_MUSIC_PLAY = "app.weddingpa.mobile.MUSIC_PLAY"
        const val ACTION_MUSIC_PAUSE = "app.weddingpa.mobile.MUSIC_PAUSE"
        const val ACTION_MUSIC_NEXT = "app.weddingpa.mobile.MUSIC_NEXT"
        const val ACTION_MUSIC_STOP = "app.weddingpa.mobile.MUSIC_STOP"
        const val ACTION_STATUS = "app.weddingpa.mobile.STATUS"

        const val MULTI_OUTPUT_SINGLE = 0
        const val MULTI_OUTPUT_LE_SHARE = 1
        const val MULTI_OUTPUT_WIFI_MASTER = 2

        const val EXTRA_INPUT_ID = "input_id"
        const val EXTRA_OUTPUT_ID = "output_id"
        // EXTRA_GAIN remains for compatibility with v0.1-v0.3 callers.
        const val EXTRA_GAIN = "gain"
        const val EXTRA_PREAMP_DB = "preamp_db"
        const val EXTRA_DUCK = "duck"
        const val EXTRA_HIGH_PASS = "high_pass"
        const val EXTRA_VOICE_ENHANCE = "voice_enhance"
        const val EXTRA_NOISE_GATE = "noise_gate"
        const val EXTRA_MULTI_OUTPUT_MODE = "multi_output_mode"
        const val EXTRA_NETWORK_HOST = "network_host"
        const val EXTRA_LOCAL_DELAY_MS = "local_delay_ms"
        const val EXTRA_MUSIC_VOLUME = "music_volume"
        const val EXTRA_MUSIC_DUCK_LEVEL = "music_duck_level"
        const val EXTRA_MUSIC_URIS = "music_uris"
        const val EXTRA_ARMED = "armed"
        const val EXTRA_TALKING = "talking"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_HEADLINE = "headline"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_ROUTE_COUNT = "route_count"
        const val EXTRA_ROUTE_SUMMARY = "route_summary"

        private const val CHANNEL_ID = "wedding_pa_active"
        private const val NOTIFICATION_ID = 4201
    }
}
