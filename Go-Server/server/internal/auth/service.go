package auth

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"log"
	"strings"
	"time"

	"github.com/phantom/server/internal/audit"
	"github.com/phantom/server/internal/cache"
	"github.com/phantom/server/internal/config"
	"github.com/phantom/server/internal/content"
	"github.com/phantom/server/internal/crypto"
	"github.com/phantom/server/internal/db"
	"github.com/phantom/server/internal/entitlement"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

var (
	ErrNotFound         = errors.New("not found")
	ErrIPMismatch       = errors.New("ip mismatch")
	ErrHWIDMismatch     = errors.New("hwid mismatch")
	ErrUsernameMismatch = errors.New("username mismatch")
	ErrBadProof         = errors.New("bad proof")
	ErrDeviceBlocked    = errors.New("device blocked")
	ErrNoChallenge      = errors.New("no challenge")
	ErrSessionInvalid   = errors.New("session invalid")
)

type StartResult struct {
	Challenge string
	ExpiresIn int
}

type FinishResult struct {
	Authenticated        bool
	Authorized           bool
	Reason               string
	AccountID             string
	Username             string
	SessionToken          string
	ExpiresIn            int
	PlanTier             string
	Modules              []string
	Features             []string
	ManifestURL          string
	ManifestSignature    string
	EntitlementExpiresAt *time.Time
}

type Service struct {
	pool      *pgxpool.Pool
	rdb       *redis.Client
	entSvc    *entitlement.Service
	auditSvc  *audit.Service
	masterKey []byte
	pepper    []byte
	baseURL   string
	cfg       *config.Config
}

func New(
	pool *pgxpool.Pool,
	rdb *redis.Client,
	entSvc *entitlement.Service,
	auditSvc *audit.Service,
	masterKey []byte,
	pepper []byte,
	baseURL string,
	cfg *config.Config,
) *Service {
	return &Service{
		pool:      pool,
		rdb:       rdb,
		entSvc:    entSvc,
		auditSvc:  auditSvc,
		masterKey: masterKey,
		pepper:    pepper,
		baseURL:   strings.TrimRight(baseURL, "/"),
		cfg:       cfg,
	}
}

func (s *Service) Start(ctx context.Context, username, rawHWID, minecraftUsername, sourceIP string) (*StartResult, error) {
	account, err := db.GetAccountByUsername(ctx, s.pool, normalize(username))
	if errors.Is(err, pgx.ErrNoRows) {
		s.auditSvc.Log("auth.start.fail", nil, nil, nil, &sourceIP, map[string]any{
			"reason":   "account_not_found",
			"username": username,
		})
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	if account.Status != "active" {
		s.auditSvc.Log("auth.start.fail", &account.ID, nil, nil, &sourceIP, map[string]any{
			"reason": "account_blocked",
		})
		return nil, ErrDeviceBlocked
	}

	device, err := db.GetDeviceByAccountID(ctx, s.pool, account.ID)
	if errors.Is(err, pgx.ErrNoRows) {
		s.auditSvc.Log("auth.start.fail", &account.ID, nil, nil, &sourceIP, map[string]any{
			"reason": "device_not_found",
		})
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	if device.BindingStatus == "suspended" || device.BindingStatus == "banned" {
		s.auditSvc.Log("auth.start.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "device_blocked",
			"status": device.BindingStatus,
		})
		return nil, ErrDeviceBlocked
	}

	if device.BindingStatus != "hwid_pending" && device.BindingStatus != "fully_bound" {
		s.auditSvc.Log("auth.start.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "device_not_enrolled",
		})
		return nil, ErrNotFound
	}

	if device.BindingStatus == "hwid_pending" {
		if device.EnrollmentIP == nil || *device.EnrollmentIP != sourceIP {
			s.auditSvc.Log("auth.start.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
				"reason": "ip_mismatch",
			})
			return nil, ErrIPMismatch
		}
	}

	hwidHash := crypto.HMACHash(s.pepper, []byte(normalizeHWID(rawHWID)))
	if device.HWIDHash == nil || *device.HWIDHash != hwidHash {
		s.auditSvc.Log("auth.start.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "hwid_mismatch",
		})
		return nil, ErrHWIDMismatch
	}

	if device.BindingStatus == "fully_bound" && minecraftUsername != "" {
		if device.MinecraftUsername == nil || !strings.EqualFold(*device.MinecraftUsername, minecraftUsername) {
			s.auditSvc.Log("auth.start.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
				"reason": "username_mismatch",
			})
			return nil, ErrUsernameMismatch
		}
	}

	challenge := make([]byte, 32)
	if _, err := rand.Read(challenge); err != nil {
		return nil, err
	}

	challengeB64 := base64.StdEncoding.EncodeToString(challenge)

	if err := cache.StoreChallenge(ctx, s.rdb, &cache.Challenge{
		DeviceID:  device.ID,
		Challenge: challengeB64,
		SourceIP:  sourceIP,
	}); err != nil {
		return nil, err
	}

	s.auditSvc.Log("auth.start.success", &account.ID, &device.ID, nil, &sourceIP, nil)

	return &StartResult{
		Challenge: challengeB64,
		ExpiresIn: 30,
	}, nil
}

