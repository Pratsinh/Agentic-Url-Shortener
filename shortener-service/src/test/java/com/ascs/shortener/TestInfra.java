package com.ascs.shortener;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.GenericContainer;

import java.net.InetSocketAddress;

/**
 * Where the storage integration tests find real infrastructure, in order of preference:
 * <ol>
 *   <li>an already running server: {@code CASSANDRA_CONTACT_POINTS} (host:port), {@code CASSANDRA_DC},
 *       {@code REDIS_URL} as environment variables or system properties;</li>
 *   <li>Docker, through Testcontainers (containers start once and are reused by every test class);</li>
 *   <li>neither: the tests are skipped ({@code @EnabledIf}), and the QA agent reports their criteria as not passed.</li>
 * </ol>
 */
final class TestInfra {
    private TestInfra() {}

    private static CassandraContainer cassandra;
    private static GenericContainer<?> redis;

    static String setting(String name) {
        String v = System.getProperty(name, System.getenv(name));
        return v == null || v.isBlank() ? null : v;
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean cassandraAvailable() {
        return setting("CASSANDRA_CONTACT_POINTS") != null || dockerAvailable();
    }

    static boolean redisAvailable() {
        return setting("REDIS_URL") != null || dockerAvailable();
    }

    static boolean cassandraAndRedisAvailable() {
        return cassandraAvailable() && redisAvailable();
    }

    static synchronized InetSocketAddress cassandraContactPoint() {
        String external = setting("CASSANDRA_CONTACT_POINTS");
        if (external != null) {
            String first = external.split(",")[0].trim();
            int colon = first.lastIndexOf(':');
            return colon < 0 ? new InetSocketAddress(first, 9042)
                    : new InetSocketAddress(first.substring(0, colon), Integer.parseInt(first.substring(colon + 1)));
        }
        if (cassandra == null) {
            cassandra = new CassandraContainer("cassandra:5.0");
            cassandra.start();
        }
        return cassandra.getContactPoint();
    }

    static synchronized String cassandraDatacenter() {
        String external = setting("CASSANDRA_DC");
        if (external != null) return external;
        return setting("CASSANDRA_CONTACT_POINTS") != null ? "datacenter1" : cassandra.getLocalDatacenter();
    }

    static synchronized String redisUrl() {
        String external = setting("REDIS_URL");
        if (external != null) return external;
        if (redis == null) {
            redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
            redis.start();
        }
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }
}
