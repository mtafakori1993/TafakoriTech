// Package main is the entry-point for the Marshal service.
//
//	@title			Marshal API
//	@version		1.0
//	@description	Manages projects for the Commander app.
//	@contact.name	Tafakori Tech
//
//	@host		127.0.0.1:8090
//	@BasePath	/
//
//	@securityDefinitions.apikey	ApiKeyAuth
//	@in							header
//	@name						X-API-Key
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	"github.com/mtafakori/marshal/internal/config"
	"github.com/mtafakori/marshal/internal/db"
	"github.com/mtafakori/marshal/internal/handler"
	"github.com/mtafakori/marshal/internal/middleware"
)

//go:embed docs/swagger.json
var openapiSpec []byte

func main() {
	_ = godotenv.Load() // load .env if present (non-fatal if missing)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	ctx := context.Background()

	store, err := db.New(ctx, cfg.DBURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer store.Close()

	if err := store.Migrate(ctx); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	r := gin.Default()
	r.Use(gin.Recovery())
	r.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	})

	// OpenAPI spec (no UI — Swagger UI is served by Kong)
	r.GET("/openapi.json", func(c *gin.Context) {
		spec := openapiSpec
		if basePath := os.Getenv("SWAGGER_BASE_PATH"); basePath != "" {
			var doc map[string]any
			if err := json.Unmarshal(openapiSpec, &doc); err == nil {
				host := os.Getenv("SWAGGER_HOST")
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
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "marshal"})
	})

	// Authenticated API group
	api := r.Group("/api/v1", middleware.JWTAuth(cfg.JWTSecret))
	{
		ph := handler.NewProjects(store)
		p := api.Group("/projects")
		p.GET("", ph.List)
		p.POST("", ph.Create)
		p.GET("/:id", ph.Get)
		p.PUT("/:id", ph.Update)
		p.DELETE("/:id", ph.Delete)
	}

	srv := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Printf("Marshal listening on %s:%d", cfg.Host, cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()

	// Graceful shutdown on SIGINT / SIGTERM
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down gracefully…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Fatalf("forced shutdown: %v", err)
	}
	log.Println("Done.")
}
