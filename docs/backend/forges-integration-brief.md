# Forges (Codeberg / Forgejo / Gitea) — Backend Integration Brief

Context handoff for the backend team / agent. Documents what the **client**
already ships today on the `feat/e8-codeberg-forgejo` branch and what
**backend work** would unlock the next jump in performance, reliability,
and feature parity for non-GitHub forges.

---

## 1. Current state — client side only

The Android / Desktop app (KMP, branch `feat/e8-codeberg-forgejo`, PR #631)
ships a **Codeberg + Forgejo source adapter** that bypasses the backend
entirely for non-GitHub forges. All Forgejo traffic goes **directly from
device → host's `/api/v1/...` endpoint**.

### What works direct-to-Forgejo today

| Surface | Endpoint | Notes |
|---|---|---|
| Repo summary | `GET /api/v1/repos/{o}/{r}` | Stars / forks / open-issues / default branch / owner avatar |
| Release list | `GET /api/v1/repos/{o}/{r}/releases?limit=50` | CRLF normalized client-side, asset `content_type` optional |
| README | `GET /api/v1/repos/{o}/{r}/contents/README.md?ref={branch}` | `/readme` does NOT exist on Forgejo — Gitea/Forgejo specific gotcha |
| README fallback | `GET /api/v1/repos/{o}/{r}/contents?ref={branch}` | Scan listing for `^README(\..+)?$` if `README.md` 404s |
| License sniff | `GET /api/v1/repos/{o}/{r}/contents/LICENSE` (+ `.md`/`.txt`/`COPYING`) | Base64 decode → regex SPDX header match (no `license` field on Forgejo repo payload) |
| Downloads total | Sum `asset.download_count` across releases | No aggregate counter on Forgejo |
| Free-text search | `GET /api/v1/repos/search?q=...&limit=5` | For smart-match / import flow |
| Asset download | `asset.browser_download_url` direct | No `Authorization` header by default; per-host PAT optional |

### Client architecture for forges

- `core/data/network/ForgejoApiClient.kt` — Ktor client per host (60s/30s
  timeouts, retry on 5xx + IOException, redirect off-method).
- `core/data/network/ForgejoClientRegistry.kt` — thread-safe per-host
  client cache (`Mutex` guarded), proxy-aware (invalidates on proxy
  change), `close()` releases all sockets.
- `core/domain/util/RepoIdCodec.kt` — packs `host fingerprint (23-bit) +
  raw id (40-bit)` into the existing 64-bit `repoId` slot. Sign bit =
  "foreign source". Necessary because the rest of the DB schema is
  keyed on GitHub-style global Long IDs.
- `core/domain/util/RepositoryUrlParser.kt` — recognizes
  `codeberg.org`, `git.disroot.org`, `gitea.com`, plus user-added hosts
  via Tweaks → Custom forges.
- `core/data/repository/ExternalImportRepositoryImpl.kt` — smart-match
  pipeline runs **4 strategies in order**: manifest hint → local
  fingerprint DB → backend `/v1/external-match` (GitHub-only today) →
  parallel Forgejo `/repos/search` fanout per configured host.

### Client-side cost of bypassing backend

Measured on `feat/e8-codeberg-forgejo`:

- **Mobile rate-limit pressure**: every user hits Codeberg's
  `2000 req / 300s per IP` HAProxy limit independently. Users on shared
  IPs (CGNAT / corporate NAT) collide.
- **Scan latency**: even with the parallel fanout shipped at
  `b89e0b53`, a worst-case scan with ~24 eligible unmatched candidates
  × 3 canonical hosts × `Semaphore(8)` + `withTimeoutOrNull(4s)` per
  call is ~12 s ceiling.
- **No fingerprint signal for Forgejo apps**: client's
  `signingFingerprintDao` is GitHub-only — Forgejo-distributed APKs
  always fall through to free-text search (low confidence, noisy).
- **Redundant work per user**: each user re-runs LICENSE base64 decode,
  CRLF normalization, regex SPDX detection. Server-side once-per-repo
  + cache would be ~1000× cheaper aggregated.
- **No edge cache hits**: Gcore / our edge can't serve responses we
  never proxy.

---

## 2. Forgejo / Codeberg API — key shape differences vs GitHub

Confirmed live against `codeberg.org/swagger.v1.json` (Forgejo
`15.0.0-108`, forked from Gitea `1.22.0`):

### Release schema

- **Missing on Forgejo** vs GitHub: `assets_url`, `node_id`,
  `discussion_url`, `mentions_count`, `reactions`, `body_html`,
  `body_text`. If any of these are required-non-null in our backend
  DTO, decode throws and releases vanish silently.
- **Extra on Forgejo**: `hide_archive_links`,
  `archive_download_count: {zip: int, tar_gz: int}`.
- `published_at` ISO-8601 with **arbitrary timezone offset** (`+02:00`
  style). `java.time.Instant.parse` does **not** accept this — use
  `OffsetDateTime.parse` or kotlinx-datetime `Instant.parse`.

### Asset (Attachment) schema

- **Missing on Forgejo**: `url`, `node_id`, `label`, `state`,
  `content_type`, `updated_at`, `uploader`. Client made all
  nullable + defaulted `content_type` to `"application/octet-stream"`.
- **Extra on Forgejo**: `uuid`, `type: "attachment"`.
- `browser_download_url` is fully qualified absolute (same as GitHub).

### Owner / User schema

- **Missing on Forgejo**: `node_id`, `gravatar_id`, `url`, all
  `*_url` fields except `html_url` + `avatar_url`, `type`,
  `site_admin`, `public_repos`, `public_gists`, `twitter_username`,
  `bio`, `blog`, `hireable`, `updated_at`.
- **Extra on Forgejo**: `login_name`, `source_id`, `language`,
  `restricted`, `active`, `prohibit_login`, `pronouns`, `visibility`,
  `starred_repos_count`, `username` (duplicate of `login`).
- `description` replaces `bio`, `website` replaces `blog`,
  `is_admin` replaces `site_admin`.

### Repository schema

- **Missing on Forgejo**: `license`, `has_pages`, `has_discussions`,
  `has_downloads`, `network_count`, `subscribers_count`.
- **Renamed on Forgejo**: `stars_count` (vs GitHub
  `stargazers_count`).
- **Extra on Forgejo**: `release_counter`, `open_pr_counter`,
  `has_releases`, `has_actions`, `has_packages`,
  `internal_tracker: {...}`, `external_tracker`, `external_wiki`.

### Endpoints that exist on GitHub but NOT on Forgejo

- `/repos/{o}/{r}/readme` → 404. Use `/contents/README.md` instead.
- `/repos/{o}/{r}/license` → 404. Sniff from `/contents/LICENSE`.
- `/repos/{o}/{r}/attestations/sha256:{digest}` → 404. No sigstore
  attestations on Forgejo.
- `/users/{username}` exists on Forgejo but with the user-schema
  diffs above — backend should NOT proxy it 1:1 if our existing
  GitHub UserProfile DTO assumes GitHub fields.
- `/api/v1/rate_limit` → 404 (Forgejo dropped Gitea's rate-limit
  endpoint). No way to introspect remaining quota.

### Rate limits

- Codeberg production: **2000 req / 300s per IP** (HAProxy-level,
  no documented `Retry-After`, no per-token quota).
- No `X-RateLimit-*` response headers (verified live).
- Self-hosted Forgejo instances default to **higher** per-token
  limits but admins can tune; treat as unknown.

### Auth

- All read-only `/repos/...` endpoints work **without auth** on
  Codeberg.
- Per-host PAT support already shipped on app side via `KSafe` +
  `HostTokenRepository` (encrypted at rest, hardware-backed Keystore
  on Android). Header is `Authorization: token {pat}` when present.

---

## 3. Backend work — ranked by leverage

### TIER 1 — biggest unlock

#### 3.1 `/v1/external-match` accepts `platform: ["github", "codeberg", "gitea", "self_hosted"]`

**Why first**: kills the per-user Forgejo HTTP fanout entirely. One
backend quota replaces N user quotas. Centralizes confidence scoring.

**Contract proposal**:

```http
POST /v1/external-match
{
  "platform": "android",
  "sources": ["github", "codeberg", "gitea"],
  "candidates": [
    {
      "package_name": "nodomain.freeyourgadget.gadgetbridge",
      "app_label": "Gadgetbridge",
      "signing_fingerprint": "ab12...",
      "version_name": "0.91.1",
      "installer_kind": "FDROID",
      "manifest_hint": { "owner": "...", "repo": "...", "confidence": 0.4 }
    }
  ]
}

→ 200
{
  "matches": [
    {
      "package_name": "nodomain.freeyourgadget.gadgetbridge",
      "suggestions": [
        {
          "owner": "Freeyourgadget",
          "repo": "Gadgetbridge",
          "source_host": "codeberg.org",
          "source": "forgejo_search",
          "confidence": 0.95,
          "stars": 1700,
          "description": "..."
        }
      ]
    }
  ]
}
```

Client-side change to integrate: drop strategy 4 in
`ExternalImportRepositoryImpl.resolveMatches`, let `backendResults` carry
`source_host`. Map to existing `RepoMatchSuggestion.sourceHost`.

#### 3.2 Server-side fingerprint DB extended for Forgejo-distributed APKs

**Why**: signing-fingerprint hit is the strongest non-manifest signal.
Currently GitHub-only — Forgejo apps lose this strategy entirely.

**Crawl loop**:
- Once daily: walk Codeberg trending + Codeberg-hosted F-Droid index
  (`https://freeyourgadget.codeberg.page/fdroid/repo` etc.) for new APK
  releases.
- Download → extract signing cert → SHA-256 → upsert into
  `signing_fingerprints` table with `{cert, host, owner, repo, last_seen}`.
- Expose via existing `/v1/external-match` or as a separate
  `/v1/signing-cert-lookup?cert={sha256}` endpoint.

**Edge case**: signing cert is stable across mirror moves, so a
GitHub→Codeberg migration auto-resolves to the new home.

### TIER 2 — performance + reliability

#### 3.3 `/v1/repo?host={host}&owner={o}&repo={r}` proxy with caching

Mirror existing `BackendApiClient.getRepo` but parameterized by host.
Backend:
- Translates Forgejo response → unified `BackendRepoResponse` (already
  defined for GitHub).
- Pre-computes `license` (LICENSE-file sniff, server-side, once per
  repo, cached forever).
- Pre-aggregates `downloadCount` (sum across release assets).
- Honors ETags + serves from edge cache (Gcore).

Client change: `DetailsRepositoryImpl.getForgejoRepository` routes
through backend when `host in known_forgejo_hosts` AND backend is
reachable; falls back to direct Forgejo on backend infra error (same
fallback policy as GitHub today, see `shouldFallbackToGithubOrRethrow`).

#### 3.4 `/v1/releases?host={host}&owner={o}&repo={r}`

Pre-processes release bodies server-side:
- CRLF → LF normalization.
- Image / link URL rewriting against Forgejo raw URL base.
- Strips `\r` from inside GFM table separator rows (current client-side
  workaround, see `processForgejoBody` in
  `DetailsRepositoryImpl.kt`).

Same fallback policy as `/v1/repo` above.

#### 3.5 `/v1/search?source={github|codeberg|all}` extension

Backend builds a unified search index by crawling Codeberg trending +
search API hourly. Client toggle in `feature/search` already supports
`SearchSource.{GitHub, Forgejo(host)}` — just needs backend to honor
the parameter.

Bonus: lets us add cross-source ranking ("apps with the same signing
cert on GitHub AND Codeberg surface ONCE").

### TIER 3 — UX polish

#### 3.6 Cross-forge dedup

Some projects mirror Codeberg ↔ GitHub (e.g. `kde/kdeconnect-android`
on both). Backend detects via:
- Identical signing cert (strongest signal)
- Identical commit SHAs at HEAD
- Identical SPDX license + same `package_name` in manifest

Merges into one canonical row with `available_on: ["github", "codeberg"]`.
Client shows multi-source chip instead of two near-duplicate rows in
Search / Details.

#### 3.7 Feed / announcements forge tags

`/v1/announcements` and `/v1/feed` entries gain optional
`source_host` field so banners and trending sections can show
"Trending on Codeberg this week" buckets.

---

## 4. Concrete first move (if doing only one thing)

**Ship 3.1 (`/v1/external-match` source extension)** first. Rationale:

- Highest user-facing latency win (kills the per-user fanout).
- Smallest client change (~2 file diff in
  `ExternalImportRepositoryImpl`).
- Reuses existing telemetry (`importMatchAttempted`).
- Centralizes rate limit budget (us → Codeberg) instead of distributing
  it across all users.
- Sets the pattern (`platform: [...]`, `source_host` per result) that
  3.3 / 3.4 / 3.5 will reuse.

After 3.1, fingerprint DB (3.2) is the next biggest signal-quality win.

---

## 5. Out of scope for this brief

- Star / unstar / favorite / follow actions on Forgejo (needs OAuth
  flow per host, separate spec).
- Forgejo `attestations` parity (sigstore not standard on Forgejo).
- Developer profile pages for Forgejo users (Forgejo `/users/{name}`
  exists but UI is GitHub-shaped today; client currently skips for
  foreign repos).
- Forgejo-hosted F-Droid repo discovery (`fdroid/repo/index-v2.json`
  on `*.codeberg.page` etc.) — orthogonal to forge search.

---

## 6. Code refs for backend agent

Files most relevant to mirror on backend side:

- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ForgejoApiClient.kt`
- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ForgejoClientRegistry.kt`
- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/ForgejoRepoNetworkModel.kt`
- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/ForgejoSearchResponse.kt`
- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/ReleaseNetwork.kt` (already shape-compatible after `AssetNetwork.contentType` nullable fix)
- `core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/ExternalImportRepositoryImpl.kt` (lines ~240-310 for the Forgejo strategy block)
- `core/data/src/commonMain/kotlin/zed/rainxch/details/data/repository/DetailsRepositoryImpl.kt` (Forgejo branch from line ~700)
- `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/util/RepoIdCodec.kt`
- `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/util/RepositoryUrlParser.kt`

Authoritative external refs:

- Codeberg Swagger (live): https://codeberg.org/swagger.v1.json
- Forgejo API docs: https://forgejo.org/docs/latest/user/api-usage/
- Gitea API (Forgejo upstream): https://docs.gitea.com/api/
- Codeberg rate-limit discussion (admin-quoted): https://codeberg.org/Codeberg/Community/issues/425

---

## 7. Branch state

- Branch: `feat/e8-codeberg-forgejo`
- Latest commit at brief authorship: `b89e0b53` (`perf(import):
  parallel Forgejo fanout + per-call timeout + concurrency cap`)
- PR: #631 (`feat(forges): codeberg + forgejo source adapter
  (preview)`)
- Targets: `versionCode 18` / `versionName 1.8.3` whatsnew (preview
  feature framing intentional so the DB migration cost of `RepoIdCodec`
  is acceptable).
