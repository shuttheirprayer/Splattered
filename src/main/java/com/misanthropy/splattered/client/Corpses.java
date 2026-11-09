package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.client.ShaderPackCompat;
import com.misanthropy.splattered.BloodType;
import com.misanthropy.splattered.BloodTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Corpses {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_GIBS = 160;
    private static final int MAX_PER_BODY = 40;
    private static final float GRAVITY = 0.055F;
    private static final float DRAG = 0.98F;
    private static final int[][] SIDES = {{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
    private static final float[][] NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private static final Int2ObjectOpenHashMap<Request> PENDING = new Int2ObjectOpenHashMap<>();
    private static final IntOpenHashSet HIDDEN = new IntOpenHashSet();
    private static final List<Body> BODIES = new ArrayList<>();
    private static final Map<Class<?>, Field[]> FIELDS = new IdentityHashMap<>();
    private static final Matrix4f VIEW_INV = new Matrix4f();
    private static final Matrix3f VIEW_INV_N = new Matrix3f();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix3f NORMAL = new Matrix3f();
    private static final Quaternionf ROT = new Quaternionf();
    private static final Quaternionf SPIN = new Quaternionf();
    private static final Vector3f FACE_N = new Vector3f();
    private static final RandomSource RANDOM = RandomSource.create();

    private static Field cubesField;
    private static Field childrenField;
    private static Field polygonsField;
    private static Field verticesField;
    private static Field posField;
    private static Field uField;
    private static Field vField;
    private static boolean broken;
    private static Vec3 viewCam = Vec3.ZERO;
    private static boolean viewValid;
    private static int gibs;

    private Corpses() {
    }

    private record Request(Vec3 dir, float force, long made) {
    }

    private record Cube(Matrix4f pose, Matrix3f normal, ModelPart.Cube cube) {
    }

    private static final class Body {
        final RenderType skin;
        final ResourceLocation texture;
        final BloodType type;
        final boolean player;
        final List<Gib> parts = new ArrayList<>();
        int sounds;

        Body(RenderType skin, ResourceLocation texture, BloodType type, boolean player) {
            this.skin = skin;
            this.texture = texture;
            this.type = type;
            this.player = player;
        }
    }

    private static final class Gib {
        final Matrix4f model = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        final float[] box = new float[6];
        final boolean[] outer = new boolean[6];
        final float[] uv = new float[48];
        int flesh;
        double x;
        double y;
        double z;
        double px;
        double py;
        double pz;
        float vx;
        float vy;
        float vz;
        final Quaternionf rot = new Quaternionf();
        final Quaternionf prev = new Quaternionf();
        final Vector3f spin = new Vector3f();
        float radius;
        float reach;
        float sink;
        int age;
        int life;
        int light;
        int every = 1;
        boolean rest;
        boolean pooled;
    }

    static void request(LivingEntity entity, Vec3 dir, float force) {
        PENDING.put(entity.getId(), new Request(dir, force, entity.level().getGameTime()));
    }

    static void vanish(LivingEntity entity) {
        HIDDEN.add(entity.getId());
    }

    static boolean pending(int id) {
        return !PENDING.isEmpty() && PENDING.containsKey(id);
    }

    public static boolean hidden(int id) {
        return !HIDDEN.isEmpty() && HIDDEN.contains(id);
    }

    static boolean isEmpty() {
        return BODIES.isEmpty();
    }

    static int size() {
        return gibs;
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || PENDING.isEmpty()) return;
        VIEW_INV.set(event.getPoseStack().last().pose()).invert();
        VIEW_INV_N.set(event.getPoseStack().last().normal()).invert();
        viewCam = event.getCamera().getPosition();
        viewValid = true;
    }

    private static boolean reflect() {
        if (broken) return false;
        if (cubesField != null) return true;
        try {
            cubesField = ObfuscationReflectionHelper.findField(ModelPart.class, "f_104212_");
            childrenField = ObfuscationReflectionHelper.findField(ModelPart.class, "f_104213_");
            polygonsField = ObfuscationReflectionHelper.findField(ModelPart.Cube.class, "f_104341_");
            Class<?> polygon = polygonsField.getType().getComponentType();
            verticesField = ObfuscationReflectionHelper.findField(polygon, "f_104359_");
            Class<?> vertex = verticesField.getType().getComponentType();
            posField = ObfuscationReflectionHelper.findField(vertex, "f_104371_");
            uField = ObfuscationReflectionHelper.findField(vertex, "f_104372_");
            vField = ObfuscationReflectionHelper.findField(vertex, "f_104373_");
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Gore gibs disabled, model fields not found", e);
            broken = true;
            return false;
        }
    }

    static void capture(LivingEntity entity, EntityModel<?> model, PoseStack stack, ResourceLocation texture, boolean player) {
        if (GoreFx.shadowPass()) return;
        Request request = PENDING.remove(entity.getId());
        if (request == null) return;
        if (!viewValid || !reflect() || !shatterBody(entity, model, stack, texture, player, request)) fallback(entity, request);
    }

    private static boolean shatterBody(LivingEntity entity, EntityModel<?> model, PoseStack stack, ResourceLocation texture, boolean player, Request request) {
        try {
            List<Cube> cubes = new ArrayList<>();
            PoseStack walk = new PoseStack();
            walk.last().pose().set(VIEW_INV).mul(stack.last().pose());
            walk.last().normal().set(VIEW_INV_N).mul(stack.last().normal());
            for (ModelPart root : roots(model)) collect(root, walk, cubes);
            if (cubes.isEmpty()) return false;
            BloodType type = BloodTypes.of(entity);
            Body body = new Body(model.renderType(texture), texture, type, player);
            float cell = request.force > 1.4F ? 3.5F : 4.0F;
            while (count(cubes, cell) > MAX_PER_BODY && cell < 16.0F) cell += 1.0F;
            Vector3f middle = new Vector3f();
            for (Cube cube : cubes) shatter(cube, cell, body);
            if (body.parts.isEmpty()) return false;
            for (Gib gib : body.parts) middle.add((float) (gib.x - viewCam.x), (float) (gib.y - viewCam.y), (float) (gib.z - viewCam.z));
            middle.div(body.parts.size());
            launch(body, middle, request, entity.level());
            while (gibs + body.parts.size() > MAX_GIBS && !BODIES.isEmpty()) gibs -= BODIES.remove(0).parts.size();
            BODIES.add(body);
            gibs += body.parts.size();
            HIDDEN.add(entity.getId());
            return true;
        } catch (RuntimeException | ReflectiveOperationException e) {
            LOGGER.debug("Gore gib capture failed for {}", entity, e);
            return false;
        }
    }

    private static void fallback(LivingEntity entity, Request request) {
        if (!(entity.level() instanceof ClientLevel level)) return;
        BloodType type = BloodTypes.of(entity);
        float bulk = GoreFx.bulk(entity);
        Vec3 center = entity.getBoundingBox().getCenter();
        int pieces = Mth.clamp(Math.round((8.0F + bulk * 6.0F) * request.force), 6, 26);
        Chunks.burst(level, center, request.dir, pieces, request.force * 1.2F, Mth.clamp(Mth.sqrt(bulk), 0.8F, 1.6F), type, RANDOM);
        Mist.puff(level, center, request.dir, 5 + Math.round(bulk * 3.0F), 0.4F * Mth.sqrt(bulk), 0.14F, type, RANDOM);
        HIDDEN.add(entity.getId());
    }

    private static List<ModelPart> roots(EntityModel<?> model) throws IllegalAccessException {
        if (model instanceof HierarchicalModel<?> hierarchical) return List.of(hierarchical.root());
        List<ModelPart> found = new ArrayList<>();
        for (Field field : fields(model.getClass())) {
            if (field.get(model) instanceof ModelPart part && !found.contains(part)) found.add(part);
        }
        List<ModelPart> roots = new ArrayList<>();
        for (ModelPart part : found) {
            boolean inner = false;
            for (ModelPart other : found) {
                if (other != part && other.getAllParts().anyMatch(p -> p == part)) {
                    inner = true;
                    break;
                }
            }
            if (!inner) roots.add(part);
        }
        return roots;
    }

    private static Field[] fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, k -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != ModelPart.class) continue;
                    try {
                        field.setAccessible(true);
                        out.add(field);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return out.toArray(new Field[0]);
        });
    }

    @SuppressWarnings("unchecked")
    private static void collect(ModelPart part, PoseStack stack, List<Cube> out) throws IllegalAccessException {
        if (!part.visible) return;
        stack.pushPose();
        part.translateAndRotate(stack);
        if (!part.skipDraw) {
            for (ModelPart.Cube cube : (List<ModelPart.Cube>) cubesField.get(part)) {
                out.add(new Cube(new Matrix4f(stack.last().pose()), new Matrix3f(stack.last().normal()), cube));
            }
        }
        for (ModelPart child : ((Map<String, ModelPart>) childrenField.get(part)).values()) collect(child, stack, out);
        stack.popPose();
    }

    private static int count(List<Cube> cubes, float cell) {
        int total = 0;
        for (Cube cube : cubes) {
            ModelPart.Cube c = cube.cube;
            total += Math.max(1, Math.round((c.maxX - c.minX) / cell)) * Math.max(1, Math.round((c.maxY - c.minY) / cell)) * Math.max(1, Math.round((c.maxZ - c.minZ) / cell));
        }
        return total;
    }

    private static float[] cuts(float lo, float hi, int n) {
        float[] out = new float[n + 1];
        float step = (hi - lo) / n;
        for (int k = 0; k <= n; k++) out[k] = lo + k * step + (k > 0 && k < n ? (RANDOM.nextFloat() - 0.5F) * 0.45F * step : 0.0F);
        return out;
    }

    private static float[][] polygons(ModelPart.Cube cube) throws IllegalAccessException {
        Object[] raw = (Object[]) polygonsField.get(cube);
        float[][] out = new float[raw.length][];
        for (int p = 0; p < raw.length; p++) {
            Object[] vertices = (Object[]) verticesField.get(raw[p]);
            float[] poly = new float[vertices.length * 5];
            for (int q = 0; q < vertices.length; q++) {
                Vector3f pos = (Vector3f) posField.get(vertices[q]);
                poly[q * 5] = pos.x;
                poly[q * 5 + 1] = pos.y;
                poly[q * 5 + 2] = pos.z;
                poly[q * 5 + 3] = uField.getFloat(vertices[q]);
                poly[q * 5 + 4] = vField.getFloat(vertices[q]);
            }
            out[p] = poly;
        }
        return out;
    }

    private static void shatter(Cube rec, float cell, Body body) throws IllegalAccessException {
        float[][] polygons = polygons(rec.cube);
        if (polygons.length == 0) return;
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] polygon : polygons) {
            for (int q = 0; q < polygon.length; q += 5) {
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], polygon[q + a]);
                    hi[a] = Math.max(hi[a], polygon[q + a]);
                }
            }
        }
        float[][] faces = new float[6][];
        for (float[] polygon : polygons) {
            for (int a = 0; a < 3; a++) {
                float first = polygon[a];
                boolean flat = true;
                for (int q = 0; q < polygon.length; q += 5) flat &= Math.abs(polygon[q + a] - first) < 1.0E-3F;
                if (!flat) continue;
                if (Math.abs(first - lo[a]) < 1.0E-3F) faces[a * 2] = polygon;
                else if (Math.abs(first - hi[a]) < 1.0E-3F) faces[a * 2 + 1] = polygon;
            }
        }
        int[] n = new int[3];
        float[][] cut = new float[3][];
        for (int a = 0; a < 3; a++) {
            n[a] = Math.max(1, Math.round((hi[a] - lo[a]) / cell));
            cut[a] = cuts(lo[a], hi[a], n[a]);
        }
        for (int i = 0; i < n[0]; i++) {
            for (int j = 0; j < n[1]; j++) {
                for (int k = 0; k < n[2]; k++) {
                    int[] idx = {i, j, k};
                    Gib gib = new Gib();
                    for (int a = 0; a < 3; a++) {
                        gib.box[a] = cut[a][idx[a]] / 16.0F;
                        gib.box[a + 3] = cut[a][idx[a] + 1] / 16.0F;
                    }
                    for (int f = 0; f < 6; f++) {
                        int a = f >> 1;
                        boolean edge = (f & 1) == 0 ? idx[a] == 0 : idx[a] == n[a] - 1;
                        gib.outer[f] = edge && faces[f] != null;
                        if (gib.outer[f]) mapUv(gib, f, faces[f], lo, hi);
                    }
                    float roll = RANDOM.nextFloat();
                    gib.flesh = body.type == BloodType.BONE ? GoreAtlas.BONE : roll < 0.6F ? GoreAtlas.MUSCLE : roll < 0.84F ? GoreAtlas.CLOT : GoreAtlas.FAT;
                    Vector4f center = new Vector4f((gib.box[0] + gib.box[3]) * 0.5F, (gib.box[1] + gib.box[4]) * 0.5F, (gib.box[2] + gib.box[5]) * 0.5F, 1.0F);
                    rec.pose.transform(center);
                    gib.model.translation(-center.x, -center.y, -center.z).mul(rec.pose);
                    gib.normal.set(rec.normal);
                    float sx = (gib.box[3] - gib.box[0]);
                    float sy = (gib.box[4] - gib.box[1]);
                    float sz = (gib.box[5] - gib.box[2]);
                    float scale = rec.pose.getScale(new Vector3f()).x;
                    gib.radius = Mth.clamp(Math.min(sx, Math.min(sy, sz)) * scale * 0.5F, 0.03F, 0.5F);
                    gib.reach = Mth.sqrt(sx * sx + sy * sy + sz * sz) * scale * 0.75F + 0.25F;
                    gib.x = gib.px = viewCam.x + center.x;
                    gib.y = gib.py = viewCam.y + center.y;
                    gib.z = gib.pz = viewCam.z + center.z;
                    body.parts.add(gib);
                }
            }
        }
    }

    private static void mapUv(Gib gib, int f, float[] polygon, float[] lo, float[] hi) {
        int axis = f >> 1;
        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;
        float[] corner = new float[8];
        for (int q = 0; q < polygon.length; q += 5) {
            int ia = polygon[q + a] > (lo[a] + hi[a]) * 0.5F ? 1 : 0;
            int ib = polygon[q + b] > (lo[b] + hi[b]) * 0.5F ? 1 : 0;
            int slot = (ib * 2 + ia) * 2;
            corner[slot] = polygon[q + 3];
            corner[slot + 1] = polygon[q + 4];
        }
        int[] side = SIDES[f];
        for (int q = 0; q < 4; q++) {
            int c = side[q];
            float pa = ((c >> a & 1) == 0 ? gib.box[a] : gib.box[a + 3]) * 16.0F;
            float pb = ((c >> b & 1) == 0 ? gib.box[b] : gib.box[b + 3]) * 16.0F;
            float s = hi[a] > lo[a] ? (pa - lo[a]) / (hi[a] - lo[a]) : 0.0F;
            float t = hi[b] > lo[b] ? (pb - lo[b]) / (hi[b] - lo[b]) : 0.0F;
            float u0 = Mth.lerp(s, corner[0], corner[2]);
            float u1 = Mth.lerp(s, corner[4], corner[6]);
            float v0 = Mth.lerp(s, corner[1], corner[3]);
            float v1 = Mth.lerp(s, corner[5], corner[7]);
            gib.uv[f * 8 + q * 2] = Mth.lerp(t, u0, u1);
            gib.uv[f * 8 + q * 2 + 1] = Mth.lerp(t, v0, v1);
        }
    }

    private static void launch(Body body, Vector3f middle, Request request, Level level) {
        Vec3 dir = request.dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : request.dir.normalize();
        float force = request.force;
        ClientLevel client = Minecraft.getInstance().level;
        for (Gib gib : body.parts) {
            float ox = (float) (gib.x - viewCam.x) - middle.x;
            float oz = (float) (gib.z - viewCam.z) - middle.z;
            float ol = Mth.sqrt(ox * ox + oz * oz);
            if (ol < 1.0E-3F) {
                ox = (float) dir.x;
                oz = (float) dir.z;
                ol = Math.max(1.0E-3F, Mth.sqrt(ox * ox + oz * oz));
            }
            ox /= ol;
            oz /= ol;
            float kick = (0.16F + 0.2F * RANDOM.nextFloat()) * force;
            gib.vx = (float) (dir.x * kick + ox * 0.17F * force + RANDOM.nextGaussian() * 0.04);
            gib.vy = (float) ((0.1F + 0.2F * RANDOM.nextFloat()) * force + Math.max(0.0, dir.y) * 0.15);
            gib.vz = (float) (dir.z * kick + oz * 0.17F * force + RANDOM.nextGaussian() * 0.04);
            gib.spin.set((float) RANDOM.nextGaussian(), (float) RANDOM.nextGaussian(), (float) RANDOM.nextGaussian()).normalize().mul(0.25F + 0.4F * RANDOM.nextFloat());
            gib.life = 200 + RANDOM.nextInt(60);
            gib.light = light(level, gib, body.type);
        }
        if (client == null || !body.type.bleeds()) return;
        double cx = viewCam.x + middle.x;
        double cy = viewCam.y + middle.y;
        double cz = viewCam.z + middle.z;
        int drops = Math.round((force > 1.0F ? 24 : 14) * (ShaderPackCompat.reducedVisuals() ? 0.5F : 1.0F));
        for (int i = 0; i < drops; i++) {
            Vec3 v = GoreFx.cone(new Vec3(dir.x * 0.3, 0.5, dir.z * 0.3), 1.2F, RANDOM).scale(0.05 + 0.12 * RANDOM.nextDouble() * (1.0 + force));
            GoreFx.spawn(client, cx + RANDOM.nextGaussian() * 0.15, cy + RANDOM.nextGaussian() * 0.3, cz + RANDOM.nextGaussian() * 0.15, v.x, v.y, v.z,
                    Mth.clamp(0.03F * (float) Math.pow(Math.exp(RANDOM.nextGaussian() * 0.9), 0.45), 0.018F, 0.12F), 60, body.type, GoreFx.DROP, RANDOM.nextInt(4));
        }
    }

    private static int light(Level level, Gib gib, BloodType type) {
        if (type.glow) return LightTexture.FULL_BRIGHT;
        return LevelRenderer.getLightColor(level, GoreFx.CURSOR.set(Mth.floor(gib.x), Mth.floor(gib.y), Mth.floor(gib.z)));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (PENDING.isEmpty() && HIDDEN.isEmpty() && BODIES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        if (!PENDING.isEmpty()) {
            long now = level.getGameTime();
            for (ObjectIterator<Int2ObjectMap.Entry<Request>> it = PENDING.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
                Int2ObjectMap.Entry<Request> e = it.next();
                Request request = e.getValue();
                if (now - request.made < 3) continue;
                int id = e.getIntKey();
                it.remove();
                if (level.getEntity(id) instanceof LivingEntity living) fallback(living, request);
            }
        }
        if (!HIDDEN.isEmpty()) {
            for (IntIterator it = HIDDEN.iterator(); it.hasNext(); ) {
                if (level.getEntity(it.nextInt()) == null) it.remove();
            }
        }
        if (BODIES.isEmpty()) return;
        long start = System.nanoTime();
        try {
            tick(level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore gibs failed", e);
            BODIES.clear();
            gibs = 0;
        }
    }

    private static void tick(ClientLevel level) {
        boolean slowed = Splattered.time().slowed();
        boolean reduced = ShaderPackCompat.reducedVisuals();
        for (int b = BODIES.size() - 1; b >= 0; b--) {
            Body body = BODIES.get(b);
            List<Gib> parts = body.parts;
            for (int i = parts.size() - 1; i >= 0; i--) {
                Gib gib = parts.get(i);
                gib.every = slowed ? Mth.clamp(Splattered.time().every(gib.x, gib.y, gib.z), 1, 127) : 1;
                if (Splattered.time().skip(gib.every)) continue;
                if (++gib.age > gib.life) {
                    parts.remove(i);
                    gibs--;
                    continue;
                }
                gib.px = gib.x;
                gib.py = gib.y;
                gib.pz = gib.z;
                gib.prev.set(gib.rot);
                if (gib.rest) {
                    if (gib.life - gib.age < 40) gib.sink += gib.radius * 2.2F / 40.0F;
                    continue;
                }
                float angle = gib.spin.length();
                if (angle > 1.0E-4F) {
                    SPIN.setAngleAxis(angle, gib.spin.x / angle, gib.spin.y / angle, gib.spin.z / angle);
                    gib.rot.premul(SPIN);
                }
                gib.vx *= DRAG;
                gib.vz *= DRAG;
                gib.vy = gib.vy * DRAG - GRAVITY;
                float speed = Mth.sqrt(gib.vx * gib.vx + gib.vy * gib.vy + gib.vz * gib.vz);
                if (body.type.stains() && (gib.age & 1) == 0 && speed > 0.08F && !reduced) {
                    GoreFx.spawn(level, gib.x, gib.y, gib.z, gib.vx * 0.2, gib.vy * 0.2, gib.vz * 0.2, 0.025F + 0.02F * RANDOM.nextFloat(), 40, body.type, GoreFx.DROP, 0);
                }
                float lead = speed > 1.0E-4F ? gib.radius / speed : 0.0F;
                double ox = gib.vx * lead;
                double oy = gib.vy * lead;
                double oz = gib.vz * lead;
                double sx = gib.x + ox;
                double sy = gib.y + oy;
                double sz = gib.z + oz;
                int hit = GoreFx.collide(level, sx, sy, sz, sx + gib.vx, sy + gib.vy, sz + gib.vz);
                if (hit == -2) {
                    parts.remove(i);
                    gibs--;
                    continue;
                }
                if (hit == -3) {
                    gib.vx *= 0.6F;
                    gib.vy *= 0.6F;
                    gib.vz *= 0.6F;
                    gib.spin.mul(0.9F);
                    gib.x += gib.vx;
                    gib.y += gib.vy;
                    gib.z += gib.vz;
                    if ((gib.age & 7) == 0 && body.type.bleeds()) Mist.cloud(level, gib.x, gib.y, gib.z, gib.radius * 3.0F, body.type, RANDOM);
                } else if (hit >= 0) {
                    Direction face = Direction.from3DDataValue(hit);
                    float impact = Math.abs(face.getAxis() == Direction.Axis.X ? gib.vx : face.getAxis() == Direction.Axis.Y ? gib.vy : gib.vz);
                    double t = GoreFx.HIT[0];
                    double hx = sx + gib.vx * t;
                    double hy = sy + gib.vy * t;
                    double hz = sz + gib.vz * t;
                    if (body.type.stains() && speed > 0.06F) {
                        Stains.splat(level, GoreFx.CURSOR.getX(), GoreFx.CURSOR.getY(), GoreFx.CURSOR.getZ(), face, hx, hy, hz,
                                Math.min(0.11F, gib.radius * 0.6F), gib.vx * 0.6F, gib.vy * 0.6F, gib.vz * 0.6F, body.type, RANDOM);
                    }
                    if (impact > 0.15F && body.sounds++ < 6) GoreSounds.SPLAT.play(level, hx, hy, hz, 0.6F, 0.65F + 0.2F * RANDOM.nextFloat());
                    gib.x = hx - ox + face.getStepX() * 0.01;
                    gib.y = hy - oy + face.getStepY() * 0.01;
                    gib.z = hz - oz + face.getStepZ() * 0.01;
                    if (face == Direction.UP && impact < 0.13F) {
                        gib.rest = true;
                        gib.vx = gib.vy = gib.vz = 0.0F;
                        gib.spin.zero();
                        gib.y = hy + gib.radius;
                        if (!gib.pooled && RANDOM.nextFloat() < 0.35F) {
                            gib.pooled = true;
                            Flows.poolAt(level, gib.x, hy, gib.z, (1.5F + gib.radius * 10.0F) * Stains.RES / 16.0F, body.type);
                        }
                    } else {
                        switch (face.getAxis()) {
                            case X -> gib.vx *= -0.25F;
                            case Y -> gib.vy *= -0.25F;
                            case Z -> gib.vz *= -0.25F;
                        }
                        if (face.getAxis() != Direction.Axis.X) gib.vx *= 0.55F;
                        if (face.getAxis() != Direction.Axis.Y) gib.vy *= 0.55F;
                        if (face.getAxis() != Direction.Axis.Z) gib.vz *= 0.55F;
                        gib.spin.mul(0.55F);
                    }
                } else {
                    gib.x += gib.vx;
                    gib.y += gib.vy;
                    gib.z += gib.vz;
                }
                if ((gib.age & 3) == 0) gib.light = light(level, gib, body.type);
            }
            if (parts.isEmpty()) BODIES.remove(b);
        }
    }

    private static boolean place(Gib gib, Matrix4f base, Matrix3f baseNormal, Vec3 cam, float partial) {
        float t = Splattered.time().partial(gib.every, partial);
        double x = Mth.lerp(t, gib.px, gib.x) - cam.x;
        double y = Mth.lerp(t, gib.py, gib.y) - gib.sink - cam.y;
        double z = Mth.lerp(t, gib.pz, gib.z) - cam.z;
        if (x * x + y * y + z * z > 64.0 * 64.0) return false;
        if (!GoreVerts.visible((float) x, (float) y, (float) z, gib.reach)) return false;
        gib.prev.slerp(gib.rot, t, ROT);
        POSE.set(base).translate((float) x, (float) y, (float) z).rotate(ROT).mul(gib.model);
        NORMAL.set(baseNormal).rotate(ROT).mul(gib.normal);
        return true;
    }

    private static void face(Gib gib, int f, int color, float u0, float v0, float span, boolean skin) {
        float[] n = NORMALS[f];
        int packed = GoreVerts.normal(NORMAL, n[0], n[1], n[2]);
        int[] side = SIDES[f];
        int axis = f >> 1;
        int a = axis == 0 ? 1 : 0;
        int c = axis == 2 ? 1 : 2;
        for (int q = 0; q < 4; q++) {
            int corner = side[q];
            float x = (corner & 1) == 0 ? gib.box[0] : gib.box[3];
            float y = (corner & 2) == 0 ? gib.box[1] : gib.box[4];
            float z = (corner & 4) == 0 ? gib.box[2] : gib.box[5];
            float u;
            float v;
            if (skin) {
                u = gib.uv[f * 8 + q * 2];
                v = gib.uv[f * 8 + q * 2 + 1];
            } else {
                u = u0 + Math.min(15.0F, (((corner >> a & 1) == 0 ? gib.box[a] : gib.box[a + 3]) - gib.box[a]) * 16.0F) * span;
                v = v0 + Math.min(15.0F, (((corner >> c & 1) == 0 ? gib.box[c] : gib.box[c + 3]) - gib.box[c]) * 16.0F) * span;
            }
            GoreVerts.put(POSE, x, y, z, color, u, v, gib.light, packed);
        }
    }

    static void draw(MultiBufferSource.BufferSource buffers, PoseStack stack, Vec3 cam, float partial) {
        Matrix4f base = stack.last().pose();
        Matrix3f baseNormal = stack.last().normal();
        for (Body body : BODIES) {
            GoreVerts.begin(buffers.getBuffer(body.skin), false);
            for (Gib gib : body.parts) {
                if (!place(gib, base, baseNormal, cam, partial)) continue;
                for (int f = 0; f < 6; f++) if (gib.outer[f]) face(gib, f, -1, 0.0F, 0.0F, 0.0F, true);
            }
            GoreVerts.end();
            ResourceLocation wound = body.type.stains() ? Wounds.texture(body.texture, body.type, 3, body.player) : null;
            if (wound == null) continue;
            GoreVerts.begin(buffers.getBuffer(RenderType.entityCutoutNoCull(wound)), false);
            for (Gib gib : body.parts) {
                if (!place(gib, base, baseNormal, cam, partial)) continue;
                for (int f = 0; f < 6; f++) if (gib.outer[f]) face(gib, f, -1, 0.0F, 0.0F, 0.0F, true);
            }
            GoreVerts.end();
        }
    }

    static void drawFlesh(Matrix4f base, Matrix3f baseNormal, Vec3 cam, float partial) {
        for (Body body : BODIES) {
            float r = 1.0F;
            float g = 1.0F;
            float b = 1.0F;
            if (body.type != BloodType.RED && body.type != BloodType.BONE) {
                int c = body.type.flesh;
                float top = Math.max(1.0F, Math.max(c >> 16 & 255, Math.max(c >> 8 & 255, c & 255)));
                r = (c >> 16 & 255) / top;
                g = (c >> 8 & 255) / top;
                b = (c & 255) / top;
            }
            int color = GoreVerts.color(r, g, b, 1.0F);
            for (Gib gib : body.parts) {
                if (!place(gib, base, baseNormal, cam, partial)) continue;
                float u0 = GoreAtlas.u(gib.flesh, 0.0F);
                float v0 = GoreAtlas.v(gib.flesh, 0.0F);
                for (int f = 0; f < 6; f++) if (!gib.outer[f]) face(gib, f, color, u0, v0, GoreAtlas.TEXEL * 0.999F, false);
            }
        }
    }

    static void clear() {
        PENDING.clear();
        HIDDEN.clear();
        BODIES.clear();
        gibs = 0;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
