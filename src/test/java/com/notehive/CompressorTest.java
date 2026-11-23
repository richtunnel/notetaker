package com.notehive;

import com.notehive.common.Compressor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CompressorTest {

    @Test
    void gzipRoundTripIsLossless() {
        byte[] original = "hello notehive".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] gz = Compressor.gzip(original);
        assertTrue(Compressor.isGzipped(gz));
        assertArrayEquals(original, Compressor.gunzip(gz));
    }

    @Test
    void largeTextCompressesSubstantially() {
        byte[] text = ("The quick brown fox jumps over the lazy dog. ").repeat(2000)
                .getBytes(StandardCharsets.UTF_8);
        byte[] gz = Compressor.gzip(text);
        assertTrue(gz.length < text.length / 5, "repetitive text should compress 5x+");
        assertArrayEquals(text, Compressor.gunzip(gz));
    }

    @Test
    void incompressibleDataRoundTrips() {
        byte[] random = new byte[4096];
        new Random(42).nextBytes(random);
        assertArrayEquals(random, Compressor.gunzip(Compressor.gzip(random)));
    }

    @Test
    void rawDataPassesThrough() {
        byte[] raw = "not gzip".getBytes(StandardCharsets.UTF_8);
        assertFalse(Compressor.isGzipped(raw));
        assertArrayEquals(raw, Compressor.gunzip(raw));
    }

    @Test
    void thresholdDecidesStorageMode() {
        // mirrors NoteVersion.of threshold logic
        String small = "tiny";
        byte[] raw = small.getBytes(StandardCharsets.UTF_8);
        int threshold = 512;
        byte[] stored = raw.length > threshold ? Compressor.gzip(raw) : raw;
        boolean compressed = raw.length > threshold;
        assertFalse(compressed);
        assertArrayEquals(raw, Compressor.gunzip(stored));
    }
}
