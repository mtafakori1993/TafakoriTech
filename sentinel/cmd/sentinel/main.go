package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"

	"github.com/mtafakori/sentinel/internal/audit"
	"github.com/mtafakori/sentinel/internal/blacklist"
	"github.com/mtafakori/sentinel/internal/captcha"
	"github.com/mtafakori/sentinel/internal/config"
	"github.com/mtafakori/sentinel/internal/db"
	"github.com/mtafakori/sentinel/internal/handler"
	"github.com/mtafakori/sentinel/internal/logger"
	"github.com/mtafakori/sentinel/internal/mfa"
	"github.com/mtafakori/sentinel/internal/middleware"
	"github.com/mtafakori/sentinel/internal/persona"
	"github.com/mtafakori/sentinel/internal/pulse"
	rl "github.com/mtafakori/sentinel/internal/ratelimit"
	"github.com/mtafakori/sentinel/internal/service"
)

//go:embed swagger.json
var openapiSpec []byte

func main() {
	_ = godotenv.Load()

	logger.Init("sentinel")
	auditLog := audit.New("sentinel", 2048)
	defer auditLog.Shutdown()

	cfg, err := config.Load()
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("config load failed")
	}

	ctx := context.Background()

	pool, err := db.New(ctx, cfg.DBURL)
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("db connection failed")
	}
	defer pool.Close()

	redisOpts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("redis URL parse failed")
	}
	rdb := redis.NewClient(redisOpts)
	defer rdb.Close()

	if err := rdb.Ping(ctx).Err(); err != nil {
		logger.Log.Warn().Err(err).Msg("redis ping failed — rate limiting and fail-counters disabled (fail open)")
	}

	limiter := rl.New(rdb)
	rateLimiter := middleware.NewRateLimiter(limiter, auditLog)

	bl := blacklist.New(rdb, cfg.AccessTokenTTL)

	altchaVerifier := captcha.New(cfg.AltchaHMACKey, cfg.AltchaMaxNumber, cfg.AltchaExpires)
	if altchaVerifier.Enabled() {
		logger.Log.Info().Msg("Altcha CAPTCHA enabled")
	}

	personaClient := persona.New(cfg.PersonaURL, cfg.PersonaAPIKey)

	var pulseClient *pulse.Client
	if cfg.PulseURL != "" && cfg.PulseAPIKey != "" {
		pulseClient = pulse.New(cfg.PulseURL, cfg.PulseAPIKey)
		logger.Log.Info().Str("pulse_url", cfg.PulseURL).Msg("pulse OTP dispatch enabled")
	} else {
		logger.Log.Warn().Msg("pulse OTP dispatch disabled: PULSE_URL or PULSE_API_KEY not set")
	}

	svc := service.New(pool, rdb, cfg, personaClient, pulseClient, bl)

	mfaCrypto, err := mfa.NewCrypto(cfg.MFAEncryptionKey, cfg.RecoveryPepper)
	if err != nil {
		logger.Log.Fatal().Err(err).Msg("mfa crypto init failed")
	}
	mfaSvc := mfa.NewService(pool, mfaCrypto, cfg.TOTPIssuer, cfg.TOTPSkew)

	h := handler.New(svc, mfaSvc, auditLog)

	if cfg.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	r.Use(middleware.Tracing())
	r.Use(middleware.Recovery())
	r.Use(middleware.RequestLogger())
	r.Use(corsMiddleware())

	// Observability
	r.GET("/metrics", gin.WrapH(promhttp.Handler()))
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "sentinel"})
	})
	r.GET("/openapi.json", openAPIHandler())

	// Altcha challenge endpoint — issues a new proof-of-work challenge
	r.GET("/altcha/challenge", func(c *gin.Context) {
		if !altchaVerifier.Enabled() {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "altcha not configured"})
			return
		}
		ch, err := altchaVerifier.Challenge()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create challenge"})
			return
		}
		c.JSON(http.StatusOK, ch)
	})

	// Public auth endpoints
	rl_ := cfg.RateLimits
	ipLoginRule := rl.Rule{Limit: rl_.Login.Limit, Window: rl_.Login.Window}
	captchaCheck := middleware.RequireCaptcha(altchaVerifier)

	r.POST("/otp/request",
		rateLimiter.ByIP(ipLoginRule),
		rateLimiter.ByIdentifier(rl.Rule{Limit: rl_.OTPRequest.Limit, Window: rl_.OTPRequest.Window}),
		captchaCheck,
		h.OTPRequest,
	)
	r.POST("/otp/verify",
		rateLimiter.ByIP(ipLoginRule),
		rateLimiter.ByIdentifier(rl.Rule{Limit: rl_.OTPVerify.Limit, Window: rl_.OTPVerify.Window}),
		captchaCheck,
		h.OTPVerify,
	)
	// Second-factor completion: gated by the mfa_challenge token from /otp/verify.
	r.POST("/mfa/login",
		rateLimiter.ByIP(ipLoginRule),
		h.MFALogin,
	)
	r.POST("/refresh",
		rateLimiter.ByIP(rl.Rule{Limit: rl_.Refresh.Limit, Window: rl_.Refresh.Window}),
		h.Refresh,
	)
	r.POST("/logout", rateLimiter.ByIP(ipLoginRule), h.Logout)

	// Protected session endpoints (any authenticated user)
	authed := r.Group("/", middleware.RequireAuth([]byte(cfg.JWTSecret), bl))
	{
		authed.GET("/sessions", h.ListSessions)
		authed.DELETE("/sessions/:session_uuid", h.RevokeSession)
		authed.POST("/sessions/logout-all", h.LogoutAll)

		// MFA enrollment / management (per-user rate limited)
		mfaSetupRule := rl.Rule{Limit: rl_.MFASetup.Limit, Window: rl_.MFASetup.Window}
		authed.POST("/mfa/setup", rateLimiter.ByUser(mfaSetupRule), h.MFASetup)
		authed.POST("/mfa/verify-setup", rateLimiter.ByUser(mfaSetupRule), h.MFAVerifySetup)
		authed.POST("/mfa/disable", rateLimiter.ByUser(mfaSetupRule), h.MFADisable)
		authed.POST("/mfa/recovery-codes/generate", rateLimiter.ByUser(rl.Rule{Limit: 3, Window: time.Hour}), h.GenRecoveryCodes)
	}

	// Admin-only account management
	admin := r.Group("/accounts",
		middleware.RequireAuth([]byte(cfg.JWTSecret), bl),
		middleware.RequireRole("super_admin"),
	)
	{
		admin.POST("/lock", h.LockAccount)
		admin.POST("/unlock", h.UnlockAccount)
	}

	srv := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		logger.Log.Info().Str("addr", srv.Addr).Msg("sentinel starting")
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Log.Fatal().Err(err).Msg("server failed")
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Log.Info().Msg("shutting down gracefully")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Log.Fatal().Err(err).Msg("forced shutdown")
	}
	logger.Log.Info().Msg("stopped")
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID, X-Captcha-Token")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

func openAPIHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		spec := openapiSpec
		var doc map[string]any
		if err := json.Unmarshal(openapiSpec, &doc); err == nil {
			host := os.Getenv("SWAGGER_HOST")
			if host == "" {
				host = c.Request.Host
			}
			doc["host"] = host
			if basePath := os.Getenv("SWAGGER_BASE_PATH"); basePath != "" {
				doc["basePath"] = basePath
			}
			if patched, err := json.Marshal(doc); err == nil {
				spec = patched
			}
		}
		c.Data(http.StatusOK, "application/json", spec)
	}
}
