package com.notehive.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Data compression utility (gzip/deflate). Used for:
 *  - note version snapshots stored as bytea (threshold-gated);
 *  - Redis cache values above a size threshold.
 * Java's GZIPInputStream handles multi-member streams, which the JDK
 * GZIPOutputStream produces when flushed — the round trip is lossless.
 */
public final class Compressor {

    private Compressor() {}

    public static boolean isGzipped(byte[] data) {
        return data != null && data.length > 2
                && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    public static byte[] gzip(byte[] data) {
        try {
            var bytes = new ByteArrayOutputStream(Math.max(64, data.length / 4));
            try (var gzip = new GZIPOutputStream(bytes) {
                { def.setLevel(Deflater.BEST_SPEED); }
            }) {
                gzip.write(data);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("gzip failed", e);
        }
    }

    public static byte[] gunzip(byte[] data) {
        if (!isGzipped(data)) {
            return data; // stored raw (small payloads skip compression)
        }
        try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            var out = new ByteArrayOutputStream(data.length * 4);
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("gunzip failed", e);
        }
    }
}
