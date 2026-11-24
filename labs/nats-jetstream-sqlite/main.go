package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"github.com/nats-io/nats-server/v2/server"
	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
)

const (
	streamName   = "ORDERS"
	consumerName = "rewards"
	subject      = "orders.paid"
)

type paidEvent struct {
	ID       string `json:"event_id"`
	MemberID int    `json:"member_id"`
	Points   int    `json:"points"`
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	dir, err := os.MkdirTemp("", "jetstream-rewards-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)

	broker, err := server.NewServer(&server.Options{
		Host:      "127.0.0.1",
		Port:      -1, // Bind an available port.
		JetStream: true,
		StoreDir:  filepath.Join(dir, "stream"),
	})
	if err != nil {
		return err
	}
	go broker.Start()
	defer func() {
		broker.Shutdown()
		broker.WaitForShutdown()
	}()
	if !broker.ReadyForConnections(10 * time.Second) {
		return fmt.Errorf("NATS did not start")
	}

	db, err := sql.Open("sqlite3", filepath.Join(dir, "rewards.db"))
	if err != nil {
		return err
	}
	defer db.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	_, err = db.ExecContext(ctx, `
		CREATE TABLE processed_events (event_id TEXT PRIMARY KEY NOT NULL);
		CREATE TABLE balances (member_id INTEGER PRIMARY KEY, points INTEGER NOT NULL);
	`)
	if err != nil {
		return fmt.Errorf("initialize SQLite: %w", err)
	}
	var sqliteVersion string
	if err := db.QueryRowContext(ctx, "SELECT sqlite_version()").Scan(&sqliteVersion); err != nil {
		return err
	}
	fmt.Printf("NATS Server %s, SQLite %s\n", server.VERSION, sqliteVersion)

	nc, err := nats.Connect(broker.ClientURL())
	if err != nil {
		return err
	}
	defer nc.Close()
	js, err := jetstream.New(nc)
	if err != nil {
		return err
	}
	stream, err := js.CreateStream(ctx, jetstream.StreamConfig{
		Name: streamName, Subjects: []string{subject}, Storage: jetstream.FileStorage,
	})
	if err != nil {
		return err
	}
	consumer, err := stream.CreateOrUpdateConsumer(ctx, jetstream.ConsumerConfig{
		Durable: consumerName, FilterSubject: subject,
		AckPolicy: jetstream.AckExplicitPolicy, AckWait: time.Second,
	})
	if err != nil {
		return err
	}
	event := paidEvent{ID: "ep-1001", MemberID: 42, Points: 100}
	body, err := json.Marshal(event)
	if err != nil {
		return err
	}
	ack, err := js.Publish(ctx, subject, body)
	if err != nil {
		return fmt.Errorf("publish order: %w", err)
	}

	first, inserted, err := consume(ctx, broker.ClientURL(), db, false)
	if err != nil {
		return fmt.Errorf("worker A: %w", err)
	}
	if first.Sequence.Stream != ack.Sequence || first.NumDelivered != 1 || !inserted {
		return fmt.Errorf("worker A should apply the first delivery, seq=%d delivery=%d inserted=%t",
			first.Sequence.Stream, first.NumDelivered, inserted)
	}
	fmt.Printf("worker A: seq=%d delivery=%d credited=%t, disconnected before ACK\n",
		first.Sequence.Stream, first.NumDelivered, inserted)

	again, inserted, err := consume(ctx, broker.ClientURL(), db, true)
	if err != nil {
		return fmt.Errorf("worker B: %w", err)
	}
	if again.Sequence.Stream != ack.Sequence || again.NumDelivered != 2 || inserted {
		return fmt.Errorf("worker B should skip the redelivery, seq=%d delivery=%d inserted=%t",
			again.Sequence.Stream, again.NumDelivered, inserted)
	}
	fmt.Printf("worker B: seq=%d delivery=%d credited=%t, ACK confirmed\n",
		again.Sequence.Stream, again.NumDelivered, inserted)

	info, err := consumer.Info(ctx)
	if err != nil {
		return err
	}
	var receipts, points int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM processed_events").Scan(&receipts); err != nil {
		return err
	}
	if err := db.QueryRowContext(ctx, "SELECT points FROM balances WHERE member_id = ?", event.MemberID).Scan(&points); err != nil {
		return err
	}
	if receipts != 1 || points != event.Points || info.NumAckPending != 0 || info.AckFloor.Stream != ack.Sequence {
		return fmt.Errorf("unexpected final state: receipts=%d points=%d ackPending=%d ackFloor=%d",
			receipts, points, info.NumAckPending, info.AckFloor.Stream)
	}
	fmt.Printf("verified: member=%d points=%d receipts=%d ackPending=%d\n",
		event.MemberID, points, receipts, info.NumAckPending)
	return nil
}

func consume(ctx context.Context, url string, db *sql.DB, confirm bool) (*jetstream.MsgMetadata, bool, error) {
	nc, err := nats.Connect(url)
	if err != nil {
		return nil, false, err
	}
	defer nc.Close()
	js, err := jetstream.New(nc)
	if err != nil {
		return nil, false, err
	}
	consumer, err := js.Consumer(ctx, streamName, consumerName)
	if err != nil {
		return nil, false, err
	}
	msg, err := consumer.Next(jetstream.FetchMaxWait(10 * time.Second))
	if err != nil {
		return nil, false, err
	}
	meta, err := msg.Metadata()
	if err != nil {
		return nil, false, err
	}
	var event paidEvent
	if err := json.Unmarshal(msg.Data(), &event); err != nil {
		return nil, false, err
	}
	if event.ID == "" || event.MemberID <= 0 || event.Points <= 0 {
		return nil, false, fmt.Errorf("invalid paid event")
	}
	inserted, err := credit(ctx, db, event)
	if err != nil {
		return nil, false, err
	}
	if confirm {
		if err := msg.DoubleAck(ctx); err != nil {
			return nil, false, err
		}
	}
	return meta, inserted, nil
}

func credit(ctx context.Context, db *sql.DB, event paidEvent) (bool, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()

	result, err := tx.ExecContext(ctx, `
		INSERT INTO processed_events (event_id) VALUES (?)
		ON CONFLICT(event_id) DO NOTHING
	`, event.ID)
	if err != nil {
		return false, err
	}
	added, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	if added == 1 {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO balances (member_id, points) VALUES (?, ?)
			ON CONFLICT(member_id) DO UPDATE SET points = balances.points + excluded.points
		`, event.MemberID, event.Points)
		if err != nil {
			return false, err
		}
	}
	return added == 1, tx.Commit()
}
