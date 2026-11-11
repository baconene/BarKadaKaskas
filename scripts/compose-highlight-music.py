# Regenerates public/audio/highlight-travel.mp3 (original music, no licence needed):
#   python3 scripts/compose-highlight-music.py travel.wav && ffmpeg -i travel.wav -b:a 128k -ac 1 public/audio/highlight-travel.mp3
"""Original 'island road trip' loop for day highlights: I-V-vi-IV in G, ukulele-style strums, bass, shaker, light drums, whistle melody."""
import numpy as np, wave, sys
SR = 44100; BPM = 112; BEAT = 60 / BPM; BAR = 4 * BEAT; LENGTH = 31.0
N = int(SR * LENGTH); mix = np.zeros(N); rng = np.random.default_rng(7)
def hz(m): return 440 * 2 ** ((m - 69) / 12)
def pluck(freq, dur, amp=0.25, bright=0.996):
    n = int(SR * dur); period = max(2, int(SR / freq)); buf = rng.uniform(-1, 1, period); out = np.zeros(n)
    for i in range(n):
        out[i] = buf[i % period]; buf[i % period] = bright * 0.5 * (buf[i % period] + buf[(i + 1) % period])
    return amp * out
def add(sig, t):
    s = int(t * SR); e = min(N, s + len(sig))
    if s < N: mix[s:e] += sig[:e - s]
chords = [[67, 71, 74, 79], [62, 66, 69, 74], [64, 67, 71, 76], [60, 64, 67, 72]]  # G D Em C
roots = [43, 38, 40, 36]
strum = [(0, 1.0, 1), (1, .7, 1), (1.5, .5, -1), (2.5, .6, -1), (3, .8, 1), (3.5, .5, -1)]  # beat, vel, dir
bars = int(LENGTH / BAR) + 1
for b in range(bars):
    t0 = b * BAR; ch = chords[b % 4]
    for beat, vel, d in strum:
        notes = ch if d > 0 else ch[::-1]
        for k, m in enumerate(notes):
            add(pluck(hz(m), 1.2, 0.09 * vel), t0 + beat * BEAT + k * 0.012)
    for beat in (0, 2):   # bass
        n = int(SR * BEAT * 1.8); tt = np.arange(n) / SR; f = hz(roots[b % 4])
        add(0.22 * np.sin(2 * np.pi * f * tt) * np.exp(-tt * 2.2), t0 + beat * BEAT)
    for beat in (0, 2):   # soft kick
        n = int(SR * 0.25); tt = np.arange(n) / SR
        add(0.35 * np.sin(2 * np.pi * (55 + 90 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 14), t0 + beat * BEAT)
    for beat in (1, 3):   # light clap
        n = int(SR * 0.12); noise = rng.uniform(-1, 1, n); env = np.exp(-np.arange(n) / SR * 35)
        add(0.06 * noise * env, t0 + beat * BEAT)
    for e in range(8):    # shaker 8ths
        n = int(SR * 0.06); noise = np.diff(rng.uniform(-1, 1, n + 1)); env = np.exp(-np.arange(n) / SR * 60)
        add((0.035 if e % 2 else 0.022) * noise * env, t0 + e * BEAT / 2)
# whistle melody from bar 4 (G major pentatonic), gentle vibrato
melody = [(79, 1), (81, .5), (83, .5), (81, 1), (79, 1), (76, 1.5), (74, .5), (76, 1), (79, 1), (81, 2), (83, 1), (81, .5), (79, .5), (76, 2), (74, 2),
          (79, 1), (83, 1), (86, 1.5), (83, .5), (81, 1), (79, 1), (76, 2), (79, 4)]
t = 4 * BAR
for _ in range(2):
    for m, beats in melody:
        dur = beats * BEAT; n = int(SR * dur); tt = np.arange(n) / SR
        f = hz(m) * (1 + 0.006 * np.sin(2 * np.pi * 5.5 * tt))
        env = np.minimum(1, tt / 0.04) * np.minimum(1, (dur - tt) / 0.08)
        add(0.07 * np.sin(2 * np.pi * np.cumsum(f) / SR) * env, t); t += dur
        if t > LENGTH - 2 * BAR: break
fade_in = np.minimum(1, np.arange(N) / (SR * 0.6)); fade_out = np.minimum(1, (N - np.arange(N)) / (SR * 2.5))
mix *= fade_in * fade_out; mix /= np.max(np.abs(mix)) * 1.12
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype('<i2').tobytes())
