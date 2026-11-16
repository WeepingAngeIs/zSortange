# Multi-pocket isolation and budget acceptance (following beta.6)

This is an evidence-only milestone unless a production defect is demonstrated. The playable version, prototype gates, data schema, damage suppression and art/UI are deliberately unchanged. It adds a distinct packaged scenario rather than counting the unchanged beta.5/beta.6 suites as new coverage.

## Scope

Two actual Minecraft client processes, Alpha and Beta, own separate pockets in a fresh installed-server world. The scenario also grants narrowly scoped shared access. It tests the active-pocket admission cap at one/two, allocation quota rejection, reductions below existing usage without hiding containers, distinct Visit/storage/Build rights, cross-owner remote and physical-container denial, independent close/reopen cycles, stale revoked-view intent, anchor replacement while another pocket remains viewed, two abrupt client-process kills/reconnects, per-cell idle cleanup, and a separate server-process reload.

Configuration changes in this fixture modify the server's actual policy objects in memory. They do not claim to test the operator draft/review UI. New pure-JVM tests additionally exercise all active-pocket cap values (1..8), the 16-user allocator boundary, stale epochs, renewals and absolute deadlines. These are allocator assertions, not 16 connected clients.

## Lower-limit semantics

`activePocketLimit` governs new distinct-pocket admission. Lowering it below currently active usage allows existing bounded sessions to drain; it does not abruptly teleport all occupants or hide storage. Users can still share an already active pocket within the separate user cap. A closed pocket cannot be newly admitted while the active count meets/exceeds the lowered cap. Existing session deadlines remain finite. `pocketLimit` restricts new cell allocation, not previously saved rooms. `pocketContainers` restricts new preparation/placement, not discovery of existing registered chest contents.

## Measurements

The test-only code reads private service state reflectively without mutating it. `multi-budget.csv` contains timestamped server-tick samples of active pockets, individual leases, logical Anya ticket requests, per-pocket requests, loaded positions in each 16-chunk destination ring, total currently loaded destination chunks, shell-preparation writes, pending users and preparation jobs. Snapshot sampling does not force chunk loading.

The two homes share one physical home chunk. Each pocket can issue 16 destination requests plus one home request keyed by that pocket identity. This is a logical request count, not a unique physical-chunk count; engine/player view propagation can load additional chunks. The existing `lastPreparedBlocks` counter counts shell writes only: it excludes the final four chest and one exit writes, chunk generation, lighting, saves and renderer work. It must not be presented as total processing. No zero-cost, hardware FPS or full-scale load-test claim is made.

## Evidence boundaries

The clients send real entry/remote/menu/exit traffic over localhost TCP, with the production JAR hash checked at startup. The fixture sets up owners, positions, sample stacks and permissions; selected cross-owner API/forged-target checks are server-side. Two SIGKILL/reconnect cycles are actual process/socket interruptions. Final restart inspection explicitly loads stored chunks as fixture setup, and does not claim those inspection chunks are unloaded before process termination.

The milestone does not certify arbitrary server/player-file crash consistency, grave behavior, full-pack renderer compatibility, arbitrary machinery/movers, live portal views, human GUI feel or large-scale multi-owner production traffic. Independent compile/regression runs are not substitutes for the separate packaged scenario's result. Consult its actual assertions and failure markers.
