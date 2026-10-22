package com.ascs.shortener.infra.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Keyspace and tables for link storage. Created at startup for development ({@code shortener.cassandra.create-schema});
 * production should apply the same CQL through its migration process with NetworkTopologyStrategy.
 *
 * <pre>
 * links       (code PK, original_url, created_at, expires_at)  -- written once with INSERT ... IF NOT EXISTS
 * link_clicks (code PK, total counter)                          -- counters cannot share a table with regular columns
 * link_access (code PK, last_accessed_at)                       -- written USING TIMESTAMP so the newest click wins
 * </pre>
 */
public final class CassandraSchema {
    private CassandraSchema() {}

    /** DDL waits for schema agreement, which takes far longer than the request timeout meant for reads and writes. */
    public static final Duration DDL_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");

    /** Keyspace names are concatenated into CQL, so only plain identifiers are accepted. */
    public static String checkKeyspace(String keyspace) {
        if (keyspace == null || !IDENTIFIER.matcher(keyspace).matches()) {
            throw new IllegalArgumentException("invalid keyspace name: " + keyspace);
        }
        return keyspace;
    }

    public static void create(CqlSession session, String keyspace, int replicationFactor) {
        String ks = checkKeyspace(keyspace);
        if (replicationFactor < 1) throw new IllegalArgumentException("replication factor must be >= 1");
        ddl(session, "CREATE KEYSPACE IF NOT EXISTS " + ks
                + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': " + replicationFactor + "}");
        ddl(session, "CREATE TABLE IF NOT EXISTS " + ks + ".links ("
                + "code text PRIMARY KEY, original_url text, created_at timestamp, expires_at timestamp)");
        ddl(session, "CREATE TABLE IF NOT EXISTS " + ks + ".link_clicks (code text PRIMARY KEY, total counter)");
        ddl(session, "CREATE TABLE IF NOT EXISTS " + ks + ".link_access (code text PRIMARY KEY, last_accessed_at timestamp)");
    }

    private static void ddl(CqlSession session, String cql) {
        session.execute(SimpleStatement.newInstance(cql).setTimeout(DDL_TIMEOUT));
    }
}
