package com.ascs.shortener.web;

import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.infra.cassandra.CassandraLinkRepository;
import com.ascs.shortener.infra.cassandra.CassandraSchema;
import com.ascs.shortener.infra.redis.RedisKeyValueCache;
import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Beans that need real infrastructure: Cassandra link storage ({@code shortener.storage=cassandra}; the CqlSession
 * comes from Spring Boot's {@code spring.cassandra.*} settings) and the Redis cache (non-empty
 * {@code shortener.cache.redis-url}). Exercised by the Testcontainers tests, which run when Docker is available.
 */
@Configuration
public class ExternalStorageConfig {

    @Bean(AppConfig.LINK_STORE)
    @ConditionalOnProperty(name = "shortener.storage", havingValue = "cassandra")
    LinkRepository cassandraLinkStore(CqlSession session,
                                      @Value("${shortener.cassandra.keyspace:shortener}") String keyspace,
                                      @Value("${shortener.cassandra.replication-factor:1}") int replicationFactor,
                                      @Value("${shortener.cassandra.create-schema:true}") boolean createSchema) {
        if (createSchema) CassandraSchema.create(session, keyspace, replicationFactor);
        return new CassandraLinkRepository(session, keyspace);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${shortener.cache.redis-url:}'.isEmpty()")
    RedisKeyValueCache redisCache(@Value("${shortener.cache.redis-url}") String url,
                                  @Value("${shortener.cache.command-timeout:200ms}") Duration commandTimeout) {
        return new RedisKeyValueCache(url, commandTimeout);
    }
}
