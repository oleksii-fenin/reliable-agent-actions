package io.github.oleksiifenin.raa.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
        String name = "raa_" + UUID.randomUUID().toString().replace("-", "");
        execute("create database " + name);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {container.getHost()});
        dataSource.setPortNumbers(new int[] {container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        dataSource.setDatabaseName(name);
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(readSchema());
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the schema in " + name, e);
        }
        return dataSource;
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

    private static String readSchema() {
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
