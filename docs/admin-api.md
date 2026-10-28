# Desktop administration API v1

The bundled React desktop client uses same-origin `/api/v1`, JSON bodies, `credentials: same-origin`, a HttpOnly SameSite=Strict session cookie and `X-CSRF-Token` for every mutation. No credentials or authority live in localStorage. All routes except login/session require authentication. A 401 clears client session. Errors use `{message:string,code?:string}`; successful reads return the objects below directly. Mutation responses may return `{}` (204 accepted).

## Contract

- `POST /auth/login {password}` / `GET /auth/session` → `{authenticated:boolean,csrfToken?:string}`. `POST /auth/logout {}`.
- `GET /sources` → `Source[]`; `POST /sources {name,type,endpoint,username,secret,roots:string[]}` → Source; `PATCH /sources/:id {roots:string[]}`. `POST /sources/:id/test {}` → `{message:string}`.
- `GET /sources/:id/directories?path=...` → `{path:string,directories:{name:string,path:string}[]}`. Paths are opaque adapter paths; server validates traversal.
- `POST /sources/:id/scan {}`; `GET /scans` → `Scan[]`; `POST /scans/:id/cancel {}`; `POST /scans/:id/retry {}`.
- `GET /library` → `Episode[]`; `POST /library` or `PATCH /library/:id` accepts `{title,seriesTitle,libraryName,seasonNumber,episodeNumber,synopsis,artworkUrl,lockedFields:string[]}`. Manual creations may be non-playable until asset linked. `GET /library/:id/candidates` → `Candidate[]`; `POST /library/:id/match {candidateId}`.
- `POST /library/mapping/preview {episodeIds:string[],seriesTitle:string,seasonNumber:number,startEpisode:number}` → `{rows:{id:string,title:string,seasonNumber:number,episodeNumber:number}[]}`. `POST /library/mapping` accepts same payload plus `confirmed:true`.
- `GET /tags` → `Tag[]`; `POST /tags {key:string,values:string[]}`; `PATCH /tags/:id {key,values}`; `DELETE /tags/:id`.
- `POST /library/tags/preview {episodeIds:string[],key:string,values:string[],operation:'append'|'remove'|'replace'}` → `{affectedCount:number}`. `POST /library/tags` same payload plus `confirmed:true`. Selection expands only currently selected logical episodes; server transaction protects batch.
- `GET /analytics?from=YYYY-MM-DD&to=YYYY-MM-DD&dimension=series|library|tag&tags=...` → `Analytics`. `tags` is JSON `{[key:string]:string[]}`; same key OR/cross key AND, union total deduplicated.
- `GET /analytics/day?date=YYYY-MM-DD` → `{date:string,totalSeconds:number,graceSeconds:number,entries:WatchEntry[]}`.
- `GET /policy` → Policy; `PUT /policy {defaultMinutes:number}` changes default only, never today's override.
- `GET /provider` → `{tmdbConfigured:boolean,openSubtitlesConfigured:boolean,status:string}`; `PUT /provider {tmdbKey?:string,openSubtitlesKey?:string}`. Blank key omitted (keep stored credential); key values never returned.
- `GET /backup` downloads versioned integrity-checked JSON backup. `POST /restore {backup:unknown,confirmed:true}` validates backup, creates pre-restore snapshot, atomically imports, omits sessions and plaintext credentials.

```ts
type Source = {
  id: string;
  name: string;
  type: "openlist" | "alist" | "smb" | "webdav";
  endpoint: string;
  roots: string[];
  credentialConfigured: boolean;
};
type Scan = {
  id: string;
  sourceId: string;
  stage: string;
  discovered: number;
  matched: number;
  completed: number;
  error?: string;
  status: "running" | "failed" | "cancelled" | "completed";
};
type Episode = {
  id: string;
  title: string;
  seriesTitle: string;
  libraryName: string;
  seasonNumber: number;
  episodeNumber: number;
  synopsis: string;
  artworkUrl?: string;
  status: "matched" | "pending" | "unmatched";
  available: boolean;
  lockedFields: string[];
  tags: Record<string, string[]>;
};
type Candidate = {
  id: string;
  title: string;
  seasonNumber: number;
  episodeNumber: number;
  evidence: string[];
};
type Tag = { id: string; key: string; values: string[] };
type Policy = {
  defaultMinutes: number;
  todayMinutes: number;
  watchedSeconds: number;
  state: string;
  timezone: string;
  todayOverride: boolean;
};
type Analytics = {
  totalSeconds: number;
  episodeCount: number;
  watchDays: number;
  days: { date: string; seconds: number; graceSeconds: number }[];
  rows: { label: string; seconds: number; episodeCount: number }[];
};
type WatchEntry = {
  id: string; // Stable display-group key, not a raw checkpoint ID.
  episodeId: string;
  libraryId: string; // Attribution captured when watching.
  seriesId: string;
  sessionId?: string; // Present only for a recognized playback-session UUID.
  title: string;
  seriesTitle: string;
  startedAt: string;
  endedAt: string;
  durationMillis: number;
  seconds: number; // floor(durationMillis / 1000), after grouping.
  kind: 'normal' | 'grace' | 'parent_extra' | 'mixed';
  kinds: ('normal' | 'grace' | 'parent_extra')[];
  normalMillis: number;
  graceMillis: number;
  parentExtraMillis: number;
};
```

