// Package main is the entry-point for the Herald CMS service.
//
//	@title			Herald CMS API
//	@version		1.0
//	@description	Headless CMS for managing pages, posts, and articles.
//	@contact.name	Tafakori Tech
//
//	@host		127.0.0.1:8092
//	@BasePath	/api/v1
//
//	@securityDefinitions.apikey	ApiKeyAuth
//	@in							header
//	@name						X-API-Key
package main

import (
	"context"
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
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"

	"github.com/mtafakori/herald/docs"
	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/content/repository/gormsql"
	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest"
	"github.com/mtafakori/herald/internal/shared/config"
	"github.com/mtafakori/herald/internal/shared/logger"
)

func main() {
	_ = godotenv.Load()

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	appLog, err := logger.New(cfg.Env)
	if err != nil {
		log.Fatalf("logger: %v", err)
	}
	defer appLog.Sync() //nolint:errcheck

	db, err := openDB(cfg.DBURL, cfg.Env)
	if err != nil {
		appLog.Fatal("db connect", zap.Error(err))
	}

	if err := runMigrations(db); err != nil {
		appLog.Fatal("migrations", zap.Error(err))
	}

	// Wire repositories
	contentRepo := gormsql.NewContentRepository(db)
	pageRepo := gormsql.NewPageRepository(db)
	postRepo := gormsql.NewPostRepository(db)
	articleRepo := gormsql.NewArticleRepository(db)
	tagRepo := gormsql.NewTagRepository(db)

	// Wire services
	pageSvc := service.NewPageService(pageRepo, contentRepo, appLog)
	postSvc := service.NewPostService(postRepo, tagRepo, appLog)
	articleSvc := service.NewArticleService(articleRepo, tagRepo, appLog)
	publishSvc := service.NewPublishService(contentRepo, appLog)
	searchSvc := service.NewSearchService(contentRepo)

	r := rest.NewRouter(cfg.APIKey, cfg.JWTSecret, appLog, pageSvc, postSvc, articleSvc, publishSvc, searchSvc)

	// Serve OpenAPI spec — base path is patched at request time for Kong compatibility
	r.GET("/openapi.json", func(c *gin.Context) {
		raw := []byte(docs.SwaggerInfo.ReadDoc())
		spec := raw
		if basePath := os.Getenv("SWAGGER_BASE_PATH"); basePath != "" {
			var doc map[string]any
			if err := json.Unmarshal(raw, &doc); err == nil {
				host := os.Getenv("SWAGGER_HOST")
				if host == "" {
					host = c.Request.Host
				}
				doc["host"] = host
				doc["basePath"] = basePath + "/api/v1"
				if patched, err := json.Marshal(doc); err == nil {
					spec = patched
				}
			}
		}
		c.Data(http.StatusOK, "application/json", spec)
	})

	srv := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		appLog.Info("Herald listening", zap.String("addr", srv.Addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			appLog.Fatal("listen", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	appLog.Info("shutting down gracefully…")
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		appLog.Fatal("forced shutdown", zap.Error(err))
	}
	appLog.Info("done")
}

func openDB(dsn, env string) (*gorm.DB, error) {
	level := gormlogger.Error
	if env != "production" {
		level = gormlogger.Info
	}
	return gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: gormlogger.Default.LogMode(level),
	})
}

// runMigrations auto-migrates GORM models (schema only — no data).
// For data migrations use the SQL files in /migrations.
func runMigrations(db *gorm.DB) error {
	return db.AutoMigrate(
		&domain.Content{},
		&domain.Category{},
		&domain.Tag{},
		&domain.Page{},
		&domain.Post{},
		&domain.Article{},
	)
}
