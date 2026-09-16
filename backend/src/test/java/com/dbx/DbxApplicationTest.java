package com.dbx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Reads the entry point's annotation rather than loading a context: the claim this ticket makes is
 * that the application compiles and asks for no datasource, and a context load would be the
 * {@code workflow} ticket's claim to make.
 */
class DbxApplicationTest {

    @Test
    void excludesDataSourceAutoConfiguration() {
        SpringBootApplication annotation =
                DbxApplication.class.getAnnotation(SpringBootApplication.class);

        assertTrue(
                Arrays.asList(annotation.exclude()).contains(DataSourceAutoConfiguration.class),
                "ADR-0012: the datasource arrives with workflow slice 1, so the entry point must "
                        + "exclude DataSourceAutoConfiguration until then");
    }
}
