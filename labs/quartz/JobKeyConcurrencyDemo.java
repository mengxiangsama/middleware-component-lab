import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

public class JobKeyConcurrencyDemo {
    private static final CountDownLatch FIRST_STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_FIRST = new CountDownLatch(1);
    private static final CountDownLatch OTHER_STARTED = new CountDownLatch(1);
    private static final CountDownLatch RERUN_STARTED = new CountDownLatch(1);

    @DisallowConcurrentExecution
    public static class ReconciliationJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            switch (context.getTrigger().getKey().getName()) {
                case "scheduled" -> {
                    FIRST_STARTED.countDown();
                    try {
                        RELEASE_FIRST.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new JobExecutionException(exception);
                    }
                }
                case "other-store" -> OTHER_STARTED.countDown();
                case "manual-rerun" -> RERUN_STARTED.countDown();
                default -> throw new JobExecutionException("Unexpected trigger");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        config.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        config.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        config.setProperty("org.quartz.threadPool.threadCount", "2");

        Scheduler scheduler = new StdSchedulerFactory(config).getScheduler();
        try {
            JobDetail storeA = newJob(ReconciliationJob.class).withIdentity("store-a").build();
            JobDetail storeB = newJob(ReconciliationJob.class).withIdentity("store-b").build();
            scheduler.scheduleJob(storeA, newTrigger().withIdentity("scheduled").startNow().build());
            scheduler.start();
            await(FIRST_STARTED, "store-a scheduled run");

            Trigger rerun = newTrigger().withIdentity("manual-rerun")
                    .forJob(storeA.getKey()).startNow().build();
            scheduler.scheduleJob(rerun);
            scheduler.scheduleJob(storeB, newTrigger().withIdentity("other-store").startNow().build());
            await(OTHER_STARTED, "store-b run while store-a is active");

            Trigger.TriggerState state = scheduler.getTriggerState(rerun.getKey());
            if (state != Trigger.TriggerState.BLOCKED || RERUN_STARTED.getCount() == 0) {
                throw new IllegalStateException("Expected store-a rerun to be blocked, got " + state);
            }
            System.out.println("store-b ran while store-a was active");
            System.out.println("store-a manual rerun: " + state);

            RELEASE_FIRST.countDown();
            await(RERUN_STARTED, "store-a manual rerun after release");
            System.out.println("store-a manual rerun started after release");
        } finally {
            RELEASE_FIRST.countDown();
            scheduler.shutdown(true);
        }
    }

    private static void await(CountDownLatch latch, String operation) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for " + operation);
        }
    }
}
