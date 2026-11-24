package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/log"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/worker"
	"go.temporal.io/sdk/workflow"
)

type ReservationResult struct {
	Receipt string
	Attempt int32
}

func ReservationWorkflow(ctx workflow.Context, endpoint, key string) (ReservationResult, error) {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{
		StartToCloseTimeout:    3 * time.Second,
		ScheduleToCloseTimeout: 12 * time.Second,
		RetryPolicy: &temporal.RetryPolicy{
			InitialInterval:    250 * time.Millisecond,
			BackoffCoefficient: 2,
			MaximumAttempts:    3,
		},
	})
	var result ReservationResult
	err := workflow.ExecuteActivity(ctx, ReserveInventory, endpoint, key).Get(ctx, &result)
	return result, err
}

func ReserveInventory(ctx context.Context, endpoint, key string) (ReservationResult, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, nil)
	if err != nil {
		return ReservationResult{}, temporal.NewNonRetryableApplicationError(
			"invalid reservation endpoint", "InvalidEndpoint", err,
		)
	}
	request.Header.Set("Idempotency-Key", key)
	httpClient := &http.Client{Timeout: 2 * time.Second}
	response, err := httpClient.Do(request)
	if err != nil {
		return ReservationResult{}, fmt.Errorf("call reservation service: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode == http.StatusServiceUnavailable {
		return ReservationResult{}, temporal.NewApplicationError("temporary response failure", "ServiceUnavailable")
	}
	if response.StatusCode != http.StatusOK {
		return ReservationResult{}, temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("unexpected reservation status %d", response.StatusCode), "UnexpectedStatus", nil,
		)
	}
	receipt, err := io.ReadAll(response.Body)
	if err != nil {
		return ReservationResult{}, fmt.Errorf("read reservation receipt: %w", err)
	}
	return ReservationResult{Receipt: string(receipt), Attempt: activity.GetInfo(ctx).Attempt}, nil
}

type inventoryFixture struct {
	mu       sync.Mutex
	statuses []int
	receipts map[string]string
}

func (fixture *inventoryFixture) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	key := r.Header.Get("Idempotency-Key")
	if r.Method != http.MethodPost || key == "" {
		http.Error(w, "expected a reservation request with an idempotency key", http.StatusBadRequest)
		return
	}

	fixture.mu.Lock()
	defer fixture.mu.Unlock()
	receipt, exists := fixture.receipts[key]
	if !exists {
		// Apply the reservation before failing the response once per idempotency key.
		fixture.receipts[key] = "reserved-" + key
		fixture.statuses = append(fixture.statuses, http.StatusServiceUnavailable)
		http.Error(w, "temporary response failure", http.StatusServiceUnavailable)
		return
	}
	fixture.statuses = append(fixture.statuses, http.StatusOK)
	fmt.Fprint(w, receipt)
}

func (fixture *inventoryFixture) verify(result ReservationResult, key string) error {
	fixture.mu.Lock()
	defer fixture.mu.Unlock()
	if len(fixture.statuses) != 2 ||
		fixture.statuses[0] != http.StatusServiceUnavailable || fixture.statuses[1] != http.StatusOK ||
		len(fixture.receipts) != 1 || fixture.receipts[key] != "reserved-"+key ||
		result.Attempt != 2 || result.Receipt != fixture.receipts[key] {
		return fmt.Errorf("unexpected result %+v, HTTP statuses %v, reservations %v",
			result, fixture.statuses, fixture.receipts)
	}
	fmt.Printf("HTTP request statuses: %v\n", fixture.statuses)
	fmt.Printf("Workflow result: receipt=%s, activity_attempt=%d\n", result.Receipt, result.Attempt)
	fmt.Printf("Reservations applied: %d\n", len(fixture.receipts))
	return nil
}

func run(address string) error {
	fixture := &inventoryFixture{receipts: make(map[string]string)}
	service := httptest.NewServer(fixture)
	defer service.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	logger := log.NewStructuredLogger(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{
		Level: slog.LevelError + 1,
	})))
	temporalClient, err := client.DialContext(ctx, client.Options{HostPort: address, Logger: logger})
	if err != nil {
		return fmt.Errorf("connect to Temporal: %w", err)
	}
	defer temporalClient.Close()

	workflowID := fmt.Sprintf("inventory-retry-%d", time.Now().UnixNano())
	taskQueue := workflowID
	taskWorker := worker.New(temporalClient, taskQueue, worker.Options{})
	taskWorker.RegisterWorkflow(ReservationWorkflow)
	taskWorker.RegisterActivity(ReserveInventory)
	if err := taskWorker.Start(); err != nil {
		return fmt.Errorf("start worker: %w", err)
	}
	defer taskWorker.Stop()

	const key = "order-123"
	execution, err := temporalClient.ExecuteWorkflow(ctx, client.StartWorkflowOptions{
		ID:        workflowID,
		TaskQueue: taskQueue,
	}, ReservationWorkflow, service.URL, key)
	if err != nil {
		return fmt.Errorf("start workflow: %w", err)
	}
	var result ReservationResult
	if err := execution.Get(ctx, &result); err != nil {
		return fmt.Errorf("get workflow result: %w", err)
	}
	return fixture.verify(result, key)
}

func main() {
	address := flag.String("address", "127.0.0.1:17233", "Temporal development server address")
	flag.Parse()
	if err := run(*address); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
