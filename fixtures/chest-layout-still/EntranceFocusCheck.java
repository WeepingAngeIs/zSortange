package dev.anya.storage.pocket;

/** No game or network needed. Uses the same lease and mask state as the client renderer. */
public final class EntranceFocusCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var viewer = new EntranceFocusState();
        var visitor = new EntranceFocusState();
        check(viewer.visibleMask(0, 0) == -1, "New viewer must start with public art");

        long first = viewer.begin(0, 0);
        long visitorFocus = visitor.begin(0, 0);
        check(viewer.visibleMask(0, 0) == -1, "Focusing alone must give no layout");
        check(viewer.applyMask(first, 0x1f, 1, 1), "First grant");
        check(viewer.visibleMask(1, 1) == 0x1f, "Show only the granted occupancy");
        check(visitor.visibleMask(1, 1) == -1, "Another viewer has no inherited grant");

        check(viewer.applyMask(first, -1, 2, 2), "Server revoked the active layout");
        check(viewer.visibleMask(2, 2) == -1, "Clear immediately on revocation");
        check(!viewer.applyMask(first, 0x1f, 3, 3), "A queued grant cannot reopen a revoked focus");
        long fresh = viewer.begin(3, 3);
        check(fresh > first && viewer.visibleMask(3, 3) == -1, "Fresh focus starts empty");
        check(!viewer.applyMask(first, 0xff, 4, 4), "Old grant rejected after refocus");
        check(viewer.applyMask(fresh, 0x8f, 4, 4), "Fresh grant");
        check(!viewer.applyMask(first, -1, 5, 5), "An old clear cannot retire a fresh grant");
        check(viewer.visibleMask(5, 5) == 0x8f, "Only fresh layout visible");
        check(viewer.applyMask(fresh, 0x0f, 6, 6), "Occupied optional pad removed");
        check(viewer.visibleMask(6, 6) == 0x0f, "Removed pad no longer visible");

        check(visitor.applyMask(visitorFocus, 0x4f, 1, 1), "Independent visitor grant");
        check(visitor.visibleMask(24, 1) == 0x4f, "Visible just before tick expiry");
        check(visitor.visibleMask(25, 1) == -1, "24-tick expiry without a packet");
        check(!visitor.applyMask(visitorFocus, 0x4f, 25, 1), "Expired focus cannot be renewed by a late grant");
        check(viewer.visibleMask(6, 1_200_000_005L) == 0x0f, "Visible just before wall clock expiry");
        check(viewer.visibleMask(6, 1_200_000_006L) == -1, "Wall clock expiry even when ticks stall");

        viewer.clear();
        long next = viewer.begin(7, 1_300_000_000L);
        check(next > fresh && viewer.visibleMask(7, 1_300_000_000L) == -1, "New connection starts empty");
        check(!viewer.applyMask(fresh, 0xff, 7, 1_300_000_000L), "Old connection's focus is retired");
        check(viewer.applyMask(next, 0x2f, 7, 1_300_000_000L), "New connection grant");
        viewer.clear(); // Same invalidation used for a screen, look-away, logout and level unload.
        check(viewer.visibleMask(7, 1_300_000_000L) == -1 && !viewer.accepts(next), "Local clear retires the focus");
        System.out.println("PASS: independent viewer state, revoke, queued updates, fresh grant, pad removal, both lease limits and local clear");
    }
}
