import dev.anya.storage.pocket.ChestLayoutProjection;

/** Exports the actual draw positions for one invented six-chest preview. */
public final class FrameSample {
    public static void main(String[] args) {
        System.out.printf("%d %d %d %d %d %d%n",
                ChestLayoutProjection.FACE_SIZE, ChestLayoutProjection.ATLAS_WIDTH,
                ChestLayoutProjection.STAMP_X, ChestLayoutProjection.STAMP_Y,
                ChestLayoutProjection.STAMP_WIDTH, ChestLayoutProjection.STAMP_HEIGHT);
        ChestLayoutProjection.forEachChest(0x9f,
                (bit, left, top) -> System.out.printf("%d %d %d%n", bit, left, top));
    }
}
