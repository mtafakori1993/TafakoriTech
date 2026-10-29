// Package main is the entry-point for the Persona IAM service.
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	"go.uber.org/zap"

	access_postgres "github.com/mtafakori/persona/internal/access/repository/postgres"
	access_handler "github.com/mtafakori/persona/internal/access/handler"
	access_service "github.com/mtafakori/persona/internal/access/service"
	perm_handler "github.com/mtafakori/persona/internal/permission/handler"
	perm_postgres "github.com/mtafakori/persona/internal/permission/repository/postgres"
	perm_service "github.com/mtafakori/persona/internal/permission/service"
	role_handler "github.com/mtafakori/persona/internal/role/handler"
	role_postgres "github.com/mtafakori/persona/internal/role/repository/postgres"
	role_service "github.com/mtafakori/persona/internal/role/service"
	team_handler "github.com/mtafakori/persona/internal/team/handler"
	team_postgres "github.com/mtafakori/persona/internal/team/repository/postgres"
	team_service "github.com/mtafakori/persona/internal/team/service"
	user_handler "github.com/mtafakori/persona/internal/user/handler"
	user_postgres "github.com/mtafakori/persona/internal/user/repository/postgres"
	user_service "github.com/mtafakori/persona/internal/user/service"
	"github.com/mtafakori/persona/shared/audit"
	"github.com/mtafakori/persona/shared/config"
	"github.com/mtafakori/persona/shared/db"
	"github.com/mtafakori/persona/shared/middleware"
)

//go:embed swagger.json
var openapiSpec []byte

func main() {
	_ = godotenv.Load() // load .env if present (non-fatal if missing)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	// Logger
	var logger *zap.Logger
	if cfg.Env == "production" {
		logger, err = zap.NewProduction()
	} else {
		logger, err = zap.NewDevelopment()
	}
	if err != nil {
		log.Fatalf("logger: %v", err)
	}
	defer logger.Sync() //nolint:errcheck

	// Audit logger — async, buffered, stdout JSON
	auditLog := audit.New("persona", 512)
	defer auditLog.Shutdown()

	// Database
	ctx := context.Background()
	pool, err := db.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Fatal("database", zap.Error(err))
	}
	defer pool.Close()

	// Repositories
	userRepo := user_postgres.New(pool)
	roleRepo := role_postgres.New(pool)
	permRepo := perm_postgres.New(pool)
	teamRepo := team_postgres.New(pool)
	accessRepo := access_postgres.New(pool)

	// Services
	userSvc := user_service.New(userRepo)
	roleSvc := role_service.New(roleRepo)
	permSvc := perm_service.New(permRepo)
	teamSvc := team_service.New(teamRepo)
	accessSvc := access_service.New(accessRepo)

	// Handlers
	userH := user_handler.New(userSvc, auditLog)
	roleH := role_handler.New(roleSvc, auditLog)
	permH := perm_handler.New(permSvc, auditLog)
	teamH := team_handler.New(teamSvc)
	accessH := access_handler.New(accessSvc)

	// Router
	if cfg.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	r.Use(gin.Recovery())

	// CORS
	r.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-ID, X-Trace-ID, X-API-Key")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	})

	// Global middleware
	r.Use(middleware.RequestID())
	r.Use(middleware.TraceID())
	r.Use(middleware.Logger(logger))

	// OpenAPI spec (patched with env overrides)
	r.GET("/openapi.json", func(c *gin.Context) {
		spec := openapiSpec
		if basePath := cfg.SwaggerBasePath; basePath != "" {
			var doc map[string]any
			if err := json.Unmarshal(openapiSpec, &doc); err == nil {
				host := cfg.SwaggerHost
				if host == "" {
					host = c.Request.Host
				}
				doc["host"] = host
				doc["basePath"] = basePath
				if patched, err := json.Marshal(doc); err == nil {
					spec = patched
				}
			}
		}
		c.Data(http.StatusOK, "application/json", spec)
	})

	// Health check (no auth required)
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "persona"})
	})

	// Internal service-to-service routes (API key required, no JWT)
	internal := r.Group("/internal", middleware.APIKey(cfg.APIKey))
	userH.RegisterInternalRoutes(internal)
	accessH.RegisterInternalRoutes(internal)

	// Authenticated API group
	api := r.Group("/api/v1", middleware.Auth(cfg.JWTSecret))
	userH.RegisterRoutes(api)
	roleH.RegisterRoutes(api)
	permH.RegisterRoutes(api)
	teamH.RegisterRoutes(api)

	// HTTP server with graceful shutdown
	addr := cfg.Host + ":" + cfg.Port
	srv := &http.Server{
		Addr:    addr,
		Handler: r,
	}

	go func() {
		logger.Info("persona listening", zap.String("addr", addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("shutdown error", zap.Error(err))
	}
	logger.Info("persona stopped")
}
