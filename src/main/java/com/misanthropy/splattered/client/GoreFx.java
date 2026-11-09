package com.misanthropy.splattered.client;

import com.misanthropy.splattered.SplatteredConfig;
import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.client.ShaderPackCompat;
import com.misanthropy.splattered.BloodType;
import com.misanthropy.splattered.BloodTypes;
import com.misanthropy.splattered.GoreNetwork;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class GoreFx {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAP = 1024;
    private static final int MAX_STREAMS = 24;
    private static final double CULL = 64.0;
    private static final float GRAVITY = 0.045F;
    private static final float DRAG = 0.985F;
    private static final float MIST_DRAG = 0.86F;
    private static final float STRETCH = 0.6F;
    static final byte DROP = 0;
    static final byte MIST = 1;
    static final byte DRIP = 2;

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
    private static final float[] SHADE = new float[CAP];
    private static final short[] AGE = new short[CAP];
    private static final short[] LIFE = new short[CAP];
    private static final byte[] TYPE = new byte[CAP];
    private static final byte[] KIND = new byte[CAP];
    private static final byte[] EVERY = new byte[CAP];
    private static final byte[] DELAY = new byte[CAP];
    private static final int[] LIGHT = new int[CAP];
    private static final float[][] RGB = new float[BloodType.ALL.length][];
    private static final List<Stream> STREAMS = new ArrayList<>();
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(1 << 16));
    static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static final RandomSource RANDOM = RandomSource.create();
    static final double[] HIT = new double[4];

    private static int count;
    private static int cursor;
    private static int slashSide = 1;
    private static boolean irisChecked;
    private static Object irisApi;
    private static Method irisShadow;

    static {
        for (BloodType type : BloodType.ALL) {
            RGB[type.ordinal()] = new float[]{(type.wet >> 16 & 255) / 255.0F, (type.wet >> 8 & 255) / 255.0F, (type.wet & 255) / 255.0F};
        }
    }

    private GoreFx() {
    }

    private static final class Stream {
        int entity;
        double x;
        double y;
        double z;
        float ox;
        float oy;
        float oz;
        float yaw;
        Vec3 dir;
        float rate;
        float power;
        int ticks;
        int life;
        BloodType type;
        RandomSource random;
    }

    public static void spray(Vec3 at, Vec3 dir, float amount, BloodType type) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !SplatteredConfig.ENABLED.get() || !type.bleeds() || far(at)) return;
        emit(level, null, at, dir, amount, (byte) 0, type, 1.0F, RANDOM);
    }

    public static void onPacket(GoreNetwork.S2CGore packet) {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || !SplatteredConfig.ENABLED.get()) return;
            Entity entity = level.getEntity(packet.entity());
            if (entity == null) return;
            BloodType type = BloodTypes.of(entity);
            Vec3 at = new Vec3(packet.x() + packet.ox(), packet.y() + packet.oy(), packet.z() + packet.oz());
            if (!type.bleeds() || far(at)) return;
            if ((packet.flags() & GoreNetwork.S2CGore.KILL) != 0) {
                entity.setPos(packet.x(), packet.y(), packet.z());
                entity.xo = entity.xOld = packet.x();
                entity.yo = entity.yOld = packet.y();
                entity.zo = entity.zOld = packet.z();
            }
            Vec3 dir = new Vec3(packet.dx() / 127.0, packet.dy() / 127.0, packet.dz() / 127.0);
            emit(level, entity, at, dir, packet.amount(), packet.flags(), type, bulk(entity), RandomSource.create(packet.seed()));
        } catch (RuntimeException e) {
            LOGGER.error("Gore spray failed", e);
        }
    }

    static float bulk(Entity entity) {
        AABB box = entity.getBoundingBox();
        double w = box.getXsize();
        return (float) Mth.clamp(Math.sqrt(w * w * box.getYsize() / 0.648), 0.35, 3.0);
    }

    private static void emit(ClientLevel level, Entity entity, Vec3 at, Vec3 dir, float amount, byte flags, BloodType type, float bulk, RandomSource random) {
        boolean heavy = (flags & GoreNetwork.S2CGore.HEAVY) != 0;
        boolean kill = (flags & GoreNetwork.S2CGore.KILL) != 0;
        boolean mild = SplatteredConfig.MILD.get();
        boolean burst = !mild && (flags & GoreNetwork.S2CGore.BURST) != 0;
        boolean melee = (flags & GoreNetwork.S2CGore.MELEE) != 0;
        Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : dir.normalize();
        float power = Mth.clamp(amount / 8.0F, 0.15F, 3.0F) * (heavy ? 1.35F : 1.0F);
        float scale = Mth.sqrt(bulk) * type.amount * (ShaderPackCompat.reducedVisuals() ? 0.5F : 1.0F) * (mild ? 0.5F : 1.0F);
        int total = Mth.clamp(Math.round((4.0F + amount * 3.5F) * scale * (heavy ? 1.5F : 1.0F) * (kill ? 1.4F : 1.0F)), 2, 140);
        Vec3 center = entity != null ? entity.getBoundingBox().getCenter() : at;
        if (type != BloodType.BONE) {
            GoreSounds.HIT.play(level, at.x, at.y, at.z, 0.45F + 0.12F * Math.min(power, 2.5F), 1.12F - 0.16F * Mth.sqrt(bulk) + 0.1F * random.nextFloat());
        }
        LivingEntity body = !mild && kill && entity instanceof LivingEntity living && !(entity instanceof Player) ? living : null;
        boolean obliterate = body != null && heavy && bulk <= 2.2F;
        if (obliterate) {
            Corpses.request(body, d, 1.6F);
            obliterate(level, center, d, power, bulk, type, random);
            return;
        }
        if (body != null) Corpses.request(body, d, burst ? 1.3F : 1.0F);
        if (burst) GoreSounds.BURST.play(level, center.x, center.y, center.z, 0.9F, 0.9F + 0.15F * random.nextFloat());
        if (heavy) Mist.puff(level, at, d, 3 + random.nextInt(4), 0.3F + 0.1F * Mth.sqrt(bulk), 0.1F, type, random);
        if (burst) Mist.puff(level, center, d, 8 + Math.round(bulk * 4.0F), 0.45F * Mth.sqrt(bulk), 0.16F, type, random);
        if (type == BloodType.BONE) {
            int chips = burst || kill ? 8 + Math.round(bulk * 4.0F) : 1 + random.nextInt(2);
            Chunks.burst(level, burst || kill ? center : at, d, chips, power, 0.6F, type, random);
            for (int i = 0; i < total / 2; i++) {
                spawn(level, at, cone(d, 1.0F, random).scale(0.04 + 0.1 * random.nextDouble()), 0.02F + 0.015F * random.nextFloat(), 8 + random.nextInt(10), type, MIST, random);
            }
            return;
        }
        if (kill && entity != null && !mild) Flows.pool(level, entity, type, bulk);
        if (burst) {
            int pieces = Mth.clamp(Math.round(8.0F + bulk * 6.0F * (heavy ? 1.4F : 1.0F)), 6, 20);
            Chunks.burst(level, center, d, pieces, power, Mth.clamp(Mth.sqrt(bulk), 0.8F, 1.6F), type, random);
        }
        if (d.y < -0.9) {
            ring(level, at, total, power, type, random);
            return;
        }
        if (melee && !heavy) {
            slash(level, at, d, amount * (kill ? 1.7F : 1.0F), type, random);
            return;
        }
        int main = Math.round(total * 0.6F);
        int back = Math.round(total * 0.12F);
        int mist = total - main - back;
        float spread = Mth.lerp(Math.min(power, 1.5F) / 1.5F, 0.75F, 0.4F);
        for (int i = 0; i < main; i++) {
            Vec3 v = cone(d.add(0.0, 0.18, 0.0), spread, random).scale((0.08 + 0.22 * random.nextDouble()) * (0.6 + 0.4 * power));
            spawn(level, at.x, at.y, at.z, v.x, v.y, v.z, volume(random, 0.03F, 0.12F), 50 + random.nextInt(30), type, DROP, (int) (random.nextFloat() * random.nextFloat() * 2.5F));
        }
        for (int i = 0; i < back; i++) {
            Vec3 v = cone(d.scale(-1.0).add(0.0, 0.35, 0.0), 0.6F, random).scale(0.06 + 0.12 * random.nextDouble());
            spawn(level, at, v, 0.03F + 0.03F * random.nextFloat(), 40 + random.nextInt(20), type, DROP, random);
        }
        for (int i = 0; i < mist; i++) {
            Vec3 v = cone(d, 1.1F, random).scale(0.05 + 0.14 * random.nextDouble() * (0.7 + 0.3 * power));
            spawn(level, at, v, 0.018F + 0.014F * random.nextFloat(), 8 + random.nextInt(12), type, MIST, random);
        }
        if (amount >= 5.0F && !mild) {
            int gouts = 1 + random.nextInt(heavy ? 3 : 2);
            for (int i = 0; i < gouts; i++) {
                Vec3 v = cone(d.add(0.0, 0.5, 0.0), 0.5F, random).scale(0.08 + 0.1 * random.nextDouble());
                spawn(level, at, v, 0.09F + 0.04F * random.nextFloat(), 70, type, DROP, random);
            }
        }
        if (!mild && entity != null && (heavy || kill || amount >= 8.0F) && STREAMS.size() < MAX_STREAMS) {
            Stream stream = new Stream();
            stream.entity = entity.getId();
            stream.x = at.x;
            stream.y = at.y;
            stream.z = at.z;
            stream.ox = (float) (at.x - entity.getX());
            stream.oy = (float) (at.y - entity.getY());
            stream.oz = (float) (at.z - entity.getZ());
            stream.yaw = yaw(entity);
            stream.dir = d;
            stream.life = 6 + random.nextInt(heavy ? 10 : 6);
            stream.rate = Math.max(1.0F, total * 0.09F);
            stream.power = power;
            stream.type = type;
            stream.random = random;
            STREAMS.add(stream);
        }
    }

    private static float volume(RandomSource random, float base, float max) {
        return Mth.clamp(base * (float) Math.pow(Math.exp(random.nextGaussian() * 0.9), 0.45), 0.016F, max);
    }

    private static void slash(ClientLevel level, Vec3 at, Vec3 d, float amount, BloodType type, RandomSource random) {
        double fx = d.x;
        double fz = d.z;
        double fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0E-4) {
            fx = 1.0;
            fz = 0.0;
            fl = 1.0;
        }
        fx /= fl;
        fz /= fl;
        slashSide = -slashSide;
        double tx = -fz * slashSide;
        double ty = random.nextGaussian() * 0.25;
        double tz = fx * slashSide;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        int n = Math.max(5, Math.round(Mth.clamp(8.0F + amount * 1.6F + random.nextInt(5), 8.0F, 24.0F) * type.amount * (ShaderPackCompat.reducedVisuals() ? 0.6F : 1.0F) * (SplatteredConfig.MILD.get() ? 0.4F : 1.0F)));
        double base = 0.24 * Math.exp(random.nextGaussian() * 0.18);
        double arc = 0.6 + random.nextDouble() * 0.7;
        double skew = (random.nextDouble() - 0.5) * 0.4;
        for (int i = 0; i < n; i++) {
            double roll = random.nextDouble();
            double vol = Math.exp(random.nextGaussian() * 0.9);
            float size = Mth.clamp(0.028F * (float) Math.pow(vol, 0.45), 0.016F, 0.11F);
            double slow = Math.min(2.0, Math.pow(vol, -0.28) * Math.exp(random.nextGaussian() * 0.22));
            double k = random.nextDouble();
            double ox = at.x + tx * (k - 0.5) * 0.35;
            double oy = at.y + ty * (k - 0.5) * 0.35 + random.nextGaussian() * 0.04;
            double oz = at.z + tz * (k - 0.5) * 0.35;
            double vx;
            double vy;
            double vz;
            int delay = 0;
            int life = 60;
            if (roll < 0.45) {
                double a = (k - 0.5) * arc + skew;
                double c = Math.cos(a) * 0.6;
                double sn = Math.sin(a) + 0.55;
                vx = fx * c + tx * sn;
                vy = ty * sn + 0.08 + random.nextDouble() * 0.12;
                vz = fz * c + tz * sn;
                double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double sp = base * (1.15 - 0.35 * k) * slow / l;
                vx *= sp;
                vy *= sp;
                vz *= sp;
                delay = (int) Math.round(k * 2.2 + random.nextDouble() * 0.6);
            } else if (roll < 0.7) {
                Vec3 v = cone(new Vec3(fx, 0.12, fz), 0.18F, random).scale(base * slow);
                vx = v.x;
                vy = v.y;
                vz = v.z;
                delay = (int) Math.round(random.nextDouble() * random.nextDouble() * 1.5);
            } else if (roll < 0.9) {
                double push = 0.03 + random.nextDouble() * 0.04;
                double side = random.nextGaussian() * 0.02;
                vx = fx * push + tx * side;
                vy = -0.01;
                vz = fz * push + tz * side;
                delay = 1 + random.nextInt(8);
                life = 90;
            } else {
                Vec3 v = cone(new Vec3(-fx, 0.15, -fz), 0.5F, random).scale(base * (0.2 + 0.15 * random.nextDouble()));
                vx = v.x;
                vy = v.y;
                vz = v.z;
            }
            spawn(level, ox, oy, oz, vx, vy, vz, size, life, type, DROP, delay);
        }
        for (int i = 0; i < 4; i++) {
            Vec3 v = cone(new Vec3(fx, 0.0, fz), 1.0F, random).scale(0.04 + 0.05 * random.nextDouble());
            double g = random.nextGaussian() * 0.1;
            spawn(level, at.x + tx * g, at.y + ty * g, at.z + tz * g, v.x, v.y, v.z, 0.012F + 0.01F * random.nextFloat(), 10 + random.nextInt(8), type, MIST, 0);
        }
    }

    private static void obliterate(ClientLevel level, Vec3 center, Vec3 d, float power, float bulk, BloodType type, RandomSource random) {
        GoreSounds.BURST.play(level, center.x, center.y, center.z, 1.0F, 0.75F + 0.15F * random.nextFloat());
        GoreSounds.HIT.play(level, center.x, center.y, center.z, 0.9F, 0.6F + 0.1F * random.nextFloat());
        float root = Mth.sqrt(bulk);
        if (type == BloodType.BONE) {
            Chunks.burst(level, center, d, 14 + Math.round(bulk * 8.0F), power * 1.6F, 0.8F, type, random);
            return;
        }
        Mist.puff(level, center, d.scale(0.4), 22 + Math.round(bulk * 12.0F), 0.7F * root, 0.22F, type, random);
        Mist.puff(level, center, d, 10, 0.45F * root, 0.35F, type, random);
        int pieces = Mth.clamp(Math.round(10.0F + bulk * 6.0F), 8, 22);
        Chunks.burst(level, center, d, pieces, Math.max(power, 1.5F) * 1.5F, Mth.clamp(root, 0.9F, 1.6F), type, random);
        int total = Mth.clamp(Math.round(90.0F * root * type.amount * (ShaderPackCompat.reducedVisuals() ? 0.5F : 1.0F)), 30, 180);
        for (int i = 0; i < total; i++) {
            Vec3 aim = i % 3 == 0 ? cone(d, 0.5F, random) : cone(new Vec3(0.0, 0.25, 0.0), 2.5F, random);
            Vec3 v = aim.scale((0.25 + 0.45 * random.nextDouble()) * (0.8 + 0.2 * Math.min(power, 2.0F)));
            spawn(level, center.add(random.nextGaussian() * 0.2, random.nextGaussian() * 0.3, random.nextGaussian() * 0.2), v, 0.05F + 0.07F * random.nextFloat(), 60, type, DROP, random);
        }
    }

    private static void ring(ClientLevel level, Vec3 at, int total, float power, BloodType type, RandomSource random) {
        for (int i = 0; i < total; i++) {
            double a = random.nextDouble() * Mth.TWO_PI;
            double s = (0.04 + 0.12 * random.nextDouble()) * (0.6 + 0.4 * power);
            spawn(level, at, new Vec3(Math.cos(a) * s, 0.08 + 0.12 * random.nextDouble(), Math.sin(a) * s), 0.03F + 0.04F * random.nextFloat(), 40, type, DROP, random);
        }
    }

    private static float yaw(Entity entity) {
        return entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
    }

    static Vec3 cone(Vec3 axis, float spread, RandomSource random) {
        Vec3 a = axis.normalize();
        return a.add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread).normalize();
    }

    static int size() {
        return count;
    }

    static RandomSource random() {
        return RANDOM;
    }

    static void spawn(ClientLevel level, Vec3 at, Vec3 v, float size, int life, BloodType type, byte kind, RandomSource random) {
        spawn(level, at.x, at.y, at.z, v.x, v.y, v.z, size, life, type, kind, 0);
    }

    static void spawn(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, float size, int life, BloodType type, byte kind, int delay) {
        int cap = ShaderPackCompat.reducedVisuals() ? CAP / 2 : CAP;
        int i;
        if (count < cap) {
            i = count++;
        } else {
            if (cursor >= count) cursor = 0;
            i = cursor++;
        }
        X[i] = PX[i] = x;
        Y[i] = PY[i] = y;
        Z[i] = PZ[i] = z;
        VX[i] = (float) vx;
        VY[i] = (float) vy;
        VZ[i] = (float) vz;
        SIZE[i] = size;
        SHADE[i] = 0.82F + 0.22F * RANDOM.nextFloat();
        AGE[i] = 0;
        LIFE[i] = (short) life;
        TYPE[i] = (byte) type.ordinal();
        KIND[i] = kind;
        EVERY[i] = 1;
        DELAY[i] = (byte) Mth.clamp(delay, 0, 100);
        LIGHT[i] = light(level, type, x, y, z);
    }

    private static int light(ClientLevel level, BloodType type, double x, double y, double z) {
        if (type.glow) return LightTexture.FULL_BRIGHT;
        return LevelRenderer.getLightColor(level, CURSOR.set(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
    }

    private static void remove(int i) {
        int last = --count;
        if (i != last) {
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
            SHADE[i] = SHADE[last];
            AGE[i] = AGE[last];
            LIFE[i] = LIFE[last];
            TYPE[i] = TYPE[last];
            KIND[i] = KIND[last];
            EVERY[i] = EVERY[last];
            DELAY[i] = DELAY[last];
            LIGHT[i] = LIGHT[last];
        }
    }

    static boolean far(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.isInitialized() ? camera.getPosition() : mc.player != null ? mc.player.getEyePosition() : at;
        return eye.distanceToSqr(at) > CULL * CULL;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || count == 0 && STREAMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        long start = System.nanoTime();
        try {
            tickStreams(level);
            tickDrops(level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore tick failed", e);
            clear();
        }
    }

    private static void tickStreams(ClientLevel level) {
        for (int s = STREAMS.size() - 1; s >= 0; s--) {
            Stream stream = STREAMS.get(s);
            Entity entity = level.getEntity(stream.entity);
            Vec3 dir = stream.dir;
            if (entity != null && !entity.isRemoved()) {
                float turn = (yaw(entity) - stream.yaw) * Mth.DEG_TO_RAD;
                float cos = Mth.cos(turn);
                float sin = Mth.sin(turn);
                stream.x = entity.getX() + stream.ox * cos - stream.oz * sin;
                stream.y = entity.getY() + stream.oy;
                stream.z = entity.getZ() + stream.ox * sin + stream.oz * cos;
                dir = new Vec3(dir.x * cos - dir.z * sin, dir.y, dir.x * sin + dir.z * cos);
            }
            int every = Splattered.time().slowed() ? Math.max(1, Splattered.time().every(stream.x, stream.y, stream.z)) : 1;
            if (Splattered.time().skip(every)) continue;
            float left = 1.0F - (float) stream.ticks / stream.life;
            float pulse = 0.55F + 0.45F * Mth.sin(stream.ticks * 1.9F);
            int n = Math.round(stream.rate * left * pulse + stream.random.nextFloat());
            Vec3 at = new Vec3(stream.x, stream.y, stream.z);
            for (int i = 0; i < n; i++) {
                Vec3 v = cone(dir.add(0.0, 0.3, 0.0), 0.22F, stream.random).scale((0.08 + 0.16 * stream.random.nextDouble()) * (0.5 + 0.5 * left) * (0.7 + 0.3 * stream.power));
                spawn(level, at, v, 0.03F + 0.04F * stream.random.nextFloat(), 50, stream.type, DROP, stream.random);
            }
            if (++stream.ticks >= stream.life) STREAMS.remove(s);
        }
    }

    private static void tickDrops(ClientLevel level) {
        boolean slowed = Splattered.time().slowed();
        int i = 0;
        while (i < count) {
            if (slowed) EVERY[i] = (byte) Mth.clamp(Splattered.time().every(X[i], Y[i], Z[i]), 1, 127);
            else EVERY[i] = 1;
            if (Splattered.time().skip(EVERY[i])) {
                i++;
                continue;
            }
            if (DELAY[i] > 0) {
                DELAY[i]--;
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
            boolean mist = KIND[i] == MIST;
            float drag = mist ? MIST_DRAG : DRAG;
            VX[i] *= drag;
            VZ[i] *= drag;
            VY[i] = VY[i] * drag - (mist ? GRAVITY * 0.25F : GRAVITY);
            double nx = X[i] + VX[i];
            double ny = Y[i] + VY[i];
            double nz = Z[i] + VZ[i];
            int hit = collide(level, X[i], Y[i], Z[i], nx, ny, nz);
            if (hit == -2) {
                remove(i);
                continue;
            }
            if (hit == -3) {
                if (RANDOM.nextFloat() < (mist ? 0.1F : 0.5F)) Mist.cloud(level, nx, ny, nz, 0.1F + SIZE[i] * 2.0F, BloodType.ALL[TYPE[i]], RANDOM);
                remove(i);
                continue;
            }
            if (hit >= 0) {
                double t = HIT[0];
                if (!mist) {
                    double lx = X[i] + (nx - X[i]) * t;
                    double ly = Y[i] + (ny - Y[i]) * t;
                    double lz = Z[i] + (nz - Z[i]) * t;
                    land(level, i, lx, ly, lz, Direction.from3DDataValue(hit));
                    if (KIND[i] == DRIP) {
                        (SIZE[i] >= 0.04F ? GoreSounds.DRIP_HEAVY : GoreSounds.DRIP_LIGHT).play(level, lx, ly, lz, 0.35F, 0.9F + 0.2F * RANDOM.nextFloat());
                    } else if (SIZE[i] >= 0.07F) {
                        GoreSounds.SPLAT.play(level, lx, ly, lz, 0.3F, 0.9F + 0.3F * RANDOM.nextFloat());
                    }
                }
                remove(i);
                continue;
            }
            X[i] = nx;
            Y[i] = ny;
            Z[i] = nz;
            if ((AGE[i] & 3) == 0) LIGHT[i] = light(level, BloodType.ALL[TYPE[i]], nx, ny, nz);
            i++;
        }
    }

    private static void land(ClientLevel level, int i, double x, double y, double z, Direction face) {
        Stains.splat(level, CURSOR.getX(), CURSOR.getY(), CURSOR.getZ(), face, x, y, z, SIZE[i], VX[i], VY[i], VZ[i], BloodType.ALL[TYPE[i]], RANDOM);
    }

    static int collide(ClientLevel level, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        if (dx * dx + dy * dy + dz * dz > 0.36) {
            int mid = test(level, x0, y0, z0, x1, y1, z1, (x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5);
            if (mid != -1) return mid;
        }
        return test(level, x0, y0, z0, x1, y1, z1, x1, y1, z1);
    }

    private static int test(ClientLevel level, double x0, double y0, double z0, double x1, double y1, double z1, double px, double py, double pz) {
        int bx = Mth.floor(px);
        int by = Mth.floor(py);
        int bz = Mth.floor(pz);
        CURSOR.set(bx, by, bz);
        if (!level.isLoaded(CURSOR) || by < level.getMinBuildHeight()) return -2;
        BlockState state = level.getBlockState(CURSOR);
        if (state.isAir()) return -1;
        if (!state.getFluidState().isEmpty()) return state.getFluidState().is(FluidTags.WATER) ? -3 : -2;
        VoxelShape shape = state.getCollisionShape(level, CURSOR);
        if (shape.isEmpty()) return -1;
        HIT[0] = Double.MAX_VALUE;
        HIT[1] = -1;
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double ox = x0 - bx;
        double oy = y0 - by;
        double oz = z0 - bz;
        if (shape == Shapes.block()) enter(ox, oy, oz, dx, dy, dz, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        else shape.forAllBoxes((ax, ay, az, cx, cy, cz) -> enter(ox, oy, oz, dx, dy, dz, ax, ay, az, cx, cy, cz));
        if (HIT[1] < 0) return -1;
        return (int) HIT[1];
    }

    private static double near;
    private static double far;
    private static int nearFace;

    private static void enter(double ox, double oy, double oz, double dx, double dy, double dz, double ax, double ay, double az, double cx, double cy, double cz) {
        near = -1.0E-3;
        far = 1.0;
        nearFace = -1;
        if (!slab(ox, dx, ax, cx, 0) || !slab(oy, dy, ay, cy, 1) || !slab(oz, dz, az, cz, 2)) return;
        int face = nearFace < 0 ? (dy <= 0 ? 3 : 2) : nearFace;
        if (near < HIT[0]) {
            HIT[0] = Math.max(near, 0.0);
            HIT[1] = FACES[face];
        }
    }

    private static boolean slab(double o, double d, double lo, double hi, int axis) {
        if (Math.abs(d) < 1.0E-9) return o >= lo && o <= hi;
        double t0 = (lo - o) / d;
        double t1 = (hi - o) / d;
        int face = d > 0 ? axis * 2 : axis * 2 + 1;
        if (t0 > t1) {
            double t = t0;
            t0 = t1;
            t1 = t;
        }
        if (t0 > near) {
            near = t0;
            nearFace = face;
        }
        far = Math.min(far, t1);
        return near <= far;
    }

    private static final int[] FACES = {
            Direction.WEST.get3DDataValue(), Direction.EAST.get3DDataValue(),
            Direction.DOWN.get3DDataValue(), Direction.UP.get3DDataValue(),
            Direction.NORTH.get3DDataValue(), Direction.SOUTH.get3DDataValue()
    };

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !SplatteredConfig.ENABLED.get() || count == 0 && Stains.isEmpty() && Chunks.isEmpty() && Mist.isEmpty() && Corpses.isEmpty() || shadowPass()) return;
        long start = System.nanoTime();
        try {
            draw(event);
            GoreStats.frame(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore draw failed", e);
            clear();
        }
    }

    private static void draw(RenderLevelStageEvent event) {
        GoreVerts.frame(event.getFrustum(), event.getCamera().getPosition());
        if (!Stains.isEmpty() && !ShaderPackCompat.isShaderPackActive()) {
            RenderType soak = GoreAtlas.soakType();
            PoseStack.Pose base = event.getPoseStack().last();
            GoreVerts.begin(BUFFERS.getBuffer(soak), true);
            Stains.draw(base.pose(), base.normal(), event.getCamera().getPosition(), true);
            GoreVerts.end();
            BUFFERS.endBatch(soak);
        }
        if (!Corpses.isEmpty()) {
            Corpses.draw(BUFFERS, event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick());
            BUFFERS.endBatch();
        }
        RenderType type = GoreAtlas.type();
        GoreVerts.begin(BUFFERS.getBuffer(type), false);
        PoseStack.Pose pose = event.getPoseStack().last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partial = event.getPartialTick();
        float u = GoreAtlas.u(GoreAtlas.WHITE, 8.0F);
        float v = GoreAtlas.v(GoreAtlas.WHITE, 8.0F);
        float leftX = camera.getLeftVector().x();
        float leftY = camera.getLeftVector().y();
        float leftZ = camera.getLeftVector().z();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && !Stains.isEmpty()) {
            Stains.upload(level);
            Stains.draw(matrix, normal, cam, false);
        }
        Chunks.draw(matrix, normal, cam, partial);
        Corpses.drawFlesh(matrix, normal, cam, partial);
        for (int i = 0; i < count; i++) {
            if (DELAY[i] > 0) continue;
            float t = Splattered.time().partial(EVERY[i], partial);
            float hx = (float) (Mth.lerp(t, PX[i], X[i]) - cam.x);
            float hy = (float) (Mth.lerp(t, PY[i], Y[i]) - cam.y);
            float hz = (float) (Mth.lerp(t, PZ[i], Z[i]) - cam.z);
            float d2 = hx * hx + hy * hy + hz * hz;
            if (d2 > CULL * CULL || d2 < 0.12F) continue;
            float size = SIZE[i];
            if (!GoreVerts.visible(hx, hy, hz, size + 1.0F)) continue;
            float ax = (float) (X[i] - PX[i]) * STRETCH;
            float ay = (float) (Y[i] - PY[i]) * STRETCH;
            float az = (float) (Z[i] - PZ[i]) * STRETCH;
            float len = Mth.sqrt(ax * ax + ay * ay + az * az);
            if (len > 0.9F) {
                float k = 0.9F / len;
                ax *= k;
                ay *= k;
                az *= k;
                len = 0.9F;
            }
            float sx;
            float sy;
            float sz;
            if (len < size) {
                ax = 0.0F;
                ay = -size;
                az = 0.0F;
                len = size;
            }
            sx = ay * hz - az * hy;
            sy = az * hx - ax * hz;
            sz = ax * hy - ay * hx;
            float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-5F) {
                sx = leftX;
                sy = leftY;
                sz = leftZ;
            } else {
                sx /= sl;
                sy /= sl;
                sz /= sl;
            }
            float nx = ax / len;
            float ny = ay / len;
            float nz = az / len;
            float head = size * 0.5F;
            float tail = size * 0.18F;
            float fx = hx + nx * head;
            float fy = hy + ny * head;
            float fz = hz + nz * head;
            float bx = hx - ax;
            float by = hy - ay;
            float bz = hz - az;
            BloodType blood = BloodType.ALL[TYPE[i]];
            float[] rgb = RGB[blood.ordinal()];
            float shade = SHADE[i];
            float fade = Mth.clamp((LIFE[i] - AGE[i]) / 6.0F, 0.0F, 1.0F);
            float alpha = blood.alpha * (KIND[i] == MIST ? 0.7F : 1.0F) * fade;
            float r = rgb[0] * shade;
            float g = rgb[1] * shade;
            float b = rgb[2] * shade;
            int light = LIGHT[i];
            vertex(matrix, normal, fx + sx * head, fy + sy * head, fz + sz * head, r, g, b, alpha, u, v, light);
            vertex(matrix, normal, fx - sx * head, fy - sy * head, fz - sz * head, r, g, b, alpha, u, v, light);
            vertex(matrix, normal, bx - sx * tail, by - sy * tail, bz - sz * tail, r * 0.55F, g * 0.55F, b * 0.55F, alpha * 0.85F, u, v, light);
            vertex(matrix, normal, bx + sx * tail, by + sy * tail, bz + sz * tail, r * 0.55F, g * 0.55F, b * 0.55F, alpha * 0.85F, u, v, light);
        }
        Mist.draw(matrix, normal, camera, partial);
        GoreVerts.end();
        BUFFERS.endBatch(type);
    }

    static void vertex(Matrix4f matrix, Matrix3f normal, float x, float y, float z, float r, float g, float b, float a, float u, float v, int light) {
        GoreVerts.put(matrix, x, y, z, GoreVerts.color(r, g, b, a), u, v, light, GoreVerts.normal(normal, 0.0F, 1.0F, 0.0F));
    }

    static boolean shadowPass() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, GoreFx.class.getClassLoader());
                irisApi = type.getMethod("getInstance").invoke(null);
                irisShadow = type.getMethod("isRenderingShadowPass");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                irisShadow = null;
            }
        }
        if (irisShadow == null) return false;
        try {
            return Boolean.TRUE.equals(irisShadow.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException e) {
            irisShadow = null;
            return false;
        }
    }

    static void clear() {
        count = 0;
        cursor = 0;
        STREAMS.clear();
        Corpses.clear();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
