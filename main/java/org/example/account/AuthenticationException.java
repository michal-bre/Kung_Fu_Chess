package org.example.account;

/**
 * Thrown by {@link AccountRepository#authenticate} when a username already
 * has a password on file and the supplied password doesn't match it.
 * Deliberately does NOT distinguish "wrong password" from "that username
 * doesn't exist yet, and this claims it" in its own type or message -
 * GameServer surfaces a single generic "invalid username or password" ERROR
 * either way, so a failed login attempt can't be used to enumerate which
 * usernames are already registered.
 */
public final class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
