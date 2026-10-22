package com.ascs.shortener.infra.redis;

import com.ascs.shortener.infra.cache.KeyValueCache;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis adapter (Lettuce). Commands time out quickly and are rejected while disconnected, so a Redis outage
 * surfaces as a fast exception that {@code CachingLinkRepository} turns into a read-through; Lettuce reconnects
 * in the background.
 */
public final class RedisKeyValueCache implements KeyValueCache, AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public RedisKeyValueCache(String redisUrl, Duration commandTimeout) {
        RedisURI uri = RedisURI.create(redisUrl);
        uri.setTimeout(commandTimeout);
        this.client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        this.connection = client.connect();
        this.commands = connection.sync();
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(commands.get(key));
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        commands.set(key, value, SetArgs.Builder.px(ttl.toMillis()));
    }

    /** Remaining time to live of a key (for diagnostics and tests); empty if the key does not exist. */
    public Optional<Duration> ttl(String key) {
        long ms = commands.pttl(key);
        return ms < 0 ? Optional.empty() : Optional.of(Duration.ofMillis(ms));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
