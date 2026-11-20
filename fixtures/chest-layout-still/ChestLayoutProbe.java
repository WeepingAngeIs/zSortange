import dev.anya.storage.pocket.ChestLayoutProjection;

/** Exports the production renderer's per-mask stamp commands without loading Minecraft. */
public final class ChestLayoutProbe {
    private static void frame(int mask) {
        StringBuilder row = new StringBuilder().append(mask).append(' ')
                .append(ChestLayoutProjection.validMask(mask));
        ChestLayoutProjection.forEachChest(mask, (bit, left, top) ->
                row.append(' ').append(bit).append(',').append(left).append(',').append(top));
        System.out.println(row);
    }

    public static void main(String[] args) {
        System.out.printf("%d %d %d %d %d %d%n",
                ChestLayoutProjection.FACE_SIZE, ChestLayoutProjection.ATLAS_WIDTH,
                ChestLayoutProjection.STAMP_X, ChestLayoutProjection.STAMP_Y,
                ChestLayoutProjection.STAMP_WIDTH, ChestLayoutProjection.STAMP_HEIGHT);
        for (int mask = -1; mask <= 256; mask++) frame(mask);
        frame(0x10f); // Four original bits plus a bit outside the eight registered pads.
    }
}
