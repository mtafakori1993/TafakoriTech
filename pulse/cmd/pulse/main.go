package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"

	"github.com/mtafakori/pulse/internal/config"
	"github.com/mtafakori/pulse/internal/db"
	"github.com/mtafakori/pulse/internal/dispatcher"
	"github.com/mtafakori/pulse/internal/handler"
	"github.com/mtafakori/pulse/internal/logger"
	"github.com/mtafakori/pulse/internal/middleware"
	"github.com/mtafakori/pulse/internal/provider"
	"github.com/mtafakori/pulse/internal/store"
)

func main() {
	_ = godotenv.Load()

	logger.Init("pulse")

	cfg, err := config.Load()
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("config load failed")
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := db.New(ctx, cfg.DBURL)
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("db connection failed")
	}
	defer pool.Close()

	st := store.New(pool)

	emailProvider := provider.NewEmailProvider(provider.EmailConfig{
		Host: cfg.SMTPHost,
		Port: cfg.SMTPPort,
		User: cfg.SMTPUser,
		Pass: cfg.SMTPPass,
		From: cfg.SMTPFrom,
	})
	smsProvider := selectSMSProvider(cfg)
	pushProvider := provider.NewPushProvider()

	d := dispatcher.New(cfg.WorkerCount, cfg.QueueBuffer, st, emailProvider, smsProvider, pushProvider)
	d.Start(ctx)
	d.Recover(ctx, 30*time.Second)

	h := handler.New(st, d)

	if cfg.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	r.Use(middleware.Tracing())
	r.Use(middleware.Recovery())
	r.Use(middleware.RequestLogger())
	r.Use(corsMiddleware())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "pulse"})
	})

	api := r.Group("/api/v1", middleware.RequireAuth([]byte(cfg.JWTSecret)))
	{
		api.POST("/notifications", h.Submit)
		api.GET("/notifications/:id", h.GetStatus)
	}

	internal := r.Group("/internal/v1", middleware.RequireAPIKey(cfg.PulseAPIKey))
	{
		internal.POST("/notifications", h.Submit)
	}

	srv := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		logger.Log.Info().Str("addr", srv.Addr).Msg("pulse starting")
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Log.Fatal().Err(err).Msg("server failed")
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Log.Info().Msg("shutting down gracefully")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Log.Fatal().Err(err).Msg("forced shutdown")
	}
	logger.Log.Info().Msg("stopped")
}

func selectSMSProvider(cfg *config.Config) provider.Provider {
	switch cfg.SMSProvider {
	case "ghasedak":
		logger.Log.Info().Str("sms_provider", "ghasedak").
			Str("template", cfg.GhasedakTemplateName).Msg("SMS provider selected")
		return provider.NewGhasedakProvider(provider.GhasedakConfig{
			APIKey:       cfg.GhasedakAPIKey,
			LineNumber:   cfg.GhasedakLineNumber,
			TemplateName: cfg.GhasedakTemplateName,
			OTPParam:     cfg.GhasedakOTPParam,
		})
	default:
		logger.Log.Info().Str("sms_provider", "smsir").
			Int("template_id", cfg.SMSIRTemplateID).Msg("SMS provider selected")
		return provider.NewSMSIRProvider(provider.SMSIRConfig{
			APIKey:     cfg.SMSIRAPIKey,
			LineNumber: cfg.SMSIRLineNumber,
			TemplateID: cfg.SMSIRTemplateID,
			OTPParam:   cfg.SMSIROTPParam,
		})
	}
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}