func (s *Service) Finish(ctx context.Context, username, proofHex, sourceIP, minecraftUsername string) (*FinishResult, error) {
	account, err := db.GetAccountByUsername(ctx, s.pool, normalize(username))
	if errors.Is(err, pgx.ErrNoRows) {
		s.auditSvc.Log("auth.finish.fail", nil, nil, nil, &sourceIP, map[string]any{
			"reason":   "account_not_found",
			"username": username,
		})
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}

	device, err := db.GetDeviceByAccountID(ctx, s.pool, account.ID)
	if err != nil {
		s.auditSvc.Log("auth.finish.fail", &account.ID, nil, nil, &sourceIP, map[string]any{
			"reason": "device_not_found",
		})
		return nil, ErrNotFound
	}

	ch, err := cache.GetChallenge(ctx, s.rdb, device.ID)
	if err != nil || ch.Used {
		s.auditSvc.Log("auth.finish.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "no_challenge_or_expired",
		})
		return nil, ErrNoChallenge
	}

	if ch.SourceIP != sourceIP {
		s.auditSvc.Log("auth.finish.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason":   "ip_mismatch",
			"expected": ch.SourceIP,
		})
		cache.DeleteChallenge(ctx, s.rdb, device.ID)
		return nil, ErrIPMismatch
	}

	cache.DeleteChallenge(ctx, s.rdb, device.ID)

	plain, err := crypto.DecryptAESGCM(s.masterKey, device.DeviceSecretEncrypted)
	if err != nil {
		return nil, err
	}

	if !crypto.HMACVerify(plain, []byte(ch.Challenge), proofHex) {
		count, _ := db.IncrementFailedAttempts(ctx, s.pool, device.ID)
		if count >= 5 {
			db.SuspendDevice(ctx, s.pool, device.ID)
			s.auditSvc.Log("auth.device.suspended", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
				"reason": "too_many_failed_attempts",
			})
		}

		s.auditSvc.Log("auth.finish.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason":          "bad_proof",
			"failed_attempts": count,
		})

		return nil, ErrBadProof
	}

	if device.BindingStatus == "hwid_pending" {
		if err := db.FullyBind(ctx, s.pool, device.ID, minecraftUsername, sourceIP); err != nil {
			return nil, err
		}
	}

	ent, err := s.entSvc.Resolve(ctx, account.ID)
	if err != nil {
		return nil, err
	}

	if !ent.Authorized {
		s.auditSvc.Log("auth.finish.entitlement_denied", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": ent.Reason,
		})

		return &FinishResult{
			Authenticated: true,
			Authorized:    false,
			AccountID:      account.ID,
			Username:       account.Username,
			Reason:         ent.Reason,
		}, nil
	}

	expiresAt := time.Now().Add(time.Duration(s.cfg.SessionTTLHours) * time.Hour)

	rawToken, tokenHash, err := crypto.GenerateToken()
	if err != nil {
		return nil, err
	}

	planTier := strings.TrimSpace(ent.PlanTier)
	if planTier == "" {
		planTier = "unknown"
	}

	modules := ent.EnabledModules
	if modules == nil {
		modules = []string{}
	}

	features := ent.EnabledFeatures
	if features == nil {
		features = []string{}
	}

	if _, err := db.CreateSession(
		ctx,
		s.pool,
		tokenHash,
		device.ID,
		account.ID,
		planTier,
		modules,
		features,
		ent.EntitlementExpiresAt,
		expiresAt,
		sourceIP,
	); err != nil {
		return nil, err
	}

	manifestURL, manifestSig, err := s.resolveManifest(ctx, ent.ContentChannel)
	if err != nil {
		log.Printf("[auth.finish] no manifest found for channel %s: %v", ent.ContentChannel, err)
		return nil, errors.New("no manifest available for your plan")
	}

	s.auditSvc.Log("auth.finish.success", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
		"plan_tier": planTier,
	})

	log.Printf(
		"[auth.finish] result username=%s account_id=%s authorized=true resolved_plan=%q expires_at=%v modules=%v features=%v manifest_url=%q",
		account.Username,
		account.ID,
		planTier,
		ent.EntitlementExpiresAt,
		modules,
		features,
		manifestURL,
	)

	return &FinishResult{
		Authenticated:        true,
		Authorized:           true,
		AccountID:             account.ID,
		Username:              account.Username,
		SessionToken:          rawToken,
		ExpiresIn:            3600,
		PlanTier:             planTier,
		Modules:              modules,
		Features:             features,
		ManifestURL:          manifestURL,
		ManifestSignature:    manifestSig,
		EntitlementExpiresAt: ent.EntitlementExpiresAt,
	}, nil
}

