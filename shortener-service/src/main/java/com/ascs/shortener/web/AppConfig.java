package com.ascs.shortener.web;

import com.ascs.shortener.app.AuditTrail;
import com.ascs.shortener.app.CodeGenerator;
import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.Base62CodeGenerator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import com.ascs.shortener.infra.cache.CachingLinkRepository;
import com.ascs.shortener.infra.cache.KeyValueCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Wires the framework-free core into Spring. The core itself has no Spring dependency.
 *
 * <p>Storage: {@code shortener.storage=memory} (default, no infrastructure) or {@code cassandra} (see
 * {@link ExternalStorageConfig}). If a cache is configured, the Redis cache-aside decorator is put in front of
 * whichever store is selected.
 */
@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    static final String LINK_STORE = "linkStore";

    @Bean(LINK_STORE)
    @ConditionalOnProperty(name = "shortener.storage", havingValue = "memory", matchIfMissing = true)
    LinkRepository memoryLinkStore() {
        return new InMemoryLinkRepository();
    }

    @Bean
    @Primary
    LinkRepository linkRepository(@Qualifier(LINK_STORE) LinkRepository store, ObjectProvider<KeyValueCache> cache, Clock clock) {
        KeyValueCache c = cache.getIfAvailable();
        return c == null ? store : new CachingLinkRepository(store, c, clock);
    }

    @Bean
    CodeGenerator codeGenerator() {
        return new Base62CodeGenerator(new SecureRandom(), 7);
    }

    @Bean
    UrlValidator urlValidator() {
        return new UrlValidator();
    }

    @Bean
    AuditTrail auditTrail(@Value("${shortener.audit.capacity:10000}") int capacity) {
        return new InMemoryAuditTrail(capacity);
    }

    @Bean
    ShortenerService shortenerService(LinkRepository repository, CodeGenerator generator,
                                      UrlValidator validator, Clock clock, AuditTrail auditTrail) {
        return new ShortenerService(repository, generator, validator, clock, auditTrail);
    }
}
