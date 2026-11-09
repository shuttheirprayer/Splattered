package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.client.ShaderPackCompat;
import com.misanthropy.splattered.BloodType;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
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
import org.joml.Vector3f;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Mist {

    private static final int CAP = 160;
    private static final float DRAG = 0.8F;

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
    private static final float[] GROW = new float[CAP];
    private static final float[] ALPHA = new float[CAP];
    private static final float[] ROLL = new float[CAP];
    private static final short[] AGE = new short[CAP];
    private static final short[] LIFE = new short[CAP];
    private static final byte[] TYPE = new byte[CAP];
    private static final byte[] EVERY = new byte[CAP];
    private static final int[] LIGHT = new int[CAP];
    private static final float[] SHADE = new float[CAP];
    private static final boolean[] WATER = new boolean[CAP];
    private static final Vector3f RIGHT = new Vector3f();
    private static final Vector3f UP = new Vector3f();

    private static int count;
    private static int cursor;

    private Mist() {
    }

    static boolean isEmpty() {
        return count == 0;
    }

    static void puff(ClientLevel level, Vec3 at, Vec3 dir, int n, float size, float speed, BloodType type, RandomSource random) {
        if (!type.bleeds() || type == BloodType.BONE) return;
        if (ShaderPackCompat.reducedVisuals()) n = (n + 1) / 2;
        for (int k = 0; k < n; k++) {
            Vec3 v = GoreFx.cone(dir, 1.2F, random).scale(speed * (0.3 + 0.7 * random.nextDouble()));
            int i = slot();
            X[i] = PX[i] = at.x + random.nextGaussian() * size * 0.3;
            Y[i] = PY[i] = at.y + random.nextGaussian() * size * 0.3;
            Z[i] = PZ[i] = at.z + random.nextGaussian() * size * 0.3;
            VX[i] = (float) v.x;
            VY[i] = (float) v.y;
            VZ[i] = (float) v.z;
            SIZE[i] = size * (0.6F + 0.6F * random.nextFloat());
            GROW[i] = 1.6F + 1.4F * random.nextFloat();
            ALPHA[i] = 0.5F + 0.3F * random.nextFloat();
            ROLL[i] = random.nextFloat() * Mth.TWO_PI;
            AGE[i] = 0;
            LIFE[i] = (short) (22 + random.nextInt(26));
            TYPE[i] = (byte) type.ordinal();
            EVERY[i] = 1;
            SHADE[i] = 0.55F + 0.35F * random.nextFloat();
            WATER[i] = false;
            LIGHT[i] = type.glow ? LightTexture.FULL_BRIGHT : LevelRenderer.getLightColor(level, GoreFx.CURSOR.set(Mth.floor(X[i]), Mth.floor(Y[i]), Mth.floor(Z[i])));
        }
    }

    static void cloud(ClientLevel level, double x, double y, double z, float size, BloodType type, RandomSource random) {
        if (!type.bleeds() || type == BloodType.BONE) return;
        int i = slot();
        X[i] = PX[i] = x;
        Y[i] = PY[i] = y;
        Z[i] = PZ[i] = z;
        VX[i] = (float) random.nextGaussian() * 0.012F;
        VY[i] = (float) random.nextGaussian() * 0.006F;
        VZ[i] = (float) random.nextGaussian() * 0.012F;
        SIZE[i] = size * (0.8F + 0.4F * random.nextFloat());
        GROW[i] = 2.5F + 1.5F * random.nextFloat();
        ALPHA[i] = 0.3F + 0.2F * random.nextFloat();
        ROLL[i] = random.nextFloat() * Mth.TWO_PI;
        AGE[i] = 0;
        LIFE[i] = (short) (70 + random.nextInt(50));
        TYPE[i] = (byte) type.ordinal();
        EVERY[i] = 1;
        SHADE[i] = 0.45F + 0.3F * random.nextFloat();
        WATER[i] = true;
        LIGHT[i] = type.glow ? LightTexture.FULL_BRIGHT : LevelRenderer.getLightColor(level, GoreFx.CURSOR.set(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
    }

    private static int slot() {
        if (count < CAP) return count++;
        if (cursor >= count) cursor = 0;
        return cursor++;
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
        GROW[i] = GROW[last];
        ALPHA[i] = ALPHA[last];
        ROLL[i] = ROLL[last];
        AGE[i] = AGE[last];
        LIFE[i] = LIFE[last];
        TYPE[i] = TYPE[last];
        EVERY[i] = EVERY[last];
        SHADE[i] = SHADE[last];
        LIGHT[i] = LIGHT[last];
        WATER[i] = WATER[last];
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || count == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            count = 0;
            return;
        }
        if (mc.isPaused()) return;
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
            VX[i] *= DRAG;
            VZ[i] *= DRAG;
            VY[i] = VY[i] * DRAG - (WATER[i] ? 0.0F : 0.0025F);
            X[i] += VX[i];
            Y[i] += VY[i];
            Z[i] += VZ[i];
            i++;
        }
    }

    static void draw(Matrix4f matrix, Matrix3f normal, Camera camera, float partial) {
        if (count == 0) return;
        Vec3 cam = camera.getPosition();
        float u0 = GoreAtlas.u(GoreAtlas.MIST, 0.0F);
        float v0 = GoreAtlas.v(GoreAtlas.MIST, 0.0F);
        float u1 = GoreAtlas.u(GoreAtlas.MIST, GoreAtlas.TILE);
        float v1 = GoreAtlas.v(GoreAtlas.MIST, GoreAtlas.TILE);
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        for (int i = 0; i < count; i++) {
            float t = Splattered.time().partial(EVERY[i], partial);
            float x = (float) (Mth.lerp(t, PX[i], X[i]) - cam.x);
            float y = (float) (Mth.lerp(t, PY[i], Y[i]) - cam.y);
            float z = (float) (Mth.lerp(t, PZ[i], Z[i]) - cam.z);
            if (x * x + y * y + z * z > 48.0F * 48.0F) continue;
            float k = (AGE[i] + t) / LIFE[i];
            float half = SIZE[i] * (1.0F + (GROW[i] - 1.0F) * (1.0F - (1.0F - k) * (1.0F - k))) * 0.5F;
            if (!GoreVerts.visible(x, y, z, half * 1.5F)) continue;
            float alpha = ALPHA[i] * (1.0F - k) * (1.0F - k) * Math.min(1.0F, (AGE[i] + t) * 0.5F + 0.3F);
            if (alpha < 0.11F) continue;
            float cos = Mth.cos(ROLL[i]) * half;
            float sin = Mth.sin(ROLL[i]) * half;
            RIGHT.set(left).mul(cos).add(up.x() * sin, up.y() * sin, up.z() * sin);
            UP.set(up).mul(cos).sub(left.x() * sin, left.y() * sin, left.z() * sin);
            BloodType type = BloodType.ALL[TYPE[i]];
            float shade = SHADE[i] * (1.0F - 0.3F * k);
            float r = (type.wet >> 16 & 255) / 255.0F * shade;
            float g = (type.wet >> 8 & 255) / 255.0F * shade;
            float b = (type.wet & 255) / 255.0F * shade;
            int light = LIGHT[i];
            GoreFx.vertex(matrix, normal, x - RIGHT.x - UP.x, y - RIGHT.y - UP.y, z - RIGHT.z - UP.z, r, g, b, alpha, u0, v1, light);
            GoreFx.vertex(matrix, normal, x - RIGHT.x + UP.x, y - RIGHT.y + UP.y, z - RIGHT.z + UP.z, r, g, b, alpha, u0, v0, light);
            GoreFx.vertex(matrix, normal, x + RIGHT.x + UP.x, y + RIGHT.y + UP.y, z + RIGHT.z + UP.z, r, g, b, alpha, u1, v0, light);
            GoreFx.vertex(matrix, normal, x + RIGHT.x - UP.x, y + RIGHT.y - UP.y, z + RIGHT.z - UP.z, r, g, b, alpha, u1, v1, light);
        }
    }

    static int size() {
        return count;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        count = 0;
        cursor = 0;
    }
}
