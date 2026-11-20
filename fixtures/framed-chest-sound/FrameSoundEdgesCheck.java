package dev.anya.storage.pocket;

import static dev.anya.storage.pocket.FrameSoundEdges.Cue.NONE;
import static dev.anya.storage.pocket.FrameSoundEdges.Cue.OFF;
import static dev.anya.storage.pocket.FrameSoundEdges.Cue.ON;

/** Drives the production cue timing from synthetic frame visibility and millisecond timestamps. */
public final class FrameSoundEdgesCheck {
    private static void expect(FrameSoundEdges edges, boolean visible, long time,
                               FrameSoundEdges.Cue expected, String reason) {
        FrameSoundEdges.Cue actual = edges.sample(visible, time);
        if (actual != expected) {
            throw new AssertionError(reason + " at " + time + " ms: expected " + expected + ", got " + actual);
        }
    }

    private static FrameSoundEdges startVisible() {
        var edges = new FrameSoundEdges();
        expect(edges, false, 1000, NONE, "Initial sample");
        expect(edges, false, 1250, NONE, "Initial settling ends");
        expect(edges, true, 1260, NONE, "First visible sample");
        expect(edges, true, 1359, NONE, "Visible for only 99 ms");
        expect(edges, true, 1360, ON, "Visible for 100 ms");
        return edges;
    }

    private static void normalHold() {
        var edges = startVisible();
        expect(edges, true, 1360, NONE, "Second draw at the same time");
        // The public illustration and authorized artwork both present a visible frame.
        expect(edges, true, 1400, NONE, "Public art still visible");
        expect(edges, true, 1430, NONE, "Authorized art still visible");
        expect(edges, true, 1499, NONE, "Return to public art with the same visible frame");
        expect(edges, false, 1500, NONE, "First absent sample");
        expect(edges, false, 1659, NONE, "Absent for only 159 ms");
        expect(edges, false, 1660, OFF, "Absent for 160 ms and 300 ms since ON");
        expect(edges, false, 1660, NONE, "Repeated absent draw");
        expect(edges, false, 1810, NONE, "Sustained absence");
    }

    private static void startupAndScanning() {
        var alreadyVisible = new FrameSoundEdges();
        expect(alreadyVisible, true, 1000, NONE, "Enter with the frame already visible");
        expect(alreadyVisible, true, 1100, NONE, "Already visible during settling");
        expect(alreadyVisible, true, 1250, NONE, "Settled already-visible frame");
        expect(alreadyVisible, true, 1500, NONE, "No belated entrance cue");

        var scan = new FrameSoundEdges();
        expect(scan, false, 1000, NONE, "Start looking elsewhere");
        expect(scan, true, 1010, NONE, "New frame inside settling window");
        expect(scan, true, 1249, NONE, "Stay quiet through settling even after 100 ms");
        expect(scan, false, 1250, NONE, "Frame gone by end of settling");
        // Use a settled, independent player for repeated short glances.
        scan.reset();
        expect(scan, false, 2000, NONE, "New stationary view");
        expect(scan, false, 2250, NONE, "Settled with no frame");
        expect(scan, true, 2260, NONE, "Start first glance");
        expect(scan, false, 2359, NONE, "Glance shorter than 100 ms");
        expect(scan, true, 2400, NONE, "Start a new glance");
        expect(scan, true, 2499, NONE, "Short glances do not accumulate");
        expect(scan, true, 2500, ON, "Second glance becomes stable");
        expect(scan, false, 2900, NONE, "Brief interruption of a visible frame");
        expect(scan, true, 3059, NONE, "Frame returns before the 160 ms exit threshold");
        expect(scan, true, 3250, NONE, "No chime from the interrupted exit");
    }

    private static void cooldown() {
        var edges = startVisible();
        expect(edges, false, 1370, NONE, "Look away immediately after ON");
        expect(edges, false, 1530, NONE, "Stable absence while sound cooldown is active");
        expect(edges, true, 1540, NONE, "Return while sound cooldown is active");
        expect(edges, true, 1659, NONE, "No cue within 299 ms of the last sound");
    }

    private static void interruptions() {
        var edges = startVisible();
        expect(edges, false, 2110, NONE, "Exactly 750 ms between draws silently rebaselines");
        expect(edges, false, 2360, NONE, "Stale frame produces no deferred OFF");
        expect(edges, true, 2370, NONE, "Fresh visible edge after the gap");
        expect(edges, true, 2470, ON, "Normal cue after the gap");
        expect(edges, false, 500, NONE, "Clock rollback silently rebaselines");
        expect(edges, false, 750, NONE, "No deferred OFF after rollback");
        expect(edges, true, 760, NONE, "Fresh visible edge in the new time range");
        expect(edges, true, 860, ON, "Prior clock cannot suppress fresh cues");

        expect(edges, false, 890, NONE, "Start an exit before leaving");
        edges.reset();
        expect(edges, true, 1040, NONE, "Rejoin with the frame already visible");
        expect(edges, true, 1290, NONE, "Rejoin inherits no pending edge or cue");
        expect(edges, false, 1300, NONE, "New exit after rejoin");
        expect(edges, false, 1460, OFF, "New session can emit a legitimate exit");
    }

    public static void main(String[] args) {
        normalHold();
        startupAndScanning();
        cooldown();
        interruptions();
        System.out.println("PASS: frame sound settling, visibility edges, debounce, cooldown, reset and clock gaps");
    }
}
