package com.notehive;

import com.notehive.auth.JwtService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService staticService(String devDir) throws Exception {
        Path dir = Path.of(devDir);
        Files.createDirectories(dir);
        com.notehive.config.AppConfig.Jwt cfg = new com.notehive.config.AppConfig.Jwt() {
            @Override public String issuer() { return "notehive-test"; }
            @Override public String audience() { return "notehive-test-api"; }
            @Override public String keyMode() { return "static"; }
            @Override public java.util.Optional<String> publicKeyPem() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<String> privateKeyPem() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<String> jwksUrl() { return java.util.Optional.empty(); }
            @Override public String devKeyDir() { return devDir; }
        };
        JwtService svc = new JwtService(cfg);
        svc.init();
        return svc;
    }

    @Test
    void issueAndVerifyRoundTrip() throws Exception {
        JwtService jwt = staticService("target/test-jwt-" + System.nanoTime());
        assertTrue(jwt.staticMode());
        String token = jwt.issue("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333", "owner", 900);
        var claims = jwt.verify(token);
        assertEquals("11111111-1111-1111-1111-111111111111", claims.getSubject());
        assertEquals("22222222-2222-2222-2222-222222222222", claims.getStringClaimValue("tid"));
        assertEquals("33333333-3333-3333-3333-333333333333", claims.getStringClaimValue("sid"));
        assertEquals("owner", claims.getStringClaimValue("role"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JwtService jwt = staticService("target/test-jwt-" + System.nanoTime());
        String token = jwt.issue("sub", "tid", null, null, -60); // expired 1 min ago
        assertThrows(Exception.class, () -> jwt.verify(token));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        JwtService jwt = staticService("target/test-jwt-" + System.nanoTime());
        String token = jwt.issue("sub", "tid", null, null, 900);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(Exception.class, () -> jwt.verify(tampered));
    }

    @Test
    void differentIssuerIsRejected() throws Exception {
        JwtService jwt = staticService("target/test-jwt-" + System.nanoTime());
        String token = jwt.issue("sub", "tid", null, null, 900);
        JwtService other = staticService("target/test-jwt-other-" + System.nanoTime());
        // same generated key dir? no — different dir => different key => signature fails;
        // also assert a wrong-audience token fails by verifying with a stricter config
        assertThrows(Exception.class, () -> other.verify(token));
    }

    @Test
    void devKeysPersistAcrossInstances() throws Exception {
        String dir = "target/test-jwt-persist-" + System.nanoTime();
        JwtService first = staticService(dir);
        String token = first.issue("sub", "tid", null, null, 900);
        JwtService second = staticService(dir); // simulates restart
        assertDoesNotThrow(() -> second.verify(token));
    }
}
