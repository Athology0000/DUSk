package db

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type ManifestModule struct {
	Name             string `json:"name"`
	URL              string `json:"url"`
	SHA256           string `json:"sha256"`
	Required         bool   `json:"required"`
	InitOrder        int    `json:"init_order"`
	ActivationPolicy string `json:"activation_policy"`
}

type ManifestNative struct {
	Name     string `json:"name"`
	URL      string `json:"url"`
	SHA256   string `json:"sha256"`
	Required bool   `json:"required"`
}

type ContentManifest struct {
	ID               string           `json:"id"`
	BuildID          string           `json:"build_id"`
	Channel          string           `json:"channel"`
	MinLoaderVersion string           `json:"minimum_loader_version"`
	ModuleKey        string           `json:"module_key,omitempty"`
	Modules          []ManifestModule `json:"modules"`
	NativeComponents []ManifestNative `json:"native_components"`
	Signature        string           `json:"signature"`
	ExpiresAt        time.Time        `json:"expires_at"`
	Revoked          bool             `json:"revoked"`
	CreatedAt        time.Time        `json:"created_at"`
}

func GetLatestManifest(ctx context.Context, pool *pgxpool.Pool, channel string) (*ContentManifest, error) {
	row := pool.QueryRow(ctx,
		`SELECT id, build_id, channel, min_loader_version, module_key, modules, native_components, signature, expires_at, revoked, created_at
		 FROM content_manifests
		 WHERE channel = $1 AND revoked = false AND expires_at > now()
		 ORDER BY created_at DESC LIMIT 1`, channel)
	return scanManifest(row)
}

func GetManifestByID(ctx context.Context, pool *pgxpool.Pool, id string) (*ContentManifest, error) {
	row := pool.QueryRow(ctx,
		`SELECT id, build_id, channel, min_loader_version, module_key, modules, native_components, signature, expires_at, revoked, created_at
		 FROM content_manifests WHERE id = $1`, id)
	return scanManifest(row)
}

func scanManifest(row scannable) (*ContentManifest, error) {
	m := &ContentManifest{}
	var modulesJSON, nativesJSON []byte
	err := row.Scan(&m.ID, &m.BuildID, &m.Channel, &m.MinLoaderVersion, &m.ModuleKey, &modulesJSON, &nativesJSON,
		&m.Signature, &m.ExpiresAt, &m.Revoked, &m.CreatedAt)
	if err != nil {
		return nil, err
	}
	json.Unmarshal(modulesJSON, &m.Modules)
	json.Unmarshal(nativesJSON, &m.NativeComponents)
	return m, nil
}

func CreateManifest(ctx context.Context, pool *pgxpool.Pool, m *ContentManifest) (*ContentManifest, error) {
	modulesJSON, _ := json.Marshal(m.Modules)
	nativesJSON, _ := json.Marshal(m.NativeComponents)
	row := pool.QueryRow(ctx,
		`INSERT INTO content_manifests (build_id, channel, min_loader_version, module_key, modules, native_components, signature, expires_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		 RETURNING id, build_id, channel, min_loader_version, module_key, modules, native_components, signature, expires_at, revoked, created_at`,
		m.BuildID, m.Channel, m.MinLoaderVersion, m.ModuleKey, modulesJSON, nativesJSON, m.Signature, m.ExpiresAt)
	return scanManifest(row)
}
