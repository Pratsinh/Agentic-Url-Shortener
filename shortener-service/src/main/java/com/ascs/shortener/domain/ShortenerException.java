package com.ascs.shortener.domain;

/**
 * Closed hierarchy of domain failures; the web layer maps each to an HTTP status.
 * Messages are logged and audited, so caller-supplied codes are neutralised before being embedded.
 */
public abstract sealed class ShortenerException extends RuntimeException
        permits ShortenerException.InvalidRequest, ShortenerException.NotFound,
                ShortenerException.Expired, ShortenerException.CodeExhausted,
                ShortenerException.StorageUnavailable {

    static final int MAX_ECHO = 32;

    protected ShortenerException(String message) {
        super(message);
    }

    protected ShortenerException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Replaces anything outside [0-9A-Za-z_-] (newlines, quotes, control chars) and truncates: blocks log forging. */
    static String printable(String raw) {
        if (raw == null) return "null";
        String cut = raw.length() > MAX_ECHO ? raw.substring(0, MAX_ECHO) + "..." : raw;
        return cut.replaceAll("[^0-9A-Za-z_.\\-]", "?");
    }

    public static final class InvalidRequest extends ShortenerException {
        public InvalidRequest(String message) { super(message); }
    }

    public static final class NotFound extends ShortenerException {
        public NotFound(String code) { super("no link for code '" + printable(code) + "'"); }
    }

    public static final class Expired extends ShortenerException {
        public Expired(String code) { super("link '" + printable(code) + "' has expired"); }
    }

    public static final class CodeExhausted extends ShortenerException {
        public CodeExhausted(int attempts) {
            super("could not allocate a unique code after " + attempts + " attempts");
        }
    }

    /** The durable store (Cassandra) cannot be reached. The cause stays attached for the log, never for the client. */
    public static final class StorageUnavailable extends ShortenerException {
        public StorageUnavailable(Throwable cause) { super("link storage is temporarily unavailable", cause); }
    }
}
