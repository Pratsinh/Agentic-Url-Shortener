package com.ascs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AscsApplicationTest {

    @Test
    void mainStartsTheApplicationContext() {
        assertDoesNotThrow(() -> AscsApplication.main(new String[]{
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off"}));
    }
}
