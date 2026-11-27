package io.github.middlewarelab.shedlock;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShedLockJdbcTest {
    private static final String LOCK_NAME = "daily-report";
    private static final int WAIT_SECONDS = 20;

    @Test
    void skipsAnOccupiedNamedLockAndRunsAfterRelease(@TempDir Path directory) throws Exception {
        String jdbcUrl = "jdbc:h2:file:" + directory.resolve("jobs");
        var observationSource = dataSource(jdbcUrl);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(observationSource);
        var observation = new JdbcTemplate(observationSource);
        var entered = new CountDownLatch(1);
        var allowFinish = new CountDownLatch(1);

        try (var workerA = startWorker(jdbcUrl, "worker-a", () -> {
                 entered.countDown();
                 await(allowFinish);
             });
             var workerB = startWorker(jdbcUrl, "worker-b", () -> {})) {
            var executor = Executors.newSingleThreadExecutor();
            var jobA = workerA.getBean(DailyReportJob.class);
            var jobB = workerB.getBean(DailyReportJob.class);
            try {
                var runA = executor.submit(jobA::run);
                await(entered);
                assertThat(lockOwner(observation)).isEqualTo("worker-a");

                jobB.run();
                assertThat(observation.queryForList("SELECT worker FROM report_runs", String.class)).isEmpty();
                assertThat(lockOwner(observation)).isEqualTo("worker-a");

                allowFinish.countDown();
                runA.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertThat(observation.queryForList("SELECT worker FROM report_runs", String.class))
                        .containsExactly("worker-a");

                jobB.run();
                assertThat(observation.queryForList("SELECT worker FROM report_runs ORDER BY id", String.class))
                        .containsExactly("worker-a", "worker-b");
                assertThat(lockOwner(observation)).isEqualTo("worker-b");
            } finally {
                allowFinish.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static String lockOwner(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT locked_by FROM shedlock WHERE name = ?", String.class, LOCK_NAME);
    }

    private static AnnotationConfigApplicationContext startWorker(
            String jdbcUrl, String workerName, Runnable beforeWrite) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("lab-worker", Map.of("report.cron", Scheduled.CRON_DISABLED)));
        var jdbc = new JdbcTemplate(dataSource(jdbcUrl));
        context.register(WorkerConfiguration.class);
        context.registerBean(LockProvider.class, () -> new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbc)
                        .withLockedByValue(workerName)
                        .usingDbTime()
                        .build()));
        context.registerBean(DailyReportJob.class, () -> new DailyReportJob(jdbc, workerName, beforeWrite));
        context.refresh();
        return context;
    }

    private static JdbcDataSource dataSource(String jdbcUrl) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser("sa");
        return dataSource;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the test worker", e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    static class WorkerConfiguration {
    }

    public static class DailyReportJob {
        private final JdbcTemplate jdbc;
        private final String workerName;
        private final Runnable beforeWrite;

        DailyReportJob(JdbcTemplate jdbc, String workerName, Runnable beforeWrite) {
            this.jdbc = jdbc;
            this.workerName = workerName;
            this.beforeWrite = beforeWrite;
        }

        @Scheduled(cron = "${report.cron:-}")
        @SchedulerLock(name = LOCK_NAME)
        public void run() {
            LockAssert.assertLocked();
            beforeWrite.run();
            jdbc.update("INSERT INTO report_runs (worker) VALUES (?)", workerName);
        }
    }
}
