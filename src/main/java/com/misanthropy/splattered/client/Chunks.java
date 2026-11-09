package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.client.ShaderPackCompat;
import com.misanthropy.splattered.BloodType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Chunks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAP = 220;
    private static final float GRAVITY = 0.05F;
    private static final float DRAG = 0.98F;
    private static final int[][] SIDES = {{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
    private static final float[][] NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final float[][] UVS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private static final double[] X = new double[CAP];
    private static final double[] Y = new double[CAP];
    private static final double[] Z = new double[CAP];
    private static final double[] PX = new double[CAP];
    private static final double[] PY = new double[CAP];
    private static final double[] PZ = new double[CAP];
    private static final float[] VX = new float[CAP];
    private static final float[] VY = new float[CAP];
    private static final float[] VZ = new float[CAP];
    private static final float[] SIZE = new float[CAP];
    private static final float[] EX = new float[CAP];
    private static final float[] EY = new float[CAP];
    private static final float[] EZ = new float[CAP];
    private static final float[] SINK = new float[CAP];
    private static final float[] ROT = new float[CAP];
    private static final float[] PROT = new float[CAP];
    private static final float[] SPIN = new float[CAP];
    private static final float[] AXIS_X = new float[CAP];
    private static final float[] AXIS_Y = new float[CAP];
    private static final float[] AXIS_Z = new float[CAP];
    private static final short[] AGE = new short[CAP];
    private static final short[] LIFE = new short[CAP];
    private static final byte[] TYPE = new byte[CAP];
    private static final byte[] EVERY = new byte[CAP];
    private static final boolean[] REST = new boolean[CAP];
    private static final int[] COLOR = new int[CAP];
    private static final int[] LIGHT = new int[CAP];
    private static final float[] UOFF = new float[CAP];
    private static final byte[] KIND = new byte[CAP];
    private static final byte[] SHAPE = new byte[CAP];
    private static final int[] SEED = new int[CAP];
    private static final float[] VOFF = new float[CAP];
    private static final Quaternionf SPIN_Q = new Quaternionf();
    private static final Vector3f AX = new Vector3f();
    private static final Vector3f AY = new Vector3f();
    private static final Vector3f AZ = new Vector3f();
    private static final float[] CX = new float[8];
    private static final float[] CY = new float[8];
    private static final float[] CZ = new float[8];
    private static final int[][][] SHAPES = {
            {{0, 0, 0}},
            {{0, 0, 0}, {1, 0, 0}},
            {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {2, 0, 1}},
            {{0, 0, 0}, {0, 0, 1}, {1, 0, 1}, {2, 0, 1}, {2, 0, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 0, 1}},
            {{0, 0, 0}, {1, 0, 0}, {2, 0, 0}},
            {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 1, 1}}
    };
    private static final float[][] CENTER = new float[SHAPES.length][];

    private static int count;
    private static int cursor;

    static {
        for (int s = 0; s < SHAPES.length; s++) {
            float[] c = new float[3];
            for (int[] cell : SHAPES[s]) for (int a = 0; a < 3; a++) c[a] += cell[a] / (float) SHAPES[s].length;
            CENTER[s] = c;
        }
    }

    private Chunks() {
    }

    static boolean isEmpty() {
        return count == 0;
    }

    static int size() {
        return count;
    }

    static void burst(ClientLevel level, Vec3 at, Vec3 dir, int n, float power, float scale, BloodType type, RandomSource random) {
        if (ShaderPackCompat.reducedVisuals()) n = (n + 1) / 2;
        for (int k = 0; k < n; k++) {
            Vec3 v = GoreFx.cone(dir.scale(0.45).add(0.0, 0.8, 0.0), 0.9F, random).scale((0.16 + 0.26 * random.nextDouble()) * (0.7 + 0.3 * Math.min(power, 2.5F)));
            float roll = random.nextFloat();
            boolean bones = type == BloodType.RED || type == BloodType.ROTTEN;
            int kind = type == BloodType.BONE ? GoreAtlas.BONE : roll < 0.5F ? GoreAtlas.MUSCLE : roll < 0.7F ? GoreAtlas.FAT : roll < 0.88F || !bones ? GoreAtlas.CLOT : GoreAtlas.BONE;
            int shape = kind == GoreAtlas.BONE ? (random.nextFloat() < 0.6F ? 6 : 1) : (int) (Math.pow(random.nextFloat(), 1.6) * SHAPES.length);
            float cell = (0.06F + 0.1F * (float) Math.pow(random.nextFloat(), 2.2)) * scale * (kind == GoreAtlas.BONE ? 0.6F : 1.0F);
            spawn(level, at, v, cell, kind, shape, color(type), type, random);
        }
    }

    static int color(BloodType type) {
        if (type == BloodType.RED || type == BloodType.BONE) return 0xFFFFFF;
        int c = type.flesh;
        float top = Math.max(1.0F, Math.max(c >> 16 & 255, Math.max(c >> 8 & 255, c & 255)));
        return (int) ((c >> 16 & 255) / top * 255.0F) << 16 | (int) ((c >> 8 & 255) / top * 255.0F) << 8 | (int) ((c & 255) / top * 255.0F);
    }

    private static void spawn(ClientLevel level, Vec3 at, Vec3 v, float size, int kind, int shape, int color, BloodType type, RandomSource random) {
        int i;
        if (count < CAP) {
            i = count++;
        } else {
            if (cursor >= count) cursor = 0;
            i = cursor++;
        }
        X[i] = PX[i] = at.x;
        Y[i] = PY[i] = at.y;
        Z[i] = PZ[i] = at.z;
        VX[i] = (float) v.x;
        VY[i] = (float) v.y;
        VZ[i] = (float) v.z;
        SIZE[i] = size;
        EX[i] = 0.6F + 0.7F * random.nextFloat();
        EY[i] = 0.5F + 0.6F * random.nextFloat();
        EZ[i] = 0.6F + 0.7F * random.nextFloat();
        SINK[i] = 0.0F;
        ROT[i] = PROT[i] = random.nextFloat() * Mth.TWO_PI;
        SPIN[i] = (0.25F + 0.35F * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
        Vec3 axis = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
        AXIS_X[i] = (float) axis.x;
        AXIS_Y[i] = (float) axis.y;
        AXIS_Z[i] = (float) axis.z;
        AGE[i] = 0;
        LIFE[i] = (short) (300 + random.nextInt(120));
        TYPE[i] = (byte) type.ordinal();
        EVERY[i] = 1;
        REST[i] = false;
        COLOR[i] = color;
        UOFF[i] = random.nextInt(GoreAtlas.TILE - 6);
        VOFF[i] = random.nextInt(GoreAtlas.TILE - 6);
        KIND[i] = (byte) kind;
        SHAPE[i] = (byte) shape;
        SEED[i] = random.nextInt();
        LIGHT[i] = light(level, type, at.x, at.y, at.z);
    }

    private static int light(ClientLevel level, BloodType type, double x, double y, double z) {
        if (type.glow) return LightTexture.FULL_BRIGHT;
        return LevelRenderer.getLightColor(level, GoreFx.CURSOR.set(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
    }

    private static void remove(int i) {
        int last = --count;
        if (i == last) return;
        X[i] = X[last];
        Y[i] = Y[last];
        Z[i] = Z[last];
        PX[i] = PX[last];
        PY[i] = PY[last];
        PZ[i] = PZ[last];
        VX[i] = VX[last];
        VY[i] = VY[last];
        VZ[i] = VZ[last];
        SIZE[i] = SIZE[last];
        EX[i] = EX[last];
        EY[i] = EY[last];
        EZ[i] = EZ[last];
        SINK[i] = SINK[last];
        ROT[i] = ROT[last];
        PROT[i] = PROT[last];
        SPIN[i] = SPIN[last];
        AXIS_X[i] = AXIS_X[last];
        AXIS_Y[i] = AXIS_Y[last];
        AXIS_Z[i] = AXIS_Z[last];
        AGE[i] = AGE[last];
        LIFE[i] = LIFE[last];
        TYPE[i] = TYPE[last];
        EVERY[i] = EVERY[last];
        REST[i] = REST[last];
        COLOR[i] = COLOR[last];
        LIGHT[i] = LIGHT[last];
        UOFF[i] = UOFF[last];
        VOFF[i] = VOFF[last];
        KIND[i] = KIND[last];
        SHAPE[i] = SHAPE[last];
        SEED[i] = SEED[last];
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || count == 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            count = 0;
            return;
        }
        if (mc.isPaused()) return;
        long start = System.nanoTime();
        try {
            tick(level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore chunks failed", e);
            count = 0;
        }
    }

    private static void tick(ClientLevel level) {
        boolean slowed = Splattered.time().slowed();
        int i = 0;
        while (i < count) {
            EVERY[i] = slowed ? (byte) Mth.clamp(Splattered.time().every(X[i], Y[i], Z[i]), 1, 127) : 1;
            if (Splattered.time().skip(EVERY[i])) {
                i++;
                continue;
            }
            if (++AGE[i] > LIFE[i]) {
                remove(i);
                continue;
            }
            PX[i] = X[i];
            PY[i] = Y[i];
            PZ[i] = Z[i];
            PROT[i] = ROT[i];
            if (REST[i]) {
                if (LIFE[i] - AGE[i] < 40) SINK[i] += SIZE[i] / 40.0F;
                i++;
                continue;
            }
            float moving = VX[i] * VX[i] + VY[i] * VY[i] + VZ[i] * VZ[i];
            BloodType blood = BloodType.ALL[TYPE[i]];
            if (moving > 0.012F && (AGE[i] & 1) == 0 && blood.stains() && !ShaderPackCompat.reducedVisuals()) {
                GoreFx.spawn(level, X[i], Y[i], Z[i], VX[i] * 0.3, VY[i] * 0.3, VZ[i] * 0.3, 0.025F + 0.02F * GoreFx.random().nextFloat(), 40, blood, GoreFx.DROP, 0);
            }
            ROT[i] += SPIN[i];
            VX[i] *= DRAG;
            VZ[i] *= DRAG;
            VY[i] = VY[i] * DRAG - GRAVITY;
            double nx = X[i] + VX[i];
            double nz = Z[i] + VZ[i];
            double edge = Y[i] + (VY[i] > 0.0F ? SIZE[i] : -SIZE[i]);
            double ny = edge + VY[i];
            int hit = GoreFx.collide(level, X[i], edge, Z[i], nx, ny, nz);
            if (hit == -2) {
                remove(i);
                continue;
            }
            if (hit == -3) {
                VX[i] *= 0.6F;
                VY[i] *= 0.6F;
                VZ[i] *= 0.6F;
                SPIN[i] *= 0.9F;
                X[i] += VX[i];
                Y[i] += VY[i];
                Z[i] += VZ[i];
                if ((AGE[i] & 7) == 0) Mist.cloud(level, X[i], Y[i], Z[i], SIZE[i] * 2.5F, blood, GoreFx.random());
            } else if (hit >= 0) {
                Direction face = Direction.from3DDataValue(hit);
                double t = GoreFx.HIT[0];
                double hx = X[i] + (nx - X[i]) * t;
                double hy = edge + VY[i] * t;
                double hz = Z[i] + (nz - Z[i]) * t;
                float speed = Mth.sqrt(VX[i] * VX[i] + VY[i] * VY[i] + VZ[i] * VZ[i]);
                float impact = Math.abs(face.getAxis() == Direction.Axis.X ? VX[i] : face.getAxis() == Direction.Axis.Y ? VY[i] : VZ[i]);
                BloodType type = BloodType.ALL[TYPE[i]];
                if (impact > 0.15F) GoreSounds.SPLAT.play(level, hx, hy, hz, 0.5F, 0.7F + 0.25F * GoreFx.random().nextFloat());
                if (speed > 0.06F) Stains.splat(level, GoreFx.CURSOR.getX(), GoreFx.CURSOR.getY(), GoreFx.CURSOR.getZ(), face, hx, hy, hz, SIZE[i] * 0.9F, VX[i] * 0.6F, VY[i] * 0.6F, VZ[i] * 0.6F, type, GoreFx.random());
                float half = SIZE[i];
                double above = Y[i] - edge;
                X[i] = hx + face.getStepX() * half;
                Y[i] = hy + above;
                Z[i] = hz + face.getStepZ() * half;
                if (face == Direction.UP && impact < 0.14F) {
                    REST[i] = true;
                    VX[i] = VY[i] = VZ[i] = 0.0F;
                    if (GoreFx.random().nextFloat() < 0.5F) Flows.poolAt(level, X[i], hy, Z[i], (1.5F + SIZE[i] * 16.0F) * Stains.RES / 16.0F, type);
                } else {
                    switch (face.getAxis()) {
                        case X -> VX[i] *= -0.3F;
                        case Y -> VY[i] *= -0.3F;
                        case Z -> VZ[i] *= -0.3F;
                    }
                    VX[i] *= 0.6F;
                    VY[i] *= face.getAxis() == Direction.Axis.Y ? 1.0F : 0.6F;
                    VZ[i] *= 0.6F;
                    SPIN[i] *= 0.6F;
                }
            } else {
                X[i] = nx;
                Y[i] += VY[i];
                Z[i] = nz;
            }
            if ((AGE[i] & 3) == 0) LIGHT[i] = light(level, BloodType.ALL[TYPE[i]], X[i], Y[i], Z[i]);
            i++;
        }
    }

    static void draw(Matrix4f matrix, Matrix3f normal, Vec3 cam, float partial) {
        float span = 6.0F * GoreAtlas.TEXEL;
        for (int i = 0; i < count; i++) {
            float t = Splattered.time().partial(EVERY[i], partial);
            float cx = (float) (Mth.lerp(t, PX[i], X[i]) - cam.x);
            float cy = (float) (Mth.lerp(t, PY[i], Y[i]) - cam.y);
            float cz = (float) (Mth.lerp(t, PZ[i], Z[i]) - cam.z);
            if (cx * cx + cy * cy + cz * cz > 48.0F * 48.0F) continue;
            float shrink = REST[i] ? 1.0F : Mth.clamp((LIFE[i] - AGE[i] - t) / 10.0F, 0.0F, 1.0F);
            float cell = SIZE[i] * shrink;
            if (cell <= 0.002F) continue;
            float base = cy - SINK[i];
            if (!GoreVerts.visible(cx, base, cz, cell * 3.2F)) continue;
            SPIN_Q.setAngleAxis(Mth.lerp(t, PROT[i], ROT[i]), AXIS_X[i], AXIS_Y[i], AXIS_Z[i]);
            AX.set(1.0F, 0.0F, 0.0F).rotate(SPIN_Q);
            AY.set(0.0F, 1.0F, 0.0F).rotate(SPIN_Q);
            AZ.set(0.0F, 0.0F, 1.0F).rotate(SPIN_Q);
            int[][] shape = SHAPES[SHAPE[i]];
            float[] mid = CENTER[SHAPE[i]];
            float us = GoreAtlas.u(KIND[i], UOFF[i]);
            float vs = GoreAtlas.v(KIND[i], VOFF[i]);
            int c = COLOR[i];
            int color = GoreVerts.color((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F, BloodType.ALL[TYPE[i]].alpha);
            for (int n = 0; n < shape.length; n++) {
                int[] q = shape[n];
                float ox = (q[0] - mid[0]) * cell;
                float oy = (q[1] - mid[1]) * cell;
                float oz = (q[2] - mid[2]) * cell;
                float jx = 0.75F + 0.5F * GoreAtlas.hash(SEED[i], n, 1);
                float jy = 0.6F + 0.6F * GoreAtlas.hash(SEED[i], n, 2);
                float jz = 0.75F + 0.5F * GoreAtlas.hash(SEED[i], n, 3);
                cube(matrix, normal, cx + AX.x * ox + AY.x * oy + AZ.x * oz, base + AX.y * ox + AY.y * oy + AZ.y * oz, cz + AX.z * ox + AY.z * oy + AZ.z * oz,
                        cell * 0.5F * jx, cell * 0.5F * jy, cell * 0.5F * jz, color, LIGHT[i], us, vs, span);
            }
        }
    }

    private static void cube(Matrix4f matrix, Matrix3f normal, float cx, float cy, float cz, float hx, float hy, float hz,
                             int color, int light, float us, float vs, float span) {
        for (int c = 0; c < 8; c++) {
            float sx = (c & 1) == 0 ? -hx : hx;
            float sy = (c & 2) == 0 ? -hy : hy;
            float sz = (c & 4) == 0 ? -hz : hz;
            CX[c] = cx + AX.x * sx + AY.x * sy + AZ.x * sz;
            CY[c] = cy + AX.y * sx + AY.y * sy + AZ.y * sz;
            CZ[c] = cz + AX.z * sx + AY.z * sy + AZ.z * sz;
        }
        for (int f = 0; f < 6; f++) {
            float[] n = NORMALS[f];
            float nx = AX.x * n[0] + AY.x * n[1] + AZ.x * n[2];
            float ny = AX.y * n[0] + AY.y * n[1] + AZ.y * n[2];
            float nz = AX.z * n[0] + AY.z * n[1] + AZ.z * n[2];
            int[] side = SIDES[f];
            int first = side[0];
            if (GoreVerts.cull && CX[first] * nx + CY[first] * ny + CZ[first] * nz >= 0.0F) continue;
            int packed = GoreVerts.normal(normal, nx, ny, nz);
            for (int k = 0; k < 4; k++) {
                int p = side[k];
                GoreVerts.put(matrix, CX[p], CY[p], CZ[p], color, us + UVS[k][0] * span, vs + UVS[k][1] * span, light, packed);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        count = 0;
        cursor = 0;
    }
}
