# Rate Limiting — how it works, file by file

Written 2026-10-02, describing the implementation added on 2026-09-30.

Files involved:

    src/main/java/com/example/config/RateLimitProperties.java
    src/main/java/com/example/config/RateLimitFilterConfig.java
    src/main/java/com/example/filter/RateLimitFilter.java
    src/main/java/com/example/service/RateLimitService.java
    src/main/java/com/example/dto/RateLimitDecision.java
    src/main/resources/application.properties          (ratelimit.* block)
    frontend/src/app/core/api.service.ts               (authInterceptor)
    frontend/src/app/app.config.ts                     (registers it)


## What & Why

The expensive parts of this app are cheap to *ask for* and costly to *answer*. One
HTTP GET to /api/recommendations/206220 can mean 35 seconds of a 14-billion-parameter
model thinking; one /api/search miss means an embedding hop plus a fused Postgres
query. Nothing stopped a script — or a stuck retry loop in the browser — from asking a
thousand times.

The rate limiter is a turnstile at the front door. It counts requests per caller per
endpoint in Redis, and if you are over your allowance it hands back 429 immediately,
before any controller, database or model is touched.


## The Big Picture

      Angular  ──Authorization: Bearer <jwt>──┐
                                              │
                                              ▼
                                  ┌───────────────────────┐
       /api/*  only  ────────────▶│   RateLimitFilter     │
       (never static files)       │  which bucket? who?   │
                                  └───────────┬───────────┘
                                              │ ("search", "206220")
                                              ▼
                                  ┌───────────────────────┐    INCR + PTTL
                                  │   RateLimitService    │◀───── one Lua ─────▶  Redis
                                  │  count vs. limit      │     round trip      (Upstash)
                                  └───────────┬───────────┘
                                              │ RateLimitDecision
                         ┌────────────────────┴────────────────────┐
                  allowed│                                         │denied
                         ▼                                         ▼
            DispatcherServlet ──▶ Controller ──▶ Service      HTTP 429 + Retry-After
              (RRF, pgvector, Ollama live here)                (chain never runs)

RateLimitProperties feeds limits into the two left boxes; RateLimitFilterConfig is what
wires the filter into Tomcat at all. Neither appears in the request path itself.


## Simple Example

Asha (user 206220) is signed in and types fast enough to fire her 31st search inside
one minute.

1. The browser sends GET /api/search?q=milk. The interceptor in api.service.ts
   attaches `Authorization: Bearer eyJ...`.
2. Tomcat matches /api/* and hands the request to RateLimitFilter — BEFORE the
   DispatcherServlet exists in the picture.
3. bucketFor("/api/search") returns "search"            (RateLimitFilter.java:74-75)
4. identity() sees the Bearer prefix, calls jwtService.parseUserId(token), gets
   206220                                               (RateLimitFilter.java:97-99)
5. RateLimitService.check("search", "206220") builds the key
   rate-limit:search:206220 and runs the Lua script. INCR returns 31.
6. 31 <= 30 is false, so allowed = false, and PTTL says 24,000 ms left
                                                        (RateLimitService.java:84-87)
7. The filter never calls chain.doFilter. It writes 429 with Retry-After: 24 and
   returns                                              (RateLimitFilter.java:64)

SearchService never ran. No embedding, no RRF, no warm() queued onto Ollama. 24
seconds later her counter key expires and the next search goes through normally.

The analogy: a nightclub with a clicker at the door. The clicker resets every minute,
the bouncer never asks the bar whether it is busy, and when the count is past 30 you
simply do not get in — the bar does no work for you at all.


## Component Roles

| File | Owns | Hands off |
|---|---|---|
| frontend/src/app/core/api.service.ts (authInterceptor) | Attaching the stored JWT to every /api/ call | request + token -> server |
| config/RateLimitFilterConfig.java:28-37 | Registration only. URL pattern /api/*, order HIGHEST_PRECEDENCE + 100 | a live filter -> Tomcat |
| filter/RateLimitFilter.java:53-65 | The decision flow: which bucket, which identity, allow or reject | bucket + identity -> service |
| service/RateLimitService.java:66-99 | The counter: key, Lua, limit comparison, fail-open | a RateLimitDecision -> filter |
| dto/RateLimitDecision.java | The verdict as four values — nothing else crosses that boundary | — |
| config/RateLimitProperties.java | The tunables from application.properties | bucket config -> service and filter |


## Deeper Details

### RateLimitFilterConfig.java

Two lines here carry all the weight.

addUrlPatterns("/api/*") (line 34) is why the limiter does not count your JavaScript.
SpaController forwards /, /login, /dashboard and /search to index.html, and the page
then pulls main-*.js, six lazy chunks, two fonts and a stylesheet. Without the
pattern, one page load would burn a dozen requests from someone's allowance.

setOrder(HIGHEST_PRECEDENCE + 100) (line 35) is the whole premise of the feature.
Filters run in order ascending, so this sits near the very front — ahead of the
DispatcherServlet, therefore ahead of every controller. "Rate limit at the HTTP entry
layer" is literally this one number. The + 100 leaves room to slot something in front
later (a request-id or CORS filter) without renumbering.

And the filter is constructed BY HAND inside the @Bean method rather than being a
@Component. That is deliberate and worth remembering: Spring Boot auto-registers any
Filter bean it finds in the context, mapped to /*. Annotating the class would have
given you TWO registrations — the automatic one on every URL and this one on /api/* —
so every API call would increment twice and your real limit would silently be 15, not
30.

### RateLimitProperties.java

A plain @ConfigurationProperties holder, activated by @EnableConfigurationProperties on
the config class (so DemoApplication stayed untouched). The interesting field is the
Map<String, Bucket>: ratelimit.buckets.search.limit=30 binds by map key, so ADDING A
BUCKET NEEDS NO JAVA CHANGE — only a matching branch in bucketFor for a path that is
not already covered.

window is a Duration, which is why 1m and 10m work in the properties file rather than a
raw millisecond count.

trustForwardedHeader defaults to false on purpose, and the reason is subtle:
X-Forwarded-For is just a header, so any client can send one. Trusting it
unconditionally means an attacker rotates a fake value per request and gets infinite
allowance — strictly worse than no limiter, because now you also believe you are
protected. Trusting it is only correct behind a proxy that OVERWRITES rather than
appends.

### RateLimitFilter.java

The flow is deliberately shallow — four decisions, no branching maze:

    String bucket = bucketFor(request.getRequestURI());     // :53
    if (!properties.isEnabled() || bucket == null) { ...pass through... }
    RateLimitDecision decision = rateLimitService.check(bucket, identity(request));
    if (decision.allowed()) { ...pass through... }
    reject(response, bucket, decision);                     // :64

bucketFor (:73-84) IS AN ALLOWLIST, NOT A BLOCKLIST. Unmatched paths return null and
are not limited at all. So /api/onboarding/* (five static dropdown reads) and
/api/dashboard/* (three indexed queries) are uncounted by design. The consequence to
know: A NEW ENDPOINT IS UNLIMITED UNTIL SOMEONE ADDS IT HERE. If you build the cart and
order flow, /api/orders gets no protection until this method mentions it.

identity (:95-107) READS THE JWT BUT NEVER ENFORCES IT. A missing, expired or forged
token lands in the catch (:100), logs at debug, and falls through to
"ip:" + clientIp(...). That asymmetry is the point: this filter's job is counting, not
authentication. Returning 401 here would have broken every endpoint in the app
overnight, since nothing was sending tokens until the interceptor shipped.

It also pointedly ignores the userId the controllers actually use —
/api/dashboard/{userId}, ?userId=. That value is caller-supplied, so keying on it would
let anyone evade their limit by typing a different number. A signed JWT subject cannot
be forged; an IP at least costs something to change.

reject (:126-140) writes the JSON by hand. Not laziness — Spring Boot 4.1 ships Jackson
3 under tools.jackson.*, while the com.fasterxml 2.x on this classpath arrives
runtime-scoped via jjwt-jackson and is invisible at compile time. Four fields of string
concatenation sidesteps that trap.

### RateLimitService.java — the only file that talks to Redis

The key is rate-limit:{bucket}:{identity} (:31), which yields rate-limit:search:206220
or rate-limit:auth:ip:127.0.0.1. Separate prefix from search:q: and reco:user:, and
nothing in the codebase scans by pattern, so the three key families cannot see each
other.

Why one Lua script instead of two commands (:44-52):

    local n = redis.call('INCR', KEYS[1])
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl < 0 then
      redis.call('PEXPIRE', KEYS[1], ARGV[1])
      ttl = tonumber(ARGV[1])
    end
    return { n, ttl }

Done as INCR then a separate EXPIRE, there is a window where the INCR lands and the
EXPIRE is lost — connection drops, Upstash hiccups. The key then has NO TTL AT ALL, so
it never resets and that user is blocked forever. A fail-closed hole inside a fail-open
design, and the kind of bug that shows up once a month and looks like nothing.

Running it as a script makes it atomic and one round trip. The ttl < 0 test rather than
n == 1 also makes it self-healing: PTTL returns -1 for a key with no expiry and -2 for
one that vanished mid-script, and either way the TTL gets re-applied on the next
request instead of wedging.

Fail-open (:96-98) mirrors SearchCacheService.get exactly: catch Exception, log at
WARN, return unenforced(). Redis down -> requests flow. This is the behaviour the
project already uses everywhere, and it has a cost worth stating plainly: A BROKEN
REDIS SILENTLY DELETES YOUR RATE LIMITING, with nothing user-visible to indicate it. A
rotated REDIS_PASSWORD disables the limiter exactly the way it disables caching.
"rate limit check failed" is the string to grep.

Note the two different log levels, which is intentional: an exceeded limit is INFO
(:90-92) because it is normal operation, while a Redis failure is WARN because it means
protection is gone.

### Fixed window, and what it costs you

The window is anchored to the FIRST request, not a rolling period. Asha's first search
at 10:00:03 creates a key with a 60-second TTL; it expires at 10:01:03 regardless of
what she does in between.

The known weakness: 30 requests at 10:01:00 and 30 more at 10:01:04 are both legal, so
A 2x BURST CAN CROSS THE BOUNDARY. A sliding window needs a sorted set per identity, a
ZREMRANGEBYSCORE cleanup per request, and more memory. For keeping casual abuse off
Ollama, fixed window is the right trade — but if you are ever defending against a
deliberate attacker, this is the seam.

### Where it does NOT help

Two honest gaps:

The recommendations bucket is 10/minute, and a cold read is ~35 seconds. Ten
SIMULTANEOUS requests satisfy that limit completely while pinning Ollama. The limiter
bounds RATE, not CONCURRENCY — an in-flight guard per user would be the right tool, and
does not exist.

And /api/search queues recommendationService.warm(userId) on every authenticated search
(SearchController.java:40). 30 searches/minute means up to 30 warms pushed at a pool of
core-size 1 with queue-capacity 10. The limiter does not make that worse, but the task
executor's bounded queue — not the rate limit — is what actually protects Ollama there.


## Current configuration

    ratelimit.enabled=true
    ratelimit.trust-forwarded-header=false

    ratelimit.buckets.search.limit=30
    ratelimit.buckets.search.window=1m

    ratelimit.buckets.recommendations.limit=10
    ratelimit.buckets.recommendations.window=1m

    ratelimit.buckets.auth.limit=5
    ratelimit.buckets.auth.window=10m

The auth bucket covers both /api/auth/login and /api/auth/signup, keyed by IP. There is
no OTP endpoint in this project; this is its equivalent, and it is a password-guessing
limit rather than a throughput one.


## How to verify

With JWT_SECRET, TWILIO_* and the Upstash REDIS_* variables set:

    ./mvnw spring-boot:run

    for i in $(seq 1 6); do
      curl -s -o /dev/null -w "%{http_code} " \
        -X POST localhost:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"mobile":"9150146944","password":"wrong"}'
    done; echo

Expect: 401 401 401 401 401 429

The auth bucket is the quick one to test — 5 per 10 min beats waiting out 30 searches.
The Upstash console will show rate-limit:auth:ip:127.0.0.1 alongside the untouched
search:q:* and reco:user:* keys. If you see no limiting at all, grep the log for
"rate limit check failed" — that is the fail-open path firing, which looks exactly like
the limiter simply not being there.

NOTE: as of 2026-10-02 this has been compiled but never run. The Lua script has not
executed against a real Redis.


## Key Takeaway

The whole feature is one number and one key: setOrder(HIGHEST_PRECEDENCE + 100) puts
the check in front of every controller so a rejected request costs nothing, and
rate-limit:{bucket}:{identity} counts it in the Redis you already have. Everything else
— the Lua atomicity, the JWT-or-IP fallback, the fail-open catch — exists to stop the
limiter from becoming a new way for the app to break.
