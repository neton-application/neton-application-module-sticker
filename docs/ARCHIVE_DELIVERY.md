# Immutable archive delivery v1

## Wire Contract

Account-realm access tokens only. `GET /app/sticker/archive/{versionRowId}/manifest`
returns the normal JSON envelope containing:

```json
{"schemaVersion":1,"format":"zip-stored-v1","versionRowId":"123","packId":"example","version":1,"manifestSchemaVersion":1,"downloadPath":"/sticker/archive/123/download","sha256":"<whole ZIP SHA-256>","size":123,"manifest":{"file":"manifest.json","sha256":"<SHA-256>","size":123},"files":[{"resourceId":"42","file":"r_42.png","sha256":"<SHA-256>","size":123,"kind":"image","mimeType":"image/png"}]}
```

The download path is relative to the existing trusted `/app` API base, never an
arbitrary authenticated URL. `GET /app/sticker/archive/{versionRowId}/download`
returns raw `application/zip`, Content-Length, quoted SHA-256 ETag, safe attachment
filename, and `Cache-Control: private, no-store`. The descriptor is also no-store.
Files exclude the separately described manifest; resource IDs are the existing
immutable sticker/PAG resource IDs, not slot keys. Kinds are `image` and `pag`.

`PackDto.versionRowId` is the existing version row `id`, emitted for every pack.
Its absence on an older server means offline delivery is unavailable, not complete.
`POST /app/sticker/install {packId,versionRowId?}` retains its Boolean response.
When supplied, the version must still be the currently published row under the
member/pack locks; otherwise HTTP 400 `STICKER_VERSION_CHANGED`. Old callers may
omit it. Clients stage/verify all bytes, install with this guard, then freshly read
installed state before atomically committing an account/platform-scoped index.

Published rows are readable by authenticated accounts before installation.
Delisted rows require an active install relation for that account and logical pack.
Drafts, unknown rows and ineligible delisted reads return 404. Authorization and
metadata checks precede archive-body reads. Existing historical single-resource
access is unchanged. No C-end upload or unauthenticated ZIP endpoint is introduced.

## Bytes and Bounds

Maximum ZIP and expanded aggregate: **33554431 bytes (<32 MiB)**. This respects
STICKER_PACK_SPEC section 3.3 and the current neton-storage Phase 1 contract.
The Local and S3 implementations do not add an explicit 32 MiB write guard; their
acceptance of ByteArrays is not permission to widen the frozen storage contract.
No multipart workaround, chunking, direct file/S3 bypass or transcoding is used.
Maximum 514 total entries and
262144 manifest bytes. Ingress limits remain unchanged. Historical resources are
not transcoded or re-rejected merely for current ingress geometry/size policy.
Unsupported MIME types, missing originals, and size/digest mismatches fail closed.
The budget includes every ZIP header/name/EOCD byte and is checked before reading
resource bodies or allocating output. A valid near-limit compressed import may
exceed this delivery budget once Stored headers/canonical names are included:
new publication fails atomically with `STICKER_ARCHIVE_SIZE`; an existing legacy
pack's export fails honestly. Ingress is unchanged; no entries are silently dropped.

ZIP32 Stored (method 0), UTF-8 flag 0x800, fixed DOS 1980-01-01 (date 0x21/time 0),
version 20, zero attributes, no extras/comments, encryption, descriptors, ZIP64,
symlinks, padding or trailing bytes. Local records are contiguous, followed by the
matching central records and EOCD. `manifest.json` is first, then deduplicated
resources ascending numeric ID, named `r_<id>.png|webp|pag`. CRC and sizes match in
both records. Existing strict v1/v2 manifest shapes remain unchanged.

## Snapshot and Storage Lifecycle

First publication freezes exact manifest UTF-8 bytes (including author NAME), file
mapping, resource digests/sizes, descriptor and whole ZIP digest/size in PostgreSQL.
Publication rolls back if sources cannot be verified. No external ZIP write occurs
inside publication. Existing published/delisted rows snapshot lazily on first
authorized archive access under author/member/pack locks; no manual backfill is
required. Store/installed listings never materialize archives.

Cache generations use unique immutable private object keys. A reservation row is
committed before writing; the cache pointer is committed only after a complete
write/read-back verification, under the cache maintenance lock. Database rollback
or process failure leaves an unreferenced reservation, never an authoritative ZIP.
The writer rechecks its reservation under that lock: if retention cleanup won
while the request was waiting, it fails before writing and a fresh request retries.
Missing/corrupt cache generations regenerate from the frozen snapshot and verified
original resource bytes, and must reproduce the stored whole ZIP digest/size.
Author renames cannot alter regeneration. A broken original fails closed rather
than publishing a new checksum. Existing generations are never overwritten.

Explicit existing admin cleanup also reclaims unreferenced archive reservations
older than 24 hours (bounded batch), under the same cache maintenance lock. Delete
the private object first, then its reservation; a database rollback can safely
retry deletion. Referenced cache generations and original published resources are
not garbage collected. This is an orphan policy, not a cross-store transaction.
The phase-1 storage/HTTP APIs buffer bounded bytes; this is not streaming delivery.
