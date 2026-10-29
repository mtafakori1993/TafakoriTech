package metrics

import (
	"strconv"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	RequestTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_requests_total",
		Help: "Total HTTP requests processed.",
	}, []string{"method", "path", "status"})

	RequestDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "sentinel_request_duration_seconds",
		Help:    "HTTP request duration in seconds.",
		Buckets: prometheus.DefBuckets,
	}, []string{"method", "path"})

	RateLimitHits = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_rate_limit_hits_total",
		Help: "Total rate limit violations.",
	}, []string{"limit_type", "endpoint"})

	AuthEvents = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_auth_events_total",
		Help: "Authentication events by type and result.",
	}, []string{"event", "result"})

	OTPAbuse = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_otp_abuse_attempts_total",
		Help: "OTP verification failures (potential abuse).",
	}, []string{"identifier_type"})

	TokenReuseTotal = promauto.NewCounter(prometheus.CounterOpts{
		Name: "sentinel_token_reuse_total",
		Help: "Total refresh token reuse (replay attack) detections.",
	})

	SessionRevocations = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_session_revocations_total",
		Help: "Session revocations by reason.",
	}, []string{"reason"}) // reason: logout, logout_all, reuse_detected, admin

	AccountLocks = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "sentinel_account_locks_total",
		Help: "Account lock events by type.",
	}, []string{"type"}) // type: auto, admin

	BlacklistHits = promauto.NewCounter(prometheus.CounterOpts{
		Name: "sentinel_blacklist_hits_total",
		Help: "Requests rejected because the session was on the JWT blacklist.",
	})
)

func RecordRequest(method, path string, status int, durationSec float64) {
	s := strconv.Itoa(status)
	RequestTotal.WithLabelValues(method, path, s).Inc()
	RequestDuration.WithLabelValues(method, path).Observe(durationSec)
}

func RecordRateLimit(limitType, endpoint string) {
	RateLimitHits.WithLabelValues(limitType, endpoint).Inc()
}

func RecordAuthEvent(event, result string) {
	AuthEvents.WithLabelValues(event, result).Inc()
}

func RecordOTPAbuse(identifierType string) {
	OTPAbuse.WithLabelValues(identifierType).Inc()
}

func RecordTokenReuse() {
	TokenReuseTotal.Inc()
}

func RecordSessionRevocation(reason string) {
	SessionRevocations.WithLabelValues(reason).Inc()
}

func RecordAccountLock(lockType string) {
	AccountLocks.WithLabelValues(lockType).Inc()
}

func RecordBlacklistHit() {
	BlacklistHits.Inc()
}
