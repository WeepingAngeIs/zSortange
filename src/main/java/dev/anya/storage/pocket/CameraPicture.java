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
        hardware = !forceSoftware && CameraTerrain.draw(scene, pixels, depth);
        if (!hardware) drawScene(scene, pixels);
        CameraActors.draw(scene, pixels);
        drawRain(scene, pixels);
        texture.upload();
        rendered = scene;
        renderedSoftwareOverride = forceSoftware;
        return ID;
    }

    record ModelFace(int state, Direction direction) {}

    private static void drawScene(CameraScene scene, NativeImage out) {
        var models = new HashMap<ModelFace, List<Facet>>();
        var surfaces = new ProjectedSurface[scene.surfaces().size()];
        for (int i = 0; i < surfaces.length; i++) {
            var s = scene.surfaces().get(i);
            var key = new ModelFace(s.stateId(), s.face());
            var facets = models.computeIfAbsent(key, CameraPicture::model);
            surfaces[i] = new ProjectedSurface(s, facets, scene);
        }
        int grid = CameraScene.SIDE, tile = SIZE / grid;
        int[] cells = scene.cells(), neighbours = new int[9];
        Vec3 eye = scene.eye(), forward = scene.forward(), up = scene.up(), right = scene.right();
        double stepX = right.x * 2 / SIZE, stepY = right.y * 2 / SIZE, stepZ = right.z * 2 / SIZE;
        for (int row = 0; row < grid; row++) for (int col = 0; col < grid; col++) {
            int cell = cells[row * grid + col], startX = col * tile, startY = row * tile;
            if (cell == 0) {
                out.fillRect(startX, startY, tile, tile, MASK);
                continue;
            }
            // A ray sample identifies a public face. Its exact projected edges may cross into an
            // adjacent allowed sample. Gather only faces actually visible in this 3x3 neighborhood.
            int count = 0;
            int backing = cell < 0 ? scene.backdrop()[row * grid + col] : cell;
            boolean sky = backing == 1;
            if (cell < 0) {
                // An animal's bounding box precedes a background ray. Only its independently
                // observed backdrop may show between the legs or around the actual model.
                if (backing >= 2) neighbours[count++] = backing - 2;
            } else {
                for (int r = Math.max(0, row - 1); r <= Math.min(grid - 1, row + 1); r++) {
                    for (int c = Math.max(0, col - 1); c <= Math.min(grid - 1, col + 1); c++) {
                        int index = cells[r * grid + c] - 2;
                        if (index < 0 || index >= surfaces.length) continue;
                        int n = 0;
                        while (n < count && neighbours[n] != index) n++;
                        if (n == count) neighbours[count++] = index;
                    }
                }
            }
            double u = (startX + .5) * 2 / SIZE - 1;
            for (int y = startY; y < startY + tile; y++) {
                double v = 1 - (y + .5) * 2 / SIZE;
                double dx = forward.x + u * right.x + v * up.x;
                double dy = forward.y + u * right.y + v * up.y;
                double dz = forward.z + u * right.z + v * up.z;
                for (int x = startX; x < startX + tile; x++, dx += stepX, dy += stepY, dz += stepZ) {
                    double nearest = Double.POSITIVE_INFINITY;
                    int color = 0;
                    for (int i = 0; i < count; i++) {
                        var face = surfaces[neighbours[i]];
                        double scale = face.intersection(dx, dy, dz);
                        if (!(scale > 0 && scale < nearest)) continue;
                        int sample = face.sample(eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale);
                        if (sample == 0) continue;
                        nearest = scale;
                        color = sample;
                    }
                    if (color == 0) color = sky && scene.sky() != null ? sky(scene.sky(), dx, dy, dz) : MASK;
                    depth[y * SIZE + x] = (float)nearest;
                    out.setPixelRGBA(x, y, color);
                }
            }
        }
    }

    static int sky(SourceSky sky, double dx, double dy, double dz) {
        int sample = sky.direction(new Vec3(dx, dy, dz));
        int rgb = SkyglassPixels.color(sample, sky.background(), sky.clarity());
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

    private static final class ProjectedSurface {
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

    /** Draw nearby precipitation in the outdoor lens volume, occluded by nearer scene surfaces. */
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
        var random = RandomSource.create(8141);
        int drops = (int)(scene.rain() * 900);
        var clientLevel = Minecraft.getInstance().level;
        long rainTick = clientLevel == null ? scene.time() : clientLevel.getGameTime();
        for (int i = 0; i < drops; i++) {
            double x = (random.nextDouble() - .5) * 10;
            double z = (random.nextDouble() - .5) * 10;
            double y = 4 - ((random.nextDouble() * 8 + Math.floorMod(rainTick, 160) * .25) % 8);
            float u = random.nextFloat();
            double distance = x * scene.forward().x + y * scene.forward().y + z * scene.forward().z;
            if (distance < .6) continue;
            double nx = (x * scene.right().x + y * scene.right().y + z * scene.right().z) / (distance * scene.right().lengthSqr());
            double ny = (x * scene.up().x + y * scene.up().y + z * scene.up().z) / (distance * scene.up().lengthSqr());
            int px = (int)((nx + 1) * SIZE * .5), py = (int)((1 - ny) * SIZE * .5);
            int length = Math.clamp((int)(SIZE * .065 / distance), 1, 22);
            for (int k = 0; k < length; k++) {
                int yy = py + k;
                if (px < 0 || yy < 0 || px >= SIZE || yy >= SIZE || distance > depth[yy * SIZE + px]) continue;
                int cell = scene.cells()[(yy * CameraScene.SIDE / SIZE) * CameraScene.SIDE + px * CameraScene.SIDE / SIZE];
                if (cell == 0) continue;
                if (cell < 0 && distance > scene.actors().get(-1-cell).feet().subtract(scene.eye()).dot(scene.forward())-.5) continue;
                int texel = rainTexture.getPixelRGBA((int)(u * rainTexture.getWidth()), k * rainTexture.getHeight() / length);
                float alpha = (texel >>> 24) / 255f * .6f;
                int old = out.getPixelRGBA(px, yy);
                int r = (int)((old & 255) * (1 - alpha) + (texel & 255) * alpha);
                int g = (int)((old >> 8 & 255) * (1 - alpha) + (texel >> 8 & 255) * alpha);
                int b = (int)((old >> 16 & 255) * (1 - alpha) + (texel >> 16 & 255) * alpha);
                out.setPixelRGBA(px, yy, 0xff000000 | b << 16 | g << 8 | r);
            }
        }
    }

    private CameraPicture() {}
}
