package tokencrypt

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

// randomKey is a fresh TOKEN_ENCRYPTION_KEY for one test: never a literal (GitGuardian, D-59).
func randomKey(t *testing.T) string {
	t.Helper()
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(b)
}

func newCipher(t *testing.T, key string) *Cipher {
	t.Helper()
	c, err := New(key)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

const ctxAccess = "user_connections:0b5f4a8e-1c2d-4e3f-9a8b-7c6d5e4f3a2b:access_token"

func TestEncryptThenDecryptWithTheSameContext(t *testing.T) {
	c := newCipher(t, randomKey(t))

	sealed, err := c.Encrypt("gmail-access-₹-ok", ctxAccess)
	if err != nil {
		t.Fatal(err)
	}
	plain, legacy, err := c.Decrypt(sealed, ctxAccess)

	if err != nil || legacy || plain != "gmail-access-₹-ok" {
		t.Fatalf("got %q legacy=%v err=%v", plain, legacy, err)
	}
	if !strings.HasPrefix(sealed, "v2:") || strings.Contains(sealed, "gmail-access") {
		t.Errorf("sealed value must be v2 ciphertext, got %q", sealed)
	}
}

func TestEachEncryptionUsesAFreshIV(t *testing.T) {
	c := newCipher(t, randomKey(t))
	a, _ := c.Encrypt("same", ctxAccess)
	b, _ := c.Encrypt("same", ctxAccess)
	if a == b {
		t.Error("two encryptions of the same text must differ")
	}
}

func TestAValueMovedToAnotherFieldOrRowFailsToDecrypt(t *testing.T) {
	c := newCipher(t, randomKey(t))
	sealed, _ := c.Encrypt("tok", ctxAccess)

	for _, other := range []string{
		strings.Replace(ctxAccess, "access_token", "refresh_token", 1),
		ConnectionContext("11111111-2222-4333-8444-555555555555", "access_token"),
	} {
		if _, _, err := c.Decrypt(sealed, other); !errors.Is(err, ErrDecrypt) {
			t.Errorf("context %q: want ErrDecrypt, got %v", other, err)
		}
	}
}

func TestAnotherKeyOrTamperedBytesFailToDecrypt(t *testing.T) {
	c := newCipher(t, randomKey(t))
	sealed, _ := c.Encrypt("tok", ctxAccess)
	raw, _ := base64.StdEncoding.DecodeString(strings.TrimPrefix(sealed, "v2:"))
	raw[len(raw)-1] ^= 1
	tampered := "v2:" + base64.StdEncoding.EncodeToString(raw)

	for name, decrypt := range map[string]func() error{
		"other key": func() error { _, _, err := newCipher(t, randomKey(t)).Decrypt(sealed, ctxAccess); return err },
		"tampered":  func() error { _, _, err := c.Decrypt(tampered, ctxAccess); return err },
		"too short": func() error {
			_, _, err := c.Decrypt("v2:"+base64.StdEncoding.EncodeToString([]byte("short")), ctxAccess)
			return err
		},
		"not b64": func() error { _, _, err := c.Decrypt("v2:%%%", ctxAccess); return err },
	} {
		if err := decrypt(); !errors.Is(err, ErrDecrypt) {
			t.Errorf("%s: want ErrDecrypt, got %v", name, err)
		}
	}
}

func TestALegacyPlainTextTokenIsReturnedAndFlagged(t *testing.T) {
	plain, legacy, err := newCipher(t, randomKey(t)).Decrypt("legacy-plain-value", ctxAccess)

	if err != nil || !legacy || plain != "legacy-plain-value" {
		t.Fatalf("got %q legacy=%v err=%v", plain, legacy, err)
	}
}

func TestThePreAOS608CiphertextIsNotMistakenForAPlainToken(t *testing.T) {
	c := newCipher(t, randomKey(t))
	sealed, _ := c.Encrypt("tok", ctxAccess)
	legacy := strings.TrimPrefix(sealed, "v2:") // the old format: the same bytes, no prefix

	if _, _, err := c.Decrypt(legacy, ctxAccess); !errors.Is(err, ErrLegacyCiphertext) {
		t.Fatalf("want ErrLegacyCiphertext, got %v", err)
	}
	for _, plain := range []string{"legacy-plain-value", "1//0gAbCd-Ef_Gh", "abcd"} {
		if _, isLegacy, err := c.Decrypt(plain, ctxAccess); err != nil || !isLegacy {
			t.Errorf("%q is a plain-text token: legacy=%v err=%v", plain, isLegacy, err)
		}
	}
}

func TestKeysMustBeBase64Of32Bytes(t *testing.T) {
	short := base64.StdEncoding.EncodeToString(make([]byte, 16))
	for _, key := range []string{"", "not base64!", short, strings.Repeat("a", 32)} {
		if _, err := New(key); !errors.Is(err, ErrInvalidKey) {
			t.Errorf("key of %d chars: want ErrInvalidKey, got %v", len(key), err)
		}
	}
	if _, err := New("  " + randomKey(t) + "\n"); err != nil {
		t.Errorf("surrounding whitespace is trimmed, like the API: %v", err)
	}
}

func TestConnectionContextMatchesTheAPI(t *testing.T) {
	// ConnectionService.tokenContext: "user_connections:$connectionId:$field"
	if got := ConnectionContext("0b5f4a8e-1c2d-4e3f-9a8b-7c6d5e4f3a2b", "access_token"); got != ctxAccess {
		t.Errorf("got %q", got)
	}
}

// crossLanguageVector is testdata/token_v2_vectors.json, shared with the API's
// TokenEncryptorCrossLanguageTest. Its key is derived at test time as SHA-256(seed): no key is
// stored anywhere, and the seed is public, so the fixture protects nothing.
type crossLanguageVector struct {
	Seed           string `json:"seed"`
	AAD            string `json:"aad"`
	Message        string `json:"message"`
	SealedByKotlin string `json:"sealedByKotlin"`
	SealedByGo     string `json:"sealedByGo"`
}

func loadVector(t *testing.T) (crossLanguageVector, *Cipher) {
	t.Helper()
	raw, err := os.ReadFile("testdata/token_v2_vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v crossLanguageVector
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	key := sha256.Sum256([]byte(v.Seed))
	return v, newCipher(t, base64.StdEncoding.EncodeToString(key[:]))
}

func TestGoDecryptsWhatTheAPIsTokenEncryptorWrote(t *testing.T) {
	v, c := loadVector(t)

	plain, legacy, err := c.Decrypt(v.SealedByKotlin, v.AAD)

	if err != nil || legacy || plain != v.Message {
		t.Fatalf("Kotlin vector: got %q legacy=%v err=%v", plain, legacy, err)
	}
}

func TestTheGoVectorForTheAPIIsValid(t *testing.T) {
	v, c := loadVector(t)

	plain, _, err := c.Decrypt(v.SealedByGo, v.AAD)

	if err != nil || plain != v.Message {
		t.Fatalf("Go vector: got %q err=%v", plain, err)
	}
	if _, _, err := c.Decrypt(v.SealedByGo, v.AAD+"x"); !errors.Is(err, ErrDecrypt) {
		t.Errorf("the vector must be bound to its AAD, got %v", err)
	}
}
