package handlers

import (
	"fmt"
	"math/rand"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/workflow"
)

// Property 6: Waste score is always between 0.0 and 1.0 inclusive.
// **Validates: Requirements 8.2**
func TestProperty_WasteScoreAlwaysBounded(t *testing.T) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	for i := 0; i < 200; i++ {
		sub := randomSubscription(rng)
		signals := randomGmailSignals(rng)

		score := ComputeWasteScore(sub, signals)

		if score < 0.0 || score > 1.0 {
			t.Errorf("Iteration %d: waste score %.4f out of bounds [0.0, 1.0]. "+
				"Sub: months_since_first=%d, Signals: login=%d, usage=%d",
				i, score,
				monthsBetween(sub.FirstBilledDate, time.Now()),
				signals.LoginEmailCount, signals.UsageEmailCount)
		}
	}
}

// Property: Waste score with zero engagement signals is always >= waste score with some engagement.
func TestProperty_WasteScoreMonotonicity(t *testing.T) {
	rng := rand.New(rand.NewSource(42))

	for i := 0; i < 100; i++ {
		sub := randomSubscription(rng)

		noEngagement := workflow.GmailSignals{
			LoginEmailCount: 0,
			UsageEmailCount: 0,
		}

		someEngagement := workflow.GmailSignals{
			LoginEmailCount: rng.Intn(10) + 1,
			UsageEmailCount: rng.Intn(10) + 1,
		}

		scoreNoEngagement := ComputeWasteScore(sub, noEngagement)
		scoreSomeEngagement := ComputeWasteScore(sub, someEngagement)

		if scoreNoEngagement < scoreSomeEngagement {
			t.Errorf("Iteration %d: no-engagement score %.4f < some-engagement score %.4f. "+
				"Sub months=%d",
				i, scoreNoEngagement, scoreSomeEngagement,
				monthsBetween(sub.FirstBilledDate, time.Now()))
		}
	}
}

func randomSubscription(rng *rand.Rand) workflow.SubscriptionData {
	monthsAgo := rng.Intn(24) // 0-23 months ago
	firstBilled := time.Now().AddDate(0, -monthsAgo, 0)
	lastBilled := time.Now().AddDate(0, 0, -rng.Intn(30))

	return workflow.SubscriptionData{
		ID:              fmt.Sprintf("sub-%d", rng.Intn(10000)),
		UserID:          "test-user",
		Name:            fmt.Sprintf("Service-%d", rng.Intn(100)),
		MerchantName:    fmt.Sprintf("Merchant-%d", rng.Intn(100)),
		Amount:          float64(rng.Intn(5000)+100) / 100.0,
		BillingCycle:    "monthly",
		FirstBilledDate: firstBilled,
		LastBilledDate:  lastBilled,
		Status:          "active",
	}
}

func randomGmailSignals(rng *rand.Rand) workflow.GmailSignals {
	return workflow.GmailSignals{
		LoginEmailCount: rng.Intn(20),
		UsageEmailCount: rng.Intn(20),
	}
}
