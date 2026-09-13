#!/usr/bin/env python3
from pathlib import Path

ROOT = Path('.weddingpa-v040-src')
SERVICE = ROOT / 'app/src/main/kotlin/app/weddingpa/mobile/AudioPassthroughService.kt'
ACTIVITY = ROOT / 'app/src/main/kotlin/app/weddingpa/mobile/MainActivity.kt'
README = ROOT / 'README.md'


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'Patch anchor missing in {path}: {old[:100]!r}')
    path.write_text(text.replace(old, new, 1))


# ---------------- Audio service / voice DSP wiring ----------------
replace_once(
    SERVICE,
    'import java.util.ArrayDeque\n',
    'import java.util.ArrayDeque\nimport java.util.Locale\n'
)

replace_once(
    SERVICE,
    """    @Volatile private var gain = 1.0f
    @Volatile private var duckMusic = true
    @Volatile private var highPass = true
""",
    """    @Volatile private var preampDb = 6.0f
    @Volatile private var duckMusic = true
    @Volatile private var highPass = true
    @Volatile private var voiceEnhance = true
    @Volatile private var noiseGate = true
    @Volatile private var lastGainReductionDb = 0f
    @Volatile private var lastLimiterHits = 0
"""
)

replace_once(
    SERVICE,
    """        gain = intent.getFloatExtra(EXTRA_GAIN, gain).coerceIn(0.25f, 4.0f)
        duckMusic = intent.getBooleanExtra(EXTRA_DUCK, duckMusic)
        highPass = intent.getBooleanExtra(EXTRA_HIGH_PASS, highPass)
""",
    """        preampDb = when {
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
"""
)

replace_once(
    SERVICE,
    """                var micLevel = 0
                if (talking) {
                    val stats = processor.process(micBuffer, read, gain, highPass)
                    micLevel = stats.levelPercent
                } else {
                    java.util.Arrays.fill(micBuffer, 0, read, 0.toShort())
                }
""",
    """                var micLevel = 0
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
"""
)

replace_once(
    SERVICE,
    '                if (talking) append(" • Speech limiter active")\n',
    """                if (talking) {
                    append("\\nVoice: ")
                    append(if (voiceEnhance) "Enhanced" else "Clean")
                    append(" • Preamp ").append(String.format(Locale.US, "%+.0f dB", preampDb))
                    if (voiceEnhance && lastGainReductionDb >= 0.5f) {
                        append(" • GR ").append(String.format(Locale.US, "%.1f dB", lastGainReductionDb))
                    }
                    if (lastLimiterHits > 0) append(" • peak-safe")
                }
"""
)

replace_once(
    SERVICE,
    """        const val EXTRA_GAIN = "gain"
        const val EXTRA_DUCK = "duck"
        const val EXTRA_HIGH_PASS = "high_pass"
""",
    """        // EXTRA_GAIN remains for compatibility with v0.1-v0.3 callers.
        const val EXTRA_GAIN = "gain"
        const val EXTRA_PREAMP_DB = "preamp_db"
        const val EXTRA_DUCK = "duck"
        const val EXTRA_HIGH_PASS = "high_pass"
        const val EXTRA_VOICE_ENHANCE = "voice_enhance"
        const val EXTRA_NOISE_GATE = "noise_gate"
"""
)

# ---------------- Main UI wiring ----------------
replace_once(
    ACTIVITY,
    '    private lateinit var audioManager: AudioManager\n',
    """    private lateinit var audioManager: AudioManager
    private val prefs by lazy { getSharedPreferences("weddingpa_settings", Context.MODE_PRIVATE) }
"""
)

replace_once(
    ACTIVITY,
    """    private lateinit var duckSwitch: Switch
    private lateinit var highPassSwitch: Switch
    private lateinit var gainSeek: SeekBar
""",
    """    private lateinit var duckSwitch: Switch
    private lateinit var highPassSwitch: Switch
    private lateinit var voiceEnhanceSwitch: Switch
    private lateinit var noiseGateSwitch: Switch
    private lateinit var gainSeek: SeekBar
"""
)

replace_once(
    ACTIVITY,
    """        bindViews()
        setupControls()
        setupMultiOutputModes()
        refreshDevices()
""",
    """        bindViews()
        setupMultiOutputModes()
        restoreSettings()
        setupControls()
        refreshDevices()
"""
)

replace_once(
    ACTIVITY,
    """    override fun onStop() {
        if (receiverRegistered) {
""",
    """    override fun onStop() {
        saveSettings()
        if (receiverRegistered) {
"""
)

