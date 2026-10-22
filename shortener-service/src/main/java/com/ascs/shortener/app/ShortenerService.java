package com.ascs.shortener.app;

import com.ascs.shortener.domain.AuditEvent;
import com.ascs.shortener.domain.AuditEvent.Action;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.domain.UrlValidator;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Core use cases: create, resolve (redirect + click tracking), stats. Framework-free.
 *
 * <p>Every create and redirect, successful or rejected, is written to the {@link AuditTrail} and logged.
 * Logs and audit records carry the target host only, never the full URL (query strings can hold tokens).
 * Logging uses {@link System.Logger} so the core keeps no logging-framework dependency; under Spring Boot
 * it is routed to SLF4J/Logback.
 */
public class ShortenerService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration MAX_TTL = Duration.ofDays(365);
    private static final Pattern CODE_FORMAT = Pattern.compile("^[0-9A-Za-z]{4,16}$");
    private static final Logger LOG = System.getLogger(ShortenerService.class.getName());

    private final LinkRepository repository;
    private final CodeGenerator generator;
    private final UrlValidator validator;
    private final Clock clock;
    private final AuditTrail audit;

    public ShortenerService(LinkRepository repository, CodeGenerator generator,
                            UrlValidator validator, Clock clock, AuditTrail audit) {
        this.repository = Objects.requireNonNull(repository);
        this.generator = Objects.requireNonNull(generator);
        this.validator = Objects.requireNonNull(validator);
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
    }

    /** @param ttl null for a non-expiring link; otherwise must be in (0, MAX_TTL] */
    public ShortLink create(String url, Duration ttl) {
        try {
            ShortLink link = doCreate(url, ttl);
            String detail = "host=" + URI.create(link.originalUrl()).getHost()
                    + (link.expiresAt() == null ? "" : " expiresAt=" + link.expiresAt());
            audit.record(link.createdAt(), Action.LINK_CREATED, link.code(), detail);
            LOG.log(Level.INFO, "link created code={0} {1}", link.code(), detail);
            return link;
        } catch (ShortenerException e) {
            audit.record(clock.instant(), Action.LINK_CREATE_REJECTED, null, e.getMessage());
            LOG.log(e instanceof ShortenerException.CodeExhausted || e instanceof ShortenerException.StorageUnavailable ? Level.WARNING : Level.INFO,
                    "link creation rejected: {0}", e.getMessage());
            throw e;
        }
    }

    private ShortLink doCreate(String url, Duration ttl) {
        String validUrl = validator.validate(url);
        if (ttl != null && (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0)) {
            throw new ShortenerException.InvalidRequest(
                    "ttl must be positive and at most " + MAX_TTL.toDays() + " days");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS); // the precision every store keeps (Cassandra timestamps are ms)
        Instant expiresAt = ttl == null ? null : now.plus(ttl);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ShortLink candidate = new ShortLink(generator.next(), validUrl, now, expiresAt);
            if (repository.saveIfAbsent(candidate)) return candidate;
            LOG.log(Level.DEBUG, "code collision on attempt {0}, retrying", attempt + 1);
        }
        throw new ShortenerException.CodeExhausted(MAX_ATTEMPTS);
    }

    /** Resolves for redirect. Only successful resolutions are counted as clicks. */
    public ShortLink resolve(String code) {
        Instant now = clock.instant();
        try {
            ShortLink link = lookup(code);
            if (link.isExpiredAt(now)) throw new ShortenerException.Expired(code);
            recordClick(code, now);
            audit.record(now, Action.LINK_REDIRECTED, code, "host=" + URI.create(link.originalUrl()).getHost());
            LOG.log(Level.DEBUG, "redirect code={0}", code);
            return link;
        } catch (ShortenerException e) {
            String known = isValidCode(code) ? code : null; // never store attacker-chosen garbage as a link code
            audit.record(now, Action.LINK_REDIRECT_REJECTED, known, e.getMessage());
            LOG.log(Level.INFO, "redirect rejected: {0}", e.getMessage());
            throw e;
        }
    }

    /** Availability over exact analytics: a click that cannot be stored must not break a redirect the cache can serve. */
    private void recordClick(String code, Instant now) {
        try {
            repository.recordClick(code, now);
        } catch (ShortenerException.StorageUnavailable e) {
            LOG.log(Level.WARNING, "click not recorded for code={0}: {1}", code, e.getMessage());
        }
    }

    /** Stats remain available after expiry. */
    public LinkStats stats(String code) {
        if (!isValidCode(code)) throw new ShortenerException.NotFound(code);
        return repository.stats(code).orElseThrow(() -> new ShortenerException.NotFound(code));
    }

    /** Audit history of one link (404 for unknown codes, so the endpoint cannot be used to probe codes). */
    public List<AuditEvent> auditOf(String code) {
        lookup(code);
        return audit.forCode(code);
    }

    /** Most recent audit events across all links, including rejected requests. */
    public List<AuditEvent> recentAudit(int limit) {
        if (limit < 1 || limit > 1000) throw new ShortenerException.InvalidRequest("limit must be 1..1000");
        return audit.recent(limit);
    }

    private ShortLink lookup(String code) {
        if (!isValidCode(code)) throw new ShortenerException.NotFound(code);
        return repository.find(code).orElseThrow(() -> new ShortenerException.NotFound(code));
    }

    private static boolean isValidCode(String code) {
        return code != null && CODE_FORMAT.matcher(code).matches();
    }
}
