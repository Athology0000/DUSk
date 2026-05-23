package auth

import (
	"errors"
	"log"
	"strings"
	"time"

	"github.com/phantom/server/internal/audit"
	"github.com/phantom/server/internal/config"
	"github.com/phantom/server/internal/crypto"
	"github.com/phantom/server/internal/db"
	"github.com/phantom/server/internal/middleware"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

type startRequest struct {
	Username          string `json:"username"`
	HWID              string `json:"hwid"`
	MinecraftUsername string `json:"minecraft_username"`
	ClientVersion     string `json:"client_version"`
	BootstrapBuildID  string `json:"bootstrap_build_id"`
}

type finishRequest struct {
	Username          string `json:"username"`
	Proof             string `json:"proof"`
	MinecraftUsername string `json:"minecraft_username"`
}

type verifyMinecraftRequest struct {
	SessionToken      string `json:"session_token"`
	MinecraftUsername string `json:"minecraft_username"`
}

type verifySessionRequest struct {
	SessionToken      string `json:"session_token"`
	Token             string `json:"token"`
	MinecraftUsername string `json:"minecraft_username"`
}

type heartbeatRequest struct {
	SessionToken string   `json:"session_token"`
	Activity     []string `json:"activity"`
}

func RegisterRoutes(app *fiber.App, svc *Service, pool *pgxpool.Pool, rdb *redis.Client, auditSvc *audit.Service, cfg *config.Config) {
	authLimit := middleware.RateLimit(rdb, 10, time.Minute, middleware.IPAndUsernameKey("auth"))
	heartbeatLimit := middleware.RateLimit(rdb, 60, time.Minute, middleware.IPAndUsernameKey("heartbeat"))

	verifyModuleLimit := middleware.RateLimit(rdb, 60, time.Minute, middleware.IPAndUsernameKey("verify-module"))

	app.Post("/auth/start", authLimit, handleStart(svc))
	app.Post("/auth/finish", authLimit, handleFinish(svc))
	app.Post("/auth/verify-minecraft", authLimit, handleVerifyMinecraft(svc))
	app.Post("/auth/verify-session", authLimit, handleVerifySession(svc))
	app.Post("/auth/verify-module", verifyModuleLimit, handleVerifyModule(svc))
	app.Post("/auth/heartbeat", heartbeatLimit, handleHeartbeat(svc))

	panelLimit := middleware.RateLimit(rdb, 20, time.Minute, middleware.IPKey("panel-auth"))
	app.Post("/auth/login", panelLimit, handlePanelLogin(pool, auditSvc))
	app.Post("/auth/register", panelLimit, handlePanelRegister(pool, cfg.AllowPublicRegistration))
	app.Get("/user/me", handleGetMe(pool))
}

func getPanelSession(c *fiber.Ctx, pool *pgxpool.Pool) (*db.PanelSession, error) {
	raw, err := middleware.ParseBearerToken(c.Get("Authorization"))
	if err != nil {
		return nil, err
	}

	hash, err := crypto.HashToken(raw)
	if err != nil {
		return nil, err
	}

	return db.GetPanelSessionByTokenHash(c.Context(), pool, hash)
}

func buildPanelUser(account *db.Account, license *db.License) fiber.Map {
	var plan *string
	var planExpiry *time.Time

	if license != nil {
		plan = &license.PlanTier
		planExpiry = license.ExpiresAt
	}

	return fiber.Map{
		"id":         account.ID,
		"username":   account.Username,
		"email":      account.Email,
		"role":       "user",
		"plan":       plan,
		"planExpiry": planExpiry,
	}
}

func handlePanelLogin(pool *pgxpool.Pool, auditSvc *audit.Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		ip := middleware.GetRealIP(c)

		var body struct {
			Email    string `json:"email"`
			Password string `json:"password"`
		}

		if err := c.BodyParser(&body); err != nil || body.Email == "" || body.Password == "" {
			return c.Status(400).JSON(fiber.Map{"error": "invalid_request"})
		}

		account, err := db.GetAccountByEmail(c.Context(), pool, body.Email)
		if err != nil {
			auditSvc.Log("panel.login.fail", nil, nil, nil, &ip, map[string]any{
				"reason": "account_not_found",
				"email":  body.Email,
			})

			return c.Status(401).JSON(fiber.Map{
				"error":   "invalid_credentials",
				"message": "Invalid email or password",
			})
		}

		if account.Status == "banned" {
			auditSvc.Log("panel.login.fail", &account.ID, nil, nil, &ip, map[string]any{
				"reason": "banned",
			})

			return c.Status(403).JSON(fiber.Map{
				"error":   "account_banned",
				"message": "Account is banned",
			})
		}

		ok, err := crypto.VerifyPassword(body.Password, account.PasswordHash)
		if err != nil || !ok {
			auditSvc.Log("panel.login.fail", &account.ID, nil, nil, &ip, map[string]any{
				"reason": "bad_password",
			})

			return c.Status(401).JSON(fiber.Map{
				"error":   "invalid_credentials",
				"message": "Invalid email or password",
			})
		}

		raw, hash, err := crypto.GenerateToken()
		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		if _, err := db.CreatePanelSession(c.Context(), pool, hash, account.ID, time.Now().Add(30*24*time.Hour)); err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		auditSvc.Log("panel.login.success", &account.ID, nil, nil, &ip, map[string]any{
			"username": account.Username,
		})

		license, _ := db.GetLicenseByAccountID(c.Context(), pool, account.ID)

		return c.JSON(fiber.Map{
			"token": raw,
			"user":  buildPanelUser(account, license),
		})
	}
}