func (s *Service) Heartbeat(ctx context.Context, sessionToken, sourceIP string) error {
	tokenHash, err := crypto.HashToken(sessionToken)
	if err != nil {
		return ErrSessionInvalid
	}

	session, err := db.GetSessionByTokenHash(ctx, s.pool, tokenHash)
	if err != nil {
		return ErrSessionInvalid
	}

	if session.Revoked {
		return ErrSessionInvalid
	}

	if session.ExpiresAt.Before(time.Now()) {
		return ErrSessionInvalid
	}

	// Extend session expiration
	newExpiresAt := time.Now().Add(time.Hour)
	if err := db.UpdateSessionExpiresAt(ctx, s.pool, session.ID, newExpiresAt); err != nil {
		return err
	}

	s.auditSvc.Log("auth.heartbeat", &session.AccountID, &session.DeviceID, nil, &sourceIP, map[string]any{
		"session_id": session.ID,
	})

	return nil
}

type VerifyMinecraftResult struct {
	Authorized           bool
	Reason               string
	PlanTier             string
	Modules              []string
	Features             []string
	ManifestURL          string
	ManifestSignature    string
	EntitlementExpiresAt *time.Time
}

type VerifySessionResult struct {
	Authorized           bool
	Reason               string
	AccountID             string
	Username             string
	MinecraftUsername    string
	PlanTier             string
	Modules              []string
	Features             []string
	ManifestURL          string
	ManifestSignature    string
	EntitlementExpiresAt *time.Time
}

type VerifyModuleResult struct {
	Authorized bool
	Reason     string
}

