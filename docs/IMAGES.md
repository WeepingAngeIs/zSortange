# Server-stored backpack interiors: security and privacy

Drop one PNG onto your open backpack or its Wardrobe. A successful drop opens the backpack and previews the image, even with Images off. The preview lasts for this menu session. Your display preference remains yours.

New server configurations allow private uploads. Existing saved switches and uploader lists retain their meaning. Older configurations missing either upload switch retain its earlier disabled default. A saved false value cannot distinguish a deliberate restriction from a previous default. On those servers an operator can enable custom images and grant upload access once in storage settings. Sharing starts disabled and requires explicit permission.

## Hard format limits

| Limit | Value |
| --- | --- |
| Accepted file | PNG only; non-interlaced, 8-bit RGB or RGBA |
| Dimensions | 32 to 256 pixels on each side; at most 65,536 pixels |
| Input bytes | 65,536 bytes (64 KiB) |
| Sanitized output bytes | 131,072 bytes (128 KiB) |
| PNG chunks | At most 128 |
| Wire part | At most 16 KiB |
| Active upload reservations | At most 8; 30-second lifetime |
| Validation workers | 1 worker, queue of 8 |
| Upload frequency | One per player per minute, at most one new reservation per server second |
| Pre-scheduling message gate | 12 image messages/player/second, 128 globally/second |
| Image downloads | At most 1 MiB per server second, plus per-player request spacing |

JPEG, GIF, APNG, indexed/grayscale/16-bit/interlaced PNG and malformed PNG streams are rejected. The strict Java decoder checks signature, chunk lengths, CRCs, ordering, declared dimensions, bounded inflation and exact row lengths before allocating/decoding. All five PNG row filters are supported. The image is re-encoded as RGBA PNG, discarding ancillary metadata. The server does not use ImageIO or native image decoders for uploaded data. The client validates the sanitized PNG before creating its bounded native texture.

## Storage quotas

Defaults are 4 images per player, 128 per world and 16 MiB of world image storage. Operator-adjustable hard ceilings are 8/player, 512/world and 64 MiB/world. Orphan PNG files count toward disk quota so interrupted catalog saves cannot bypass it. The world-local image catalog is capped at 1 MiB. Files have server-generated UUID names under `anya_storage/interiors`; item stacks store an opaque image reference, not image bytes.

Validation is off the server thread. Permissions, current menu/session, backpack identity, capacity quotas and item validity are checked again before commit. Catalog and image writes use atomic moves; failure is reported rather than silently resetting/deleting an existing library. Preserve the whole world folder in backups.

## Visibility and moderation

An owner may see their own pending image privately. Other players need ALL of: server sharing enabled, approved image, an explicit UUID grant, and a valid authorized current bag/menu request. Owning or holding the backpack alone does not confer access to somebody else's image. At most 16 explicit viewers can be added to an image.

The image library has owner deletion and explicit viewer grant/revocation. Operators have a separate moderation-library route to inspect, approve/revoke or delete. Delete requires confirmation. Public worn-backpack packets never contain image IDs, private names, contents or image pixels. Uploaded images never decorate the public exterior model.

The client keeps at most eight downloaded textures in memory, not on disk. It reauthorizes on short leases, clears on close/disconnect, and receives invalidation on deletion or access changes. Server owners can read their own server files, and authorized recipients can take screenshots. Revoking permission cannot erase copies already captured by a recipient. Do not upload confidential images on an untrusted server.

Moderation is manual, not an automatic content-classification service. There is no malware/scanner claim. Unit checks and code review do not replace two-client authorization, revocation, disconnect, upload-flood and full-pack acceptance tests; those end-to-end cases have not been claimed as passed.
