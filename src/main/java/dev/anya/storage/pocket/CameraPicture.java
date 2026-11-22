package dev.anya.storage.pocket;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Project the camera's visible surfaces with the current resource pack. No level or chunk access. */
final class CameraPicture {
    static final int SIZE = 384;
    static final int MASK = 0xff221716;
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("anyastorage", "dynamic/outdoor_camera");
    private static DynamicTexture texture;
    private static CameraScene rendered;
    private static final float[] depth = new float[SIZE * SIZE];
    private static NativeImage rainTexture;
    private static boolean rainTried;
    private static boolean hardware;
    // Set only by the disposable comparison fixture through reflection.
    private static boolean forceSoftware, renderedSoftwareOverride;

    static CameraScene rendered() { return rendered; }
    static boolean hardware() { return hardware; }

    static void clear() {
        rendered = null;
        if (texture != null) Minecraft.getInstance().getTextureManager().release(ID);
        texture = null;
        if (rainTexture != null) rainTexture.close();
        rainTexture = null;
        rainTried = false;
        hardware = false;
        CameraTerrain.clear();
        CameraActors.clear();
    }

    static ResourceLocation surface(CameraScene scene) {
        if (rendered == scene && texture != null && renderedSoftwareOverride == forceSoftware) return ID;
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
        }
        NativeImage pixels = texture.getPixels();
        if (pixels == null || scene.cells().length != CameraScene.SIDE * CameraScene.SIDE) return null;
        var view = new SurfaceView(scene);
        hardware = !forceSoftware && CameraTerrain.draw(scene, view, pixels, depth);
        if (!hardware) drawScene(scene, view, pixels);
        CameraActors.draw(scene, pixels, depth);
        drawRain(scene, pixels);
        texture.upload();
        rendered = scene;
        renderedSoftwareOverride = forceSoftware;
        return ID;
    }

    record ModelFace(int state, Direction direction) {}

    // Nine angular samples plus the eight physical neighbours of a face at an actor edge.
    static final int MAX_NEIGHBOURS = 17;

    static int nearbyFaces(CameraScene scene, int row, int col, int[] indices) {
        int grid = CameraScene.SIDE, mid = row * grid + col;
        int cell = scene.cells()[mid], backing = cell < 0 ? scene.backdrop()[mid] : cell;
        var faces = scene.surfaces();
        var anchor = backing >= 2 && backing != CameraScene.ATMOSPHERE ? faces.get(backing - 2) : null;
        if (cell == 0 || cell == CameraScene.ATMOSPHERE || (cell < 0 && anchor == null)) return 0;
        int count = 0;
        boolean besideActor = cell < 0;
        for (int r = Math.max(0, row - 1); r <= Math.min(grid - 1, row + 1); r++) {
            for (int c = Math.max(0, col - 1); c <= Math.min(grid - 1, col + 1); c++) {
                int i = r * grid + c, neighbour = scene.cells()[i];
                int code = neighbour < 0 ? scene.backdrop()[i] : neighbour;
                if (code < 2 || code == CameraScene.ATMOSPHERE) continue;
                if (cell < 0 || neighbour < 0) {
                    if (anchor == null || !CameraScene.adjacentOnPlane(anchor, faces.get(code - 2))) continue;
                    besideActor = true;
                }
                count = include(indices, count, code - 2);
            }
        }
        // The edge of a real floor tile can project between two sample centers. Its adjoining
        // physical face may have been seen on the other side of a wide cow. Use only faces the
        // server actually sent, in the same plane and touching this independently verified one.
        if (besideActor && anchor != null) {
            for (int i = 0; i < faces.size(); i++)
                if (CameraScene.adjacentOnPlane(anchor, faces.get(i))) count = include(indices, count, i);
        }
        return count;
    }

    private static int include(int[] indices, int count, int index) {
        for (int i = 0; i < count; i++) if (indices[i] == index) return count;
        if (count < indices.length) indices[count++] = index;
        return count;
    }

    static int quadrant(int localX, int localY, int tile) {
        return (localY < tile / 2 ? 0 : 2) + (localX < tile / 2 ? 0 : 1);
    }

    /** Bind each supplemental source ray only to its own quarter of the source cell. */
    static void edgeFaces(CameraScene scene, int cell, int[] base, int count, int[] edges) {
        int backing = scene.cells()[cell] < 0 ? scene.backdrop()[cell] : scene.cells()[cell];
        for (int q = 0; q < 4; q++) {
            int proof = scene.edgeBackdrop()[cell * 4 + q];
            edges[q] = proof < 0 ? -1 : 0;
            if (backing < 2 || backing == CameraScene.ATMOSPHERE
                    || proof < 2 || proof == CameraScene.ATMOSPHERE || proof == backing) continue;
            var anchor = scene.surfaces().get(backing - 2);
            var adjoining = scene.surfaces().get(proof - 2);
            for (int i = 0; i < count; i++) {
                var surface = scene.surfaces().get(base[i]);
                if (CameraScene.adjacentOnPlane(anchor, surface) && CameraScene.sharesEdge(surface, adjoining)) {
                    edges[q] = proof;
                    break;
                }
            }
        }
    }

    /** Both renderers use the same angular permissions. A null entry masks the entire region.
     * A positive fine ray can use only its own observed face and its observed, touching peers;
     * a material/color on the opposite side of an unknown or denied ray proves nothing. */
    static final class SurfaceView {
        final ProjectedSurface[] surfaces;
        private final int[][] coarse, fine;
        private static final int TILE = SIZE / CameraScene.SIDE, MICRO = TILE / CameraScene.DETAIL_SIDE;

        SurfaceView(CameraScene scene) {
            var models = new HashMap<ModelFace, List<Facet>>();
            surfaces = new ProjectedSurface[scene.surfaces().size()];
            for (int i = 0; i < surfaces.length; i++) {
                var s = scene.surfaces().get(i);
                var key = new ModelFace(s.stateId(), s.face());
                surfaces[i] = new ProjectedSurface(s, models.computeIfAbsent(key, CameraPicture::model), scene);
            }
            var refs = new int[MAX_NEIGHBOURS];
            var edge = new int[4];
            coarse = new int[scene.cells().length*4][];
            for (int i = 0; i < scene.cells().length; i++) {
                if (scene.cells()[i] == 0) continue;
                int count = nearbyFaces(scene, i/CameraScene.SIDE, i%CameraScene.SIDE, refs);
                edgeFaces(scene, i, refs, count, edge);
                var base = java.util.Arrays.copyOf(refs,count);
                for (int q = 0; q < 4; q++) {
                    if (edge[q] < 0) continue;
                    if (edge[q] == 0) coarse[4*i+q] = base;
                    else {
                        var withEdge = java.util.Arrays.copyOf(base,count+1);
                        withEdge[count] = edge[q]-2;
                        coarse[4*i+q] = withEdge;
                    }
                }
            }
            fine = new int[scene.detail().length][];
            int side = CameraScene.DETAIL_SIDE, area = CameraScene.DETAIL_AREA;
            for (int k = 0; k < fine.length; k++) {
                int code = scene.detail()[k];
                if (code < 2) continue;
                int cell = k/area, sub = k%area, x = cell%CameraScene.SIDE*side + sub%side,
                        y = cell/CameraScene.SIDE*side + sub/side;
                var anchor = scene.surfaces().get(code-2);
                refs[0] = code-2;
                int count = 1;
                for (int r = Math.max(0,y-1); r <= Math.min(CameraScene.SIDE*side-1,y+1); r++) {
                    for (int c = Math.max(0,x-1); c <= Math.min(CameraScene.SIDE*side-1,x+1); c++) {
                        int other = scene.detail()[((r/side)*CameraScene.SIDE+c/side)*area+(r%side)*side+c%side];
                        if (other >= 2 && CameraScene.adjacentOnPlane(anchor,scene.surfaces().get(other-2)))
                            count = include(refs,count,other-2);
                    }
                }
                fine[k] = java.util.Arrays.copyOf(refs,count);
            }
        }

        int[] at(CameraScene scene, int cell, int localX, int localY) {
            var ordinary = coarse[4*cell + quadrant(localX,localY,TILE)];
            if (ordinary == null || fine.length == 0) return ordinary;
            int i = cell*CameraScene.DETAIL_AREA + localY/MICRO*CameraScene.DETAIL_SIDE + localX/MICRO;
            return scene.detail()[i] == 0 ? ordinary : fine[i];
        }
    }

    private static void drawScene(CameraScene scene, SurfaceView view, NativeImage out) {
        int grid = CameraScene.SIDE, tile = SIZE / grid;
        int[] cells = scene.cells();
        Vec3 eye = scene.eye(), forward = scene.forward(), up = scene.up(), right = scene.right();
        double stepX = right.x * 2 / SIZE, stepY = right.y * 2 / SIZE, stepZ = right.z * 2 / SIZE;
        for (int row = 0; row < grid; row++) for (int col = 0; col < grid; col++) {
            int index = row*grid+col, cell = cells[index], startX = col * tile, startY = row * tile;
            if (cell == 0) {
                out.fillRect(startX, startY, tile, tile, MASK);
                continue;
            }
            int backing = cell < 0 ? scene.backdrop()[index] : cell;
            double u = (startX + .5) * 2 / SIZE - 1;
            for (int y = startY; y < startY + tile; y++) {
                double v = 1 - (y + .5) * 2 / SIZE;
                double dx = forward.x + u * right.x + v * up.x;
                double dy = forward.y + u * right.y + v * up.y;
                double dz = forward.z + u * right.z + v * up.z;
                for (int x = startX; x < startX + tile; x++, dx += stepX, dy += stepY, dz += stepZ) {
                    var permitted = view.at(scene,index,x-startX,y-startY);
                    if (permitted == null) {
                        depth[y * SIZE + x] = 0;
                        out.setPixelRGBA(x, y, MASK);
                        continue;
                    }
                    double nearest = Double.POSITIVE_INFINITY;
                    int color = 0;
                    for (int ref : permitted) {
                        var face = view.surfaces[ref];
                        double scale = face.intersection(dx, dy, dz);
                        if (!(scale > 0 && scale < nearest)) continue;
                        int sample = face.sample(eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale);
                        if (sample == 0) continue;
                        nearest = scale;
                        color = sample;
                    }
                    if (color == 0) color = background(scene.sky(), backing, dx, dy, dz);
                    depth[y * SIZE + x] = (float)nearest;
                    out.setPixelRGBA(x, y, color);
                }
            }
        }
    }

    static int background(SourceSky sky, int code, double dx, double dy, double dz) {
        if (sky == null || (code != 1 && code != CameraScene.ATMOSPHERE)) return MASK;
        // Open air to the local range limit proves the ambient color only. Celestial sprites
        // require the server's independent authorization along the remaining line to the sky.
        int rgb = sky.background();
        if (code == 1) rgb = SkyglassPixels.color(sky.direction(new Vec3(dx, dy, dz)), rgb, sky.clarity());
        return 0xff000000 | (rgb & 255) << 16 | (rgb & 0xff00) | rgb >>> 16;
    }

    static float light(int sky, int block, CameraScene scene) {
        // Source skylight already includes the day/night and weather darkening at capture.
        float value = Math.max(sky, block) / 15f;
        return .055f + .945f * value / (2f - value);
    }

    static int shade(int abgr, int tint, float light) {
        int r = (int)((abgr & 255) * ((tint >> 16) & 255) * light / 255);
        int g = (int)(((abgr >> 8) & 255) * ((tint >> 8) & 255) * light / 255);
        int b = (int)(((abgr >> 16) & 255) * (tint & 255) * light / 255);
        return 0xff000000 | b << 16 | g << 8 | r;
    }

    static List<Facet> model(ModelFace key) {
        try {
            return bakeFace(key);
        } catch (RuntimeException unsupportedModel) {
            // Some pack models require client block-entity data. The camera never requests it.
            return List.of();
        }
    }

    private static List<Facet> bakeFace(ModelFace key) {
        var state = Block.stateById(key.state());
        var baked = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        var random = RandomSource.create(42);
        var facets = new ArrayList<Facet>();
        // Models can supply both culled faces and general quads. Count only axis-aligned faces
        // that coincide with the plane seen by the authorized server ray.
        for (Direction group : new Direction[]{key.direction(), null}) {
            random.setSeed(42);
            for (BakedQuad quad : baked.getQuads(state, group, random)) {
                if (quad.getDirection() != key.direction() || facets.size() >= 48) continue;
                var sprite = quad.getSprite();
                var data = quad.getVertices();
                int stride = data.length / 4;
                if (stride < 6) continue;
                Vertex[] points = new Vertex[4];
                for (int n = 0; n < 4; n++) {
                    int at = n * stride;
                    float x = Float.intBitsToFloat(data[at]), y = Float.intBitsToFloat(data[at + 1]), z = Float.intBitsToFloat(data[at + 2]);
                    float u = (Float.intBitsToFloat(data[at + 4]) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                    float v = (Float.intBitsToFloat(data[at + 5]) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
                    points[n] = switch (key.direction().getAxis()) {
                        case X -> new Vertex(z, y, x, u, v);
                        case Y -> new Vertex(x, z, y, u, v);
                        case Z -> new Vertex(x, y, z, u, v);
                    };
                }
                float plane = points[0].plane;
                if (Math.abs(points[1].plane - plane) > .002 || Math.abs(points[2].plane - plane) > .002 || Math.abs(points[3].plane - plane) > .002) continue;
                facets.add(new Facet(points[0], points[1], points[2], sprite, quad.isTinted()));
                facets.add(new Facet(points[0], points[2], points[3], sprite, quad.isTinted()));
            }
        }
        return facets;
    }

    record Vertex(float s, float t, float plane, float u, float v) {}

    static final class Facet {
        final Vertex a, b, c;
        final double inv;
        final TextureAtlasSprite sprite;
        final boolean tinted;
        Facet(Vertex a, Vertex b, Vertex c, TextureAtlasSprite sprite, boolean tinted) {
            this.a = a; this.b = b; this.c = c; this.sprite = sprite; this.tinted = tinted;
            double det = (b.t - c.t) * (a.s - c.s) + (c.s - b.s) * (a.t - c.t);
            inv = Math.abs(det) < .000001 ? 0 : 1 / det;
        }
        int sample(double s, double t, int tint, float brightness) {
            if (inv == 0) return 0;
            double k = ((b.t - c.t) * (s - c.s) + (c.s - b.s) * (t - c.t)) * inv;
            double l = ((c.t - a.t) * (s - c.s) + (a.s - c.s) * (t - c.t)) * inv;
            double m = 1 - k - l;
            if (Math.min(Math.min(k, l), m) < -.00001) return 0;
            int w = sprite.contents().width(), h = sprite.contents().height();
            int x = Math.clamp((int)((k * a.u + l * b.u + m * c.u) * w), 0, w - 1);
            int y = Math.clamp((int)((k * a.v + l * b.v + m * c.v) * h), 0, h - 1);
            int texel = sprite.getPixelRGBA(0, x, y);
            return (texel >>> 24) < 32 ? 0 : shade(texel, tinted ? tint : 0xffffff, brightness);
        }
    }

    static final class ProjectedSurface {
        final CameraScene.Surface s;
        final List<Facet> facets;
        final int axis;
        final double planeFromEye, localPlane;
        final float brightness;
        ProjectedSurface(CameraScene.Surface s, List<Facet> facets, CameraScene scene) {
            this.s = s; this.facets = facets;
            axis = switch (s.face().getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
            planeFromEye = s.plane() - (axis == 0 ? scene.eye().x : axis == 1 ? scene.eye().y : scene.eye().z);
            localPlane = s.plane() - (axis == 0 ? s.x() : axis == 1 ? s.y() : s.z());
            brightness = light(s.skyLight(), s.blockLight(), scene) * switch (s.face()) {
                case UP -> 1f; case DOWN -> .5f; case NORTH, SOUTH -> .8f; default -> .6f;
            };
        }
        double intersection(double dx, double dy, double dz) {
            double d = axis == 0 ? dx : axis == 1 ? dy : dz;
            return d == 0 ? -1 : planeFromEye / d;
        }
        int sample(double x, double y, double z) {
            double h = axis == 0 ? z - s.z() : x - s.x();
            double v = axis == 1 ? z - s.z() : y - s.y();
            if (h < -.001 || v < -.001 || h > 1.001 || v > 1.001) return 0;
            for (int i = facets.size() - 1; i >= 0; i--) {
                var f = facets.get(i);
                if (Math.abs(localPlane - f.a.plane) > .009) continue;
                int color = f.sample(h, v, s.tint(), brightness);
                if (color != 0) return color;
            }
            return 0;
        }
    }

    private static final double[] RAIN_DISTANCE = {.8, 1.55, 2.7, 4.5};

    /** Sample the entire rain sheet on four vertical shells in front of the exterior lens. */
    private static void drawRain(CameraScene scene, NativeImage out) {
        if (!(scene.rain() > .01f)) return;
        if (!rainTried) {
            rainTried = true;
            var id = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
            try (var stream = Minecraft.getInstance().getResourceManager().open(id)) {
                rainTexture = NativeImage.read(stream);
            } catch (java.io.IOException unavailable) { return; }
        }
        if (rainTexture == null) return;
        var forward = scene.forward(); var right = scene.right(); var up = scene.up();
        double[] actorDepth = new double[scene.actors().size()];
        for (int i = 0; i < actorDepth.length; i++)
            actorDepth[i] = scene.actors().get(i).feet().subtract(scene.eye()).dot(forward) - .5;
        int tw = rainTexture.getWidth(), th = rainTexture.getHeight(), grid = CameraScene.SIDE;
        double[] offset = new double[RAIN_DISTANCE.length];
        for (int i = 0; i < offset.length; i++)
            offset[i] = .371 * i - scene.weatherTick() * (6 + i) / 128.0;
        // Source simulation time keeps the rain moving while the daylight clock is frozen.
        for (int y = 0; y < SIZE; y++) {
            double ny = 1 - (y + .5) * 2 / SIZE;
            for (int x = 0; x < SIZE; x++) {
                int cell = scene.cells()[y * grid / SIZE * grid + x * grid / SIZE];
                if (cell == 0) continue;
                int pixel = out.getPixelRGBA(x, y);
                if (pixel == MASK) continue;
                double nx = (x + .5) * 2 / SIZE - 1;
                double dx = forward.x + nx * right.x + ny * up.x;
                double dy = forward.y + nx * right.y + ny * up.y;
                double dz = forward.z + nx * right.z + ny * up.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < .05) continue;
                double turn = Math.atan2(dz, dx) / (2 * Math.PI);
                double limit = depth[y * SIZE + x];
                if (cell < 0) limit = Math.min(limit, actorDepth[-1 - cell]);
                for (int layer = RAIN_DISTANCE.length - 1; layer >= 0; layer--) {
                    double radius = RAIN_DISTANCE[layer], distance = radius / horizontal;
                    double height = scene.eye().y + dy * distance;
                    if (distance >= limit || Math.abs(height) > 6) continue;
                    // A full repeat of the pack's sheet is roughly one block wide by four high.
                    // Integral repeats meet at the back seam. The gentle crosswind shears the streaks.
                    double u = turn * Math.round(2 * Math.PI * radius) + height * .10 + .217 * layer;
                    double v = -height * .25 + offset[layer];
                    int sample = rainTexture.getPixelRGBA(Math.floorMod((int)Math.floor(u * tw), tw),
                            Math.floorMod((int)Math.floor(v * th), th));
                    int opacity = sample >>> 24;
                    if (opacity == 0) continue;
                    float alpha = opacity / 255f * scene.rain() * (1 - layer * .065f);
                    int r = (int)((pixel & 255) * (1 - alpha) + (sample & 255) * alpha);
                    int g = (int)((pixel >> 8 & 255) * (1 - alpha) + (sample >> 8 & 255) * alpha);
                    int b = (int)((pixel >> 16 & 255) * (1 - alpha) + (sample >> 16 & 255) * alpha);
                    pixel = 0xff000000 | b << 16 | g << 8 | r;
                }
                out.setPixelRGBA(x, y, pixel);
            }
        }
    }

    private CameraPicture() {}
}
