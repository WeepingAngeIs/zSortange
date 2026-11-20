# Server-stored backpack interiors: security and privacy

This is a NEW feature in 0.3.0-beta.1. It was not present in beta.1 (0.2.0). Uploads default OFF; built-in interiors do not depend on them.

## Enable deliberately

An operator opens `/anya storage`, expansion policy, and enables `imagesEnabled`. Keep `imageUploadsForAll=false` to allow only operators and UUIDs in `imageUploaders`. Keep `imageSharing=false` for private-only interiors. `imageModeration=true` is the default. Review and apply the draft.

A permitted player opens a backpack's **Style/Wardrobe** screen and drags exactly one local PNG onto the game window. No filename, filesystem path or URL is sent to the server. There is no URL fetch command, remote downloader or arbitrary-path server API. The client refuses a symlink or oversized source file.

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
