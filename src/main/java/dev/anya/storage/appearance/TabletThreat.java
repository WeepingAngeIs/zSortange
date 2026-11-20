package dev.anya.storage.appearance;

import net.minecraft.client.Minecraft;

import static dev.anya.storage.appearance.TabletIdle.Face.*;

/** One short look shared by the local player's visible tablets. The server supplies only a bearing. */
final class TabletThreat {
    private record Glance(TabletIdle.Face notice, TabletIdle.Face look) {}
    // Clockwise from straight ahead. For a threat behind, check over both shoulders.
    private static final Glance[] DIRECTIONS = {
            new Glance(SHY_0, SHY_4), new Glance(SHY_0, GIGGLE_2),
            new Glance(NOTICE_4, GIGGLE_1), new Glance(SMUG_8, PEEK_7),
            new Glance(NOTICE_1, GIGGLE_1), new Glance(SMUG_0, PEEK_1),
            new Glance(NOTICE_4, NOTICE_1), new Glance(SHY_0, NOTICE_2)
    };
    private static Glance glance;
    private static long started;

    static void receive(int octant) {
        var mc = Minecraft.getInstance();
        if (octant < 0 || octant >= DIRECTIONS.length || !visible(mc)) return;
        TabletFaces.sample(mc.player, false);
        long tick = TabletFaces.clock();
        if (!TabletFaces.hasVisibleTablet() || face(tick) != null) return;
        glance = DIRECTIONS[octant];
        started = tick;
        TabletWarningSound.play();
    }

    private static boolean visible(Minecraft mc) {
        return mc.player != null && mc.screen == null && !mc.options.hideGui
                && mc.getCameraEntity() == mc.player && mc.player.isAlive()
                && !mc.player.isSpectator() && !mc.player.isScoping() && !HeldTablet.handling();
    }

    static void expire(Minecraft mc) {
        if (glance != null && (!visible(mc) || !TabletFaces.hasVisibleTablet())) clear();
        face(TabletFaces.clock());
    }

    static TabletIdle.Face face(long tick) {
        if (glance == null) return null;
        long age = tick - started;
        if (age < 0 || age >= 18) {
            clear();
            return null;
        }
        if (age < 5) return glance.notice();
        if (glance == DIRECTIONS[4] && age >= 10 && age < 16) return SMUG_4;
        return age < 16 ? glance.look() : SHUT;
    }

    static void clear() { glance = null; }
    private TabletThreat() {}
}
