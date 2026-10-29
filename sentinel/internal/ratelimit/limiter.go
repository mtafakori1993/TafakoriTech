package ratelimit

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// slidingWindow is a Lua script for atomic sliding window rate limiting.
// Returns {1, 0} if allowed, or {0, retryAfterMs} if denied.
const slidingWindow = `
local key     = KEYS[1]
local now     = tonumber(ARGV[1])
local window  = tonumber(ARGV[2])
local limit   = tonumber(ARGV[3])
local id      = ARGV[4]

local cutoff = now - window
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
local count = redis.call('ZCARD', key)

if count >= limit then
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    if #oldest > 0 then
        return {0, tonumber(oldest[2]) + window - now}
    end
    return {0, window}
end

redis.call('ZADD', key, now, id)
redis.call('PEXPIRE', key, window + 1000)
return {1, 0}
`

type Rule struct {
	Limit  int
	Window time.Duration
}

type Result struct {
	Allowed    bool
	RetryAfter time.Duration
}

type Limiter struct {
	rdb    *redis.Client
	script *redis.Script
}

func New(rdb *redis.Client) *Limiter {
	return &Limiter{
		rdb:    rdb,
		script: redis.NewScript(slidingWindow),
	}
}

// Allow checks whether the given key is within the rate limit.
// keyType is a namespace prefix (e.g. "ip", "identifier", "user").
// On Redis error the limiter fails open to avoid blocking the service.
func (l *Limiter) Allow(ctx context.Context, keyType, key string, rule Rule) (*Result, error) {
	redisKey := fmt.Sprintf("ratelimit:%s:%s", keyType, key)
	nowMS := time.Now().UnixMilli()
	windowMS := rule.Window.Milliseconds()

	vals, err := l.script.Run(ctx, l.rdb,
		[]string{redisKey},
		nowMS, windowMS, rule.Limit, uuid.New().String(),
	).Int64Slice()
	if err != nil {
		// Fail open: do not block traffic on Redis failure
		return &Result{Allowed: true}, err
	}

	return &Result{
		Allowed:    vals[0] == 1,
		RetryAfter: time.Duration(vals[1]) * time.Millisecond,
	}, nil
}