func handlePanelRegister(pool *pgxpool.Pool, allowPublicRegistration bool) fiber.Handler {
	return func(c *fiber.Ctx) error {
		if !allowPublicRegistration {
			return c.Status(403).JSON(fiber.Map{
				"error":   "registration_disabled",
				"message": "Public registration is disabled",
			})
		}

		var body struct {
			Username string `json:"username"`
			Email    string `json:"email"`
			Password string `json:"password"`
		}

		if err := c.BodyParser(&body); err != nil || body.Username == "" || body.Password == "" {
			return c.Status(400).JSON(fiber.Map{
				"error":   "invalid_request",
				"message": "Username and password required",
			})
		}

		if len(body.Password) < 8 {
			return c.Status(400).JSON(fiber.Map{
				"error":   "invalid_request",
				"message": "Password must be at least 8 characters",
			})
		}

		hash, err := crypto.HashPassword(body.Password)
		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		username := strings.ToLower(strings.TrimSpace(body.Username))

		var emailPtr *string
		if body.Email != "" {
			e := strings.TrimSpace(body.Email)
			emailPtr = &e
		}

		account, err := db.CreateAccount(c.Context(), pool, username, hash, emailPtr)
		if err != nil {
			return c.Status(409).JSON(fiber.Map{
				"error":   "username_taken",
				"message": "Username already taken",
			})
		}

		raw, tokenHash, err := crypto.GenerateToken()
		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		if _, err := db.CreatePanelSession(c.Context(), pool, tokenHash, account.ID, time.Now().Add(30*24*time.Hour)); err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		return c.Status(201).JSON(fiber.Map{
			"token": raw,
			"user":  buildPanelUser(account, nil),
		})
	}
}

func handleGetMe(pool *pgxpool.Pool) fiber.Handler {
	return func(c *fiber.Ctx) error {
		sess, err := getPanelSession(c, pool)
		if err != nil {
			return c.Status(401).JSON(fiber.Map{"error": "unauthorized"})
		}

		account, err := db.GetAccountByID(c.Context(), pool, sess.AccountID)
		if err != nil {
			return c.Status(404).JSON(fiber.Map{"error": "not_found"})
		}

		if account.Status == "banned" {
			return c.Status(403).JSON(fiber.Map{
				"error":   "account_banned",
				"message": "Your account has been banned",
			})
		}

		license, _ := db.GetLicenseByAccountID(c.Context(), pool, account.ID)
		device, _ := db.GetDeviceByAccountID(c.Context(), pool, account.ID)
		keyPrefix := db.GetRedeemedKeyHashPrefix(c.Context(), pool, account.ID)

		var plan *string
		var planExpiry *time.Time

		if license != nil {
			plan = &license.PlanTier
			planExpiry = license.ExpiresAt
		}

		var hwidBound bool
		var mcUsername *string

		if device != nil {
			hwidBound = device.HWIDHash != nil
			mcUsername = device.MinecraftUsername
		}

		return c.JSON(fiber.Map{
			"id":                account.ID,
			"username":          account.Username,
			"email":             account.Email,
			"plan":              plan,
			"planExpiry":        planExpiry,
			"hwid":              nil,
			"hwidBound":         hwidBound,
			"minecraftUsername": mcUsername,
			"createdAt":         account.CreatedAt,
			"licenseKeyPrefix":  keyPrefix,
		})
	}
}

