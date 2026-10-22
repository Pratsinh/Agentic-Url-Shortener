package com.ascs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point: starts the URL shortener HTTP service. */
@SpringBootApplication
public class AscsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AscsApplication.class, args);
    }
}
