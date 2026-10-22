package com.ascs.shortener.web;

import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.AuditEvent;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** HTTP adapter for the shortener use cases; errors are mapped by {@link ApiExceptionHandler}. */
@RestController
public class ShortenerController {

    private final ShortenerService service;
    private final String baseUrl;

    public ShortenerController(ShortenerService service,
                               @Value("${shortener.base-url:http://localhost:8080}") String baseUrl) {
        this.service = service;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @PostMapping("/api/v1/links")
    public ResponseEntity<CreateLinkResponse> create(@RequestBody CreateLinkRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        ShortLink link = service.create(request.url(), ttl);
        String shortUrl = baseUrl + "/" + link.code();
        return ResponseEntity.created(URI.create(shortUrl)).body(new CreateLinkResponse(
                link.code(), shortUrl, link.originalUrl(), link.createdAt(), link.expiresAt()));
    }

    /** 302 (not 301) on purpose: browsers must not cache the redirect, or clicks would go uncounted. */
    @GetMapping("/{code:[0-9A-Za-z]+}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortLink link = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.originalUrl())).build();
    }

    @GetMapping("/api/v1/links/{code}/stats")
    public LinkStats stats(@PathVariable String code) {
        return service.stats(code);
    }

    @GetMapping("/api/v1/links/{code}/audit")
    public List<AuditEvent> audit(@PathVariable String code) {
        return service.auditOf(code);
    }

    @GetMapping("/api/v1/audit")
    public List<AuditEvent> recentAudit(@RequestParam(defaultValue = "100") int limit) {
        return service.recentAudit(limit);
    }
}
