package io.github.middlewarelab.batch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentImportTest {
    private static final String CHECKPOINT_KEY = "shipmentReader.read.count";
    private static final String WRITE_FAILURE = "simulated failure after the second chunk reached JDBC";

    @TempDir
    Path workspace;

    @Test
    @Timeout(30)
    void resumesAnImportFromTheLastCommittedChunk() throws Exception {
        String url = "jdbc:h2:file:" + workspace.resolve("shipment-import");
        var first = new BatchRun(url);
        first.initializeSchema();
        JobParameters parameters = new JobParametersBuilder()
                .addString("importId", "shipments-2026-11-24", true)
                .toJobParameters();

        JobExecution failed = first.launch(parameters, true);
        assertEquals(BatchStatus.FAILED, failed.getStatus());
        assertTrue(failed.getAllFailureExceptions().stream()
                .anyMatch(error -> WRITE_FAILURE.equals(error.getMessage())));
        assertTrue(Files.isRegularFile(workspace.resolve("shipment-import.mv.db")));

        // Use newly constructed framework objects and connections for the restart.
        var second = new BatchRun(url);
        assertEquals(List.of(new Shipment(101, 1200), new Shipment(102, 800)), second.importedRows());
        var persistedStep = second.loadExecution(failed.getId()).getStepExecutions().iterator().next();
        int checkpoint = persistedStep.getExecutionContext().getInt(CHECKPOINT_KEY);
        assertEquals(2, checkpoint);
        assertEquals(2, persistedStep.getWriteCount());

        JobExecution completed = second.launch(parameters, false);
        assertEquals(BatchStatus.COMPLETED, completed.getStatus());
        assertEquals(failed.getJobInstance().getId(), completed.getJobInstance().getId());
        assertNotEquals(failed.getId(), completed.getId());
        var completedStep = completed.getStepExecutions().iterator().next();
        assertEquals(3, completedStep.getReadCount());
        assertEquals(3, completedStep.getWriteCount());
        assertEquals(List.of(
                new Shipment(101, 1200), new Shipment(102, 800), new Shipment(103, 600),
                new Shipment(104, 1000), new Shipment(105, 400)), second.importedRows());
        System.out.printf("First launch FAILED: 2 rows committed, persisted reader checkpoint=%d%n", checkpoint);
        System.out.printf("Relaunch COMPLETED: wrote %d remaining rows in the same job instance, 5 rows total%n",
                completedStep.getWriteCount());
    }

    private record Shipment(int id, int costCents) {
    }

    private static class BatchRun {
        private final DriverManagerDataSource dataSource;
        private final JdbcTransactionManager transactionManager;
        private final JdbcTemplate jdbc;

        BatchRun(String url) {
            dataSource = new DriverManagerDataSource(url, "sa", "");
            transactionManager = new JdbcTransactionManager(dataSource);
            jdbc = new JdbcTemplate(dataSource);
        }

        void initializeSchema() {
            new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql"))
                    .execute(dataSource);
            jdbc.execute("CREATE TABLE imported_shipment (id INT PRIMARY KEY, cost_cents INT NOT NULL)");
        }

        JobExecution launch(JobParameters parameters, boolean failSecondChunk) throws Exception {
            var repositoryFactory = new JobRepositoryFactoryBean();
            repositoryFactory.setDataSource(dataSource);
            repositoryFactory.setTransactionManager(transactionManager);
            repositoryFactory.afterPropertiesSet();
            var repository = repositoryFactory.getObject();

            var reader = new FlatFileItemReaderBuilder<Shipment>()
                    .name("shipmentReader")
                    .resource(new ClassPathResource("shipments.csv"))
                    .saveState(true)
                    .linesToSkip(1)
                    .delimited()
                    .names("shipmentId", "costCents")
                    .fieldSetMapper(fields -> new Shipment(
                            fields.readInt("shipmentId"), fields.readInt("costCents")))
                    .build();
            var writer = new JdbcBatchItemWriterBuilder<Shipment>()
                    .dataSource(dataSource)
                    .sql("INSERT INTO imported_shipment (id, cost_cents) VALUES (?, ?)")
                    .itemPreparedStatementSetter((shipment, statement) -> {
                        statement.setInt(1, shipment.id());
                        statement.setInt(2, shipment.costCents());
                    })
                    .build();
            var step = new StepBuilder("writeShipments", repository)
                    .<Shipment, Shipment>chunk(2, transactionManager)
                    .reader(reader)
                    .writer(chunk -> {
                        writer.write(chunk);
                        if (failSecondChunk && chunk.getItems().stream().anyMatch(shipment -> shipment.id() == 104)) {
                            throw new IllegalStateException(WRITE_FAILURE);
                        }
                    })
                    .build();
            Job job = new JobBuilder("shipmentImport", repository).start(step).build();

            var launcher = new TaskExecutorJobLauncher();
            launcher.setJobRepository(repository);
            launcher.afterPropertiesSet();
            return launcher.run(job, parameters);
        }

        JobExecution loadExecution(long id) throws Exception {
            var factory = new JobExplorerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionManager(transactionManager);
            factory.afterPropertiesSet();
            return factory.getObject().getJobExecution(id);
        }

        List<Shipment> importedRows() {
            return jdbc.query("SELECT id, cost_cents FROM imported_shipment ORDER BY id",
                    (row, index) -> new Shipment(row.getInt("id"), row.getInt("cost_cents")));
        }
    }
}
