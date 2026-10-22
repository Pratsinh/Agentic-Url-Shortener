package com.ascs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which acceptance criteria (ids from the user stories, e.g. {@code "AC-1.1"}) a test verifies.
 * The QA agent reads these from the test sources to build the functional-coverage matrix.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Verifies {
    String[] value();
}
