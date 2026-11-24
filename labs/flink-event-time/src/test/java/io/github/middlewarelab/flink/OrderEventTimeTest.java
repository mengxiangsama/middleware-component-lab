package io.github.middlewarelab.flink;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderEventTimeTest {
    @Test
    @Timeout(60)
    void sumsTwoWindowsAndCollectsTheLatePayment() throws Exception {
        var config = new Configuration();
        config.set(RestOptions.BIND_ADDRESS, "127.0.0.1");
        config.set(RestOptions.BIND_PORT, "0");
        var env = StreamExecutionEnvironment.createLocalEnvironment(1, config);
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);

        var late = new OutputTag<Payment>("late-payment") {};
        var totals = env.fromData(
                        new Payment("p1", "store-7", 1000, 1000),
                        new Payment("p2", "store-7", 12000, 400),
                        new Payment("p3", "store-7", 3000, 700),
                        new Payment("p4", "store-7", 7000, 500),
                        new Payment("p5", "store-7", 17000, 600))
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<Payment>forGenerator(context -> new FixtureWatermarks())
                                .withTimestampAssigner((payment, previousTimestamp) -> payment.eventTimeMs))
                .keyBy(payment -> payment.storeId)
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(10)))
                .sideOutputLateData(late)
                .process(new SumWindow());

        var observed = new ArrayList<String>();
        var latePayments = totals.getSideOutput(late)
                .map(payment -> "late " + payment.id + " " + payment.amountCents);
        try (var results = totals.union(latePayments).executeAndCollect()) {
            results.forEachRemaining(observed::add);
        }
        List<String> sorted = observed.stream().sorted().toList();
        assertEquals(List.of(
                "late p4 500",
                "total store-7 [0,10000) 1700 watermark=9999",
                "total store-7 [10000,20000) 1000 watermark=19999"
        ), sorted);
        sorted.forEach(System.out::println);
    }

    private static final class FixtureWatermarks implements WatermarkGenerator<Payment> {
        @Override
        public void onEvent(Payment payment, long eventTimestamp, WatermarkOutput output) {
            if ("p3".equals(payment.id)) {
                output.emitWatermark(new Watermark(9999));
            } else if ("p5".equals(payment.id)) {
                output.emitWatermark(new Watermark(19999));
            }
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
        }
    }

    private static final class SumWindow extends ProcessWindowFunction<Payment, String, String, TimeWindow> {
        @Override
        public void process(String storeId, Context context, Iterable<Payment> payments, Collector<String> output) {
            long cents = 0;
            for (Payment payment : payments) {
                cents += payment.amountCents;
            }
            TimeWindow window = context.window();
            output.collect("total " + storeId + " [" + window.getStart() + "," + window.getEnd() + ") "
                    + cents + " watermark=" + context.currentWatermark());
        }
    }

    public static class Payment {
        public String id;
        public String storeId;
        public long eventTimeMs;
        public long amountCents;

        public Payment() {
        }

        Payment(String id, String storeId, long eventTimeMs, long amountCents) {
            this.id = id;
            this.storeId = storeId;
            this.eventTimeMs = eventTimeMs;
            this.amountCents = amountCents;
        }
    }
}
