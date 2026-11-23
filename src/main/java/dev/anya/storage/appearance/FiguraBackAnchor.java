package dev.anya.storage.appearance;

import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.ParentType;
import org.figuramc.figura.model.PartCustomization.PartType;

/** Rest geometry is measured once per loaded rig. The attachment's live matrix is sampled separately. */
record FiguraBackAnchor(FiguraModelPart part, float x, float y, float z, float scale) {
    static FiguraBackAnchor find(FiguraModelPart root) {
        var search = new Search();
        search.visit(root, 0);
        if (search.overBudget || search.markers > 1) return null;
        return search.markers == 1 ? search.marker : search.torsos == 1 ? search.torso : null;
    }

    // The plain pack shell runs from y=2..16 and z=3.5..12 on its 16-pixel item grid.
    // Put the middle of the shell on the torso centerline with its straps at the back surface.
    private static FiguraBackAnchor contact(FiguraModelPart part, double x, double y, double z, double fit) {
        float pixel = (float)(.72 * fit);
        return new FiguraBackAnchor(part, (float)x, (float)y - pixel, (float)z + 4.5f * pixel, 16 * pixel);
    }

    private static final class Search {
        int nodes, vertices, markers, torsos;
        boolean overBudget;
        FiguraBackAnchor marker, torso;

        void visit(FiguraModelPart part, int depth) {
            if (overBudget || part == null) return;
            if (++nodes > 2048 || depth > 64) { overBudget = true; return; }
            var type = part.parentType;
            if (type != ParentType.None && type != ParentType.Body) return;
            if (part.customization.partType != PartType.GROUP) return;
            if ("BackpackPivot".equalsIgnoreCase(part.name) || "BackpackAnchor".equalsIgnoreCase(part.name)) {
                var p = part.customization.getPivot();
                marker = contact(part, p.x, p.y, p.z, 1);
                markers++;
                return;
            }
            if (type == ParentType.Body || "body".equalsIgnoreCase(part.name) || "torso".equalsIgnoreCase(part.name)) {
                var candidate = trunk(part);
                if (candidate != null) { torso = candidate; torsos++; }
            }
            if (part.children != null) for (var child : part.children) visit(child, depth + 1);
        }

        FiguraBackAnchor trunk(FiguraModelPart group) {
            Bounds best = bounds(group);
            boolean ambiguous = false;
            if (group.children != null) for (var child : group.children) {
                if (child.customization.partType == PartType.GROUP
                        || (child.parentType != ParentType.None && child.parentType != ParentType.Body)) continue;
                var next = bounds(child);
                if (next == null) continue;
                if (best == null || next.volume() > best.volume() * 1.25) {
                    best = next;
                    ambiguous = false;
                } else if (next.volume() >= best.volume() * .8 && !next.sameShape(best)) {
                    ambiguous = true;
                }
            }
            if (best == null || ambiguous) return null;
            return contact(best.part, (best.loX + best.hiX) / 2, (best.loY + best.hiY) / 2,
                    best.hiZ, Math.min(best.width() / 8, best.height() / 12));
        }

        Bounds bounds(FiguraModelPart part) {
            if (overBudget || part.vertices == null || part.vertices.isEmpty()) return null;
            var box = new Bounds(part);
            for (var list : part.vertices.values()) {
                if (list == null) continue;
                vertices += list.size();
                if (vertices > 32768) { overBudget = true; return null; }
                for (var vertex : list) box.include(vertex.x, vertex.y, vertex.z);
            }
            // A flat ribbon, wide wing, or long tail is a poor estimate of torso size.
            double w = box.width(), h = box.height(), d = box.hiZ - box.loZ;
            return w > .1 && w < 128 && d > .1 && d <= w * 1.3 && h >= w * .75 && h <= w * 3 ? box : null;
        }
    }

    private static final class Bounds {
        final FiguraModelPart part;
        double loX = Double.POSITIVE_INFINITY, loY = loX, loZ = loX;
        double hiX = Double.NEGATIVE_INFINITY, hiY = hiX, hiZ = hiX;
        Bounds(FiguraModelPart part) { this.part = part; }
        void include(double x, double y, double z) {
            loX = Math.min(loX, x); loY = Math.min(loY, y); loZ = Math.min(loZ, z);
            hiX = Math.max(hiX, x); hiY = Math.max(hiY, y); hiZ = Math.max(hiZ, z);
        }
        double width() { return hiX - loX; }
        double height() { return hiY - loY; }
        double volume() { return width() * height() * (hiZ - loZ); }
        boolean sameShape(Bounds other) {
            return Math.abs(loX-other.loX)<.15 && Math.abs(loY-other.loY)<.15 && Math.abs(loZ-other.loZ)<.15
                    && Math.abs(hiX-other.hiX)<.15 && Math.abs(hiY-other.hiY)<.15 && Math.abs(hiZ-other.hiZ)<.15;
        }
    }
}
