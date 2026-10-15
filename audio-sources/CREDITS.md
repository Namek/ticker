# Recorded sounds

The singing-bowl excerpt starts 175 ms into its source recording, placing the strong strike near the start of the sample. Its duration remains 3 seconds. This removes the quiet intro that made the strike appear late relative to the interval flash.

Moments' sounds are edits of real acoustic recordings. The source audio files are included here; the app bundles only the edited WAVs and does not download audio.

| App sound | Original recording | Creator | License |
| --- | --- | --- | --- |
| Clock tick | [Grandfather clock, ticking](https://bigsoundbank.com/grandfather-clock-ticking-s1567.html), `clock-original.wav` | Joseph SARDIN | CC0 1.0 / public domain |
| Electric wall clock | [Wall clock tick.wav](https://freesound.org/people/itinerantmonk108/sounds/639497/), `electric-clock-original.mp3` | itinerantmonk108 | CC0 1.0 / public domain |
| Gong | [gong hit soft close .wav](https://freesound.org/people/thma/sounds/245876/), `gong-original.mp3` | thma | CC0 1.0 / public domain |
| Singing bowl | [SingingBowl1.ogg](https://commons.wikimedia.org/wiki/File:SingingBowl1.ogg), `bowl-original.ogg` | BambooBeast | Released into the public domain worldwide by the creator |
| Soft bell | [Small bell 1](https://bigsoundbank.com/small-bell-1-s0292.html), `bell-original.mp3` | Joseph SARDIN | CC0 1.0 / public domain |

Sources and license statements checked on 2026-10-05; the electric wall-clock recording and its license were checked on 2026-10-06. BigSoundBank and Freesound identify the selected recordings as CC0: [CC0 1.0 legal text](https://creativecommons.org/publicdomain/zero/1.0/legalcode). The singing-bowl page also grants unconditional use if a worldwide public-domain dedication is not legally possible.

Original download URLs:

- Clock: <https://bigsoundbank.com/UPLOAD/bwf-en/1567.wav>
- Electric wall clock: <https://cdn.freesound.org/previews/639/639497_2397507-hq.mp3>
- Gong: <https://cdn.freesound.org/previews/245/245876_40734-hq.mp3>
- Bell: <https://bigsoundbank.com/UPLOAD/mp3/0292.mp3>
- Bowl: <https://upload.wikimedia.org/wikipedia/commons/2/25/SingingBowl1.ogg>

The gong uses Freesound's publicly available high-quality MP3 preview of thma's studio recording of a medium-sized gong tuned to F sharp. It replaces the previous BigSoundBank gong in version 1.3.1. The original WAV requires a Freesound login and is not included.

The clock uses the original 48 kHz / 24-bit WAV recording of a real grandfather clock's pendulum, captured with a SoundDevices MixPre-3 and Rode NT5. Version 1.4.1 replaces the previous compressed clock sample with a single tick from 1.032 to 1.332 seconds of this recording. Its recorded attack and body are retained without dynamic compression, equalization, or pitch changes.

Electric wall clock, added in version 1.5.1, uses Freesound's public high-quality MP3 preview of itinerantmonk108's quartz wall-clock recording. The original WAV requires a Freesound login and is not included. The app keeps one tick from 8.315 to 8.465 seconds, with a 1 ms edge fade and 40 ms tail fade, normalized to 94% peak. No dynamic compression, equalization, pitch change, or synthesis is applied.

Run `python3 scripts/prepare_sounds.py` with FFmpeg installed to reproduce the app samples. Processing converts to mono 44.1 kHz / 16-bit PCM, trims to one strike, and fades the tail. The gong peaks at 45% of full scale, leaving about 7 dB of headroom without compression; the other sounds peak at 94%. Only the bell receives gentle dynamic compression. Processing does not synthesize tones or change pitch. Clock tick is 300 ms, Electric wall clock is 150 ms, gong and bowl are 3 seconds, and bell is 2 seconds. The build uses the checked-in WAVs and does not require FFmpeg.