func (s *Service) VerifyModule(ctx context.Context, rawToken, moduleName, minecraftUsernameInput, sourceIP string) (*VerifyModuleResult, error) {
	minecraftUsernameInput = strings.TrimSpace(minecraftUsernameInput)
	moduleName = strings.TrimSpace(moduleName)

	if moduleName == "" {
		return &VerifyModuleResult{Authorized: false, Reason: "missing_module_name"}, nil
	}

	tokenHash, err := crypto.HashToken(rawToken)
	if err != nil {
		return nil, ErrSessionInvalid
	}

	sess, err := db.GetSessionByTokenHash(ctx, s.pool, tokenHash)
	if err != nil || sess.Revoked || time.Now().After(sess.ExpiresAt) {
		return nil, ErrSessionInvalid
	}

	device, err := db.GetDeviceByID(ctx, s.pool, sess.DeviceID)
	if err != nil {
		return nil, err
	}

	if device.BindingStatus == "suspended" || device.BindingStatus == "banned" {
		return &VerifyModuleResult{Authorized: false, Reason: "device_blocked"}, nil
	}

	account, err := db.GetAccountByID(ctx, s.pool, sess.AccountID)
	if err != nil {
		return nil, err
	}
	if account.Status != "active" {
		return &VerifyModuleResult{Authorized: false, Reason: "account_blocked"}, nil
	}

	if device.MinecraftUsername == nil || !strings.EqualFold(*device.MinecraftUsername, minecraftUsernameInput) {
		s.auditSvc.Log("auth.verify_module.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "minecraft_username_mismatch",
			"module": moduleName,
		})
		return &VerifyModuleResult{Authorized: false, Reason: "minecraft_username_mismatch"}, nil
	}

	ent, err := s.entSvc.Resolve(ctx, account.ID)
	if err != nil {
		return nil, err
	}
	if !ent.Authorized {
		return &VerifyModuleResult{Authorized: false, Reason: ent.Reason}, nil
	}

	if !moduleAllowed(moduleName, ent.EnabledModules) {
		s.auditSvc.Log("auth.verify_module.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "not_entitled",
			"module": moduleName,
		})
		return &VerifyModuleResult{Authorized: false, Reason: "not_entitled"}, nil
	}

	s.auditSvc.Log("auth.verify_module.success", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
		"module": moduleName,
	})
	return &VerifyModuleResult{Authorized: true, Reason: ""}, nil
}

func moduleAllowed(moduleName string, enabled []string) bool {
	if moduleName == "phantom-core" {
		return true
	}
	short := strings.TrimPrefix(moduleName, "phantom-")
	for _, m := range enabled {
		if m == "*" || m == moduleName || m == short {
			return true
		}
	}
	return false
}

func (s *Service) VerifyMinecraft(ctx context.Context, rawToken, minecraftUsername, sourceIP string) (*VerifyMinecraftResult, error) {
	tokenHash, err := crypto.HashToken(rawToken)
	if err != nil {
		return nil, ErrSessionInvalid
	}

	sess, err := db.GetSessionByTokenHash(ctx, s.pool, tokenHash)
	if err != nil || sess.Revoked || time.Now().After(sess.ExpiresAt) {
		return nil, ErrSessionInvalid
	}

	device, err := db.GetDeviceByID(ctx, s.pool, sess.DeviceID)
	if err != nil {
		return nil, err
	}

	account, err := db.GetAccountByID(ctx, s.pool, sess.AccountID)
	if err != nil {
		return nil, err
	}

	if account.Status != "active" {
		s.auditSvc.Log("auth.verify_mc.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "account_blocked",
		})
		return &VerifyMinecraftResult{
			Authorized: false,
			Reason:     "account_blocked",
		}, nil
	}

	switch device.BindingStatus {
	case "hwid_pending":
		if err := db.FullyBind(ctx, s.pool, device.ID, minecraftUsername, sourceIP); err != nil {
			return nil, err
		}
	case "fully_bound":
		if device.MinecraftUsername == nil || !strings.EqualFold(*device.MinecraftUsername, minecraftUsername) {
			s.auditSvc.Log("auth.verify_mc.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
				"reason": "minecraft_username_mismatch",
			})
			return &VerifyMinecraftResult{
				Authorized: false,
				Reason:     "minecraft_username_mismatch",
			}, nil
		}
	default:
		s.auditSvc.Log("auth.verify_mc.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "device_not_eligible",
			"status": device.BindingStatus,
		})
		return &VerifyMinecraftResult{
			Authorized: false,
			Reason:     "device_not_eligible",
		}, nil
	}

	ent, err := s.entSvc.Resolve(ctx, account.ID)
	if err != nil {
		return nil, err
	}

	if !ent.Authorized {
		s.auditSvc.Log("auth.verify_mc.entitlement_denied", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": ent.Reason,
		})
		return &VerifyMinecraftResult{
			Authorized: false,
			Reason:     ent.Reason,
		}, nil
	}

	manifestURL, manifestSig, _ := s.resolveManifest(ctx, ent.ContentChannel)

	s.auditSvc.Log("auth.verify_mc.success", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
		"plan_tier": ent.PlanTier,
	})

	return &VerifyMinecraftResult{
		Authorized:           true,
		PlanTier:             ent.PlanTier,
		Modules:              ent.EnabledModules,
		Features:             ent.EnabledFeatures,
		ManifestURL:          manifestURL,
		ManifestSignature:    manifestSig,
		EntitlementExpiresAt: ent.EntitlementExpiresAt,
	}, nil
}

