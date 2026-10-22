package com.ascs.shortener.app;

/** Produces candidate short codes. Uniqueness is enforced by the repository, not the generator. */
@FunctionalInterface
public interface CodeGenerator {
    String next();
}
