package app.weddingpa.mobile

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale

class MainActivity : Activity() {

    data class DeviceOption(val id: Int, val label: String) {
        override fun toString(): String = label
    }

    private lateinit var audioManager: AudioManager
    private val prefs by lazy { getSharedPreferences("weddingpa_settings", Context.MODE_PRIVATE) }
    private lateinit var inputSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var multiOutputSpinner: Spinner
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var talkButton: Button
    private lateinit var armButton: Button
    private lateinit var latchSwitch: Switch
    private lateinit var duckSwitch: Switch
    private lateinit var highPassSwitch: Switch
    private lateinit var voiceEnhanceSwitch: Switch
    private lateinit var noiseGateSwitch: Switch
    private lateinit var gainSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var leCapabilityText: TextView
    private lateinit var routeSummaryText: TextView
    private lateinit var syncTestButton: Button

    private lateinit var satelliteIpEdit: EditText
    private lateinit var discoverSatelliteButton: Button
    private lateinit var networkInfoText: TextView
    private lateinit var localDelaySeek: SeekBar
    private lateinit var localDelayLabel: TextView
    private lateinit var satelliteBufferSeek: SeekBar
    private lateinit var satelliteBufferLabel: TextView

    private lateinit var musicInfoText: TextView
    private lateinit var chooseMusicButton: Button
    private lateinit var playMusicButton: Button
    private lateinit var pauseMusicButton: Button
    private lateinit var nextMusicButton: Button
    private lateinit var stopMusicButton: Button
    private lateinit var musicVolumeSeek: SeekBar
    private lateinit var musicVolumeLabel: TextView

    private var armed = false
    private var talking = false
    private var satelliteRunning = false
    private var pendingArm = false
    private var receiverRegistered = false
    private var suppressModeCallback = false
    private var discovering = false

    private var inputOptions: List<DeviceOption> = emptyList()
    private var outputOptions: List<DeviceOption> = emptyList()
    private val musicUris = mutableListOf<Uri>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioPassthroughService.ACTION_STATUS -> {
                    if (isSatelliteMode()) return
                    armed = intent.getBooleanExtra(AudioPassthroughService.EXTRA_ARMED, false)
                    talking = intent.getBooleanExtra(AudioPassthroughService.EXTRA_TALKING, false)
                    val headline = intent.getStringExtra(AudioPassthroughService.EXTRA_HEADLINE)
                        ?: if (armed) "ARMED" else "DISARMED"
                    val detail = intent.getStringExtra(AudioPassthroughService.EXTRA_DETAIL) ?: ""
                    val level = intent.getIntExtra(AudioPassthroughService.EXTRA_LEVEL, 0)
                    val routeSummary = intent.getStringExtra(AudioPassthroughService.EXTRA_ROUTE_SUMMARY)
                    val routeCount = intent.getIntExtra(AudioPassthroughService.EXTRA_ROUTE_COUNT, 0)
                    renderState(headline, detail, level)
                    routeSummaryText.text = when {
                        routeSummary.isNullOrBlank() -> "Routed outputs: not available until PA is armed"
                        routeCount > 1 -> "Routed outputs ($routeCount): $routeSummary\n✓ Android reports simultaneous multi-output routing."
                        isLeShareMode() -> "Routed outputs (${routeCount.coerceAtLeast(1)}): $routeSummary\nLE Share is armed; Android currently reports one output. Enable Audio sharing in Bluetooth settings, then refresh."
                        isWifiMasterMode() -> "Master local output: $routeSummary\nThe exact same WeddingPA PCM is also sent over Wi-Fi to the satellite."
                        else -> "Routed output: $routeSummary"
                    }
                }

