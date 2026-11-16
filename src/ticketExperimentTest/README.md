# Isolated efficient-ticket verification

This source set is not a playable mod and is not included in the production JAR.

The pure selector test compares 6,000 deterministic snapshots with an independent per-ID oracle. It covers pending players, visit leases, expired or stale epochs, preparation, missing/unfinished records and occupants. These are property assertions, not connected-client tests.

The engine churn fixture visits RESERVED rooms before requesting remote storage. An explicitly closed remote session is removed from its deadline expectations before a new visit is admitted; other live viewers retain their original deadline assertions. Owner/guest views, finite contender admission, guarded transfers, eight queued unsafe returns and final ticket/cache cleanup remain strict checks.

Use the exact final source's `Efficient per-pocket selection and maximum-cap churn` workflow artifacts. Its two fresh-world/JVM baseline/candidate pairs have reversed run order. The stress jobs use 17 embedded-connection server identities, with at most 16 leases and eight active pockets, not 17 real clients. Separate jobs use actual client JVMs for room editing, cold recovery and remote/visitor/socket/shutdown transitions against the identical experiment artifact.

The early ordering and deadline-expectation failures are retained as fixture failures, not erased or counted as passing production evidence. No mutating item-transfer retry is introduced. Production source, beta.6 format and experimental gates are unchanged.
