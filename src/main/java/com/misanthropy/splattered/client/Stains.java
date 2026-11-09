package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.BloodType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayDeque;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Stains {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int RES = GoreAtlas.TILE;
    private static final int PIXELS = RES * RES;
    private static final int AGED_PER_TICK = 16;
    private static final int UPLOADS_PER_FRAME = 32;
    private static final double VIEW = 48.0;
    private static final int[] STAGES = {0, 1200, 2400, 3600, 6000, 6300, 6600, 6900, 7200};
    private static final float[] FADE = {1.0F, 1.0F, 1.0F, 1.0F, 0.8F, 0.6F, 0.4F, 0.22F};
    private static final int GONE = STAGES.length - 1;

    private static final Long2ObjectOpenHashMap<Face> FACES = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<Face> ORDER = new ArrayDeque<>();
    private static final ArrayDeque<Face> DIRTY = new ArrayDeque<>();
    private static final IntArrayList FREE = new IntArrayList();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static final float[][] AXES = new float[6][];
    private static final boolean[] FLIP = new boolean[6];
    private static final int[] STAGE = new int[PIXELS];
    private static final int[] DIST = new int[PIXELS];
    private static final byte[] BAND = new byte[PIXELS];
    private static final float[][][] WET = new float[BloodType.ALL.length][][];
    private static final float[][][] DRY = new float[BloodType.ALL.length][][];
    private static final float[][] TINT_WET = new float[BloodType.ALL.length][];
    private static final float[][] TINT_DRY = new float[BloodType.ALL.length][];
    private static final float[] SOLID = {0.38F, 0.7F, 0.9F, 0.95F};

    private static long clock;
    static final int WET_PRINT = 180;
    private static int sweep;
    private static Face[] sweepList = new Face[0];

    static {
        for (int t = GoreAtlas.RESERVED + GoreAtlas.SLOTS - 1; t >= GoreAtlas.RESERVED; t--) FREE.add(t);
        for (Direction dir : Direction.values()) {
            float[] a = axes(dir);
            AXES[dir.get3DDataValue()] = a;
            float cx = a[1] * a[5] - a[2] * a[4];
            float cy = a[2] * a[3] - a[0] * a[5];
            float cz = a[0] * a[4] - a[1] * a[3];
            FLIP[dir.get3DDataValue()] = cx * dir.getStepX() + cy * dir.getStepY() + cz * dir.getStepZ() < 0.0F;
        }
        for (BloodType type : BloodType.ALL) {
            WET[type.ordinal()] = bands(type.wet, 0.68F, 0.4F, 0.24F);
            DRY[type.ordinal()] = bands(type.dried, 0.77F, 0.51F, 0.34F);
            TINT_WET[type.ordinal()] = tint(type.wet, 0.62F, 0.06F);
            TINT_DRY[type.ordinal()] = tint(type.dried, 0.62F, 0.0F);
        }
    }

    private Stains() {
    }

    private static float[][] bands(int c, float mid, float dark, float deep) {
        float r = (c >> 16 & 255) / 255.0F;
        float g = (c >> 8 & 255) / 255.0F;
        float b = (c & 255) / 255.0F;
        return new float[][]{{r, g, b}, {r * mid, g * mid, b * mid}, {r * dark, g * dark, b * dark}, {r * deep, g * deep, b * deep}};
    }

    private static float[] tint(int c, float k, float lift) {
        float r = (c >> 16 & 255) / 255.0F;
        float g = (c >> 8 & 255) / 255.0F;
        float b = (c & 255) / 255.0F;
        float top = Math.max(1.0E-3F, Math.max(r, Math.max(g, b)));
        return new float[]{r / top * k, g / top * k + lift, b / top * k + lift};
    }

    private static float[] axes(Direction dir) {
        return switch (dir.getAxis()) {
            case Y -> new float[]{1, 0, 0, 0, 0, 1};
            case Z -> new float[]{1, 0, 0, 0, -1, 0};
            case X -> new float[]{0, 0, 1, 0, -1, 0};
        };
    }

    static final class Face {
        final long pos;
        final int dir;
        final int plane;
        final long key;
        final byte[] amount = new byte[PIXELS];
        final byte[] type = new byte[PIXELS];
        final int[] painted = new int[PIXELS];
        final boolean[] cover;
        int tile = -1;
        int light;
        long next = Long.MAX_VALUE;
        boolean dirty;
        boolean dead;
        boolean glow;

        Face(long pos, int dir, int plane, long key, boolean[] cover) {
            this.pos = pos;
            this.dir = dir;
            this.plane = plane;
            this.key = key;
            this.cover = cover;
        }
    }

    static boolean isEmpty() {
        return FACES.isEmpty();
    }

    static int size() {
        return FACES.size();
    }

    private static long key(long pos, int dir, int plane) {
        return pos * 31L * 17L + dir * 17L + plane;
    }

    static void splat(ClientLevel level, int bx, int by, int bz, Direction face, double x, double y, double z,
                      float size, float vx, float vy, float vz, BloodType type, RandomSource random) {
        if (!type.stains()) return;
        int dir = face.get3DDataValue();
        float[] a = AXES[dir];
        double lx = x - bx;
        double ly = y - by;
        double lz = z - bz;
        float s = (float) ((lx * a[0] + ly * a[1] + lz * a[2]) + (a[1] < 0 ? 1 : 0)) * RES;
        float t = (float) ((lx * a[3] + ly * a[4] + lz * a[5]) + (a[4] < 0 ? 1 : 0)) * RES;
        double along = face.getAxis().choose(lx, ly, lz);
        int plane = Mth.clamp((int) Math.round(along * 16.0), 0, 16);
        float vs = vx * a[0] + vy * a[1] + vz * a[2];
        float vt = vx * a[3] + vy * a[4] + vz * a[5];
        float speed = Mth.sqrt(vx * vx + vy * vy + vz * vz);
        float tangent = Mth.sqrt(vs * vs + vt * vt);
        float radius = Mth.clamp(size * RES * (0.8F + speed * 2.2F), 0.6F, 7.5F);
        float stretch = 1.0F + Math.min(radius > 2.2F ? 0.9F : 2.2F, tangent / Math.max(0.05F, speed) * 1.8F * Math.min(1.0F, speed * 3.0F));
        float ds = tangent > 1.0E-4F ? vs / tangent : 1.0F;
        float dt = tangent > 1.0E-4F ? vt / tangent : 0.0F;
        int strength = Mth.clamp((int) (70 + size * 900), 60, 200);
        blob(level, bx, by, bz, dir, plane, s, t, radius, stretch, ds, dt, strength, type, random);
        if (radius > 2.0F) {
            int lobes = 1 + random.nextInt(3);
            for (int i = 0; i < lobes; i++) {
                float os = (float) random.nextGaussian() * radius * 0.3F;
                float ot = (float) random.nextGaussian() * radius * 0.3F;
                blob(level, bx, by, bz, dir, plane, s + os, t + ot, radius * (0.55F + 0.3F * random.nextFloat()), 1.0F, ds, dt, strength - 10, type, random);
            }
        }
        boolean glancing = stretch > 1.45F;
        if (radius > 1.8F && speed > 0.1F) fingers(level, bx, by, bz, dir, plane, s, t, radius, glancing, ds, dt, strength, type, random);
        if (stretch > 1.4F && radius > 0.9F) {
            int sats = 1 + random.nextInt(3);
            for (int i = 0; i < sats; i++) {
                float d = radius * stretch * (1.1F + 1.4F * random.nextFloat());
                float side = (random.nextFloat() - 0.5F) * radius;
                blob(level, bx, by, bz, dir, plane, s + ds * d - dt * side, t + dt * d + ds * side,
                        0.6F + 0.5F * random.nextFloat(), 1.6F, ds, dt, strength - 20, type, random);
            }
        }
        if (speed > 0.18F && radius > 1.2F) {
            int specks = 2 + random.nextInt(5);
            float base = (float) Mth.atan2(dt, ds);
            for (int i = 0; i < specks; i++) {
                float angle = glancing ? base + (float) random.nextGaussian() * 0.5F : random.nextFloat() * Mth.TWO_PI;
                float d = radius * (1.6F + 2.4F * random.nextFloat());
                blob(level, bx, by, bz, dir, plane, s + Mth.cos(angle) * d, t + Mth.sin(angle) * d, 0.5F, 1.0F, ds, dt, strength - 30, type, random);
            }
        }
        if (face.getAxis().isHorizontal()) {
            if (size >= 0.06F || random.nextFloat() < 0.2F) Flows.runner(bx, by, bz, dir, plane, s, t, type, random);
        } else if (face == Direction.DOWN && size >= 0.045F) {
            Flows.hang(x, y - 0.03, z, type, random);
        }
    }

    private static void fingers(ClientLevel level, int bx, int by, int bz, int dir, int plane, float s, float t, float radius,
                                boolean glancing, float ds, float dt, int strength, BloodType type, RandomSource random) {
        int n = glancing ? 2 + random.nextInt(3) : 4 + random.nextInt(4);
        float base = (float) Mth.atan2(dt, ds);
        for (int i = 0; i < n; i++) {
            float angle = glancing ? base + (random.nextFloat() - 0.5F) * 0.9F : i * Mth.TWO_PI / n + (random.nextFloat() - 0.5F) * 0.6F;
            float length = radius * (0.6F + 0.9F * random.nextFloat()) * (glancing ? 1.4F : 1.0F);
            float bend = (float) random.nextGaussian() * 0.15F;
            float start = radius * 0.7F;
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            for (float k = 0.0F; k <= 1.0F; k += 0.5F / length) {
                float a = angle + bend * k;
                float r = start + k * length;
                float w = Math.max(0.5F, radius * 0.22F * (1.0F - k * 0.75F));
                blob(level, bx, by, bz, dir, plane, s + Mth.cos(a) * r, t + Mth.sin(a) * r, w, 1.0F, cos, sin, strength - 30, type, random);
            }
            float a = angle + bend;
            float r = start + length + 0.6F;
            blob(level, bx, by, bz, dir, plane, s + Mth.cos(a) * r, t + Mth.sin(a) * r, Math.max(0.7F, radius * 0.2F), 1.0F, cos, sin, strength - 20, type, random);
        }
    }

    static boolean dot(ClientLevel level, int bx, int by, int bz, int dir, int plane, int ps, int pt, int add, BloodType type) {
        int cs = Math.floorDiv(ps, RES);
        int ct = Math.floorDiv(pt, RES);
        Face face = face(level, bx, by, bz, dir, plane, cs, ct);
        if (face == null) return false;
        int ls = ps - cs * RES;
        int lt = pt - ct * RES;
        if (!face.cover[lt * RES + ls]) return false;
        paint(face, ls, lt, add, type);
        return true;
    }

    static Vec3 point(int bx, int by, int bz, int dir, int plane, float s, float t, float out) {
        float[] a = AXES[dir];
        Direction d = Direction.from3DDataValue(dir);
        double x = bx + (a[0] * s + a[3] * t) / RES;
        double y = by + (a[1] * s + a[4] * t) / RES + (a[4] < 0 ? 1 : 0);
        double z = bz + (a[2] * s + a[5] * t) / RES;
        double p = plane / 16.0;
        switch (d.getAxis()) {
            case X -> x += p;
            case Y -> y += p;
            case Z -> z += p;
        }
        return new Vec3(x + d.getStepX() * out, y + d.getStepY() * out, z + d.getStepZ() * out);
    }

    static void pool(ClientLevel level, int bx, int by, int bz, int plane, float s, float t, float radius, int strength, BloodType type, RandomSource random) {
        blob(level, bx, by, bz, Direction.UP.get3DDataValue(), plane, s, t, radius, 1.0F, 1.0F, 0.0F, strength, type, random);
    }

    private static void blob(ClientLevel level, int bx, int by, int bz, int dir, int plane, float s, float t,
                             float radius, float stretch, float ds, float dt, int strength, BloodType type, RandomSource random) {
        float p1 = random.nextFloat() * Mth.TWO_PI;
        float p2 = random.nextFloat() * Mth.TWO_PI;
        float rough = Math.min(1.0F, radius / 3.0F);
        float w1 = (0.06F + 0.08F * random.nextFloat()) * rough;
        float w2 = (0.03F + 0.04F * random.nextFloat()) * rough;
        float major = radius * stretch;
        int reach = Mth.ceil(major + 1.0F);
        int s0 = Mth.floor(s) - reach;
        int t0 = Mth.floor(t) - reach;
        Face cached = null;
        int cachedS = Integer.MIN_VALUE;
        int cachedT = Integer.MIN_VALUE;
        for (int pt = t0; pt <= t0 + reach * 2; pt++) {
            for (int ps = s0; ps <= s0 + reach * 2; ps++) {
                float ex = ps + 0.5F - s;
                float ey = pt + 0.5F - t;
                float u = (ex * ds + ey * dt) / major;
                float v = (-ex * dt + ey * ds) / radius;
                float r2 = u * u + v * v;
                if (r2 > 1.6F) continue;
                float angle = (float) Mth.atan2(v, u);
                float edge = 1.0F + w1 * Mth.sin(angle * 3.0F + p1) + w2 * Mth.sin(angle * 5.0F + p2);
                if (r2 > edge * edge) continue;
                int add = (int) (strength * (1.15F - 0.5F * r2 / (edge * edge)));
                int cs = Math.floorDiv(ps, RES);
                int ct = Math.floorDiv(pt, RES);
                if (cached == null || cs != cachedS || ct != cachedT) {
                    cachedS = cs;
                    cachedT = ct;
                    cached = face(level, bx, by, bz, dir, plane, cs, ct);
                }
                if (cached == null) continue;
                paint(cached, ps - cs * RES, pt - ct * RES, add, type);
            }
        }
    }

    private static Face face(ClientLevel level, int bx, int by, int bz, int dir, int plane, int cs, int ct) {
        float[] a = AXES[dir];
        int x = bx + (int) (a[0] * cs + a[3] * ct);
        int y = by + (int) (a[1] * cs + a[4] * ct);
        int z = bz + (int) (a[2] * cs + a[5] * ct);
        long pos = BlockPos.asLong(x, y, z);
        long key = key(pos, dir, plane);
        Face found = FACES.get(key);
        if (found != null && found.pos == pos && found.dir == dir && found.plane == plane) return found;
        boolean[] cover = cover(level, x, y, z, Direction.from3DDataValue(dir), plane);
        if (cover == null) return null;
        if (FREE.isEmpty()) kill(ORDER.peekFirst());
        if (found != null) kill(found);
        Face made = new Face(pos, dir, plane, key, cover);
        made.tile = FREE.popInt();
        made.light = sample(level, made);
        FACES.put(key, made);
        ORDER.addLast(made);
        return made;
    }

    private static boolean[] cover(ClientLevel level, int x, int y, int z, Direction dir, int plane) {
        CURSOR.set(x, y, z);
        if (!level.isLoaded(CURSOR)) return null;
        BlockState state = level.getBlockState(CURSOR);
        if (state.isAir() || !state.getFluidState().isEmpty()) return null;
        VoxelShape shape = state.getCollisionShape(level, CURSOR);
        if (shape.isEmpty()) return null;
        boolean boundary = plane == (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0);
        if (boundary) {
            CURSOR.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            BlockState next = level.getBlockState(CURSOR);
            if (!next.isAir() && Block.isShapeFullBlock(next.getCollisionShape(level, CURSOR))) return null;
        }
        float[] a = AXES[dir.get3DDataValue()];
        double p = plane / 16.0;
        boolean positive = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        boolean[] cover = new boolean[PIXELS];
        boolean[] any = new boolean[1];
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            double faceAt = positive ? dir.getAxis().choose(x1, y1, z1) : dir.getAxis().choose(x0, y0, z0);
            if (Math.abs(faceAt - p) > 0.01) return;
            double sa = a[0] * x0 + a[1] * y0 + a[2] * z0;
            double sb = a[0] * x1 + a[1] * y1 + a[2] * z1;
            double ta = a[3] * x0 + a[4] * y0 + a[5] * z0 + (a[4] < 0 ? 1 : 0);
            double tb = a[3] * x1 + a[4] * y1 + a[5] * z1 + (a[4] < 0 ? 1 : 0);
            double smin = Math.min(sa, sb) * RES;
            double smax = Math.max(sa, sb) * RES;
            double tmin = Math.min(ta, tb) * RES;
            double tmax = Math.max(ta, tb) * RES;
            for (int pt = 0; pt < RES; pt++) {
                if (pt + 0.5 < tmin || pt + 0.5 > tmax) continue;
                for (int ps = 0; ps < RES; ps++) {
                    if (ps + 0.5 < smin || ps + 0.5 > smax) continue;
                    cover[pt * RES + ps] = true;
                    any[0] = true;
                }
            }
        });
        return any[0] ? cover : null;
    }

    private static void paint(Face face, int ps, int pt, int add, BloodType type) {
        int i = pt * RES + ps;
        if (!face.cover[i]) return;
        int now = face.amount[i] & 255;
        face.amount[i] = (byte) Math.min(255, now + add);
        if (now < add) face.type[i] = (byte) type.ordinal();
        face.painted[i] = (int) clock;
        face.next = clock;
        mark(face);
    }

    private static void mark(Face face) {
        if (!face.dirty) {
            face.dirty = true;
            DIRTY.addLast(face);
        }
    }

    private static int sample(ClientLevel level, Face face) {
        Direction dir = Direction.from3DDataValue(face.dir);
        CURSOR.set(BlockPos.getX(face.pos) + dir.getStepX(), BlockPos.getY(face.pos) + dir.getStepY(), BlockPos.getZ(face.pos) + dir.getStepZ());
        return LevelRenderer.getLightColor(level, CURSOR);
    }

    private static void kill(Face face) {
        if (face == null || face.dead) return;
        face.dead = true;
        FACES.remove(face.key);
        ORDER.remove(face);
        if (face.tile >= 0) {
            NativeImage image = GoreAtlas.image();
            image.fillRect(GoreAtlas.tileX(face.tile), GoreAtlas.tileY(face.tile), GoreAtlas.TILE, GoreAtlas.TILE, 0);
            FREE.add(face.tile);
            face.tile = -1;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FACES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        clock++;
        long start = System.nanoTime();
        try {
            age(level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore stains failed", e);
            clear();
        }
    }

    private static void age(ClientLevel level) {
        if (sweep >= sweepList.length) {
            sweepList = ORDER.toArray(sweepList.length >= ORDER.size() ? sweepList : new Face[ORDER.size()]);
            if (sweepList.length > ORDER.size()) sweepList[ORDER.size()] = null;
            sweep = 0;
        }
        for (int n = 0; n < AGED_PER_TICK && sweep < sweepList.length; n++) {
            Face face = sweepList[sweep];
            sweepList[sweep++] = null;
            if (face == null) {
                sweep = sweepList.length;
                break;
            }
            if (face.dead) continue;
            visit(level, face);
        }
    }

    private static void visit(ClientLevel level, Face face) {
        Direction dir = Direction.from3DDataValue(face.dir);
        CURSOR.set(BlockPos.getX(face.pos), BlockPos.getY(face.pos), BlockPos.getZ(face.pos));
        if (!level.isLoaded(CURSOR)) {
            kill(face);
            return;
        }
        BlockState state = level.getBlockState(CURSOR);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            kill(face);
            return;
        }
        CURSOR.move(dir);
        if (!level.getFluidState(CURSOR).isEmpty()) {
            kill(face);
            return;
        }
        int wash = dir != Direction.DOWN && level.isRainingAt(dir == Direction.UP ? CURSOR : CURSOR.above()) ? (dir == Direction.UP ? 34 : 14) : 0;
        if (wash > 0) {
            boolean left = false;
            for (int i = 0; i < PIXELS; i++) {
                int amt = face.amount[i] & 255;
                if (amt == 0) continue;
                amt = Math.max(0, amt - wash);
                face.amount[i] = (byte) amt;
                left |= amt > 0;
            }
            if (!left) {
                kill(face);
                return;
            }
            face.next = clock;
        }
        face.light = face.glow ? LightTexture.FULL_BRIGHT : sample(level, face);
        if (clock >= face.next) mark(face);
    }

    static void upload(ClientLevel level) {
        if (DIRTY.isEmpty()) return;
        NativeImage image = GoreAtlas.image();
        for (int n = 0; n < UPLOADS_PER_FRAME && !DIRTY.isEmpty(); n++) {
            Face face = DIRTY.pollFirst();
            face.dirty = false;
            if (face.dead) continue;
            if (!shade(face, image)) {
                kill(face);
                continue;
            }
            GoreAtlas.upload(face.tile);
        }
    }

    private static boolean shade(Face face, NativeImage image) {
        long next = Long.MAX_VALUE;
        boolean alive = false;
        for (int i = 0; i < PIXELS; i++) {
            int amt = face.amount[i] & 255;
            if (amt == 0) {
                STAGE[i] = -1;
                continue;
            }
            long age = clock - face.painted[i];
            int stage = 0;
            while (stage < GONE && age >= STAGES[stage + 1]) stage++;
            if (stage >= GONE) {
                face.amount[i] = 0;
                STAGE[i] = -1;
                continue;
            }
            STAGE[i] = stage;
            alive = true;
            next = Math.min(next, face.painted[i] + (long) STAGES[stage + 1]);
        }
        face.next = next;
        if (!alive) return false;
        int ox = GoreAtlas.tileX(face.tile);
        int oy = GoreAtlas.tileY(face.tile);
        NativeImage soak = GoreAtlas.soakImage();
        int seedX = BlockPos.getX(face.pos) * RES;
        int seedY = BlockPos.getY(face.pos) * 7 + BlockPos.getZ(face.pos) * RES;
        distances();
        for (int i = 0; i < PIXELS; i++) {
            int d = DIST[i];
            float q = d > 1 ? d + (GoreAtlas.hash(seedX + i % RES + 7, seedY + i / RES + 3, face.dir) - 0.5F) * 1.8F : d;
            BAND[i] = (byte) (q > 5 ? 3 : q > 3 ? 2 : q > 1 ? 1 : 0);
            if (d > 0 && (face.amount[i] & 255) < 60) BAND[i] = 0;
        }
        boolean glow = false;
        for (int pt = 0; pt < RES; pt++) {
            for (int ps = 0; ps < RES; ps++) {
                int i = pt * RES + ps;
                int stage = STAGE[i];
                if (stage < 0) {
                    soak.setPixelRGBA(ox + ps, oy + pt, 0);
                    int j = i - RES - 1;
                    if (ps > 0 && pt > 0 && STAGE[j] >= 0 && STAGE[j] < 3 && DIST[j] >= 2) {
                        image.setPixelRGBA(ox + ps, oy + pt, abgr(0.04F, 0.016F, 0.016F, 0.12F * (1.0F - STAGE[j] / 3.0F)));
                    } else {
                        image.setPixelRGBA(ox + ps, oy + pt, 0);
                    }
                    continue;
                }
                BloodType type = BloodType.ALL[face.type[i]];
                glow |= type.glow && stage < 2;
                float dry = Math.min(stage, 3) / 3.0F;
                int band = BAND[i];
                int tone = Math.max(band, 1);
                float[] wet = WET[type.ordinal()][tone];
                float[] dried = DRY[type.ordinal()][tone];
                float value = 0.9F + 0.2F * GoreAtlas.hash(seedX + ps, seedY + pt, face.dir + 11);
                float hue = (GoreAtlas.hash(seedX + ps + 11, seedY + pt + 5, face.dir + 17) - 0.5F) * 0.35F;
                float r = Mth.lerp(dry, wet[0], dried[0]) * value;
                float g = Mth.lerp(dry, wet[1], dried[1]) * value * (1.0F + 1.1F * hue);
                float b = Mth.lerp(dry, wet[2], dried[2]) * value * (1.0F + 0.5F * hue);
                float k = 1.0F;
                if (dry > 0.5F && DIST[i] == 1) k *= 0.85F;
                if (empty(ps, pt + 1) || empty(ps + 1, pt)) k *= 0.86F;
                if (band >= 2 && (band(ps, pt + 1) < 2 || band(ps + 1, pt) < 2)) k *= 0.9F;
                float clot = GoreAtlas.hash(seedX + ps * 3 + 1, seedY + pt * 5 + 2, face.dir + 29);
                if (band >= 2 && clot > 0.9F || band == 1 && clot > 0.95F) k *= 0.55F;
                float alpha = type.alpha * SOLID[band] * (1.0F - 0.45F * dry) * FADE[stage];
                image.setPixelRGBA(ox + ps, oy + pt, abgr(r * k, g * k, b * k, alpha));
                float[] tw = TINT_WET[type.ordinal()];
                float[] td = TINT_DRY[type.ordinal()];
                float darken = 1.0F - 0.12F * band;
                soak.setPixelRGBA(ox + ps, oy + pt, abgr(Mth.lerp(dry, tw[0], td[0]) * darken, Mth.lerp(dry, tw[1], td[1]) * darken,
                        Mth.lerp(dry, tw[2], td[2]) * darken, 0.92F * type.alpha * FADE[stage]));
            }
        }
        face.glow = glow;
        if (glow) face.light = LightTexture.FULL_BRIGHT;
        return true;
    }

    private static int band(int ps, int pt) {
        if (ps < 0 || ps >= RES || pt < 0 || pt >= RES) return 3;
        return BAND[pt * RES + ps];
    }

    private static boolean empty(int ps, int pt) {
        if (ps < 0 || ps >= RES || pt < 0 || pt >= RES) return false;
        return STAGE[pt * RES + ps] < 0;
    }

    private static void distances() {
        int far = 99;
        for (int pt = 0; pt < RES; pt++) {
            for (int ps = 0; ps < RES; ps++) {
                int i = pt * RES + ps;
                if (STAGE[i] < 0) {
                    DIST[i] = 0;
                    continue;
                }
                int up = pt > 0 ? DIST[i - RES] : far;
                int left = ps > 0 ? DIST[i - 1] : far;
                DIST[i] = Math.min(far, Math.min(up, left) + 1);
            }
        }
        for (int pt = RES - 1; pt >= 0; pt--) {
            for (int ps = RES - 1; ps >= 0; ps--) {
                int i = pt * RES + ps;
                if (DIST[i] == 0) continue;
                int down = pt < RES - 1 ? DIST[i + RES] : far;
                int right = ps < RES - 1 ? DIST[i + 1] : far;
                DIST[i] = Math.min(DIST[i], Math.min(down, right) + 1);
            }
        }
    }

    private static int abgr(float r, float g, float b, float a) {
        int ri = Mth.clamp((int) (r * 255.0F), 0, 255);
        int gi = Mth.clamp((int) (g * 255.0F), 0, 255);
        int bi = Mth.clamp((int) (b * 255.0F), 0, 255);
        int ai = Mth.clamp((int) (a * 255.0F), 0, 255);
        return ai << 24 | bi << 16 | gi << 8 | ri;
    }

    static void draw(Matrix4f matrix, Matrix3f normal, Vec3 cam, boolean soak) {
        for (Face face : ORDER) {
            if (face.tile < 0) continue;
            int x = BlockPos.getX(face.pos);
            int y = BlockPos.getY(face.pos);
            int z = BlockPos.getZ(face.pos);
            double rx = x + 0.5 - cam.x;
            double ry = y + 0.5 - cam.y;
            double rz = z + 0.5 - cam.z;
            double d2 = rx * rx + ry * ry + rz * rz;
            if (d2 > VIEW * VIEW) continue;
            Direction dir = Direction.from3DDataValue(face.dir);
            double p = face.plane / 16.0;
            double facing = dir.getAxis().choose(cam.x - x, cam.y - y, cam.z - z) - p;
            if (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? facing <= 0 : facing >= 0) continue;
            if (!GoreVerts.visible((float) rx, (float) ry, (float) rz, 0.9F)) continue;
            float lift = (soak ? 0.0012F : 0.0018F) + (float) Math.sqrt(d2) * (soak ? 0.00005F : 0.00008F);
            float[] a = AXES[face.dir];
            float bx = (float) (x - cam.x) + dir.getStepX() * lift;
            float by = (float) (y - cam.y) + dir.getStepY() * lift;
            float bz = (float) (z - cam.z) + dir.getStepZ() * lift;
            switch (dir.getAxis()) {
                case X -> bx += (float) p;
                case Y -> by += (float) p;
                case Z -> bz += (float) p;
            }
            if (a[4] < 0) by += 1.0F;
            float u0 = GoreAtlas.u(face.tile, 0.0F);
            float v0 = GoreAtlas.v(face.tile, 0.0F);
            float u1 = GoreAtlas.u(face.tile, RES);
            float v1 = GoreAtlas.v(face.tile, RES);
            int packed = soak ? 0 : GoreVerts.normal(normal, dir.getStepX(), dir.getStepY(), dir.getStepZ());
            int light = face.light;
            boolean flip = FLIP[face.dir];
            corner(matrix, a, bx, by, bz, 0, 0, u0, v0, light, packed, soak);
            corner(matrix, a, bx, by, bz, flip ? 0 : 1, flip ? 1 : 0, flip ? u0 : u1, flip ? v1 : v0, light, packed, soak);
            corner(matrix, a, bx, by, bz, 1, 1, u1, v1, light, packed, soak);
            corner(matrix, a, bx, by, bz, flip ? 1 : 0, flip ? 0 : 1, flip ? u1 : u0, flip ? v0 : v1, light, packed, soak);
        }
    }

    private static void corner(Matrix4f matrix, float[] a, float bx, float by, float bz, int cs, int ct, float u, float v, int light, int packed, boolean soak) {
        float x = bx + a[0] * cs + a[3] * ct;
        float y = by + a[1] * cs + a[4] * ct;
        float z = bz + a[2] * cs + a[5] * ct;
        if (soak) {
            GoreVerts.putSoak(matrix, x, y, z, u, v);
        } else {
            GoreVerts.put(matrix, x, y, z, -1, u, v, light, packed);
        }
    }

    static int wetAt(ClientLevel level, double x, double y, double z) {
        if (FACES.isEmpty()) return -1;
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int k = 0; k < 2; k++) {
            int by = Mth.floor(y - 0.02) - k;
            CURSOR.set(bx, by, bz);
            BlockState state = level.getBlockState(CURSOR);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, CURSOR);
            if (shape.isEmpty()) continue;
            int plane = Mth.clamp((int) Math.round(shape.max(Direction.Axis.Y) * 16.0), 0, 16);
            Face face = FACES.get(key(BlockPos.asLong(bx, by, bz), Direction.UP.get3DDataValue(), plane));
            if (face == null || face.dead) continue;
            int ps = Mth.clamp((int) ((x - bx) * RES), 0, RES - 1);
            int pt = Mth.clamp((int) ((z - bz) * RES), 0, RES - 1);
            int best = -1;
            for (int dt = -1; dt <= 1; dt++) {
                for (int ds = -1; ds <= 1; ds++) {
                    int s2 = ps + ds;
                    int t2 = pt + dt;
                    if (s2 < 0 || t2 < 0 || s2 >= RES || t2 >= RES) continue;
                    int i = t2 * RES + s2;
                    int amt = face.amount[i] & 255;
                    if (amt > WET_PRINT && clock - face.painted[i] < STAGES[2] && (best < 0 || amt > (best & 255))) best = face.type[i] << 8 | amt;
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    static void print(ClientLevel level, double x, double y, double z, float yaw, boolean left, BloodType type, int strength) {
        if (!type.stains()) return;
        int bx = Mth.floor(x);
        int by = Mth.floor(y - 0.02);
        int bz = Mth.floor(z);
        CURSOR.set(bx, by, bz);
        BlockState state = level.getBlockState(CURSOR);
        if (state.isAir()) return;
        VoxelShape shape = state.getCollisionShape(level, CURSOR);
        if (shape.isEmpty()) return;
        int plane = Mth.clamp((int) Math.round(shape.max(Direction.Axis.Y) * 16.0), 0, 16);
        float fx = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        float fz = Mth.cos(yaw * Mth.DEG_TO_RAD);
        float side = (left ? -1.0F : 1.0F) * 0.125F;
        double cx = x - fz * side;
        double cz = z + fx * side;
        float s = (float) (cx - bx) * RES;
        float t = (float) (cz - bz) * RES;
        int dir = Direction.UP.get3DDataValue();
        blob(level, bx, by, bz, dir, plane, s + fx * 1.6F, t + fz * 1.6F, 2.0F, 1.5F, fx, fz, strength, type, RandomSource.create(BlockPos.asLong(bx, by, bz) ^ (long) (s * 31 + t)));
        blob(level, bx, by, bz, dir, plane, s - fx * 2.6F, t - fz * 2.6F, 1.7F, 1.0F, fx, fz, strength - 10, type, RandomSource.create(BlockPos.asLong(bx, by, bz) ^ (long) (t * 17 + s)));
    }

    static void clear() {
        for (Face face : ORDER.toArray(new Face[0])) kill(face);
        FACES.clear();
        ORDER.clear();
        DIRTY.clear();
        sweep = sweepList.length;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!FACES.isEmpty()) clear();
    }
}
