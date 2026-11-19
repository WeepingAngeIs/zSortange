package dev.anya.storage.pocket;

/** Runs with just StillFrameState.java and a JDK; no game or loader classes. */
public final class StillFrameStateCheck {
    private static void require(boolean expected, String reason) {
        if (!expected) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        var viewer = new StillFrameState();
        require(viewer.material() == 0 && !viewer.apply(1, 1, 1), "No grant on a public viewer");
        viewer.begin(1);
        require(!viewer.apply(2, 1, 1) && viewer.material() == 0, "Incorrect nonce accepted");
        require(!viewer.apply(1, 1, 3), "Unrecognized material accepted");
        require(viewer.apply(1, 2, 2), "Positive frame failed");
        require(!viewer.apply(1, 1, 1) && viewer.material() == 2, "Older frame overwrote current frame");
        require(viewer.apply(1, 3, 0) && viewer.material() == 0, "Revocation left a private frame");
        require(!viewer.apply(1, 4, 1), "Revoked token reopened the view");
        viewer.begin(2);
        require(!viewer.apply(1, 99, 1) && viewer.material() == 0, "Old frame resurfaced under fresh grant");
        require(viewer.apply(2, 1, 2) && viewer.material() == 2, "Fresh authorized frame missing");
        require(!viewer.apply(1, 100, 0) && viewer.material() == 2, "Old clear erased fresh grant");
        viewer.clear();
        require(!viewer.apply(2, 2, 2) && viewer.material() == 0, "Local expiry/logout accepted old frame");
        System.out.println("StillFrameState passed ordering, revocation, fresh grants and local expiry.");
    }
}