func handleStart(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		var req startRequest

		if err := c.BodyParser(&req); err != nil || req.Username == "" || req.HWID == "" {
			return c.Status(401).JSON(fiber.Map{"error": "authentication_failed"})
		}

		ip := middleware.GetRealIP(c)

		result, err := svc.Start(c.Context(), req.Username, req.HWID, req.MinecraftUsername, ip)
		if errors.Is(err, ErrIPMismatch) ||
			errors.Is(err, ErrHWIDMismatch) ||
			errors.Is(err, ErrNotFound) ||
			errors.Is(err, ErrDeviceBlocked) ||
			errors.Is(err, ErrUsernameMismatch) {
			return c.Status(401).JSON(fiber.Map{"error": "authentication_failed"})
		}

		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "authentication_failed"})
		}

		return c.JSON(fiber.Map{
			"challenge":  result.Challenge,
			"expires_in": result.ExpiresIn,
		})
	}
}

func handleFinish(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		var req finishRequest

		if err := c.BodyParser(&req); err != nil || req.Username == "" || req.Proof == "" {
			return c.Status(401).JSON(fiber.Map{"error": "authentication_failed"})
		}

		ip := middleware.GetRealIP(c)

		log.Printf(
			"[auth.finish.route] request ip=%s username=%s minecraft_username=%q",
			ip,
			req.Username,
			req.MinecraftUsername,
		)

		result, err := svc.Finish(c.Context(), req.Username, req.Proof, ip, req.MinecraftUsername)
		if errors.Is(err, ErrIPMismatch) ||
			errors.Is(err, ErrBadProof) ||
			errors.Is(err, ErrNoChallenge) ||
			errors.Is(err, ErrNotFound) {
			return c.Status(401).JSON(fiber.Map{"error": "authentication_failed"})
		}

		if err != nil {
			log.Printf("[auth.finish.route] internal_error ip=%s username=%s err=%v", ip, req.Username, err)
			return c.Status(500).JSON(fiber.Map{"error": "authentication_failed"})
		}

		if !result.Authorized {
			return c.JSON(fiber.Map{
				"authenticated": true,
				"authorized":    false,
				"reason":        result.Reason,
			})
		}

		log.Printf(
			"[auth.finish.route] response ip=%s authorized=true username=%s account_id=%s plan_tier=%q expires_at=%v modules=%v features=%v manifest_url=%q manifest_sig_present=%t",
			ip,
			req.Username,
			result.AccountID,
			result.PlanTier,
			result.EntitlementExpiresAt,
			result.Modules,
			result.Features,
			result.ManifestURL,
			result.ManifestSignature != "",
		)

		return c.JSON(fiber.Map{
			"authenticated":          true,
			"authorized":             true,
			"account_id":             result.AccountID,
			"username":               result.Username,
			"session_token":          result.SessionToken,
			"expires_in":             result.ExpiresIn,
			"plan_tier":              result.PlanTier,
			"enabled_modules":        result.Modules,
			"enabled_features":       result.Features,
			"entitlement_expires_at": result.EntitlementExpiresAt,
			"manifest_url":           result.ManifestURL,
			"manifest_signature":     result.ManifestSignature,
		})
	}
}

func handleVerifyMinecraft(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		var req verifyMinecraftRequest

		if err := c.BodyParser(&req); err != nil || req.SessionToken == "" || req.MinecraftUsername == "" {
			return c.Status(400).JSON(fiber.Map{"error": "invalid_request"})
		}

		ip := middleware.GetRealIP(c)

		result, err := svc.VerifyMinecraft(c.Context(), req.SessionToken, req.MinecraftUsername, ip)
		if errors.Is(err, ErrSessionInvalid) {
			return c.Status(401).JSON(fiber.Map{"error": "session_invalid"})
		}

		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		if !result.Authorized {
			return c.JSON(fiber.Map{
				"authorized": false,
				"reason":     result.Reason,
			})
		}

		return c.JSON(fiber.Map{
			"authorized":             true,
			"plan_tier":              result.PlanTier,
			"enabled_modules":        result.Modules,
			"enabled_features":       result.Features,
			"manifest_url":           result.ManifestURL,
			"manifest_signature":     result.ManifestSignature,
			"entitlement_expires_at": result.EntitlementExpiresAt,
		})
	}
}

