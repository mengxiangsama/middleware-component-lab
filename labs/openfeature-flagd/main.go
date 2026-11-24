package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"strconv"
	"time"

	flagd "github.com/open-feature/go-sdk-contrib/providers/flagd/pkg"
	"github.com/open-feature/go-sdk/openfeature"
)

func main() {
	if len(os.Args) != 2 {
		log.Fatal("usage: checkout-flags <flagd-port>")
	}
	port, err := strconv.Atoi(os.Args[1])
	if err != nil || port < 1 || port > 65535 {
		log.Fatalf("invalid port %q", os.Args[1])
	}
	if err := verify(port); err != nil {
		log.Fatal(err)
	}
}

func verify(port int) error {
	provider, err := flagd.NewProvider(
		flagd.WithRPCResolver(),
		flagd.WithHost("127.0.0.1"),
		flagd.WithPort(uint16(port)),
		flagd.WithDeadline(5000),
		flagd.WithoutCache(),
	)
	if err != nil {
		return fmt.Errorf("create provider: %w", err)
	}
	defer openfeature.Shutdown()
	if err := openfeature.SetProviderAndWait(provider); err != nil {
		return fmt.Errorf("connect to flagd: %w", err)
	}
	client := openfeature.NewClient("checkout")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	const flag = "new-shipping-estimate"
	pilotStore := openfeature.NewEvaluationContext("customer-101", map[string]any{"storeId": "store-sh-01"})
	otherStore := openfeature.NewEvaluationContext("customer-101", map[string]any{"storeId": "store-hz-01"})

	pilot, err := client.BooleanValueDetails(ctx, flag, false, pilotStore)
	if err != nil {
		return fmt.Errorf("evaluate pilot store: %w", err)
	}
	if !pilot.Value || pilot.Variant != "pilot" || pilot.Reason != openfeature.TargetingMatchReason {
		return fmt.Errorf("unexpected pilot result: %+v", pilot)
	}
	fmt.Printf("pilot store: enabled=%t variant=%s reason=%s\n", pilot.Value, pilot.Variant, pilot.Reason)

	stable, err := client.BooleanValueDetails(ctx, flag, false, otherStore)
	if err != nil {
		return fmt.Errorf("evaluate other store: %w", err)
	}
	if stable.Value || stable.Variant != "stable" || stable.Reason != openfeature.DefaultReason {
		return fmt.Errorf("unexpected stable result: %+v", stable)
	}
	fmt.Printf("other store: enabled=%t variant=%s reason=%s\n", stable.Value, stable.Variant, stable.Reason)

	missing, err := client.BooleanValueDetails(ctx, "absent-shipping-estimate", false, pilotStore)
	if err == nil || missing.Value || missing.ErrorCode != openfeature.FlagNotFoundCode {
		return fmt.Errorf("expected false with FLAG_NOT_FOUND, got %+v (error: %v)", missing, err)
	}
	fmt.Printf("missing flag: enabled=%t error=%s\n", missing.Value, missing.ErrorCode)
	return nil
}
