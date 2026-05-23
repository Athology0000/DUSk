package auth

import (
	"errors"
	"log"
	"strings"

	"github.com/phantom/server/internal/middleware"
	"github.com/gofiber/fiber/v2"
)

type verifyModuleRequest struct {
	ModuleName        string `json:"module_name"`
	MinecraftUsername string `json:"minecraft_username"`
}

func handleVerifyModule(svc *Service) fiber.Handler {
	return func(c *fiber.Ctx) error {
		ip := middleware.GetRealIP(c)

		rawToken, err := middleware.ParseBearerToken(c.Get("Authorization"))
		if err != nil {
			return c.Status(401).JSON(fiber.Map{
				"authorized": false,
				"reason":     "missing_session_token",
			})
		}

		var req verifyModuleRequest
		if err := c.BodyParser(&req); err != nil {
			return c.Status(400).JSON(fiber.Map{
				"authorized": false,
				"reason":     "invalid_request",
			})
		}

		moduleName := strings.TrimSpace(req.ModuleName)
		mcUsername := strings.TrimSpace(req.MinecraftUsername)
		if moduleName == "" {
			return c.Status(400).JSON(fiber.Map{
				"authorized": false,
				"reason":     "missing_module_name",
			})
		}

		result, err := svc.VerifyModule(c.Context(), rawToken, moduleName, mcUsername, ip)
		if errors.Is(err, ErrSessionInvalid) {
			return c.Status(401).JSON(fiber.Map{
				"authorized": false,
				"reason":     "session_invalid",
			})
		}
		if err != nil {
			log.Printf("[auth.verify_module.route] internal_error ip=%s module=%s err=%v", ip, moduleName, err)
			return c.Status(500).JSON(fiber.Map{
				"authorized": false,
				"reason":     "internal_error",
			})
		}

		status := 200
		if !result.Authorized {
			status = 403
		}

		log.Printf("[auth.verify_module.route] ip=%s module=%s authorized=%t reason=%s",
			ip, moduleName, result.Authorized, result.Reason,
		)

		return c.Status(status).JSON(fiber.Map{
			"authorized": result.Authorized,
			"reason":     result.Reason,
		})
	}
}
