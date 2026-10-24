package com.nova.soundbarkeepalive;

/** Fixed, seamless one-second stereo loop. No files, decoding, network or runtime synthesis loop. */
final class Signal {
    static final int RATE = 48000;
    static final int CHANNELS = 2;
    static final int HZ = 200;
    // Experimental: approximately -60 dBFS peak. Hardware detection is not guaranteed.
    static final int PEAK = 32;

    static short[] samples() {
        short[] pcm = new short[RATE * CHANNELS];
        for (int frame = 0; frame < RATE; frame++) {
            short value = (short) Math.round(PEAK * Math.sin(2 * Math.PI * HZ * frame / RATE));
            pcm[frame * CHANNELS] = value;
            pcm[frame * CHANNELS + 1] = value;
        }
        return pcm;
    }
}
