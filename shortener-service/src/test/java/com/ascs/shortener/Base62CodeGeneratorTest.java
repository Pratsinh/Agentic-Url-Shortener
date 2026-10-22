package com.ascs.shortener;

import com.ascs.shortener.infra.Base62CodeGenerator;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base62CodeGeneratorTest {

    @Test
    void producesCodesOfRequestedLengthAndAlphabet() {
        Base62CodeGenerator gen = new Base62CodeGenerator(new SecureRandom(), 7);
        for (int i = 0; i < 1000; i++) {
            String code = gen.next();
            assertEquals(7, code.length());
            assertTrue(code.matches("[0-9A-Za-z]{7}"), "unexpected code " + code);
        }
    }

    @Test
    void rejectsUnsafeLengths() {
        assertThrows(IllegalArgumentException.class, () -> new Base62CodeGenerator(new SecureRandom(), 3));
        assertThrows(IllegalArgumentException.class, () -> new Base62CodeGenerator(new SecureRandom(), 17));
    }
}
