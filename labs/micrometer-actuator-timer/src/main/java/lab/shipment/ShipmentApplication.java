package lab.shipment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@SpringBootApplication
@RestController
public class ShipmentApplication {
    private final MeterRegistry registry;
    private final Timer accepted;
    private final Timer rejected;

    public ShipmentApplication(MeterRegistry registry) {
        this.registry = registry;
        this.accepted = shipmentTimer("accepted");
        this.rejected = shipmentTimer("rejected");
    }

    public static void main(String[] args) {
        SpringApplication.run(ShipmentApplication.class, args);
    }

    @GetMapping("/shipments/{id}/eligibility")
    public ResponseEntity<String> eligibility(@PathVariable String id) throws InterruptedException {
        Timer.Sample sample = Timer.start(registry);
        if ("ship-103".equals(id)) {
            // Reproduce a slow dependency lookup without requiring a separate service.
            Thread.sleep(150);
        }

        boolean eligible = "ship-101".equals(id) || "ship-103".equals(id);
        long elapsedNanos = sample.stop(eligible ? accepted : rejected);
        return ResponseEntity.status(eligible ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Lab-Elapsed-Nanos", Long.toString(elapsedNanos))
                .body(eligible ? "accepted" : "rejected");
    }

    private Timer shipmentTimer(String result) {
        return Timer.builder("shipment.validation")
                .description("Shipment eligibility check duration")
                .tag("result", result)
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(250))
                .register(registry);
    }
}
