package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import static dev.anya.storage.pocket.FrameSoundEdges.Cue;

/** Offline: Minecraft/JOML pose matrices and synthetic completed-frame observations. */
public final class SixFacePreviewCheck {
    private static final float EPS = 0.00002f;

    // Yaw and the expected horizontal look direction are independent of the production snap code.
    private record Heading(float yaw, Direction forward) {}
    private static final Heading[] HEADINGS = {
            new Heading(0, Direction.SOUTH), new Heading(90, Direction.WEST),
            new Heading(180, Direction.NORTH), new Heading(270, Direction.EAST),
            new Heading(361, Direction.SOUTH), new Heading(-91, Direction.EAST),
            new Heading(44.9f, Direction.SOUTH), new Heading(45.1f, Direction.WEST),
            new Heading(134.9f, Direction.WEST), new Heading(135.1f, Direction.NORTH),
            new Heading(-135.1f, Direction.NORTH), new Heading(-134.9f, Direction.EAST),
            new Heading(-45.1f, Direction.EAST), new Heading(-44.9f, Direction.SOUTH)
    };

    private static Vector3f unit(Direction direction) {
        return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static Vector3f texel(Matrix4fc pose, float u, float v, float depth) {
        // These are the real renderer's UV and vertex conventions, including top-down texture V.
        return new Vector3f(.5f - u, .5f - v, -depth).mulPosition(pose);
    }

    private static void near(Vector3f actual, Vector3f expected, String label) {
        if (!(actual.distanceSquared(expected) <= EPS * EPS)) {
            throw new AssertionError(label + ": got " + actual + ", expected " + expected);
        }
    }

    private static void surface(Direction face, Heading view) {
        PoseStack stack = new PoseStack();
        stack.translate(.5, .5, .5);
        EntranceFacePose.orient(stack, face, view.yaw());
        Matrix4fc pose = stack.last().pose();
        Vector3f normal = unit(face);
        Vector3f artworkUp = switch (face) {
            case UP -> unit(view.forward());             // looking down: screen top points ahead
            case DOWN -> unit(view.forward()).negate();  // looking up: screen top points behind
            default -> unit(Direction.UP);               // a wall picture stays world-upright
        };
        Vector3f artworkRight = new Vector3f(artworkUp).cross(normal);
        String label = face + ", yaw " + view.yaw();
        for (float depth : new float[]{.501f, .5015f}) {
            Vector3f center = new Vector3f(.5f).fma(depth, normal);
            near(texel(pose,.5f,.5f,depth), center, label + " center of selected face layer");
            // On a cardinal plane these four expected points exactly span the unit square.
            // A yaw wobble may never shrink it, rotate its corners off the block or mirror the texture.
            for (float u : new float[]{0,1}) {
                for (float v : new float[]{0,1}) {
                    Vector3f expected = new Vector3f(center).fma(u-.5f,artworkRight).fma(.5f-v,artworkUp);
                    near(texel(pose,u,v,depth), expected, label + " UV (" + u + "," + v + ")");
                }
            }
            // EntranceStudyRenderer.quad submits bottom-left, bottom-right, top-right, top-left.
            Vector3f bottomLeft = texel(pose,0,1,depth);
            Vector3f bottomRight = texel(pose,1,1,depth);
            Vector3f topRight = texel(pose,1,0,depth);
            Vector3f winding = bottomRight.sub(bottomLeft).cross(topRight.sub(bottomLeft));
            near(winding, normal, label + " outward front-face winding");
        }
    }

    private static void sample(FrameSoundEdges edges, boolean drawn, long time, Cue expected, String where) {
        Cue actual = edges.sample(drawn,time);
        if (actual != expected) throw new AssertionError(where + ": got " + actual + ", expected " + expected);
    }

    private static BlockHitResult faceHit(Direction face, boolean inside) {
        var pos = new BlockPos(13, 74, -19);
        Vec3 hitPoint = Vec3.atCenterOf(pos).add(face.getStepX()*.5, face.getStepY()*.5, face.getStepZ()*.5);
        return new BlockHitResult(hitPoint, face, pos, inside);
    }

    private static void rayPolicy(Direction face) {
        var outside = faceHit(face,false);
        var inside = faceHit(face,true);
        var miss = BlockHitResult.miss(outside.getLocation(),face,outside.getBlockPos());
        for (boolean singleMarker : new boolean[]{false,true}) {
            boolean expected = !singleMarker || face != Direction.UP && face != Direction.DOWN;
            if (EntranceFaceHit.accepts(outside,singleMarker) != expected
                    || EntranceFaceHit.accepts(inside,singleMarker) || EntranceFaceHit.accepts(miss,singleMarker)) {
                throw new AssertionError("Exterior/inside/miss policy for " + face + ", single marker " + singleMarker);
            }
        }
    }

    private static void soundCircuit() {
        var edges = new FrameSoundEdges();
        sample(edges,false,0,Cue.NONE,"initially elsewhere");
        sample(edges,false,250,Cue.NONE,"settled");
        // Simulate a completed draw on every face crossed. Stay long enough on each for a spurious
        // per-face ON/OFF to register, and turn in place across a snap boundary on top and bottom.
        Direction[] path = {Direction.NORTH,Direction.UP,Direction.EAST,Direction.DOWN,Direction.WEST,Direction.SOUTH};
        long time = 300;
        for (int step = 0; step < path.length; step++) {
            var hit = faceHit(path[step],false);
            for (int frame = 0; frame < 8; frame++, time += 50) {
                surface(path[step], frame < 4 ? HEADINGS[6] : HEADINGS[7]);
                Cue expected = step == 0 && frame == 2 ? Cue.ON : Cue.NONE;
                sample(edges,EntranceFaceHit.accepts(hit,false),time,expected,path[step] + " drawn, frame " + frame);
            }
        }
        sample(edges,false,time,Cue.NONE,"first absent frame");
        sample(edges,false,time+160,Cue.OFF,"settled absence");
        sample(edges,false,time+350,Cue.NONE,"still absent");
    }

    public static void main(String[] args) {
        for (Direction face : Direction.values()) {
            rayPolicy(face);
            for (Heading heading : HEADINGS) surface(face,heading);
        }
        soundCircuit();
        System.out.println("PASS: six exterior face rays, marker scope, inside and missed rays; "
                + "six face planes, outward winding and upright unmirrored UVs at 14 yaws, "
                + "two layer depths; synthetic continuous draw across six faces produced one ON and one OFF.");
    }
}