func handleVerifySession(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		ip := middleware.GetRealIP(c)

		log.Printf(
			"[auth.verify_session.route] request ip=%s content_type=%s body_len=%d",
			ip,
			c.Get("Content-Type"),
			len(c.Body()),
		)

		var req verifySessionRequest
		if err := c.BodyParser(&req); err != nil {
			log.Printf(
				"[auth.verify_session.route] body_parse_failed ip=%s err=%v body=%s",
				ip,
				err,
				string(c.Body()),
			)

			return c.Status(400).JSON(fiber.Map{
				"authorized": false,
				"reason":     "invalid_json",
			})
		}

		rawToken := strings.TrimSpace(req.SessionToken)
		if rawToken == "" {
			rawToken = strings.TrimSpace(req.Token)
		}

		minecraftUsername := strings.TrimSpace(req.MinecraftUsername)

		log.Printf(
			"[auth.verify_session.route] parsed ip=%s token_present=%t minecraft_username=%q",
			ip,
			rawToken != "",
			minecraftUsername,
		)

		if rawToken == "" {
			log.Printf("[auth.verify_session.route] missing_session_token ip=%s", ip)

			return c.Status(400).JSON(fiber.Map{
				"authorized": false,
				"reason":     "missing_session_token",
			})
		}

		result, err := svc.VerifySession(c.Context(), rawToken, ip, minecraftUsername)

		if errors.Is(err, ErrSessionInvalid) {
			log.Printf("[auth.verify_session.route] response ip=%s status=401 reason=session_invalid", ip)

			return c.Status(401).JSON(fiber.Map{
				"authorized": false,
				"reason":     "session_invalid",
			})
		}

		if err != nil {
			log.Printf("[auth.verify_session.route] response ip=%s status=500 err=%v", ip, err)

			return c.Status(500).JSON(fiber.Map{
				"authorized": false,
				"reason":     "internal_error",
			})
		}

		status := 200
		if !result.Authorized {
			status = 403
		}

		log.Printf(
			"[auth.verify_session.route] response ip=%s status=%d authorized=%t alias=%s account_id=%s minecraft_username=%q modules=%v manifest_present=%t",
			ip,
			status,
			result.Authorized,
			result.Username,
			result.AccountID,
			result.MinecraftUsername,
			result.Modules,
			result.ManifestURL != "",
		)

		return c.Status(status).JSON(fiber.Map{
			"authorized":             result.Authorized,
			"reason":                 result.Reason,
			"alias":                  result.Username,
			"username":               result.Username,
			"account_id":             result.AccountID,
			"plan_tier":              result.PlanTier,
			"enabled_modules":        result.Modules,
			"enabled_features":       result.Features,
			"minecraft_bound":        strings.TrimSpace(result.MinecraftUsername) != "",
			"minecraft_username":     nullableString(result.MinecraftUsername),
			"minecraft_uuid":         nil,
			"manifest_url":           result.ManifestURL,
			"manifest_signature":     result.ManifestSignature,
			"entitlement_expires_at": result.EntitlementExpiresAt,
		})
	}
}

func nullableString(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}

	return value
}

func handleHeartbeat(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		var req heartbeatRequest

		if err := c.BodyParser(&req); err != nil || req.SessionToken == "" {
			return c.Status(400).JSON(fiber.Map{"error": "invalid_request"})
		}

		ip := middleware.GetRealIP(c)

		err := svc.Heartbeat(c.Context(), req.SessionToken, ip)
		if errors.Is(err, ErrSessionInvalid) {
			return c.Status(401).JSON(fiber.Map{"error": "session_invalid"})
		}

		if err != nil {
			return c.Status(500).JSON(fiber.Map{"error": "internal_error"})
		}

		return c.JSON(fiber.Map{"status": "ok"})
	}
}
