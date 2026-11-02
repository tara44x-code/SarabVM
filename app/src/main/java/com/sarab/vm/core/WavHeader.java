package com.sarab.vm.core;

/** The 44-byte header QEMU's "wav" sound backend writes first. Pure Java. */
public final class WavHeader {
    public final int channels;
    public final int sampleRate;
    public final int bits;

    private WavHeader(int channels, int sampleRate, int bits) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bits = bits;
    }

    /** @return null when the bytes are not a PCM WAV header */
    public static WavHeader parse(byte[] h) {
        if (h == null || h.length < 44) return null;
        if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return null;
        if (h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') return null;
        int channels = (h[22] & 0xFF) | (h[23] & 0xFF) << 8;
        int rate = (h[24] & 0xFF) | (h[25] & 0xFF) << 8 | (h[26] & 0xFF) << 16 | (h[27] & 0xFF) << 24;
        int bits = (h[34] & 0xFF) | (h[35] & 0xFF) << 8;
        if (channels < 1 || channels > 2 || rate < 4000 || rate > 192000 || (bits != 8 && bits != 16)) return null;
        return new WavHeader(channels, rate, bits);
    }
}
