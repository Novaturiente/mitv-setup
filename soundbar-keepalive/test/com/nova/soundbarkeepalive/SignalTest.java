package com.nova.soundbarkeepalive;

public final class SignalTest {
    public static void main(String[] args) {
        short[] pcm = Signal.samples();
        assert pcm.length == Signal.RATE * Signal.CHANNELS;
        long sum = 0;
        int peak = 0, risingCrossings = 0;
        for (int i = 0; i < Signal.RATE; i++) {
            int v = pcm[i * 2];
            assert v == pcm[i * 2 + 1] : "Stereo mismatch";
            assert Math.abs(v) <= Signal.PEAK : "Amplitude out of bounds";
            if (i > 0 && pcm[(i - 1) * 2] <= 0 && v > 0) risingCrossings++;
            sum += v;
            peak = Math.max(peak, Math.abs(v));
        }
        assert sum == 0 : "DC offset";
        assert peak == Signal.PEAK && peak > 0 : "Silent or clipped signal";
        assert risingCrossings == Signal.HZ : "Wrong frequency";
        assert Math.abs(pcm[0] - pcm[pcm.length - 2]) <= 1 : "Loop seam discontinuity";
        assert pcm.length * 2 == 192000 : "Unexpected buffer allocation";
        System.out.println("PASS: stereo, " + Signal.HZ + " Hz, amplitude, zero DC, loop seam, 192 KB PCM buffer");
    }
}