func (s *Service) VerifySession(ctx context.Context, rawToken, sourceIP, minecraftUsernameInput string) (*VerifySessionResult, error) {
	started := time.Now()
	minecraftUsernameInput = strings.TrimSpace(minecraftUsernameInput)

	log.Printf("[auth.verify_session] start ip=%s token_present=%t token=%s minecraft_input=%q",
		sourceIP,
		strings.TrimSpace(rawToken) != "",
		shortToken(rawToken),
		minecraftUsernameInput,
	)

	tokenHash, err := crypto.HashToken(rawToken)
	if err != nil {
		log.Printf("[auth.verify_session] token_hash_failed ip=%s err=%v", sourceIP, err)
		return nil, ErrSessionInvalid
	}

	log.Printf("[auth.verify_session] token_hash_ok ip=%s token_hash=%s",
		sourceIP,
		shortHash(tokenHash),
	)

	sess, err := db.GetSessionByTokenHash(ctx, s.pool, tokenHash)
	if err != nil {
		log.Printf("[auth.verify_session] session_lookup_failed ip=%s token_hash=%s err=%v",
			sourceIP,
			shortHash(tokenHash),
			err,
		)
		return nil, ErrSessionInvalid
	}

	log.Printf("[auth.verify_session] session_found ip=%s session_id=%s account_id=%s device_id=%s revoked=%t expires_at=%s now=%s",
		sourceIP,
		sess.ID,
		sess.AccountID,
		sess.DeviceID,
		sess.Revoked,
		sess.ExpiresAt.Format(time.RFC3339),
		time.Now().Format(time.RFC3339),
	)

	if sess.Revoked {
		log.Printf("[auth.verify_session] session_revoked ip=%s session_id=%s", sourceIP, sess.ID)
		return nil, ErrSessionInvalid
	}

	if time.Now().After(sess.ExpiresAt) {
		log.Printf("[auth.verify_session] session_expired ip=%s session_id=%s expires_at=%s",
			sourceIP,
			sess.ID,
			sess.ExpiresAt.Format(time.RFC3339),
		)
		return nil, ErrSessionInvalid
	}

	device, err := db.GetDeviceByID(ctx, s.pool, sess.DeviceID)
	if err != nil {
		log.Printf("[auth.verify_session] device_lookup_failed ip=%s device_id=%s err=%v",
			sourceIP,
			sess.DeviceID,
			err,
		)
		return nil, err
	}

	log.Printf("[auth.verify_session] device_found ip=%s device_id=%s binding_status=%s minecraft_username=%q",
		sourceIP,
		device.ID,
		device.BindingStatus,
		ptrStringValue(device.MinecraftUsername),
	)

	if minecraftUsernameInput != "" {
		switch device.BindingStatus {
		case "hwid_pending":
			if err := db.FullyBind(ctx, s.pool, device.ID, minecraftUsernameInput, sourceIP); err != nil {
				log.Printf("[auth.verify_session] minecraft_bind_failed ip=%s device_id=%s err=%v",
					sourceIP,
					device.ID,
					err,
				)
				return nil, err
			}

			device.MinecraftUsername = &minecraftUsernameInput
			device.BindingStatus = "fully_bound"

			log.Printf("[auth.verify_session] minecraft_bound ip=%s device_id=%s minecraft_username=%q",
				sourceIP,
				device.ID,
				minecraftUsernameInput,
			)

		case "fully_bound":
			if device.MinecraftUsername == nil || strings.TrimSpace(*device.MinecraftUsername) == "" {
				if err := db.FullyBind(ctx, s.pool, device.ID, minecraftUsernameInput, sourceIP); err != nil {
					log.Printf("[auth.verify_session] minecraft_rebind_empty_failed ip=%s device_id=%s err=%v",
						sourceIP,
						device.ID,
						err,
					)
					return nil, err
				}

				device.MinecraftUsername = &minecraftUsernameInput

				log.Printf("[auth.verify_session] minecraft_bound_empty ip=%s device_id=%s minecraft_username=%q",
					sourceIP,
					device.ID,
					minecraftUsernameInput,
				)
			} else if !strings.EqualFold(*device.MinecraftUsername, minecraftUsernameInput) {
				log.Printf(
					"[auth.verify_session] minecraft_username_mismatch ip=%s device_id=%s expected=%q got=%q",
					sourceIP,
					device.ID,
					*device.MinecraftUsername,
					minecraftUsernameInput,
				)

				s.auditSvc.Log("auth.verify_session.fail", &sess.AccountID, &device.ID, nil, &sourceIP, map[string]any{
					"reason":   "minecraft_username_mismatch",
					"expected": *device.MinecraftUsername,
					"got":      minecraftUsernameInput,
				})

				return &VerifySessionResult{
					Authorized: false,
					Reason:     "minecraft_username_mismatch",
				}, nil
			}
		default:
			log.Printf("[auth.verify_session] minecraft_bind_skipped ip=%s device_id=%s status=%s",
				sourceIP,
				device.ID,
				device.BindingStatus,
			)
		}
	}

	account, err := db.GetAccountByID(ctx, s.pool, sess.AccountID)
	if err != nil {
		log.Printf("[auth.verify_session] account_lookup_failed ip=%s account_id=%s err=%v",
			sourceIP,
			sess.AccountID,
			err,
		)
		return nil, err
	}

	log.Printf("[auth.verify_session] account_found ip=%s account_id=%s username=%s status=%s",
		sourceIP,
		account.ID,
		account.Username,
		account.Status,
	)

	if account.Status != "active" {
		log.Printf("[auth.verify_session] account_blocked ip=%s account_id=%s status=%s",
			sourceIP,
			account.ID,
			account.Status,
		)

		s.auditSvc.Log("auth.verify_session.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "account_blocked",
			"status": account.Status,
		})

		return &VerifySessionResult{
			Authorized: false,
			Reason:     "account_blocked",
		}, nil
	}

	if device.BindingStatus == "suspended" || device.BindingStatus == "banned" {
		log.Printf("[auth.verify_session] device_blocked ip=%s account_id=%s device_id=%s status=%s",
			sourceIP,
			account.ID,
			device.ID,
			device.BindingStatus,
		)

		s.auditSvc.Log("auth.verify_session.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": "device_blocked",
			"status": device.BindingStatus,
		})

		return &VerifySessionResult{
			Authorized: false,
			Reason:     "device_blocked",
		}, nil
	}

	ent, err := s.entSvc.Resolve(ctx, account.ID)
	if err != nil {
		log.Printf("[auth.verify_session] entitlement_lookup_failed ip=%s account_id=%s err=%v",
			sourceIP,
			account.ID,
			err,
		)
		return nil, err
	}

	log.Printf("[auth.verify_session] entitlement_result ip=%s account_id=%s authorized=%t reason=%q plan=%s modules=%v features=%v channel=%s expires_at=%v",
		sourceIP,
		account.ID,
		ent.Authorized,
		ent.Reason,
		ent.PlanTier,
		ent.EnabledModules,
		ent.EnabledFeatures,
		ent.ContentChannel,
		ent.EntitlementExpiresAt,
	)

	if !ent.Authorized {
		log.Printf("[auth.verify_session] entitlement_denied ip=%s account_id=%s reason=%s",
			sourceIP,
			account.ID,
			ent.Reason,
		)

		s.auditSvc.Log("auth.verify_session.entitlement_denied", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
			"reason": ent.Reason,
		})

		return &VerifySessionResult{
			Authorized: false,
			Reason:     ent.Reason,
		}, nil
	}

	manifestURL, manifestSig, manifestErr := s.resolveManifest(ctx, ent.ContentChannel)
	if manifestErr == nil {

		log.Printf("[auth.verify_session] manifest_found ip=%s account_id=%s channel=%s manifest_id=%s manifest_url=%s signature_present=%t",
			sourceIP,
			account.ID,
			ent.ContentChannel,
			manifestIDFromURL(manifestURL),
			manifestURL,
			manifestSig != "",
		)
	} else {
		log.Printf("[auth.verify_session] manifest_missing_or_disabled ip=%s account_id=%s channel=%s err=%v",
			sourceIP,
			account.ID,
			ent.ContentChannel,
			manifestErr,
		)
	}

	minecraftUsername := ""
	if device.MinecraftUsername != nil {
		minecraftUsername = strings.TrimSpace(*device.MinecraftUsername)
	}

	modules := ent.EnabledModules
	if modules == nil {
		modules = []string{}
	}

	features := ent.EnabledFeatures
	if features == nil {
		features = []string{}
	}

	s.auditSvc.Log("auth.verify_session.success", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
		"plan_tier":          ent.PlanTier,
		"minecraft_username": minecraftUsername,
	})

	log.Printf("[auth.verify_session] success ip=%s account_id=%s username=%s minecraft_bound=%t minecraft_username=%q modules=%v manifest_present=%t duration_ms=%d",
		sourceIP,
		account.ID,
		account.Username,
		minecraftUsername != "",
		minecraftUsername,
		modules,
		manifestURL != "",
		time.Since(started).Milliseconds(),
	)

	return &VerifySessionResult{
		Authorized:           true,
		AccountID:             account.ID,
		Username:              account.Username,
		MinecraftUsername:     minecraftUsername,
		PlanTier:              ent.PlanTier,
		Modules:               modules,
		Features:              features,
		ManifestURL:           manifestURL,
		ManifestSignature:     manifestSig,
		EntitlementExpiresAt: ent.EntitlementExpiresAt,
	}, nil
}

