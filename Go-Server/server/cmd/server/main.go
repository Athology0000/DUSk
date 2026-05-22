package main

import (
	"context"
	"io"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/phantom/server/internal/admin"
	"github.com/phantom/server/internal/audit"
	"github.com/phantom/server/internal/auth"
	"github.com/phantom/server/internal/cache"
	"github.com/phantom/server/internal/config"
	"github.com/phantom/server/internal/content"
	"github.com/phantom/server/internal/db"
	"github.com/phantom/server/internal/enrollment"
	"github.com/phantom/server/internal/entitlement"
	"github.com/phantom/server/internal/logbuf"
	"github.com/phantom/server/internal/middleware"
	"github.com/phantom/server/internal/panel"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
)

func main() {
	log.SetOutput(io.MultiWriter(os.Stdout, logbuf.Writer()))

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	log.Println("BOOTING PHANTOM SERVER VERSION: railway-debug-root-health-v2")

	ctx := context.Background()

	pool, err := db.NewPool(ctx, cfg.DBURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer pool.Close()

	if err := db.RunMigrations(ctx, pool, cfg.MigrationsDir); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	rdb, err := cache.NewClient(cfg.RedisURL)
	if err != nil {
		log.Fatalf("redis: %v", err)
	}
	defer rdb.Close()

	entSvc := entitlement.New(pool)
	auditSvc := audit.New(pool)

	authSvc := auth.New(
		pool,
		rdb,
		entSvc,
		auditSvc,
		cfg.MasterKey,
		cfg.ServerPepper,
		cfg.BaseURL,
		cfg,
	)

	enrollSvc := enrollment.New(
		pool,
		auditSvc,
		cfg.MasterKey,
		cfg.ServerPepper,
	)

	contentSvc := content.New(
		pool,
		entSvc,
		cfg.ContentDir,
		cfg.ManifestSigningKey,
		cfg.ModuleEncryptionKey,
		cfg.BaseURL,
	)

	// =========================
	// Public API server
	// =========================
	pub := fiber.New(fiber.Config{
		DisableStartupMessage: true,
		BodyLimit:             cfg.BodyLimit,
	})

	pub.Use(middleware.RealIP())

	pub.Use(cors.New(cors.Config{
		AllowOrigins: cfg.PublicCORSAllowOrigins,
		AllowHeaders: "Content-Type,Authorization",
		AllowMethods: "GET,POST,PUT,PATCH,DELETE,OPTIONS",
	}))

	pub.Use(middleware.RateLimit(
		rdb,
		120,
		time.Minute,
		middleware.IPKey("global"),
	))

	pub.Use(middleware.SecurityHeaders())

	pub.Get("/", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"ok":      true,
			"service": "phantom-public-api",
			"message": "Phantom public API is online",
			"version": "railway-debug-root-health-v2",
		})
	})

	pub.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"ok":        true,
			"service":   "phantom-public-api",
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"version":   "railway-debug-root-health-v2",
		})
	})

	pub.Post("/debug/body", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"ok":           true,
			"content_type": c.Get("Content-Type"),
			"body_len":     len(c.Body()),
			"body":         string(c.Body()),
		})
	})

	auth.RegisterRoutes(pub, authSvc, pool, rdb, auditSvc, cfg)
	enrollment.RegisterRoutes(pub, enrollSvc, rdb)
	panel.RegisterRoutes(pub, pool, rdb, auditSvc, cfg.MasterKey)
	content.RegisterRoutes(pub, contentSvc, pool, rdb, cfg.StrictSessionIP)

	// =========================
	// Admin API server
	// =========================
	adm := fiber.New(fiber.Config{
		DisableStartupMessage: true,
		BodyLimit:             cfg.BodyLimit,
	})

	adm.Use(middleware.RealIP())

	adm.Use(cors.New(cors.Config{
		AllowOrigins: cfg.AdminCORSAllowOrigins,
		AllowHeaders: "Content-Type,Authorization",
		AllowMethods: "GET,POST,PUT,PATCH,DELETE,OPTIONS",
	}))

	adm.Use(middleware.RateLimit(
		rdb,
		60,
		time.Minute,
		middleware.IPKey("admin-global"),
	))

	adm.Use(middleware.SecurityHeaders())

	adm.Get("/", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"ok":      true,
			"service": "phantom-admin-api",
			"message": "Phantom admin API is online",
			"version": "railway-debug-root-health-v2",
		})
	})

	adm.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"ok":        true,
			"service":   "phantom-admin-api",
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"version":   "railway-debug-root-health-v2",
		})
	})

	admin.RegisterRoutes(
		adm,
		pool,
		rdb,
		cfg.ManifestSigningKey,
		auditSvc,
		cfg.AdminAPISecret,
	)

	// =========================
	// Start servers
	// =========================
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Printf("public listening on :%s", cfg.PublicPort)

		if err := pub.Listen(":" + cfg.PublicPort); err != nil {
			log.Printf("public server stopped: %v", err)
		}
	}()

	go func() {
		log.Printf("admin listening on :%s", cfg.AdminPort)

		if err := adm.Listen(":" + cfg.AdminPort); err != nil {
			log.Printf("admin server stopped: %v", err)
		}
	}()

	<-quit

	log.Println("shutting down")

	if err := pub.ShutdownWithTimeout(10 * time.Second); err != nil {
		log.Printf("public shutdown error: %v", err)
	}

	if err := adm.ShutdownWithTimeout(10 * time.Second); err != nil {
		log.Printf("admin shutdown error: %v", err)
	}
}
