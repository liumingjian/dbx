package com.dbx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * The backend's single entry point.
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded because no datasource exists yet: H2, Flyway
 * and the connection pool arrive with {@code workflow} slice 1, which owns them (ADR-0012). Without
 * the exclusion, the JDBC starter would make Spring Boot demand a URL it cannot be given.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class DbxApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbxApplication.class, args);
    }
}
