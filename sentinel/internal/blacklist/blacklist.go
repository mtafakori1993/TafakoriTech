package blacklist

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

const keyPrefix = "sentinel:session:revoked:"

// Blacklist tracks revoked session UUIDs in Redis.
// Access tokens embed a session UUID (sid claim); the middleware
// rejects any token whose session appears here, achieving immediate
// revocation without waiting for the token's exp to pass.
//
// TTL is set to the service's AccessTokenTTL — after that window, no
// live access token bearing the session could reach us anyway.
type Blacklist struct {
	rdb *redis.Client
	ttl time.Duration
}

func New(rdb *redis.Client, accessTokenTTL time.Duration) *Blacklist {
	return &Blacklist{rdb: rdb, ttl: accessTokenTTL}
}

// RevokeSession adds sessionUUID to the blacklist.  Idempotent.
func (b *Blacklist) RevokeSession(ctx context.Context, sessionUUID string) error {
	if sessionUUID == "" {
		return nil
	}
	return b.rdb.Set(ctx, keyPrefix+sessionUUID, "1", b.ttl).Err()
}

// IsSessionRevoked returns true if sessionUUID is on the blacklist.
// Treats Redis errors as "not revoked" (fail-open) to avoid blocking
// legitimate traffic on transient Redis hiccups.
func (b *Blacklist) IsSessionRevoked(ctx context.Context, sessionUUID string) bool {
	if sessionUUID == "" {
		return false
	}
	n, err := b.rdb.Exists(ctx, keyPrefix+sessionUUID).Result()
	return err == nil && n > 0
}
