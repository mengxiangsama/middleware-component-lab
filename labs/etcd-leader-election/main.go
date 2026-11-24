package main

import (
	"context"
	"fmt"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"time"

	clientv3 "go.etcd.io/etcd/client/v3"
	"go.etcd.io/etcd/client/v3/concurrency"
	"go.etcd.io/etcd/server/v3/embed"
)

const (
	electionPrefix = "/orders-lab/reconciliation"
	outputKey      = "/orders-lab/daily-output"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() (runErr error) {
	dir, err := os.MkdirTemp("", "etcd-election-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	logPath := filepath.Join(dir, "etcd.log")
	defer func() {
		if runErr != nil {
			if contents, err := os.ReadFile(logPath); err == nil {
				fmt.Fprint(os.Stderr, string(contents))
			}
		}
	}()

	cfg := embed.NewConfig()
	cfg.Dir = dir
	cfg.LogLevel = "error"
	cfg.LogOutputs = []string{logPath}
	loopback := []url.URL{{Scheme: "http", Host: "127.0.0.1:0"}}
	cfg.ListenPeerUrls, cfg.AdvertisePeerUrls = loopback, loopback
	cfg.ListenClientUrls, cfg.AdvertiseClientUrls = loopback, loopback
	cfg.InitialCluster = cfg.InitialClusterFromName(cfg.Name)
	node, err := embed.StartEtcd(cfg)
	if err != nil {
		return err
	}
	defer node.Close()
	select {
	case <-node.Server.ReadyNotify():
	case err := <-node.Err():
		return fmt.Errorf("etcd startup: %w", err)
	case <-time.After(15 * time.Second):
		return fmt.Errorf("etcd did not become ready")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	endpoint := "http://" + node.Clients[0].Addr().String()
	client, err := clientv3.New(clientv3.Config{
		Endpoints:   []string{endpoint},
		DialTimeout: 5 * time.Second,
	})
	if err != nil {
		return err
	}
	defer client.Close()

	status, err := client.Status(ctx, endpoint)
	if err != nil {
		return err
	}
	fmt.Printf("etcd %s\n", status.Version)

	sessionA, err := concurrency.NewSession(client, concurrency.WithTTL(3), concurrency.WithContext(ctx))
	if err != nil {
		return err
	}
	defer sessionA.Orphan()
	sessionB, err := concurrency.NewSession(client, concurrency.WithTTL(3), concurrency.WithContext(ctx))
	if err != nil {
		return err
	}
	defer sessionB.Close()

	workerA := concurrency.NewElection(sessionA, electionPrefix)
	workerB := concurrency.NewElection(sessionB, electionPrefix)
	if err := workerA.Campaign(ctx, "worker-a"); err != nil {
		return err
	}
	if ok, err := writeResult(ctx, client, workerA, "worker-a:ready"); err != nil || !ok {
		return fmt.Errorf("worker A should write while leader: applied=%t error=%v", ok, err)
	}

	electedB := make(chan error, 1)
	go func() { electedB <- workerB.Campaign(ctx, "worker-b") }()
	if err := waitForTwoCandidates(ctx, client); err != nil {
		return err
	}
	leader, err := workerB.Leader(ctx)
	if err != nil {
		return err
	}
	if len(leader.Kvs) != 1 || string(leader.Kvs[0].Value) != "worker-a" {
		return fmt.Errorf("worker A should remain leader while worker B waits")
	}
	fmt.Println("worker-a is leader; worker-b is queued")

	// Stop keepalive without revoking the lease, so etcd must expire it.
	sessionA.Orphan()
	select {
	case err := <-electedB:
		if err != nil {
			return err
		}
	case <-ctx.Done():
		return ctx.Err()
	}
	if ok, err := writeResult(ctx, client, workerB, "worker-b:ready"); err != nil || !ok {
		return fmt.Errorf("worker B should write after takeover: applied=%t error=%v", ok, err)
	}
	fmt.Println("worker-b took over after worker-a lease expiration")

	if ok, err := writeResult(ctx, client, workerA, "worker-a:stale"); err != nil || ok {
		return fmt.Errorf("old leadership should be rejected: applied=%t error=%v", ok, err)
	}
	result, err := client.Get(ctx, outputKey)
	if err != nil {
		return err
	}
	if len(result.Kvs) != 1 || string(result.Kvs[0].Value) != "worker-b:ready" {
		return fmt.Errorf("stale worker changed the result")
	}
	fmt.Println("stale worker-a write rejected; final value=worker-b:ready")
	return nil
}

func waitForTwoCandidates(ctx context.Context, client *clientv3.Client) error {
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		resp, err := client.Get(ctx, electionPrefix+"/", clientv3.WithPrefix(), clientv3.WithCountOnly())
		if err != nil {
			return err
		}
		if resp.Count == 2 {
			return nil
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func writeResult(ctx context.Context, client *clientv3.Client, election *concurrency.Election, value string) (bool, error) {
	response, err := client.Txn(ctx).
		If(clientv3.Compare(clientv3.CreateRevision(election.Key()), "=", election.Rev())).
		Then(clientv3.OpPut(outputKey, value)).
		Commit()
	if err != nil {
		return false, err
	}
	return response.Succeeded, nil
}
