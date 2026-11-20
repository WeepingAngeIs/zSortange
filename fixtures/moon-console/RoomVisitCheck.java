package dev.anya.storage.pocket;

import java.util.Random;
import java.util.UUID;

/** Runs against the normal packaged scheduler using a fixed RNG for repeatability. */
public final class RoomVisitCheck {
    private static void verify(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var visits = new RoomVisitSchedule(new Random(41));
        UUID owner = new UUID(1,1), guest = new UUID(1,2), remote = new UUID(1,3);
        int ownerStart=-1, guestStart=-1, nextVisit=-1;
        for (int tick=0; tick<2500; tick++) {
            int ownerCue = visits.advance(owner,tick);
            if (ownerCue >= 0) {
                verify(ownerCue < 3, "Unknown choreography");
                if (ownerStart<0) ownerStart=tick;
                else { nextVisit=tick; break; }
            } else if (ownerStart>=0 && tick==ownerStart+96) {
                verify(ownerCue==RoomVisitSchedule.HIDE, "Client needs one clear at expiry");
            } else verify(ownerCue==RoomVisitSchedule.NONE, "Unexpected repeated cue");

            if (guestStart<0) {
                int guestCue = visits.advance(guest,tick);
                if (guestCue>=0) {
                    guestStart=tick;
                    verify(visits.end(guest), "Revocation during a visible visit must clear that viewer");
                    verify(!visits.end(guest), "Revoked viewer cleared twice");
                }
            }
            verify(!visits.end(remote), "Unscheduled Remote viewer received a clear");
        }
        verify(ownerStart>=100 && ownerStart<=220 && guestStart>=100 && guestStart<=220, "First appearance window");
        verify(nextVisit>=ownerStart+96+900 && nextVisit<=ownerStart+96+2100, "Rare return window or cross-viewer cancellation");
        visits.clear();
        verify(!visits.end(owner), "Server shutdown retained a viewer");
        verify(visits.advance(guest,4000)==RoomVisitSchedule.NONE && !visits.end(guest), "New arrival inherited an earlier appearance");
        System.out.println("Packaged room Cupid scheduling passed expiry, cooldown and immediate per-viewer cancellation.");
    }
}
