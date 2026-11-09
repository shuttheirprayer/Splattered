package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.BloodType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Flows {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RUNNERS = 64;
    private static final int MAX_HANGS = 32;
    private static final int MAX_POOLS = 24;
    private static final int POOL_TICKS = 40;

    private static final List<Runner> RUNNERS = new ArrayList<>();
    private static final List<Hang> HANGS = new ArrayList<>();
    private static final List<Pool> POOLS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private Flows() {
    }

    private static final class Runner {
        int bx;
        int by;
        int bz;
        int dir;
        int plane;
        int ps;
        float pt;
        float speed;
        float left;
        float length;
        BloodType type;
    }

    private static final class Hang {
        double x;
        double y;
        double z;
        int timer;
        int drops;
        BloodType type;
    }

    private static final class Pool {
        int bx;
        int by;
        int bz;
        int plane;
        float s;
        float t;
        float target;
        long seed;
        int ticks;
        BloodType type;
    }

    static boolean isEmpty() {
        return RUNNERS.isEmpty() && HANGS.isEmpty() && POOLS.isEmpty();
    }

    static void runner(int bx, int by, int bz, int dir, int plane, float s, float t, BloodType type, RandomSource random) {
        if (RUNNERS.size() >= MAX_RUNNERS) return;
        Runner runner = new Runner();
        runner.bx = bx;
        runner.by = by;
        runner.bz = bz;
        runner.dir = dir;
        runner.plane = plane;
        runner.ps = Mth.floor(s);
        runner.pt = t;
        runner.speed = 0.18F + 0.27F * random.nextFloat();
        runner.length = 4.0F + 14.0F * random.nextFloat();
        runner.left = runner.length;
        runner.type = type;
        RUNNERS.add(runner);
    }

    static void hang(double x, double y, double z, BloodType type, RandomSource random) {
        if (HANGS.size() >= MAX_HANGS) return;
        Hang hang = new Hang();
        hang.x = x;
        hang.y = y;
        hang.z = z;
        hang.timer = 15 + random.nextInt(40);
        hang.drops = 1 + random.nextInt(3);
        hang.type = type;
        HANGS.add(hang);
    }

    static void pool(ClientLevel level, Entity entity, BloodType type, float bulk) {
        AABB box = entity.getBoundingBox();
        float target = Mth.clamp(3.0F + bulk * 5.0F, 3.0F, 14.0F) * Mth.sqrt(type.amount) * Stains.RES / 16.0F;
        poolAt(level, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, target, type);
    }

    static void poolAt(ClientLevel level, double cx, double minY, double cz, float target, BloodType type) {
        if (!type.stains() || POOLS.size() >= MAX_POOLS) return;
        int bx = Mth.floor(cx);
        int bz = Mth.floor(cz);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 3; k++) {
            int by = Mth.floor(minY - 0.05) - k;
            cursor.set(bx, by, bz);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) return;
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (shape.isEmpty()) continue;
            double top = shape.max(Direction.Axis.Y);
            if (minY - (by + top) > 1.2) return;
            Pool pool = new Pool();
            pool.bx = bx;
            pool.by = by;
            pool.bz = bz;
            pool.plane = Mth.clamp((int) Math.round(top * 16.0), 0, 16);
            pool.s = (float) (cx - bx) * Stains.RES;
            pool.t = (float) (cz - bz) * Stains.RES;
            pool.target = target;
            pool.seed = RANDOM.nextLong();
            pool.type = type;
            POOLS.add(pool);
            return;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        long start = System.nanoTime();
        try {
            tickRunners(level);
            tickHangs(level);
            tickPools(level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore flows failed", e);
            clear();
        }
    }

    private static void tickRunners(ClientLevel level) {
        for (int i = RUNNERS.size() - 1; i >= 0; i--) {
            Runner runner = RUNNERS.get(i);
            int before = Mth.floor(runner.pt);
            runner.pt += runner.speed;
            runner.speed *= 0.97F;
            int after = Mth.floor(runner.pt);
            boolean done = runner.speed < 0.03F;
            for (int row = before + 1; row <= after && !done; row++) {
                if (RANDOM.nextFloat() < 0.07F) runner.ps += RANDOM.nextBoolean() ? 1 : -1;
                int add = (int) (60 + 70 * runner.left / runner.length);
                if (!Stains.dot(level, runner.bx, runner.by, runner.bz, runner.dir, runner.plane, runner.ps, row, add, runner.type)) {
                    Vec3 at = Stains.point(runner.bx, runner.by, runner.bz, runner.dir, runner.plane, runner.ps + 0.5F, row - 0.5F, 0.04F);
                    GoreFx.spawn(level, at, new Vec3(0.0, -0.02, 0.0), 0.035F, 40, runner.type, GoreFx.DRIP, RANDOM);
                    done = true;
                    break;
                }
                if (--runner.left <= 0.0F) done = true;
            }
            if (done) RUNNERS.remove(i);
        }
    }

    private static void tickHangs(ClientLevel level) {
        for (int i = HANGS.size() - 1; i >= 0; i--) {
            Hang hang = HANGS.get(i);
            if (--hang.timer > 0) continue;
            GoreFx.spawn(level, new Vec3(hang.x, hang.y, hang.z), new Vec3(0.0, -0.01, 0.0), 0.03F + 0.02F * RANDOM.nextFloat(), 60, hang.type, GoreFx.DRIP, RANDOM);
            if (--hang.drops <= 0) HANGS.remove(i);
            else hang.timer = 25 + RANDOM.nextInt(50);
        }
    }

    private static void tickPools(ClientLevel level) {
        for (int i = POOLS.size() - 1; i >= 0; i--) {
            Pool pool = POOLS.get(i);
            float k = (float) ++pool.ticks / POOL_TICKS;
            float grow = 1.0F - (1.0F - k) * (1.0F - k);
            Stains.pool(level, pool.bx, pool.by, pool.bz, pool.plane, pool.s, pool.t, Math.max(0.8F, pool.target * grow), 26, pool.type, RandomSource.create(pool.seed));
            if (pool.ticks >= POOL_TICKS) POOLS.remove(i);
        }
    }

    static void clear() {
        RUNNERS.clear();
        HANGS.clear();
        POOLS.clear();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
