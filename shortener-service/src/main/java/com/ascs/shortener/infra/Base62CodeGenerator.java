package com.ascs.shortener.infra;

import com.ascs.shortener.app.CodeGenerator;

import java.util.Random;

/** Random base62 codes. Pass a SecureRandom in production so codes are not guessable/enumerable. */
public final class Base62CodeGenerator implements CodeGenerator {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final Random random;
    private final int length;

    public Base62CodeGenerator(Random random, int length) {
        if (length < 4 || length > 16) throw new IllegalArgumentException("length must be 4..16");
        this.random = java.util.Objects.requireNonNull(random, "random");
        this.length = length;
    }

    @Override
    public String next() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(out);
    }
}
