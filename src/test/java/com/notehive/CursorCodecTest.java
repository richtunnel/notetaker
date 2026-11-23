package com.notehive;

import com.notehive.common.ApiException;
import com.notehive.common.CursorCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripPreservesPosition() {
        UUID id = UUID.randomUUID();
        String encoded = codec.encode(new CursorCodec.Cursor(1730000000000000L, id));
        CursorCodec.Cursor decoded = codec.decode(encoded);
        assertEquals(1730000000000000L, decoded.t());
        assertEquals(id, decoded.i());
    }

    @Test
    void tamperedSignatureIsRejected() {
        String encoded = codec.encode(new CursorCodec.Cursor(123L, UUID.randomUUID()));
        // flip one payload char (replace first base64 char after prefix)
        String tampered = "A" + encoded.substring(1);
        assertThrows(ApiException.Validation.class, () -> codec.decode(tampered));
    }

    @Test
    void foreignKeyCannotForgeCursors() {
        UUID id = UUID.randomUUID();
        CursorCodec attacker = new CursorCodec(
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                        .getBytes(StandardCharsets.UTF_8));
        String forged = attacker.encode(new CursorCodec.Cursor(999L, id));
        assertThrows(ApiException.Validation.class, () -> codec.decode(forged));
    }

    @Test
    void blankCursorMeansFirstPage() {
        assertNull(codec.decode(null));
        assertNull(codec.decode("  "));
    }

    @Test
    void sha256IsStable() {
        assertEquals(CursorCodec.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)),
                CursorCodec.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(64, CursorCodec.sha256Hex(new byte[0]).length());
    }
}
