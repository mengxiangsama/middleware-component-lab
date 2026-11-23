package io.github.flywaylab;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStartupMigrationTest {
    @TempDir
    Path workspace;
    private Path migrations;

    @BeforeEach
    void copyMigrations() throws IOException {
        migrations = Files.createDirectory(workspace.resolve("migration"));
        for (String name : List.of("V1__create_orders.sql", "V2__add_order_status.sql")) {
            try (var sql = getClass().getResourceAsStream("/db/migration/" + name)) {
                Files.copy(sql, migrations.resolve(name));
            }
        }
    }

    @Test
    void upgradesExistingOrderBeforeReadyAndDoesNotRepeatMigrations() {
        try (var oldApp = start("1", jdbc -> {})) {
            oldApp.getBean(JdbcTemplate.class).update("INSERT INTO orders (id, amount) VALUES (101, 19.90)");
        }

        var readyOrders = new ArrayList<Order>();
        try (var newApp = start("2", jdbc -> readyOrders.add(readOrder(jdbc)))) {
            assertEquals(List.of(new Order(101L, new BigDecimal("19.90"), "PENDING")), readyOrders);
            assertEquals(List.of("1", "2"), appliedVersions(newApp));
        }
        try (var restartedApp = start("2", jdbc -> {})) {
            assertEquals(List.of("1", "2"), appliedVersions(restartedApp));
            assertEquals(readyOrders.get(0), readOrder(restartedApp.getBean(JdbcTemplate.class)));
        }
    }

    @Test
    void blocksReadyWhenAppliedSqlHasChanged() throws IOException {
        try (var app = start("2", jdbc -> {})) {
            assertEquals(List.of("1", "2"), appliedVersions(app));
        }

        Path v2 = migrations.resolve("V2__add_order_status.sql");
        Files.writeString(v2, Files.readString(v2).replace("'PENDING'", "'PAID'"));
        var ready = new AtomicBoolean();

        var failure = assertThrows(BeanCreationException.class,
                () -> start("2", jdbc -> ready.set(true)));
        var cause = assertInstanceOf(FlywayValidateException.class,
                NestedExceptionUtils.getMostSpecificCause(failure));
        assertTrue(cause.getMessage().contains("Migration checksum mismatch"));
        assertFalse(ready.get());
    }

    private ConfigurableApplicationContext start(String target, Consumer<JdbcTemplate> onReady) {
        ApplicationListener<ApplicationReadyEvent> reader = event ->
                onReady.accept(event.getApplicationContext().getBean(JdbcTemplate.class));
        return new SpringApplicationBuilder(MigrationApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .listeners(reader)
                .run("--spring.datasource.url=jdbc:h2:file:" + workspace.resolve("orders"),
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.sql.init.mode=never",
                        "--spring.flyway.locations=filesystem:" + migrations,
                        "--spring.flyway.target=" + target,
                        "--spring.flyway.validate-on-migrate=true");
    }

    private List<String> appliedVersions(ConfigurableApplicationContext app) {
        return app.getBean(JdbcTemplate.class).queryForList("""
                SELECT "version" FROM "flyway_schema_history"
                WHERE "success" = TRUE AND "version" IS NOT NULL ORDER BY "installed_rank"
                """, String.class);
    }

    private Order readOrder(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id, amount, status FROM orders WHERE id = 101",
                (row, number) -> new Order(row.getLong("id"), row.getBigDecimal("amount"), row.getString("status")));
    }

    private record Order(long id, BigDecimal amount, String status) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MigrationApplication {
    }
}
