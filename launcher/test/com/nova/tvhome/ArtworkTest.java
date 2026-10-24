package com.nova.tvhome;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class ArtworkTest {
    public static void main(String[] args) throws Exception {
        if (!ArtworkTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run ArtworkTest with java -ea");
        }
        sampling();
        encodedLimit();
        interruptedRead();
        schemes();
        System.out.println("ArtworkTest passed");
    }

    private static void sampling() {
        assert Artwork.sampleSize(512, 512, 1, 1) == 1;
        assert Artwork.sampleSize(513, 512, 1, 1) == 2;
        assert Artwork.sampleSize(2048, 2048, 1024, 1024) == 1;
        assert Artwork.sampleSize(2049, 1, Integer.MAX_VALUE, Integer.MAX_VALUE) == 2;
        assert Artwork.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1) == 4194304;
        assert Artwork.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE) == 1048576;
        int[] sizes = {1, 2, 511, 512, 513, 2048, 2049, 65535, Integer.MAX_VALUE};
        int[][] targets = {{1, 1}, {320, 180}, {1024, 1024},
                {Integer.MAX_VALUE, 1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
        for (int width : sizes) {
            for (int height : sizes) {
                for (int[] target : targets) {
                    int sample = Artwork.sampleSize(width, height, target[0], target[1]);
                    assert sample > 0 && (sample & (sample - 1)) == 0;
                    assert fits(width, height, sample, target[0], target[1]);
                    assert sample == 1 || !fits(width, height, sample / 2, target[0], target[1]);
                }
            }
        }
        try {
            Artwork.sampleSize(0, 10, 1, 1);
            throw new AssertionError("Zero source dimension accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            Artwork.sampleSize(10, -1, 1, 1);
            throw new AssertionError("Negative source dimension accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            Artwork.sampleSize(10, 10, 0, 1);
            throw new AssertionError("Zero target dimension accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static boolean fits(int width, int height, int sample, int targetWidth, int targetHeight) {
        long decodedWidth = ((long) width + sample - 1) / sample;
        long decodedHeight = ((long) height + sample - 1) / sample;
        java.math.BigInteger limit = java.math.BigInteger.valueOf(targetWidth)
                .multiply(java.math.BigInteger.valueOf(targetHeight)).multiply(java.math.BigInteger.valueOf(4))
                .max(java.math.BigInteger.valueOf(262144));
        return decodedWidth <= 2048 && decodedHeight <= 2048
                && java.math.BigInteger.valueOf(decodedWidth * decodedHeight).compareTo(limit) <= 0;
    }

    private static void encodedLimit() throws IOException {
        int limit = 2 * 1024 * 1024;
        byte[] exact = new byte[limit];
        Arrays.fill(exact, (byte) 73);
        assert Arrays.equals(exact, Artwork.readLimited(new ByteArrayInputStream(exact), limit));
        assert Artwork.readLimited(new ByteArrayInputStream(new byte[0]), limit).length == 0;
        assert Artwork.readLimited(new ByteArrayInputStream(new byte[0]), 0).length == 0;
        assert Arrays.equals(new byte[] {1, 2, 3}, Artwork.readLimited(
                new ByteArrayInputStream(new byte[] {1, 2, 3}), Integer.MAX_VALUE));
        expectTooLarge(new ByteArrayInputStream(new byte[limit + 1]), limit);
        expectTooLarge(new ByteArrayInputStream(new byte[] {1}), 0);
        ByteArrayInputStream retained = new ByteArrayInputStream(new byte[limit + 2]);
        expectTooLarge(retained, limit);
        assert retained.available() == 1 : "Reader consumed more than limit plus one byte";
        boolean[] closed = {false};
        InputStream input = new ByteArrayInputStream(new byte[] {1}) {
            @Override public void close() { closed[0] = true; }
        };
        assert Artwork.readLimited(input, 1).length == 1;
        assert !closed[0] : "Caller owns stream";
        try {
            Artwork.readLimited(new InputStream() {
                @Override public int read() throws IOException { throw new IOException("sentinel"); }
            }, 1);
            throw new AssertionError("Read error swallowed");
        } catch (IOException expected) {
            assert "sentinel".equals(expected.getMessage());
        }
    }

    private static void expectTooLarge(InputStream input, int limit) throws IOException {
        try {
            Artwork.readLimited(input, limit);
            throw new AssertionError("Oversized input accepted");
        } catch (IOException expected) {
            assert "Artwork exceeds encoded byte limit".equals(expected.getMessage());
        }
    }

    private static void interruptedRead() throws IOException {
        Thread.currentThread().interrupt();
        try {
            Artwork.readLimited(new ByteArrayInputStream(new byte[]{1}), 1);
            throw new AssertionError("Interrupted artwork read continued");
        } catch (java.io.InterruptedIOException expected) {
            assert Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
    }

    private static void schemes() {
        for (String scheme : new String[] {"https", "HTTPS"}) {
            assert Artwork.isAllowedScheme(scheme) : scheme;
        }
        for (String scheme : new String[] {null, "", "http", "file", "ftp", "data", "content", "android.resource", " https", "https:"}) {
            assert !Artwork.isAllowedScheme(scheme) : scheme;
        }
    }
}
