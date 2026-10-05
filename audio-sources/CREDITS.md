# Recorded sounds

Tick's sounds are edits of real acoustic recordings. The originals are included here; the app bundles only the edited WAVs and does not download audio.

| App sound | Original recording | Creator | License |
| --- | --- | --- | --- |
| Clock tick | [Clock](https://bigsoundbank.com/clock-s0007.html), `clock-original.mp3` | Joseph SARDIN | CC0 1.0 / public domain |
| Gong | [Gong (Sweet)](https://bigsoundbank.com/gong-sweet-s1482.html), `gong-original.mp3` | Joseph SARDIN | CC0 1.0 / public domain |
| Singing bowl | [SingingBowl1.ogg](https://commons.wikimedia.org/wiki/File:SingingBowl1.ogg), `bowl-original.ogg` | BambooBeast | Released into the public domain worldwide by the creator |
| Soft bell | [Small bell 1](https://bigsoundbank.com/small-bell-1-s0292.html), `bell-original.mp3` | Joseph SARDIN | CC0 1.0 / public domain |

Sources and license statements checked on 2026-10-05. BigSoundBank identifies these recordings as CC0: [CC0 1.0 legal text](https://creativecommons.org/publicdomain/zero/1.0/legalcode). The singing-bowl page also grants unconditional use if a worldwide public-domain dedication is not legally possible.

Original download URLs:

- Clock: <https://bigsoundbank.com/UPLOAD/mp3/0007.mp3>
- Gong: <https://bigsoundbank.com/UPLOAD/mp3/1482.mp3>
- Bell: <https://bigsoundbank.com/UPLOAD/mp3/0292.mp3>
- Bowl: <https://upload.wikimedia.org/wikipedia/commons/2/25/SingingBowl1.ogg>

Run `python3 scripts/prepare_sounds.py` with FFmpeg installed to reproduce the app samples. Processing converts to mono 44.1 kHz / 16-bit PCM, trims to one strike, fades the tail, and normalizes each peak to 94% of full scale. Clock and bell also receive gentle dynamic compression to lift their body relative to isolated peaks. Processing does not synthesize tones or change pitch. Clock is 300 ms, gong and bowl are 3 seconds, and bell is 2 seconds. The build uses the checked-in WAVs and does not require FFmpeg.
