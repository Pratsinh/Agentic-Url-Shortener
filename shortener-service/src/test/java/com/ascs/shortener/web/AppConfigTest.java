package com.ascs.shortener.web;

import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import com.ascs.shortener.infra.cache.CachingLinkRepository;
import com.ascs.shortener.infra.cache.KeyValueCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class AppConfigTest {

    @Test
    void theCacheDecoratorIsAddedOnlyWhenACacheIsConfigured() {
        AppConfig config = new AppConfig();
        LinkRepository store = new InMemoryLinkRepository();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        assertSame(store, config.linkRepository(store, beans.getBeanProvider(KeyValueCache.class), Clock.systemUTC()));

        beans.registerSingleton("cache", new KeyValueCache() {
            @Override public Optional<String> get(String key) { return Optional.empty(); }
            @Override public void set(String key, String value, Duration ttl) { }
        });
        assertInstanceOf(CachingLinkRepository.class,
                config.linkRepository(store, beans.getBeanProvider(KeyValueCache.class), Clock.systemUTC()));
    }
}
