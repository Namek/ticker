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
| Low bell | [bec low bell solo.wav](https://freesound.org/people/HMTSCCSound/sounds/554655/), `low-bell-original.mp3` | HMTSCCSound | CC0 1.0 / public domain |
| Wooden temple block | [Ashboy34 - Temple block - (wooden room)](https://freesound.org/people/Sadiquecat/sounds/742277/), `temple-block-original.mp3` | Sadiquecat, based on a recording by ashboy34 | CC0 1.0 / public domain |
| Tingsha cymbals | [Tingsha Cymbal](https://freesound.org/people/steffcaffrey/sounds/435074/), `tingsha-original.mp3` | steffcaffrey | CC0 1.0 / public domain |

Sources and license statements checked on 2026-10-05; the electric wall-clock recording and its license were checked on 2026-10-06. BigSoundBank and Freesound identify the selected recordings as CC0: [CC0 1.0 legal text](https://creativecommons.org/publicdomain/zero/1.0/legalcode). The singing-bowl page also grants unconditional use if a worldwide public-domain dedication is not legally possible.

The Low bell recording and its CC0 license were checked on 2026-10-15. It is a separate recording of the lowest bell in the Bethesda Church tower in Saratoga Springs, struck once softly by rope. The app takes a 3-second excerpt starting at 0.475 seconds, with a 1 ms attack fade and 900 ms tail fade. Its pitch is unchanged; it peaks at 75% to prevent clipping when tails overlap. The original WAV requires a Freesound login; the included file is its public high-quality MP3 preview.

Wooden temple block and Tingsha cymbals were added in version 1.6.5; their CC0 license statements were checked on 2026-10-15. The included sources are Freesound's public high-quality MP3 previews. The temple-block source is Sadiquecat's reverberated and compressed edit of [ashboy34's wooden-block recording](https://freesound.org/people/ashboy34/sounds/544881/). The app retains the excerpt from 0.001 to 0.301 seconds with a 100 ms tail fade, removing the quiet remainder of the 1.212-second source. Tingsha retains the excerpt from 0.010 to 2.510 seconds with a 400 ms tail fade, removing the quiet remainder of the 8.184-second source. Both have a 1 ms attack fade and unchanged pitch. Tingsha receives the same gentle dynamic compression as Soft bell, with a 70% peak to leave headroom for overlapping tails.

Original download URLs:

- Clock: <https://bigsoundbank.com/UPLOAD/bwf-en/1567.wav>
- Electric wall clock: <https://cdn.freesound.org/previews/639/639497_2397507-hq.mp3>
- Gong: <https://cdn.freesound.org/previews/245/245876_40734-hq.mp3>
- Low bell: <https://cdn.freesound.org/previews/554/554655_7990256-hq.mp3>
- Bell: <https://bigsoundbank.com/UPLOAD/mp3/0292.mp3>
- Bowl: <https://upload.wikimedia.org/wikipedia/commons/2/25/SingingBowl1.ogg>
- Wooden temple block: <https://cdn.freesound.org/previews/742/742277_5287430-hq.mp3>
- Tingsha cymbals: <https://cdn.freesound.org/previews/435/435074_1844073-hq.mp3>

The gong uses Freesound's publicly available high-quality MP3 preview of thma's studio recording of a medium-sized gong tuned to F sharp. It replaces the previous BigSoundBank gong in version 1.3.1. The original WAV requires a Freesound login and is not included.

The clock uses the original 48 kHz / 24-bit WAV recording of a real grandfather clock's pendulum, captured with a SoundDevices MixPre-3 and Rode NT5. Version 1.4.1 replaces the previous compressed clock sample with a single tick from 1.032 to 1.332 seconds of this recording. Its recorded attack and body are retained without dynamic compression, equalization, or pitch changes.

Electric wall clock, added in version 1.5.1, uses Freesound's public high-quality MP3 preview of itinerantmonk108's quartz wall-clock recording. The original WAV requires a Freesound login and is not included. The app keeps one tick from 8.315 to 8.465 seconds, with a 1 ms edge fade and 40 ms tail fade, normalized to 94% peak. No dynamic compression, equalization, pitch change, or synthesis is applied.

Run `python3 scripts/prepare_sounds.py` with FFmpeg installed to reproduce the app samples. Processing converts to mono 44.1 kHz / 16-bit PCM, trims to one strike, and fades the tail. The gong peaks at 45% of full scale, leaving about 7 dB of headroom without compression. Low bell peaks at 75% and Tingsha at 70%; the other sounds peak at 94%. Soft bell and Tingsha receive gentle dynamic compression. Processing does not synthesize tones or change pitch. Clock tick and Wooden temple block are 300 ms, Electric wall clock is 150 ms, Gong, Singing bowl, and Low bell are 3 seconds, Soft bell is 2 seconds, and Tingsha cymbals are 2.5 seconds. The build uses the checked-in WAVs and does not require FFmpeg.
