package io.github.quartzlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuartzJobCheckpointTest {
    private static final JobKey AUDIT = new JobKey("order-audit", "warehouse");
    private static final String NEXT_PAGE = "nextPage";

    @TempDir
    Path work;

    @Test
    void nextPageSurvivesSchedulerRestart() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:file:" + work.resolve("checkpoints"), "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("org/quartz/impl/jdbcjobstore/tables_h2.sql"))
                .execute(source);

        var first = startScheduler(source);
        try {
            Scheduler scheduler = first.getScheduler();
            scheduler.addJob(JobBuilder.newJob(AuditPageJob.class)
                    .withIdentity(AUDIT)
                    .usingJobData(NEXT_PAGE, "1")
                    .storeDurably()
                    .build(), false);
            scheduler.triggerJob(AUDIT);
            scheduler.triggerJob(AUDIT);
            awaitPage(scheduler, "3");
        } finally {
            first.destroy();
        }

        var restarted = startScheduler(source);
        try {
            Scheduler scheduler = restarted.getScheduler();
            assertEquals("3", page(scheduler));
            scheduler.triggerJob(AUDIT);
            awaitPage(scheduler, "4");
            System.out.println("Quartz checkpoint: before restart=3, after next execution=4");
        } finally {
            restarted.destroy();
        }
    }

    private static SchedulerFactoryBean startScheduler(DataSource source) throws Exception {
        var properties = new Properties();
        properties.setProperty("org.quartz.jobStore.useProperties", "true");
        properties.setProperty("org.quartz.threadPool.threadCount", "2");

        var factory = new SchedulerFactoryBean();
        factory.setSchedulerName("audit-checkpoints");
        factory.setDataSource(source);
        factory.setQuartzProperties(properties);
        factory.setWaitForJobsToCompleteOnShutdown(true);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static String page(Scheduler scheduler) throws SchedulerException {
        return scheduler.getJobDetail(AUDIT).getJobDataMap().getString(NEXT_PAGE);
    }

    private static void awaitPage(Scheduler scheduler, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        String latest;
        do {
            latest = page(scheduler);
            if (expected.equals(latest)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, latest);
    }

    @PersistJobDataAfterExecution
    @DisallowConcurrentExecution
    public static class AuditPageJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            var state = context.getJobDetail().getJobDataMap();
            int page = Integer.parseInt(state.getString(NEXT_PAGE));
            // A real audit job processes the selected page before advancing this cursor.
            state.put(NEXT_PAGE, Integer.toString(page + 1));
        }
    }
}
