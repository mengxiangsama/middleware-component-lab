package io.github.quartzlab;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

public class ReconcileStoreJob implements Job {
    private final JdbcTemplate jdbc;

    public ReconcileStoreJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void execute(JobExecutionContext context) {
        var parameters = context.getMergedJobDataMap();
        jdbc.update("INSERT INTO reconciliation_runs (store_id, business_date) VALUES (?, ?)",
                parameters.getString("storeId"), LocalDate.parse(parameters.getString("businessDate")));
    }
}
