# Wedding PA v0.3.0

Native Android wedding/portable PA app for a USB-C wireless lav and Bluetooth speakers.

## New in v0.3.0

- **Wi-Fi Dual / MASTER mode**: phone #1 captures the microphone, plays WeddingPA's internal music, creates one final mono PCM mix, plays it locally, and streams that exact mix to phone #2 over the local Wi-Fi network.
- **Wi-Fi Dual / SATELLITE mode**: phone #2 receives the PCM stream and outputs it through its normal Android media route (for example, a second classic-Bluetooth Bose speaker).
- Automatic LAN discovery with manual IP fallback.
- Adjustable **master local delay (0–500 ms)** and **satellite jitter buffer (40–500 ms)**.
- Three-pulse sync test is injected into the final mix, so both master and satellite receive the same test signal.
- Built-in local-file playlist player using Android platform decoders. Music is downmixed to mono for distributed-speaker consistency and mixed with the live microphone before local/network output.
- Internal music automatically ducks to about 28% while TALK is active.
- Existing Single/system-route and LE Audio Share modes retained.

## Why the internal music player exists

Android does not guarantee that a normal app can capture another app's protected playback PCM. Spotify and similar services can block playback capture. Therefore **guaranteed Wi-Fi dual-speaker music + microphone synchronization uses local audio files selected inside WeddingPA**. External apps can still be used in normal Single/LE modes.

## Recommended two-speaker setup

1. Install the same WeddingPA APK on both Android phones.
2. Put both phones on the same Wi-Fi network.
3. Connect phone #1 to Bluetooth speaker #1.
4. Connect phone #2 to Bluetooth speaker #2.
5. On phone #2 choose `Wi-Fi Dual — this phone is SATELLITE` and tap `START SATELLITE`.
6. On phone #1 choose `Wi-Fi Dual — this phone is MASTER` and tap `Discover WeddingPA satellite`.
7. Choose local music files on the master, arm the PA, then press Play.
8. Run the 3-pulse sync test. Start with master delay = 100 ms and satellite buffer = 100 ms. If master speaker is early, increase master delay; if satellite audio crackles from Wi-Fi jitter, increase satellite buffer.

## Notes

- Network audio is PCM16 mono in ~10 ms UDP frames to minimize latency and avoid lossy re-encoding.
- The master networking path is isolated from the realtime audio loop with a bounded sender queue.
- Satellite uses a jitter buffer and inserts silence for packets that are confirmed missing instead of stalling playback.
- Speaker Bluetooth codec latency still exists on each phone, so final calibration is intentionally manual via the sync pulse.
