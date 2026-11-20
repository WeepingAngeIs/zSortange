package dev.anya.storage.pocket;

import java.nio.file.Path;

/** Exercises the packaged client choice, public state, and eight-pad projection without opening a game. */
public final class ExteriorCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        for (var type : new Class<?>[]{EntranceLayoutClient.class, ChestLayoutProjection.class}) {
            var actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(actual.equals(jar), "Loaded " + type + " from " + actual);
        }
        require(EntranceLayoutClient.CHEST_LAYOUT_STILL == Boolean.parseBoolean(args[1]), "Unexpected preview style");
        // These calls run before any level or connection exists. A legacy replay must never open a grant.
        EntranceLayoutClient.receiveStill(null, null);
        require(EntranceLayoutClient.visibleStill(null) == 0, "Legacy marker became visible");
        require(EntranceLayoutClient.visibleMask(null) == -1, "Unfocused entrance exposed a layout");
        for (int input = -1; input <= 256; input++) {
            boolean granted = input >= 0 && input < 256 && (input & 15) == 15;
            require(ChestLayoutProjection.validMask(input) == granted, "Unexpected grant for mask " + input);
            int[] painted = {0};
            ChestLayoutProjection.forEachChest(input, (bit, x, y) -> {
                require(bit >= 0 && bit < 8 && (painted[0] & (1 << bit)) == 0, "Unexpected chest stamp");
                require(x >= 0 && y >= 0 && x + ChestLayoutProjection.STAMP_WIDTH <= 256
                        && y + ChestLayoutProjection.STAMP_HEIGHT <= 256, "Stamp outside the frame");
                painted[0] |= 1 << bit;
            });
            require(painted[0] == (granted ? input : 0), "Wrong projection for mask " + input);
        }
    }
}