## Desktop behavior

Empty installations render empty states; there is no demo fallback. The browser only submits configuration; scans, credentials, quota, history, statistics and backup authority remain on the TV. Screens are desktop only (minimum width 1080px). HTTP transport is explicitly disclosed as unencrypted and intended for a trusted home LAN. TV shutdown/sleep can make service unavailable; reconnect after reopening the app.

## Asset and credential updates

- `PATCH /sources/:id {username,secret}` replaces source credentials; blank secrets are never populated from a read. Root updates automatically enqueue scans.
- `GET /library/:id/assets` returns available assets `{id,name,sourceId,path,selected:boolean,startMs?:number,endMs?:number}[]`. `PUT /library/:id/assets {assets:[{assetId,startMs?,endMs?}]}` transactionally replaces mappings for this logical episode, preserving its tags, history and progress. An empty array unlinks resources. Boundaries are milliseconds with nonnegative start and end strictly greater than start; omitted end uses file end. The desktop editor displays seconds with millisecond precision.
- Backup response is `{version:1,createdAt:string,payload:string,sha256:string}`. Restore validates the complete object server-side. HTTP body limit is 48 MiB; backup payload limit 32 MiB. Source credential status becomes unconfigured after restore; use “更新凭据”.

## Cached artwork and stable aggregation keys

`Episode.artworkLocalUrl?: string` points to authenticated same-origin `/api/v1/artwork/:episodeId` bytes when a bounded cached cover exists. The desktop prefers this URL over the provider URL so backed-up covers remain visible offline. Cookies authenticate image reads; JavaScript receives no source credentials. `Analytics.rows[].id` is a stable aggregation key, distinct from its display label; identical labels must remain separate rows.

Successful restore invalidates all Web sessions and TV one-time grants. The desktop tells the parent to log in again; the next protected API read returns 401 and clears its in-memory authentication.

A scan may have `status: "waiting"`, `stage: "waiting_retry"` and `cursor.retryAtMillis: number` while metadata rate limits persist a scheduled retry. This is an active task: the desktop continues polling, displays the actual scheduled local retry time, and offers cancel. A terminal `retry_exhausted` stage is distinct from success.

## OpenList / AList authentication modes

OpenList/AList source create and credential update bodies explicitly send `authMode: "password" | "token"`. Password mode requires `username` and `secret` as the account login password; token mode sends only `secret` as the access token and omits `username`. New desktop forms default to password mode. The server returns an effective `Source.authMode`: legacy vaults with a nonempty username infer password mode, otherwise token mode. A desktop reading older metadata without this field conservatively defaults to token mode. Switching mode requires corresponding new credentials; the two form inputs are held separately in memory and never reused across modes. SMB/WebDAV continue their existing username/password contract without an `authMode` field.

## Daily viewing display groups (0.1.3)

`GET /analytics/day` returns one entry per playback session, logical episode,
recorded family date, and historical library/series attribution. Published event
IDs have the form `sessionUUID:sequence`, optionally followed by `:normal` or
`:grace`; pauses, buffering gaps, and policy-kind changes therefore remain within
the same displayed viewing. A different session UUID creates a separate entry,
even when the same episode immediately plays again. Cross-midnight records stay
on their respective recorded dates.

Older opaque IDs are not joined merely because episode names or times are close.
Only an explicit opaque `checkpoint:normal` / `checkpoint:grace` pair with matching
context and exactly contiguous boundaries is combined. Unrecognized independent
events remain separate rather than risk merging repeat viewings. No database
migration or history clearing is required.

Raw intervals remain unchanged. Daily totals still use the original deduplicated
events. Entry durations and per-kind durations sum milliseconds before converting
to seconds; `endedAt - startedAt` may include pauses and is not the watched duration.
Mixed entries expose all `kinds` instead of inheriting only their first kind.
Positive `durationMillis` below 1000 is shown as “不到1秒”, not “0分钟”.
