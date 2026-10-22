package com.ascs.shortener.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the OpenAPI contract (from the shortener-api module) that this service implements. */
@RestController
public class ContractController {

    /** Classpath location of the contract inside the shortener-api jar. */
    public static final String CONTRACT = "openapi/shortener-v1.yaml";

    @GetMapping("/api/v1/openapi.yaml")
    public ResponseEntity<Resource> contract() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(new ClassPathResource(CONTRACT));
    }
}
