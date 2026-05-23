package content

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	ccrypto "github.com/phantom/server/internal/crypto"
	"github.com/phantom/server/internal/db"
)

type signedManifestPayload struct {
	BuildID          string              `json:"build_id"`
	Channel          string              `json:"channel"`
	MinLoaderVersion string              `json:"minimum_loader_version"`
	ModuleKey        string              `json:"module_key,omitempty"`
	Modules          []db.ManifestModule `json:"modules"`
	NativeComponents []db.ManifestNative `json:"native_components"`
}

func BuildStableManifest(_ context.Context, contentDir, baseURL, channel string, signingKey, moduleKey []byte) (*db.ContentManifest, error) {
	modulesDir := filepath.Join(contentDir, "modules")
	entries, err := os.ReadDir(modulesDir)
	if err != nil {
		return nil, err
	}

	baseURL = strings.TrimRight(baseURL, "/")
	if channel == "" {
		channel = "stable"
	}

	modules := make([]db.ManifestModule, 0)
	for _, entry := range entries {
		if entry.IsDir() || !isPhantomModuleArtifact(entry.Name()) {
			continue
		}

		jarPath := filepath.Join(modulesDir, entry.Name())
		bytecode, err := readModuleArtifact(jarPath, moduleKey)
		if err != nil {
			return nil, err
		}

		hash := sha256Hex(bytecode)
		moduleName := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		policy := "verify_on_toggle"
		if moduleName == "phantom-core" {
			policy = "auto"
		}
		modules = append(modules, db.ManifestModule{
			Name:             moduleName,
			URL:              baseURL + "/content/module/" + url.PathEscape(moduleName),
			SHA256:           hash,
			Required:         moduleName == "phantom-core",
			InitOrder:        len(modules),
			ActivationPolicy: policy,
		})
	}

	if len(modules) == 0 {
		return nil, ErrNotFound
	}

	sort.SliceStable(modules, func(i, j int) bool {
		return modules[i].Name < modules[j].Name
	})

	for i := range modules {
		modules[i].InitOrder = i
	}

	natives, err := buildNativeManifest(contentDir, baseURL)
	if err != nil {
		return nil, err
	}

	payload := signedManifestPayload{
		BuildID:          "stable-filesystem",
		Channel:          channel,
		MinLoaderVersion: "1",
		ModuleKey:        base64.StdEncoding.EncodeToString(moduleKey),
		Modules:          modules,
		NativeComponents: natives,
	}

	signature, err := ccrypto.SignManifest(signingKey, payload)
	if err != nil {
		return nil, err
	}

	return &db.ContentManifest{
		ID:               "stable",
		BuildID:          payload.BuildID,
		Channel:          payload.Channel,
		MinLoaderVersion: payload.MinLoaderVersion,
		ModuleKey:        payload.ModuleKey,
		Modules:          payload.Modules,
		NativeComponents: payload.NativeComponents,
		Signature:        signature,
		ExpiresAt:        time.Now().Add(15 * time.Minute),
		Revoked:          false,
		CreatedAt:        time.Now(),
	}, nil
}

func buildNativeManifest(contentDir, baseURL string) ([]db.ManifestNative, error) {
	nativeDir := filepath.Join(contentDir, "native")
	entries, err := os.ReadDir(nativeDir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return []db.ManifestNative{}, nil
		}
		return nil, err
	}

	natives := make([]db.ManifestNative, 0)
	for _, entry := range entries {
		if entry.IsDir() || !isNativeArtifact(entry.Name()) {
			continue
		}

		path := filepath.Join(nativeDir, entry.Name())
		bytes, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}

		natives = append(natives, db.ManifestNative{
			Name:     entry.Name(),
			URL:      baseURL + "/content/native/" + url.PathEscape(entry.Name()),
			SHA256:   sha256Hex(bytes),
			Required: true,
		})
	}

	sort.SliceStable(natives, func(i, j int) bool {
		return natives[i].Name < natives[j].Name
	})

	return natives, nil
}

func NormalizeModuleName(name string) string {
	safeName := filepath.Base(strings.TrimSpace(name))
	return strings.TrimSuffix(safeName, filepath.Ext(safeName))
}

func moduleFilePath(contentDir, name string) (string, error) {
	moduleName := NormalizeModuleName(name)
	if moduleName == "" || moduleName == "." {
		return "", ErrNotFound
	}

	path := filepath.Join(contentDir, "modules", moduleName+".jar")
	if _, err := os.Stat(path); err == nil {
		return path, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}

	encPath := filepath.Join(contentDir, "modules", moduleName+".enc")
	if _, encErr := os.Stat(encPath); encErr == nil {
		return encPath, nil
	} else if !errors.Is(encErr, os.ErrNotExist) {
		return "", encErr
	}

	return "", ErrNotFound
}

func isModuleArtifact(name string) bool {
	ext := strings.ToLower(filepath.Ext(name))
	return ext == ".jar" || ext == ".enc"
}

func isPhantomModuleArtifact(name string) bool {
	return strings.HasPrefix(strings.ToLower(name), "phantom") && isModuleArtifact(name)
}

func isNativeArtifact(name string) bool {
	ext := strings.ToLower(filepath.Ext(name))
	return ext == ".dll" || ext == ".so" || ext == ".dylib"
}

func readModuleArtifact(path string, moduleKey []byte) ([]byte, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	if strings.EqualFold(filepath.Ext(path), ".enc") {
		return ccrypto.DecryptAESGCM(moduleKey, data)
	}

	return data, nil
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
