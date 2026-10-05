package com.johnboniello.runwalktimer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Synthesised cue sounds, matching the tones of the web version. */
final class Sounds {
    static final int TICK = 0, GO = 1, EAT_START = 2, EAT_STOP = 3, DRINK = 4;

    private static final int RATE = 44100;
    private static final int SQUARE = 0, TRIANGLE = 1, SINE = 2;

    private final AudioTrack[] tracks = new AudioTrack[5];

    Sounds(AudioAttributes attrs) {
        tracks[TICK] = build(attrs, new double[][]{{880, 0.12, 0, SQUARE, 0.2}});
        tracks[GO] = build(attrs, new double[][]{{1320, 0.6, 0, SQUARE, 0.2}});
        tracks[EAT_START] = build(attrs, new double[][]{
                {660, 0.18, 0.25, TRIANGLE, 0.45}, {880, 0.18, 0.45, TRIANGLE, 0.45}, {1100, 0.3, 0.65, TRIANGLE, 0.45}});
        tracks[EAT_STOP] = build(attrs, new double[][]{
                {1100, 0.18, 0.25, TRIANGLE, 0.45}, {880, 0.18, 0.45, TRIANGLE, 0.45}, {660, 0.3, 0.65, TRIANGLE, 0.45}});
        tracks[DRINK] = build(attrs, new double[][]{
                {1560, 0.09, 0.25, SINE, 0.5}, {1560, 0.09, 0.4, SINE, 0.5}, {1560, 0.09, 0.55, SINE, 0.5}});
    }

    void play(int which) {
        AudioTrack t = tracks[which];
        if (t == null) return;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (IllegalStateException ignored) {
        }
    }

    void release() {
        for (AudioTrack t : tracks) if (t != null) t.release();
    }

    /** notes: {frequency Hz, duration s, start offset s, waveform, gain} */
    private static AudioTrack build(AudioAttributes attrs, double[][] notes) {
        double end = 0;
        for (double[] n : notes) end = Math.max(end, n[1] + n[2]);
        int len = (int) ((end + 0.05) * RATE);
        short[] pcm = new short[len];
        for (double[] n : notes) {
            double f = n[0], dur = n[1];
            int start = (int) (n[2] * RATE), count = (int) (dur * RATE);
            int wave = (int) n[3];
            double gain = n[4];
            for (int i = 0; i < count && start + i < len; i++) {
                double t = (double) i / RATE;
                double phase = (f * t) % 1.0;
                double v;
                if (wave == SQUARE) v = phase < 0.5 ? 1 : -1;
                else if (wave == TRIANGLE) v = 4 * Math.abs(phase - 0.5) - 1;
                else v = Math.sin(2 * Math.PI * phase);
                // 10 ms attack, 30 ms release, so the tones do not click
                double env = Math.min(1, Math.min(t / 0.01, (dur - t) / 0.03));
                int s = pcm[start + i] + (int) (v * env * gain * Short.MAX_VALUE);
                pcm[start + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            }
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(len * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(pcm, 0, len);
        return track;
    }
}
