package org.example.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * A small, dependency-free password hasher: SHA-256 over a random 16-byte
 * per-account salt plus the password, both hex-encoded for storage. This
 * project has no working internet access to pull in a real password-hashing
 * library (bcrypt/Argon2/scrypt - the same constraint SqliteAccountRepository's
 * class doc already documents for the SQLite JDBC driver jar itself), so this
 * uses only {@code java.security}, which ships with every JDK.
 *
 * That's a real, acknowledged trade-off: SHA-256 is fast, which makes it
 * comparatively weak against offline brute-forcing of a stolen hash - not
 * something to reach for in a production system. It does correctly satisfy
 * what the CTD 26 spec actually asks for (slide 5: "Login with username +
 * password, save at SQLite db on server side") for what remains a local
 * teaching project's account gate, and it never stores or logs a password in
 * plain text either way.
 */
final class PasswordHasher {

    private static final int SALT_BYTES = 16;

    private PasswordHasher() {
    }

    static String randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return toHex(salt);
    }

    static String hash(String password, String saltHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(fromHex(saltHex));
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return toHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JDK - this can never actually happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