                NetworkAudioReceiverService.ACTION_STATUS -> {
                    if (!isSatelliteMode()) return
                    satelliteRunning = intent.getBooleanExtra(NetworkAudioReceiverService.EXTRA_RUNNING, false)
                    armed = satelliteRunning
                    talking = false
                    val headline = intent.getStringExtra(NetworkAudioReceiverService.EXTRA_HEADLINE) ?: "SATELLITE"
                    val detail = intent.getStringExtra(NetworkAudioReceiverService.EXTRA_DETAIL) ?: ""
                    renderState(headline, detail, 0)
                    routeSummaryText.text = if (satelliteRunning) {
                        "Satellite output: Android system media route → connect this phone to speaker #2."
                    } else {
                        "Satellite is stopped."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(AudioManager::class.java)
        bindViews()
        setupMultiOutputModes()
        restoreSettings()
        setupControls()
        refreshDevices()
        refreshLeCapabilities()
        updateGainLabel()
        updateDelayLabels()
        updateMusicVolumeLabel()
        updatePlaylistInfo()
        updateModeControls()
        renderState("DISARMED", "Choose your microphone and speaker, then arm the PA.", 0)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioPassthroughService.ACTION_STATUS)
                addAction(NetworkAudioReceiverService.ACTION_STATUS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshLeCapabilities()
        queryCurrentMode()
    }

    override fun onStop() {
        saveSettings()
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun bindViews() {
        inputSpinner = findViewById(R.id.inputSpinner)
        outputSpinner = findViewById(R.id.outputSpinner)
        multiOutputSpinner = findViewById(R.id.multiOutputSpinner)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        levelMeter = findViewById(R.id.levelMeter)
        talkButton = findViewById(R.id.talkButton)
        armButton = findViewById(R.id.armButton)
        latchSwitch = findViewById(R.id.latchSwitch)
        duckSwitch = findViewById(R.id.duckSwitch)
        highPassSwitch = findViewById(R.id.highPassSwitch)
        voiceEnhanceSwitch = findViewById(R.id.voiceEnhanceSwitch)
        noiseGateSwitch = findViewById(R.id.noiseGateSwitch)
        gainSeek = findViewById(R.id.gainSeek)
        gainLabel = findViewById(R.id.gainLabel)
        leCapabilityText = findViewById(R.id.leCapabilityText)
        routeSummaryText = findViewById(R.id.routeSummaryText)
        syncTestButton = findViewById(R.id.syncTestButton)

        satelliteIpEdit = findViewById(R.id.satelliteIpEdit)
        discoverSatelliteButton = findViewById(R.id.discoverSatelliteButton)
        networkInfoText = findViewById(R.id.networkInfoText)
        localDelaySeek = findViewById(R.id.localDelaySeek)
        localDelayLabel = findViewById(R.id.localDelayLabel)
        satelliteBufferSeek = findViewById(R.id.satelliteBufferSeek)
        satelliteBufferLabel = findViewById(R.id.satelliteBufferLabel)

        musicInfoText = findViewById(R.id.musicInfoText)
        chooseMusicButton = findViewById(R.id.chooseMusicButton)
        playMusicButton = findViewById(R.id.playMusicButton)
        pauseMusicButton = findViewById(R.id.pauseMusicButton)
        nextMusicButton = findViewById(R.id.nextMusicButton)
        stopMusicButton = findViewById(R.id.stopMusicButton)
        musicVolumeSeek = findViewById(R.id.musicVolumeSeek)
        musicVolumeLabel = findViewById(R.id.musicVolumeLabel)
    }

    private fun setupMultiOutputModes() {
        val modes = listOf(
            "Single / Android system media route",
            "System LE Audio Share — 2+ speakers",
            "Wi-Fi Dual — this phone is MASTER",
            "Wi-Fi Dual — this phone is SATELLITE"
        )
        multiOutputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        multiOutputSpinner.setSelection(MODE_SINGLE, false)
    }

    private fun setupControls() {
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            refreshDevices()
            refreshLeCapabilities()
            queryCurrentMode()
        }
        findViewById<Button>(R.id.bluetoothButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        findViewById<Button>(R.id.appSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        discoverSatelliteButton.setOnClickListener { discoverSatellite() }

        syncTestButton.setOnClickListener {
            if (isSatelliteMode()) {
                Toast.makeText(this, "Run the sync test from the MASTER phone", Toast.LENGTH_SHORT).show()
            } else if (!armed) {
                Toast.makeText(this, "Arm the PA first, then run the sync test", Toast.LENGTH_SHORT).show()
            } else if (talking) {
                Toast.makeText(this, "Stop talking before the sync test", Toast.LENGTH_SHORT).show()
            } else {
                sendServiceAction(AudioPassthroughService.ACTION_SYNC_TEST)
                Toast.makeText(this, "3 synchronized pulses sent — adjust master delay until both speakers sound like one click", Toast.LENGTH_LONG).show()
            }
        }

        armButton.setOnClickListener {
            if (isSatelliteMode()) {
                if (satelliteRunning || armed) stopSatellite() else startSatellite()
                return@setOnClickListener
            }

            if (armed) {
                sendServiceAction(AudioPassthroughService.ACTION_DISARM)
            } else {
                if (isWifiMasterMode() && satelliteIpEdit.text.toString().trim().isBlank()) {
                    Toast.makeText(this, "Discover the satellite phone or enter its LAN IP first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (hasRequiredPermissions()) {
                    armPa()
                } else {
                    pendingArm = true
                    requestRequiredPermissions()
                }
            }
        }

        talkButton.setOnClickListener { /* Touch listener below owns the interaction. */ }
        talkButton.setOnTouchListener { v, event ->
            if (isSatelliteMode()) return@setOnTouchListener true
            if (!armed) {
                if (event.action == MotionEvent.ACTION_UP) {
                    Toast.makeText(this, "Arm the PA first", Toast.LENGTH_SHORT).show()
                    v.performClick()
                }
                return@setOnTouchListener true
            }
            if (latchSwitch.isChecked) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (talking) stopTalking() else startTalking()
                    v.performClick()
                }
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startTalking()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopTalking()
                        v.performClick()
                    }
                }
            }
            true
        }

        latchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && talking) stopTalking()
            renderTalkButton()
        }
        duckSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        highPassSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        voiceEnhanceSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        noiseGateSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }

        gainSeek.setOnSeekBarChangeListener(simpleSeekListener {
            updateGainLabel()
            pushConfigIfArmed()
        })
        localDelaySeek.setOnSeekBarChangeListener(simpleSeekListener {
            updateDelayLabels()
            pushConfigIfArmed()
        })
        satelliteBufferSeek.setOnSeekBarChangeListener(simpleSeekListener {
            updateDelayLabels()
            if (satelliteRunning) startSatellite()
        })
        musicVolumeSeek.setOnSeekBarChangeListener(simpleSeekListener {
            updateMusicVolumeLabel()
            pushConfigIfArmed()
        })

        inputSpinner.onItemSelectedListener = SimpleItemSelectedListener { pushConfigIfArmed() }
        outputSpinner.onItemSelectedListener = SimpleItemSelectedListener { pushConfigIfArmed() }
        multiOutputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressModeCallback) return
                if (armed || satelliteRunning) return
                if (position == MODE_LE_SHARE || position == MODE_WIFI_MASTER) {
                    suppressModeCallback = true
                    outputSpinner.setSelection(0, false)
                    suppressModeCallback = false
                }
                updateModeControls()
                queryCurrentMode()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        chooseMusicButton.setOnClickListener { choosePlaylist() }
        playMusicButton.setOnClickListener { playInternalMusic() }
        pauseMusicButton.setOnClickListener { if (armed && !isSatelliteMode()) sendServiceAction(AudioPassthroughService.ACTION_MUSIC_PAUSE) }
        nextMusicButton.setOnClickListener { if (armed && !isSatelliteMode()) sendServiceAction(AudioPassthroughService.ACTION_MUSIC_NEXT) }
        stopMusicButton.setOnClickListener { if (armed && !isSatelliteMode()) sendServiceAction(AudioPassthroughService.ACTION_MUSIC_STOP) }
    }

    private fun simpleSeekListener(onChanged: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChanged() }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun armPa() {
        val intent = Intent(this, AudioPassthroughService::class.java).apply {
            action = AudioPassthroughService.ACTION_ARM
            this@MainActivity.putConfig(this)
        }
        startForegroundService(intent)
    }

    private fun startSatellite() {
        val intent = Intent(this, NetworkAudioReceiverService::class.java).apply {
            action = NetworkAudioReceiverService.ACTION_START
            putExtra(NetworkAudioReceiverService.EXTRA_BUFFER_MS, satelliteBufferMs())
        }
        startForegroundService(intent)
        satelliteRunning = true
        armed = true
        renderState("SATELLITE STARTING…", "Waiting for WeddingPA master on ${localIpv4()}:${NetworkPcmSender.DEFAULT_PORT}", 0)
    }

    private fun stopSatellite() {
        startService(Intent(this, NetworkAudioReceiverService::class.java).setAction(NetworkAudioReceiverService.ACTION_STOP))
        satelliteRunning = false
        armed = false
        renderState("SATELLITE STOPPED", "Select START SATELLITE when speaker #2 is connected to this phone.", 0)
    }

    private fun queryCurrentMode() {
        if (isSatelliteMode()) {
            startService(Intent(this, NetworkAudioReceiverService::class.java).setAction(NetworkAudioReceiverService.ACTION_QUERY))
        } else {
            sendServiceAction(AudioPassthroughService.ACTION_QUERY)
        }
    }

    private fun startTalking() {
        if (!armed || talking || isSatelliteMode()) return
        talking = true
        sendServiceAction(AudioPassthroughService.ACTION_TALK_START)
        renderTalkButton()
    }

    private fun stopTalking() {
        if (!talking) return
        talking = false
        sendServiceAction(AudioPassthroughService.ACTION_TALK_STOP)
        renderTalkButton()
    }

    private fun pushConfigIfArmed() {
        if (!armed || isSatelliteMode()) return
        startService(Intent(this, AudioPassthroughService::class.java).apply {
            action = AudioPassthroughService.ACTION_CONFIG
            this@MainActivity.putConfig(this)
        })
    }

    private fun putConfig(intent: Intent) {
        intent.putExtra(AudioPassthroughService.EXTRA_INPUT_ID, selectedInputId())
        intent.putExtra(
            AudioPassthroughService.EXTRA_OUTPUT_ID,
            if (isLeShareMode() || isWifiMasterMode()) AudioPassthroughService.DEVICE_AUTO else selectedOutputId()
        )
        intent.putExtra(AudioPassthroughService.EXTRA_PREAMP_DB, currentPreampDb())
        intent.putExtra(AudioPassthroughService.EXTRA_DUCK, duckSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_HIGH_PASS, highPassSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_VOICE_ENHANCE, voiceEnhanceSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_NOISE_GATE, noiseGateSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_MULTI_OUTPUT_MODE, selectedAudioServiceMode())
        intent.putExtra(AudioPassthroughService.EXTRA_NETWORK_HOST, satelliteIpEdit.text.toString().trim())
        intent.putExtra(AudioPassthroughService.EXTRA_LOCAL_DELAY_MS, localDelayMs())
        intent.putExtra(AudioPassthroughService.EXTRA_MUSIC_VOLUME, musicVolume())
        intent.putExtra(AudioPassthroughService.EXTRA_MUSIC_DUCK_LEVEL, 0.28f)
    }

    private fun sendServiceAction(actionName: String) {
        startService(Intent(this, AudioPassthroughService::class.java).apply { action = actionName })
    }

    private fun choosePlaylist() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        startActivityForResult(intent, REQUEST_MUSIC)
    }

    @Deprecated("Deprecated in Android API; retained for dependency-free minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MUSIC || resultCode != RESULT_OK || data == null) return
        val selected = mutableListOf<Uri>()
        val clip: ClipData? = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) selected += clip.getItemAt(i).uri
        } else {
            data.data?.let { selected += it }
        }
        if (selected.isEmpty()) return
        musicUris.clear()
        selected.distinct().forEach { uri ->
            musicUris += uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        }
        updatePlaylistInfo()
    }

    private fun playInternalMusic() {
        if (isSatelliteMode()) {
            Toast.makeText(this, "Music is controlled by the MASTER phone", Toast.LENGTH_SHORT).show()
            return
        }
        if (!armed) {
            Toast.makeText(this, "Arm WeddingPA first", Toast.LENGTH_SHORT).show()
            return
        }
        if (musicUris.isEmpty()) {
            Toast.makeText(this, "Choose one or more audio files first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, AudioPassthroughService::class.java).apply {
            action = AudioPassthroughService.ACTION_MUSIC_PLAY
            putStringArrayListExtra(AudioPassthroughService.EXTRA_MUSIC_URIS, ArrayList(musicUris.map { it.toString() }))
        }
        startService(intent)
    }

    private fun updatePlaylistInfo() {
        musicInfoText.text = if (musicUris.isEmpty()) {
            "No internal playlist selected. For guaranteed Wi-Fi dual-speaker music, choose local audio files here."
        } else {
            val names = musicUris.take(3).map { displayName(it) }
            buildString {
                append(musicUris.size).append(if (musicUris.size == 1) " track selected" else " tracks selected")
                append("\n").append(names.joinToString(" • "))
                if (musicUris.size > 3) append(" • +").append(musicUris.size - 3).append(" more")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "Audio"
        } catch (_: Throwable) { uri.lastPathSegment ?: "Audio" }
    }

    private fun discoverSatellite() {
        if (discovering) return
        discovering = true
        discoverSatelliteButton.isEnabled = false
        networkInfoText.text = "Searching the local network for WeddingPA satellite…"

        Thread({
            var foundIp: String? = null
            var foundName: String? = null
            var error: String? = null
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 250
                    val bytes = NetworkPcmSender.DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
                    val targets = linkedSetOf<InetAddress>()
                    targets += InetAddress.getByName("255.255.255.255")
                    try {
                        Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                            ni.interfaceAddresses.mapNotNullTo(targets) { it.broadcast }
                        }
                    } catch (_: Throwable) {}
                    targets.forEach { target ->
                        try { socket.send(DatagramPacket(bytes, bytes.size, target, NetworkPcmSender.DEFAULT_PORT)) } catch (_: Throwable) {}
                    }

                    val deadline = System.currentTimeMillis() + 1800
                    val reply = ByteArray(512)
                    while (System.currentTimeMillis() < deadline && foundIp == null) {
                        try {
                            val packet = DatagramPacket(reply, reply.size)
                            socket.receive(packet)
                            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            if (text.startsWith(NetworkPcmSender.DISCOVERY_RESPONSE_PREFIX)) {
                                val tail = text.removePrefix(NetworkPcmSender.DISCOVERY_RESPONSE_PREFIX)
                                foundName = tail.substringBefore('|').ifBlank { "Android satellite" }
                                foundIp = packet.address.hostAddress
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            runOnUiThread {
                discovering = false
                discoverSatelliteButton.isEnabled = isWifiMasterMode() && !armed
                if (foundIp != null) {
                    satelliteIpEdit.setText(foundIp)
                    networkInfoText.text = "✓ Found ${foundName ?: "satellite"} at $foundIp:${NetworkPcmSender.DEFAULT_PORT}"
                } else {
                    networkInfoText.text = if (error != null) {
                        "Discovery failed: $error\nYou can enter the satellite phone's LAN IP manually."
                    } else {
                        "No satellite answered. Start SATELLITE mode on phone #2 and keep both phones on the same Wi-Fi; manual IP also works."
                    }
                }
            }
        }, "WeddingPA-Discovery").start()
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

    private fun selectedInputId(): Int = (inputSpinner.selectedItem as? DeviceOption)?.id ?: AudioPassthroughService.DEVICE_AUTO
    private fun selectedOutputId(): Int = (outputSpinner.selectedItem as? DeviceOption)?.id ?: AudioPassthroughService.DEVICE_AUTO
    private fun selectedAudioServiceMode(): Int = when (multiOutputSpinner.selectedItemPosition) {
        MODE_LE_SHARE -> AudioPassthroughService.MULTI_OUTPUT_LE_SHARE
        MODE_WIFI_MASTER -> AudioPassthroughService.MULTI_OUTPUT_WIFI_MASTER
        else -> AudioPassthroughService.MULTI_OUTPUT_SINGLE
    }
    private fun isLeShareMode(): Boolean = multiOutputSpinner.selectedItemPosition == MODE_LE_SHARE
    private fun isWifiMasterMode(): Boolean = multiOutputSpinner.selectedItemPosition == MODE_WIFI_MASTER
    private fun isSatelliteMode(): Boolean = multiOutputSpinner.selectedItemPosition == MODE_WIFI_SATELLITE
    private fun currentPreampDb(): Float = gainSeek.progress.toFloat() - 6f
    private fun localDelayMs(): Int = localDelaySeek.progress
    private fun satelliteBufferMs(): Int = 40 + satelliteBufferSeek.progress
    private fun musicVolume(): Float = musicVolumeSeek.progress / 100f

    private fun updateGainLabel() {
        gainLabel.text = String.format(Locale.US, "Mic preamp: %+.0f dB", currentPreampDb())
    }

    private fun updateDelayLabels() {
        localDelayLabel.text = "Master local speaker delay: ${localDelayMs()} ms"
        satelliteBufferLabel.text = "Satellite jitter buffer: ${satelliteBufferMs()} ms"
    }

    private fun updateMusicVolumeLabel() {
        musicVolumeLabel.text = "Internal music volume: ${musicVolumeSeek.progress}%"
    }

    private fun updateModeControls() {
        val satellite = isSatelliteMode()
        val wifiMaster = isWifiMasterMode()
        val forceSystemRoute = isLeShareMode() || wifiMaster || satellite

        inputSpinner.isEnabled = !satellite && !armed
        inputSpinner.alpha = if (inputSpinner.isEnabled) 1f else 0.55f
        outputSpinner.isEnabled = !forceSystemRoute && !armed
        outputSpinner.alpha = if (outputSpinner.isEnabled) 1f else 0.55f

        talkButton.isEnabled = !satellite
        latchSwitch.isEnabled = !satellite
        gainSeek.isEnabled = !satellite
        highPassSwitch.isEnabled = !satellite
        duckSwitch.isEnabled = !satellite
        voiceEnhanceSwitch.isEnabled = !satellite
        noiseGateSwitch.isEnabled = !satellite

        findViewById<View>(R.id.wifiPanel).visibility = if (wifiMaster || satellite) View.VISIBLE else View.GONE
        findViewById<View>(R.id.musicPanel).visibility = if (satellite) View.GONE else View.VISIBLE
        leCapabilityText.visibility = if (isLeShareMode()) View.VISIBLE else View.GONE

        satelliteIpEdit.isEnabled = wifiMaster && !armed
        discoverSatelliteButton.isEnabled = wifiMaster && !armed && !discovering
        localDelaySeek.isEnabled = wifiMaster && !armed
        satelliteBufferSeek.isEnabled = satellite && !armed

        chooseMusicButton.isEnabled = !satellite
        playMusicButton.isEnabled = !satellite
        pauseMusicButton.isEnabled = !satellite
        nextMusicButton.isEnabled = !satellite
        stopMusicButton.isEnabled = !satellite
        musicVolumeSeek.isEnabled = !satellite

        multiOutputSpinner.isEnabled = !armed && !satelliteRunning
        networkInfoText.text = when {
            wifiMaster -> "MASTER: phone #1 plays locally and sends the same final WeddingPA PCM to phone #2 over Wi-Fi. Default 100 ms local delay matches the satellite buffer; use the pulse test to trim it."
            satellite -> "SATELLITE: connect this phone to speaker #2, keep it on the same Wi-Fi, then tap START SATELLITE. Address: ${localIpv4()}:${NetworkPcmSender.DEFAULT_PORT}"
            isLeShareMode() -> "LE Share: Android owns synchronization. This requires real LE Audio sinks."
            else -> "Single/system route: normal WeddingPA output."
        }
        renderTalkButton()
    }


    private fun restoreSettings() {
        gainSeek.progress = prefs.getInt("preamp_progress", 12).coerceIn(0, 30)
        latchSwitch.isChecked = prefs.getBoolean("latch", false)
        duckSwitch.isChecked = prefs.getBoolean("duck", true)
        highPassSwitch.isChecked = prefs.getBoolean("high_pass", true)
        voiceEnhanceSwitch.isChecked = prefs.getBoolean("voice_enhance", true)
        noiseGateSwitch.isChecked = prefs.getBoolean("noise_gate", true)
        localDelaySeek.progress = prefs.getInt("local_delay", 100).coerceIn(0, 500)
        satelliteBufferSeek.progress = prefs.getInt("satellite_buffer", 60).coerceIn(0, 460)
        musicVolumeSeek.progress = prefs.getInt("music_volume", 100).coerceIn(0, 125)
        satelliteIpEdit.setText(prefs.getString("satellite_ip", "") ?: "")
        multiOutputSpinner.setSelection(
            prefs.getInt("output_mode", MODE_SINGLE).coerceIn(MODE_SINGLE, MODE_WIFI_SATELLITE),
            false
        )
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("preamp_progress", gainSeek.progress)
            .putBoolean("latch", latchSwitch.isChecked)
            .putBoolean("duck", duckSwitch.isChecked)
            .putBoolean("high_pass", highPassSwitch.isChecked)
            .putBoolean("voice_enhance", voiceEnhanceSwitch.isChecked)
            .putBoolean("noise_gate", noiseGateSwitch.isChecked)
            .putInt("local_delay", localDelaySeek.progress)
            .putInt("satellite_buffer", satelliteBufferSeek.progress)
            .putInt("music_volume", musicVolumeSeek.progress)
            .putString("satellite_ip", satelliteIpEdit.text.toString().trim())
            .putInt("output_mode", multiOutputSpinner.selectedItemPosition)
            .apply()
    }

    private fun refreshDevices() {
        val previousInput = selectedInputIdOrAuto()
        val previousOutput = selectedOutputIdOrAuto()

        val inputs = mutableListOf(DeviceOption(AudioPassthroughService.DEVICE_AUTO, "Auto — USB mic first, then phone mic"))
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .sortedWith(compareBy<AudioDeviceInfo> { inputPriority(it.type) }.thenBy { it.id })
            .forEach { inputs += DeviceOption(it.id, "${deviceTypeName(it.type)} — ${safeProductName(it)}") }

        val outputs = mutableListOf(DeviceOption(AudioPassthroughService.DEVICE_AUTO, "System media route — recommended"))
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .sortedWith(compareBy<AudioDeviceInfo> { outputPriority(it.type) }.thenBy { it.id })
            .forEach { outputs += DeviceOption(it.id, "${deviceTypeName(it.type)} — ${safeProductName(it)}") }

        inputOptions = inputs
        outputOptions = outputs
        inputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, inputs)
        outputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, outputs)
        selectById(inputSpinner, inputs, previousInput)
        selectById(outputSpinner, outputs, if (isLeShareMode() || isWifiMasterMode() || isSatelliteMode()) AudioPassthroughService.DEVICE_AUTO else previousOutput)
        updateModeControls()
    }

    private fun refreshLeCapabilities() {
        val text = if (Build.VERSION.SDK_INT < 33) {
            "LE Audio capability: Android 13+ required for capability reporting"
        } else if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            "LE Audio capability: grant Nearby devices/Bluetooth permission to check"
        } else {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null) {
                    "LE Audio capability: no Bluetooth adapter"
                } else {
                    val leAudio = adapter.isLeAudioSupported() == BluetoothStatusCodes.FEATURE_SUPPORTED
                    val broadcastSource = adapter.isLeAudioBroadcastSourceSupported() == BluetoothStatusCodes.FEATURE_SUPPORTED
                    buildString {
                        append("Phone LE Audio: ").append(if (leAudio) "SUPPORTED" else "not reported")
                        append("\nLE Audio broadcast/share source: ").append(if (broadcastSource) "SUPPORTED" else "not reported")
                        if (broadcastSource) append("\n✓ Phone can act as an LE Audio broadcast source when Android exposes Audio sharing for compatible speakers.")
                    }
                }
            } catch (_: SecurityException) {
                "LE Audio capability: Bluetooth permission blocked"
            } catch (t: Throwable) {
                "LE Audio capability check unavailable: ${t.javaClass.simpleName}"
            }
        }
        leCapabilityText.text = text
    }

    private fun selectedInputIdOrAuto(): Int = (inputSpinner.selectedItem as? DeviceOption)?.id ?: AudioPassthroughService.DEVICE_AUTO
    private fun selectedOutputIdOrAuto(): Int = (outputSpinner.selectedItem as? DeviceOption)?.id ?: AudioPassthroughService.DEVICE_AUTO

    private fun selectById(spinner: Spinner, options: List<DeviceOption>, wantedId: Int) {
        val index = options.indexOfFirst { it.id == wantedId }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(index, false)
    }

    private fun inputPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 3
        else -> 4
    }

    private fun outputPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
        else -> 4
    }

    private fun safeProductName(device: AudioDeviceInfo): String {
        return try { device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device ${device.id}" }
        catch (_: SecurityException) { "Device ${device.id}" }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
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

    private fun renderState(headline: String, detail: String, level: Int) {
        statusTitle.text = headline
        statusDetail.text = detail
        levelMeter.progress = level.coerceIn(0, 100)
        statusTitle.setTextColor(
            when {
                talking -> Color.rgb(240, 82, 82)
                armed -> Color.rgb(53, 199, 111)
                else -> Color.rgb(247, 184, 75)
            }
        )
        armButton.text = when {
            isSatelliteMode() && (satelliteRunning || armed) -> "STOP SATELLITE"
            isSatelliteMode() -> "START SATELLITE"
            armed -> "DISARM PA"
            else -> "ARM PA"
        }
        syncTestButton.isEnabled = armed && !talking && !isSatelliteMode()
        multiOutputSpinner.isEnabled = !armed && !satelliteRunning
        updateModeControlsAfterState()
        renderTalkButton()
    }

    private fun updateModeControlsAfterState() {
        val satellite = isSatelliteMode()
        val wifiMaster = isWifiMasterMode()
        inputSpinner.isEnabled = !satellite && !armed
        outputSpinner.isEnabled = !satellite && !isLeShareMode() && !wifiMaster && !armed
        satelliteIpEdit.isEnabled = wifiMaster && !armed
        discoverSatelliteButton.isEnabled = wifiMaster && !armed && !discovering
        localDelaySeek.isEnabled = wifiMaster && !armed
        satelliteBufferSeek.isEnabled = satellite && !armed
        gainSeek.isEnabled = !satellite
        highPassSwitch.isEnabled = !satellite
        duckSwitch.isEnabled = !satellite
        voiceEnhanceSwitch.isEnabled = !satellite
        noiseGateSwitch.isEnabled = !satellite
        findViewById<View>(R.id.wifiPanel).visibility = if (wifiMaster || satellite) View.VISIBLE else View.GONE
        findViewById<View>(R.id.musicPanel).visibility = if (satellite) View.GONE else View.VISIBLE
        leCapabilityText.visibility = if (isLeShareMode()) View.VISIBLE else View.GONE
    }

    private fun renderTalkButton() {
        talkButton.setBackgroundResource(if (talking) R.drawable.bg_talk_live else R.drawable.bg_talk_idle)
        talkButton.text = when {
            isSatelliteMode() -> "SATELLITE AUDIO"
            talking -> "LIVE — TALK NOW"
            latchSwitch.isChecked -> "TAP TO TALK"
            else -> "HOLD TO TALK"
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        refreshDevices()
        refreshLeCapabilities()
        if (pendingArm) {
            pendingArm = false
            if (hasRequiredPermissions()) armPa()
            else Toast.makeText(this, "Microphone and Bluetooth permissions are required for PA mode.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 7001
        private const val REQUEST_MUSIC = 7002
        private const val MODE_SINGLE = 0
        private const val MODE_LE_SHARE = 1
        private const val MODE_WIFI_MASTER = 2
        private const val MODE_WIFI_SATELLITE = 3
    }
}

private class SimpleItemSelectedListener(private val onSelected: () -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
