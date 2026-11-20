package dev.anya.storage.appearance;

/** Sample the packaged clocks for the offline preview, including the moment the tablet is selected. */
public final class PortraitTimeline {
    public static void main(String[] args) {
        var playlist = new GuardianBlink.Playlist();
        var seen = new boolean[8];
        for (int i = 0; i < seen.length; i++) {
            var opening = new GuardianBlink(true);
            opening.choose(playlist);
            int row = opening.sequence();
            if (seen[row]) throw new AssertionError("Repeated opening " + row);
            seen[row] = true;
            for (int ms = 0; ms <= 1110; ms += 10) {
                var frame = opening.present(ms, ms >= 60, ms >= 60);
                String face = switch (frame) {
                    case WAIT, DONE -> frame.name();
                    default -> "gui_" + row + "_" + opening.column();
                };
                System.out.println("open" + row + " " + ms + " " + face);
            }
            if (opening.coversInput()) throw new AssertionError("Opening still covers storage");
        }

        var tablet = new TabletIdle(0, 0);
        int tick = 0;
        for (int visit = 0; visit < 4; visit++) {
            int deadline = tick + 1400;
            while (tablet.frame(tick) == TabletIdle.Face.DARK && tick < deadline) tick++;
            var first = tablet.frame(tick);
            if (first == TabletIdle.Face.DARK) throw new AssertionError("Missing idle visit");
            int row = first.row();
            if ((visit == 0 && first != TabletIdle.Face.SQUINT) || (visit > 0 && row != visit + 3))
                throw new AssertionError("Unexpected idle visit " + first);
            String label = row < 0 ? "squint" : "idle" + row;
            int start = tick;
            int selectedAt = start + 20;
            while (true) {
                if (tick == selectedAt) tablet.select(true, tick);
                var face = tablet.frame(tick);
                System.out.println(label + " " + (50 * (tick - start)) + " item_" + face.sprite);
                if (face == TabletIdle.Face.DARK) break;
                if (tick >= selectedAt + 15) throw new AssertionError("Cupid stayed on the selected tablet");
                tick++;
            }
            tablet.select(false, tick);
        }
    }
}
