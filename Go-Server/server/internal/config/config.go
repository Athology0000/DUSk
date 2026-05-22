// server/internal/config/config.go
package config

import (
	"encoding/base64"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/joho/godotenv"
)

type Config struct {
	MasterKey             []byte // 32 bytes, AES-256
	ServerPepper          []byte // 32 bytes, HMAC key
	ManifestSigningKey    []byte // Ed25519 private key (64 bytes)
	ModuleEncryptionKey   []byte // 32 bytes, AES-256; defaults to MasterKey
	DBURL                 string
	RedisURL              string
	AdminAPISecret        string
	PublicPort            string
	AdminPort             string
	ContentDir            string
	BaseURL               string
	StrictSessionIP       bool
	AppEnv                string
	AllowPublicRegistration bool
	PublicCORSAllowOrigins string
	AdminCORSAllowOrigins  string
	BodyLimit             int
	SessionTTLHours       int
}

func Load() (*Config, error) {
	_ = godotenv.Load()

	masterKey, err := decodeBase64Env("MASTER_KEY", 32)
	if err != nil {
		return nil, err
	}
	pepper, err := decodeBase64Env("SERVER_PEPPER", 32)
	if err != nil {
		return nil, err
	}
	signingKey, err := decodeBase64Env("MANIFEST_SIGNING_KEY", 64)
	if err != nil {
		return nil, err
	}

	publicOrigins := sanitizeOrigins(getEnvOr("CORS_ALLOW_ORIGINS", "http://localhost:3001,http://localhost:3002,http://127.0.0.1:3001,http://127.0.0.1:3002,http://localhost:5173,http://localhost:5174"))
	adminOrigins := sanitizeOrigins(getEnvOr("ADMIN_CORS_ALLOW_ORIGINS", publicOrigins))

	cfg := &Config{
		MasterKey:             masterKey,
		ServerPepper:          pepper,
		ManifestSigningKey:    signingKey,
		ModuleEncryptionKey:   decodeOptionalBase64Env("MODULE_ENCRYPTION_KEY", 32, masterKey),
		DBURL:                 requireEnv("DB_URL"),
		RedisURL:              requireEnv("REDIS_URL"),
		AdminAPISecret:        requireEnv("ADMIN_API_SECRET"),
		PublicPort:            getEnvOr("PUBLIC_PORT", "8080"),
		AdminPort:             getEnvOr("ADMIN_PORT", "8081"),
		ContentDir:            getEnvOr("CONTENT_DIR", "./content"),
		BaseURL:               getEnvOr("BASE_URL", "http://localhost:8080"),
		StrictSessionIP:       getEnvOr("STRICT_SESSION_IP", "true") == "true",
		AppEnv:                strings.ToLower(getEnvOr("APP_ENV", "development")),
		AllowPublicRegistration: getEnvOr("ALLOW_PUBLIC_REGISTRATION", "") == "true",
		PublicCORSAllowOrigins: publicOrigins,
		AdminCORSAllowOrigins:  adminOrigins,
		SessionTTLHours:       getEnvIntOr("SESSION_TTL_HOURS", 1),
		BodyLimit:             getEnvIntOr("BODY_LIMIT_BYTES", 10*1024*1024),
	}

	if cfg.AppEnv != "production" && os.Getenv("ALLOW_PUBLIC_REGISTRATION") == "" {
		cfg.AllowPublicRegistration = true
	}

	if cfg.AppEnv == "production" && !strings.HasPrefix(cfg.BaseURL, "https://") {
		return nil, fmt.Errorf("BASE_URL must use https:// in production")
	}

	return cfg, nil
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("required env var %s is not set", key))
	}
	return v
}

func getEnvOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getEnvIntOr(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return def
}

// sanitizeOrigins normalizes a comma-separated CORS origin list to bare
// scheme://host[:port] entries. A stray path, query, fragment, or trailing
// slash on any origin makes gofiber's cors.New panic at boot, so they are
// stripped defensively here — a misconfigured env var should never crash
// the server.
func sanitizeOrigins(raw string) string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		origin := strings.TrimSpace(part)
		if origin == "" {
			continue
		}
		if origin == "*" {
			out = append(out, origin)
			continue
		}
		if u, err := url.Parse(origin); err == nil && u.Scheme != "" && u.Host != "" {
			out = append(out, u.Scheme+"://"+u.Host)
			continue
		}
		out = append(out, strings.TrimRight(origin, "/"))
	}
	return strings.Join(out, ",")
}

func decodeBase64Env(key string, expectedLen int) ([]byte, error) {
	raw := requireEnv(key)
	b, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return nil, fmt.Errorf("%s: invalid base64: %w", key, err)
	}
	if len(b) != expectedLen {
		return nil, fmt.Errorf("%s: expected %d bytes, got %d", key, expectedLen, len(b))
	}
	return b, nil
}

func decodeOptionalBase64Env(key string, expectedLen int, fallback []byte) []byte {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}

	b, err := base64.StdEncoding.DecodeString(raw)
	if err != nil || len(b) != expectedLen {
		panic(fmt.Sprintf("%s: expected base64-encoded %d-byte value", key, expectedLen))
	}

	return b
}
