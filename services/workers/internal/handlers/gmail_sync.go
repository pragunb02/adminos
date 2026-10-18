package handlers

import (
	"context"
	"errors"
	"fmt"
	"log"
	"regexp"

	"github.com/adminos/adminos/workers/internal/gmail"
)

const (
	// fullSyncDays bounds a full sync (first sync, or after the history ID expired).
	fullSyncDays = 30
	// maxFullSyncMessages bounds a full sync's size; newest mail comes first.
	maxFullSyncMessages = 500
)

type syncMode string

const (
	syncIncremental syncMode = "incremental"
	syncFull        syncMode = "full"
)

// fetchedMail is one sync's financial email, plus where the next sync starts.
type fetchedMail struct {
	Emails []GmailEmail
	// NewHistoryID is Gmail's real mailbox history ID: the startHistoryId of the next incremental sync.
	NewHistoryID string
	Mode         syncMode
	// NotFinancial counts messages skipped by the sender filters (only their headers were read).
	NotFinancial int
	// Unauthenticated counts messages from a listed sender that Gmail didn't authenticate
	// (AOS-624): spoofed bank mail. Nothing of them is parsed.
	Unauthenticated int
	// Gone counts messages deleted between listing and fetching.
	Gone int
}

// gmailHistoryID is what Gmail's history IDs look like (an unsigned integer). Anything else — e.g.
// the old made-up "history_<unix time>" values (AOS-401) — means there is no usable start point.
var gmailHistoryID = regexp.MustCompile(`^[0-9]{1,20}$`)

// fetchMail gets the financial email added since the stored history ID (AOS-401, D-59):
//   - with a usable history ID, users.history.list lists what was added; each new message's From and
//     Authentication-Results (metadata only) are qualified (gmail.Qualify), and only bank mail is
//     fetched;
//   - with none, or when Gmail answers 404 (history expired), a bounded full sync runs instead: the
//     filtered search over the last fullSyncDays days, at most maxFullSyncMessages messages.
//
// Gmail being unreachable is an error (the job is retried, D-43), never an empty success.
func (h *GmailHandler) fetchMail(ctx context.Context, accessToken, storedHistoryID string) (*fetchedMail, error) {
	if gmailHistoryID.MatchString(storedHistoryID) {
		out, err := h.incrementalSync(ctx, accessToken, storedHistoryID)
		if err == nil {
			return out, nil
		}
		if !errors.Is(err, gmail.ErrHistoryExpired) {
			return nil, err
		}
		log.Printf("[gmail] History ID expired: running a %d-day full sync", fullSyncDays)
	}
	return h.fullSync(ctx, accessToken, storedHistoryID)
}

func (h *GmailHandler) incrementalSync(ctx context.Context, accessToken, startHistoryID string) (*fetchedMail, error) {
	out := &fetchedMail{Mode: syncIncremental, NewHistoryID: startHistoryID}
	var ids []string
	seen := map[string]bool{}
	pageToken := ""
	for {
		page, err := h.gmailClient.ListHistory(ctx, accessToken, startHistoryID, pageToken)
		if err != nil {
			return nil, err
		}
		for _, hist := range page.History {
			for _, added := range hist.MessagesAdded {
				if id := added.Message.ID; id != "" && !seen[id] {
					seen[id] = true
					ids = append(ids, id)
				}
			}
		}
		if page.HistoryID != "" {
			out.NewHistoryID = page.HistoryID
		}
		if page.NextPageToken == "" {
			break
		}
		pageToken = page.NextPageToken
	}

	for _, id := range ids {
		meta, err := h.gmailClient.GetMessageMetadata(ctx, accessToken, id)
		if errors.Is(err, gmail.ErrNotFound) {
			out.Gone++
			continue
		}
		if err != nil {
			return nil, err
		}
		if skippedLabel(meta.LabelIDs) {
			out.NotFinancial++
			continue
		}
		if !out.admit(gmail.Qualify(meta.Payload.Headers)) {
			continue
		}
		if err := h.fetchFull(ctx, accessToken, id, out); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func (h *GmailHandler) fullSync(ctx context.Context, accessToken, previousHistoryID string) (*fetchedMail, error) {
	// The history ID is read *before* listing, so mail that arrives during the sync is picked up by
	// the next incremental run rather than missed.
	profile, err := h.gmailClient.GetProfile(ctx, accessToken)
	if err != nil {
		return nil, err
	}
	out := &fetchedMail{Mode: syncFull, NewHistoryID: profile.HistoryID}
	if out.NewHistoryID == "" {
		out.NewHistoryID = previousHistoryID
	}
	query := gmail.SearchQuery(fullSyncDays)
	pageToken := ""
	var ids []string
	for len(ids) < maxFullSyncMessages {
		page, err := h.gmailClient.ListMessages(ctx, accessToken, query, pageToken)
		if err != nil {
			return nil, err
		}
		for _, m := range page.Messages {
			if len(ids) == maxFullSyncMessages {
				break
			}
			ids = append(ids, m.ID)
		}
		if page.NextPageToken == "" {
			break
		}
		pageToken = page.NextPageToken
	}
	if len(ids) == maxFullSyncMessages {
		log.Printf("[gmail] Full sync capped at %d messages", maxFullSyncMessages)
	}
	for _, id := range ids {
		if err := h.fetchFull(ctx, accessToken, id, out); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// fetchFull reads one financial message's headers and readable body into out. The full message is
// qualified again (AOS-624): for a full sync this is the only check, because Gmail's search is not
// an authentication; for an incremental one it re-checks the complete header set.
func (h *GmailHandler) fetchFull(ctx context.Context, accessToken, id string, out *fetchedMail) error {
	msg, err := h.gmailClient.GetMessage(ctx, accessToken, id)
	if errors.Is(err, gmail.ErrNotFound) {
		out.Gone++
		return nil
	}
	if err != nil {
		return fmt.Errorf("fetch message: %w", err)
	}
	if skippedLabel(msg.LabelIDs) {
		out.NotFinancial++
		return nil
	}
	if !out.admit(gmail.Qualify(msg.Payload.Headers)) {
		return nil
	}
	body := gmail.PlainBody(msg)
	if body == "" {
		body = msg.Snippet
	}
	out.Emails = append(out.Emails, GmailEmail{
		MessageID: msg.ID,
		From:      msg.Header("From"),
		Subject:   msg.Header("Subject"),
		Date:      msg.Header("Date"),
		Body:      body,
	})
	return nil
}

// admit counts a rejected verdict and reports whether the message is authenticated bank mail.
func (out *fetchedMail) admit(v gmail.Verdict) bool {
	switch v {
	case gmail.BankMail:
		return true
	case gmail.Unauthenticated:
		out.Unauthenticated++
	default:
		out.NotFinancial++
	}
	return false
}

// skippedLabel: spam, trash, drafts and chats are never read (Gmail search skips them too).
func skippedLabel(labels []string) bool {
	for _, l := range labels {
		switch l {
		case "SPAM", "TRASH", "DRAFT", "CHAT":
			return true
		}
	}
	return false
}