replace_once(
    ACTIVITY,
    """        duckSwitch = findViewById(R.id.duckSwitch)
        highPassSwitch = findViewById(R.id.highPassSwitch)
        gainSeek = findViewById(R.id.gainSeek)
""",
    """        duckSwitch = findViewById(R.id.duckSwitch)
        highPassSwitch = findViewById(R.id.highPassSwitch)
        voiceEnhanceSwitch = findViewById(R.id.voiceEnhanceSwitch)
        noiseGateSwitch = findViewById(R.id.noiseGateSwitch)
        gainSeek = findViewById(R.id.gainSeek)
"""
)

replace_once(
    ACTIVITY,
    """        duckSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        highPassSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }

        gainSeek.setOnSeekBarChangeListener(simpleSeekListener {
""",
    """        duckSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        highPassSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        voiceEnhanceSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }
        noiseGateSwitch.setOnCheckedChangeListener { _, _ -> pushConfigIfArmed() }

        gainSeek.setOnSeekBarChangeListener(simpleSeekListener {
"""
)

replace_once(
    ACTIVITY,
    """        intent.putExtra(AudioPassthroughService.EXTRA_GAIN, currentGain())
        intent.putExtra(AudioPassthroughService.EXTRA_DUCK, duckSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_HIGH_PASS, highPassSwitch.isChecked)
""",
    """        intent.putExtra(AudioPassthroughService.EXTRA_PREAMP_DB, currentPreampDb())
        intent.putExtra(AudioPassthroughService.EXTRA_DUCK, duckSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_HIGH_PASS, highPassSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_VOICE_ENHANCE, voiceEnhanceSwitch.isChecked)
        intent.putExtra(AudioPassthroughService.EXTRA_NOISE_GATE, noiseGateSwitch.isChecked)
"""
)

replace_once(
    ACTIVITY,
    """    private fun currentGain(): Float = 0.5f + gainSeek.progress / 100f
    private fun localDelayMs(): Int = localDelaySeek.progress
""",
    """    private fun currentPreampDb(): Float = gainSeek.progress.toFloat() - 6f
    private fun localDelayMs(): Int = localDelaySeek.progress
"""
)

replace_once(
    ACTIVITY,
    """    private fun updateGainLabel() {
        gainLabel.text = String.format(Locale.US, "Mic gain: %.2f×", currentGain())
    }
""",
    """    private fun updateGainLabel() {
        gainLabel.text = String.format(Locale.US, "Mic preamp: %+.0f dB", currentPreampDb())
    }
"""
)

replace_once(
    ACTIVITY,
    """        gainSeek.isEnabled = !satellite
        highPassSwitch.isEnabled = !satellite
        duckSwitch.isEnabled = !satellite

        satelliteIpEdit.isEnabled = wifiMaster && !armed
""",
    """        gainSeek.isEnabled = !satellite
        highPassSwitch.isEnabled = !satellite
        duckSwitch.isEnabled = !satellite
        voiceEnhanceSwitch.isEnabled = !satellite
        noiseGateSwitch.isEnabled = !satellite

        findViewById<View>(R.id.wifiPanel).visibility = if (wifiMaster || satellite) View.VISIBLE else View.GONE
        findViewById<View>(R.id.musicPanel).visibility = if (satellite) View.GONE else View.VISIBLE
        leCapabilityText.visibility = if (isLeShareMode()) View.VISIBLE else View.GONE

        satelliteIpEdit.isEnabled = wifiMaster && !armed
"""
)

replace_once(
    ACTIVITY,
    """        localDelaySeek.isEnabled = wifiMaster && !armed
        satelliteBufferSeek.isEnabled = satellite && !armed
    }

    private fun renderTalkButton() {
""",
    """        localDelaySeek.isEnabled = wifiMaster && !armed
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
"""
)

persistence = """
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

"""
replace_once(
    ACTIVITY,
    '    private fun refreshDevices() {\n',
    persistence + '    private fun refreshDevices() {\n'
)

readme = README.read_text()
section = """

## v0.4.0 polish

The live microphone path now uses a speech-oriented preamp chain instead of a raw multiplier plus threshold limiter: 2-pole rumble filtering, a gentle noise-floor expander, dB preamp, presence EQ, soft-knee leveling and a -1 dBFS safety limiter. The UI is reorganized around the event workflow (status → talk/arm → voice → routing), advanced Wi-Fi controls are contextual, and operator settings persist between launches.

v0.3.0 remains the rollback branch; Wi-Fi dual-phone output, LE Audio share mode, the internal PCM music player, sync pulse test and route-fallback microphone safety are retained.
"""
if '## v0.4.0 polish' not in readme:
    README.write_text(readme.rstrip() + section + '\n')

print('WeddingPA v0.4.0 service/UI wiring patched successfully')
