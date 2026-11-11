package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Proves which PostgreSQL major version the suite ran on, so the CI matrix (14 and latest) cannot pass vacuously. */
class PostgresVersionTest {

    @Test
    void serverMajorVersionMatchesTheRequestedImage() throws SQLException {
        String image = System.getProperty("raa.postgres.image", "postgres:18-alpine");
        Matcher tag = Pattern.compile(":(\\d+)").matcher(image);
        assertThat(tag.find()).as("image tag with a major version: %s", image).isTrue();

        try (Connection connection = PostgresTestDatabase.get().newDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("show server_version_num")) {
            resultSet.next();
            int major = Integer.parseInt(resultSet.getString(1)) / 10_000;
            assertThat(major).isEqualTo(Integer.parseInt(tag.group(1))).isGreaterThanOrEqualTo(14);
        }
    }
}
