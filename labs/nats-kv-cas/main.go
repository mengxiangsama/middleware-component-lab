package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/nats-io/nats-server/v2/server"
	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
)

const (
	bucketName = "IMPORT_PROGRESS"
	counterKey = "batch.42.completed"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	storeDir, err := os.MkdirTemp("", "nats-kv-cas-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(storeDir)

	broker, err := server.NewServer(&server.Options{
		Host: "127.0.0.1", Port: -1, JetStream: true, StoreDir: storeDir,
	})
	if err != nil {
		return fmt.Errorf("create NATS Server: %w", err)
	}
	go broker.Start()
	defer func() {
		broker.Shutdown()
		broker.WaitForShutdown()
	}()
	if !broker.ReadyForConnections(10 * time.Second) {
		return errors.New("NATS Server was not ready within 10s")
	}
	fmt.Printf("NATS Server %s\n", server.VERSION)

	connA, err := nats.Connect(broker.ClientURL(), nats.Timeout(2*time.Second), nats.NoReconnect())
	if err != nil {
		return fmt.Errorf("connect writer A: %w", err)
	}
	defer connA.Close()
	connB, err := nats.Connect(broker.ClientURL(), nats.Timeout(2*time.Second), nats.NoReconnect())
	if err != nil {
		return fmt.Errorf("connect writer B: %w", err)
	}
	defer connB.Close()
	jsA, err := jetstream.New(connA)
	if err != nil {
		return fmt.Errorf("create writer A JetStream client: %w", err)
	}
	jsB, err := jetstream.New(connB)
	if err != nil {
		return fmt.Errorf("create writer B JetStream client: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	kvA, err := jsA.CreateKeyValue(ctx, jetstream.KeyValueConfig{
		Bucket: bucketName, Storage: jetstream.FileStorage,
	})
	if err != nil {
		return fmt.Errorf("create bucket: %w", err)
	}
	kvB, err := jsB.KeyValue(ctx, bucketName)
	if err != nil {
		return fmt.Errorf("open bucket for writer B: %w", err)
	}
	return verifyCounter(ctx, kvA, kvB)
}

func verifyCounter(ctx context.Context, kvA, kvB jetstream.KeyValue) error {
	initialRevision, err := kvA.Create(ctx, counterKey, []byte("10"))
	if err != nil {
		return fmt.Errorf("initialize counter: %w", err)
	}
	// Read through both connections before either writer sends its update.
	snapshotA, err := kvA.Get(ctx, counterKey)
	if err != nil {
		return fmt.Errorf("writer A initial read: %w", err)
	}
	snapshotB, err := kvB.Get(ctx, counterKey)
	if err != nil {
		return fmt.Errorf("writer B initial read: %w", err)
	}
	if snapshotA.Revision() != initialRevision || snapshotB.Revision() != initialRevision ||
		string(snapshotA.Value()) != "10" || string(snapshotB.Value()) != "10" {
		return fmt.Errorf("expected both writers to read the initial value 10 at revision %d", initialRevision)
	}
	fmt.Printf("writers A and B read count=10 revision=%d\n", initialRevision)

	revisionA, err := increment(ctx, kvA, snapshotA, 2)
	if err != nil {
		return fmt.Errorf("writer A update: %w", err)
	}
	if revisionA <= initialRevision {
		return fmt.Errorf("writer A revision did not advance: %d", revisionA)
	}
	fmt.Printf("writer A applied +2 revision=%d\n", revisionA)

	_, err = increment(ctx, kvB, snapshotB, 3)
	if !errors.Is(err, jetstream.ErrKeyExists) {
		return fmt.Errorf("writer B expected a revision conflict, got %v", err)
	}
	fmt.Printf("writer B stale update rejected: %v\n", err)

	fresh, err := kvB.Get(ctx, counterKey)
	if err != nil {
		return fmt.Errorf("writer B refresh: %w", err)
	}
	if fresh.Revision() != revisionA || string(fresh.Value()) != "12" {
		return fmt.Errorf("rejected update changed the counter: count=%s revision=%d", fresh.Value(), fresh.Revision())
	}
	revisionB, err := increment(ctx, kvB, fresh, 3)
	if err != nil {
		return fmt.Errorf("writer B retry: %w", err)
	}
	if revisionB <= revisionA {
		return fmt.Errorf("writer B revision did not advance: %d", revisionB)
	}

	final, err := kvA.Get(ctx, counterKey)
	if err != nil {
		return fmt.Errorf("read final counter: %w", err)
	}
	if final.Revision() != revisionB || string(final.Value()) != "15" {
		return fmt.Errorf("unexpected final counter: count=%s revision=%d", final.Value(), final.Revision())
	}
	fmt.Printf("writer B refreshed count=%s revision=%d and applied +3\n", fresh.Value(), fresh.Revision())
	fmt.Printf("final count=%s revision=%d\n", final.Value(), final.Revision())
	return nil
}

func increment(ctx context.Context, kv jetstream.KeyValue, item jetstream.KeyValueEntry, delta int) (uint64, error) {
	count, err := strconv.Atoi(string(item.Value()))
	if err != nil {
		return 0, fmt.Errorf("parse counter: %w", err)
	}
	return kv.Update(ctx, item.Key(), []byte(strconv.Itoa(count+delta)), item.Revision())
}
