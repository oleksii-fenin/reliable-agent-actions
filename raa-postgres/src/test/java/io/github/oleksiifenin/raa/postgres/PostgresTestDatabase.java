package io.github.oleksiifenin.raa.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container per test JVM with the journal schema applied. The image comes from the system property
 * {@code raa.postgres.image} (Gradle property {@code postgresImage}). Testcontainers removes the container when the
 * JVM exits.
 */
final class PostgresTestDatabase {

    private static final String SCHEMA = "/io/github/oleksiifenin/raa/postgres/schema/V1__raa_action.sql";
    private static PostgresTestDatabase instance;

    private final PostgreSQLContainer container;

    private PostgresTestDatabase(PostgreSQLContainer container) {
        this.container = container;
    }

    static synchronized PostgresTestDatabase get() {
        if (instance == null) {
            PostgreSQLContainer container = new PostgreSQLContainer(
                    System.getProperty("raa.postgres.image", "postgres:18-alpine"))
                    .withCommand("postgres", "-c", "max_connections=200");
            container.start();
            instance = new PostgresTestDatabase(container);
            instance.execute(readSchema());
        }
        return instance;
    }

    /** A data source that opens a new connection per call (no pool), like a separate process would. */
    DataSource newDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }

    /**
     * Creates an empty database with the schema, for tests that must not see rows of other tests (the reconciler takes
     * any row that needs it).
     */
    DataSource newDatabase() {
        return newDatabase(Duration.ZERO);
    }

    /**
     * Creates an empty database with the schema whose clock is {@code clockShift} apart from the real one, so the
     * database and the JVM clocks differ (docs/DESIGN.md §9, Clock). In that database {@code clock_timestamp()} is a
     * function of the schema {@code raa_test_clock} that adds the shift to {@code pg_catalog.clock_timestamp()}; the
     * database's {@code search_path} puts that schema before {@code pg_catalog} (and after {@code public}, where the
     * tables are created), and the journal schema is applied after it, so the column defaults use the shifted clock as
     * well.
     */
    DataSource newDatabase(Duration clockShift) {
        String name = "raa_" + UUID.randomUUID().toString().replace("-", "");
        execute("create database " + name);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {container.getHost()});
        dataSource.setPortNumbers(new int[] {container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        dataSource.setDatabaseName(name);
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        if (!clockShift.isZero()) {
            execute(dataSource, "create schema raa_test_clock;"
                    + " create function raa_test_clock.clock_timestamp() returns timestamptz language sql volatile"
                    + " as $$ select pg_catalog.clock_timestamp() + " + clockShift.toMillis()
                    + " * interval '1 millisecond' $$;"
                    + " alter database " + name + " set search_path = public, raa_test_clock, pg_catalog");
        }
        // A new connection, so the search_path of the database applies.
        execute(dataSource, readSchema());
        return dataSource;
    }

    private static void execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not set up the database: " + sql, e);
        }
    }

    /**
     * Returns what a child process needs to reach this database: the JDBC URL, the user and the password.
     *
     * @return three values, in that order
     */
    List<String> connectionArguments() {
        return List.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    void execute(String sql) {
        try (Connection connection = newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    static String readSchema() {
        try (InputStream in = PostgresTestDatabase.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("schema not on the class path: " + SCHEMA);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
