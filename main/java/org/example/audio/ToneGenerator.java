package org.example.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

/**
 * Synthesizes and plays short sine-wave beeps entirely in memory - no audio
 * asset files exist anywhere in this project (PieceSprites is the only
 * other asset pipeline, and it's purely visual), so rather than source or
 * vendor binary sound files, this generates PCM samples on the fly from a
 * frequency/duration/volume. Every tone this project needs (see
 * SoundPlayer) is just a different combination of those three numbers, so
 * one small generator covers all of them.
 */
final class ToneGenerator {

    private static final float SAMPLE_RATE = 44100f;

    private ToneGenerator() {
    }

    /**
     * Plays a single sine-wave tone. Starts playback and returns almost
     * immediately - the Clip plays on its own line - and never throws: a
     * machine with no audio device at all (e.g. a headless CI/build
     * sandbox) silently produces no sound instead of failing the caller,
     * since sound is always a non-essential UI nicety layered on top of
     * gameplay that must keep working either way.
     */
    static void play(double frequencyHz, int durationMillis, double volume) {
        try {
            byte[] samples = renderSineWave(frequencyHz, durationMillis, volume);
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            Clip clip = AudioSystem.getClip();
            clip.open(format, samples, 0, samples.length);
            clip.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            // No usable audio output on this machine - sound is best-effort.
        }
    }

    private static byte[] renderSineWave(double frequencyHz, int durationMillis, double volume) {
        int sampleCount = (int) (SAMPLE_RATE * durationMillis / 1000.0);
        byte[] samples = new byte[sampleCount * 2]; // 16-bit mono -> 2 bytes/sample

        // A short fade-in/out avoids the audible "click" a hard-edged sine
        // burst produces at sample 0 and sample N - the waveform starts and
        // ends near zero amplitude instead of at an arbitrary phase.
        int fadeSamples = Math.max(1, Math.min(sampleCount / 4, (int) (SAMPLE_RATE * 0.01)));

        for (int i = 0; i < sampleCount; i++) {
            double angle = 2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE;
            double envelope = 1.0;
            if (i < fadeSamples) {
                envelope = i / (double) fadeSamples;
            } else if (i > sampleCount - fadeSamples) {
                envelope = (sampleCount - i) / (double) fadeSamples;
            }
            short value = (short) (Math.sin(angle) * volume * envelope * Short.MAX_VALUE);
            samples[i * 2] = (byte) (value & 0xFF);
            samples[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return samples;
    }
}