func normalize(s string) string {
	return strings.ToLower(strings.TrimSpace(s))
}

func (s *Service) resolveManifest(ctx context.Context, channel string) (string, string, error) {
	manifest, err := db.GetLatestManifest(ctx, s.pool, channel)
	if err == nil {
		return s.baseURL + "/content/manifest/" + manifest.ID, manifest.Signature, nil
	}

	stableManifest, stableErr := content.BuildStableManifest(
		ctx,
		s.cfg.ContentDir,
		s.baseURL,
		channel,
		s.cfg.ManifestSigningKey,
		s.cfg.ModuleEncryptionKey,
	)
	if stableErr != nil {
		return "", "", err
	}

	return s.baseURL + "/content/manifest/stable", stableManifest.Signature, nil
}

func manifestIDFromURL(value string) string {
	value = strings.TrimRight(value, "/")
	if idx := strings.LastIndex(value, "/"); idx >= 0 {
		return value[idx+1:]
	}
	return value
}

func normalizeHWID(s string) string {
	return strings.ToUpper(strings.TrimSpace(s))
}

func shortToken(raw string) string {
	raw = strings.TrimSpace(raw)
	if len(raw) <= 12 {
		return raw
	}
	return raw[:6] + "..." + raw[len(raw)-6:]
}

func shortHash(raw string) string {
	raw = strings.TrimSpace(raw)
	if len(raw) <= 16 {
		return raw
	}
	return raw[:8] + "..." + raw[len(raw)-8:]
}

func ptrStringValue(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
