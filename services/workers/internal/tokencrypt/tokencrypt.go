// Package tokencrypt reads and writes secrets at rest in the format of the API's TokenEncryptor
// (services/api/.../infrastructure/crypto/TokenEncryptor.kt, AOS-608), so both services share the
// OAuth tokens in user_connections (AOS-401, D-59).
//
// Format: "v2:" + base64(iv[12] ‖ ciphertext ‖ tag[16]), AES-256-GCM, with the place the value is
// stored (e.g. "user_connections:<id>:access_token") as GCM associated data, so a value copied to
// another row or field fails to decrypt.
package tokencrypt

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

const (
	prefix    = "v2:"
	ivLength  = 12
	tagLength = 16
	keyLength = 32
)

// KeyHelp explains what TOKEN_ENCRYPTION_KEY must be (the API's TokenEncryptor.KEY_HELP).
const KeyHelp = "TOKEN_ENCRYPTION_KEY must be base64 of 32 random bytes. Generate one with: openssl rand -base64 32"

// ErrInvalidKey means TOKEN_ENCRYPTION_KEY is not base64 of exactly 32 bytes.
var ErrInvalidKey = errors.New(KeyHelp)

// ErrDecrypt means a "v2:" value could not be authenticated: wrong key, wrong context, or tampered bytes.
var ErrDecrypt = errors.New("encrypted value could not be decrypted")

// Cipher encrypts and decrypts values bound to a context. Safe for concurrent use.
type Cipher struct {
	aead cipher.AEAD
}

// New builds a Cipher from TOKEN_ENCRYPTION_KEY (base64 of 32 bytes). The key is never logged.
func New(base64Key string) (*Cipher, error) {
	key, err := base64.StdEncoding.DecodeString(strings.TrimSpace(base64Key))
	if err != nil || len(key) != keyLength {
		return nil, ErrInvalidKey
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("aes cipher: %w", err)
	}
	// 12-byte nonce and 16-byte tag: the JDK GCM parameters the API uses.
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("gcm: %w", err)
	}
	return &Cipher{aead: aead}, nil
}

// Encrypt seals plaintext bound to context, with a fresh random IV.
func (c *Cipher) Encrypt(plaintext, context string) (string, error) {
	iv := make([]byte, ivLength)
	if _, err := rand.Read(iv); err != nil {
		return "", fmt.Errorf("random iv: %w", err)
	}
	sealed := c.aead.Seal(iv, iv, []byte(plaintext), []byte(context))
	return prefix + base64.StdEncoding.EncodeToString(sealed), nil
}

// Decrypt opens a value written by Encrypt (or by the API) with the same context.
//
// A value without the "v2:" prefix is a legacy plain-text token, written before tokens were
// encrypted: it is returned as is with legacy = true, so the caller re-encrypts it on its next
// write. A "v2:" value that fails authentication is an error, never accepted.
func (c *Cipher) Decrypt(value, context string) (plaintext string, legacy bool, err error) {
	if !strings.HasPrefix(value, prefix) {
		if looksLikeLegacyCiphertext(value) {
			return "", false, ErrLegacyCiphertext
		}
		return value, true, nil
	}
	combined, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(value, prefix))
	if err != nil || len(combined) < ivLength+tagLength {
		return "", false, ErrDecrypt
	}
	opened, err := c.aead.Open(nil, combined[:ivLength], combined[ivLength:], []byte(context))
	if err != nil {
		return "", false, ErrDecrypt
	}
	return string(opened), false, nil
}

// ErrLegacyCiphertext means an unprefixed value that is the API's pre-AOS-608 ciphertext (plain
// base64 of iv ‖ ciphertext ‖ tag), not a plain-text token. Only the API can read those; treating
// one as a token would wrap the ciphertext in a new layer and lose the real token.
var ErrLegacyCiphertext = errors.New("value is legacy API ciphertext, not a plain-text token")

// looksLikeLegacyCiphertext: Google tokens contain '.', '-' or '_' ("ya29.…", "1//0g…-…"), which
// standard base64 never does, so a value that decodes as base64 to at least an IV and a tag is a
// pre-AOS-608 ciphertext.
func looksLikeLegacyCiphertext(value string) bool {
	b, err := base64.StdEncoding.DecodeString(value)
	return err == nil && len(b) >= ivLength+tagLength
}

// ConnectionContext is where a user_connections token lives: the string the API binds
// (ConnectionService.tokenContext).
func ConnectionContext(connectionID, field string) string {
	return "user_connections:" + connectionID + ":" + field
}
