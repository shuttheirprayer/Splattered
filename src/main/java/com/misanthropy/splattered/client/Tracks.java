package com.misanthropy.splattered.client;

import com.misanthropy.splattered.SplatteredConfig;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.client.ShaderPackCompat;
import com.misanthropy.splattered.BloodType;
import com.misanthropy.splattered.BloodTypes;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class Tracks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double RANGE = 24.0;
    private static final float STEP = 0.55F;
    private static final int PRINTS = 8;
    private static final Int2ObjectOpenHashMap<Track> TRACKS = new Int2ObjectOpenHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private static int clock;

    private Tracks() {
    }

    private static final class Track {
        double x;
        double z;
        float walked;
        int prints;
        int type;
        boolean left;
        int bleed;
        int seen;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || SplatteredConfig.MILD.get()) return;
        if ((++clock & 1) != 0) return;
        long start = System.nanoTime();
        try {
            scan(mc, level);
            GoreStats.tick(System.nanoTime() - start);
        } catch (RuntimeException e) {
            LOGGER.error("Gore tracks failed", e);
            TRACKS.clear();
        }
    }

    private static void scan(Minecraft mc, ClientLevel level) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        Vec3 cam = camera.getPosition();
        boolean stains = !Stains.isEmpty();
        boolean reduced = ShaderPackCompat.reducedVisuals();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || living.isDeadOrDying()) continue;
            if (living.distanceToSqr(cam) > RANGE * RANGE) continue;
            float max = living.getMaxHealth();
            boolean hurt = max > 0.0F && living.getHealth() < max * 0.5F;
            Track track = TRACKS.get(living.getId());
            if (track == null) {
                if (!stains && !hurt) continue;
                BloodType type = BloodTypes.of(living);
                if (!type.bleeds()) continue;
                track = new Track();
                track.x = living.getX();
                track.z = living.getZ();
                track.bleed = 10 + RANDOM.nextInt(20);
                TRACKS.put(living.getId(), track);
            }
            track.seen = clock;
            boolean ground = living.onGround() && living.getBbWidth() <= 1.4F;
            if (stains && ground && (clock & 3) == 0) {
                int wet = Stains.wetAt(level, living.getX(), living.getY(), living.getZ());
                if (wet >= 0) {
                    if (track.prints == 0) GoreSounds.SPLAT.play(level, living.getX(), living.getY(), living.getZ(), 0.25F, 1.1F + 0.2F * RANDOM.nextFloat());
                    track.prints = PRINTS;
                    track.type = wet >> 8;
                }
            }
            double dx = living.getX() - track.x;
            double dz = living.getZ() - track.z;
            track.x = living.getX();
            track.z = living.getZ();
            track.walked += (float) Math.sqrt(dx * dx + dz * dz);
            if (track.walked >= STEP) {
                track.walked = 0.0F;
                if (track.prints > 0 && ground) {
                    Stains.print(level, living.getX(), living.getY(), living.getZ(), living.yBodyRot, track.left, BloodType.ALL[track.type], 60 + track.prints * 8);
                    track.prints--;
                    track.left = !track.left;
                }
            }
            if (hurt && !reduced && --track.bleed <= 0) {
                float frac = living.getHealth() / max;
                track.bleed = (int) Mth.lerp(frac * 2.0F, 4.0F, 20.0F) + RANDOM.nextInt(6);
                BloodType type = BloodTypes.of(living);
                AABB box = living.getBoundingBox();
                double x = Mth.lerp(0.25 + 0.5 * RANDOM.nextDouble(), box.minX, box.maxX);
                double y = Mth.lerp(0.3 + 0.4 * RANDOM.nextDouble(), box.minY, box.maxY);
                double z = Mth.lerp(0.25 + 0.5 * RANDOM.nextDouble(), box.minZ, box.maxZ);
                Vec3 motion = living.getDeltaMovement();
                GoreFx.spawn(level, x, y, z, motion.x * 0.5, -0.01, motion.z * 0.5, 0.03F + 0.025F * RANDOM.nextFloat(), 60, type, GoreFx.DRIP, 0);
            }
        }
        if ((clock & 63) == 0) {
            for (ObjectIterator<Int2ObjectMap.Entry<Track>> it = TRACKS.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
                Track track = it.next().getValue();
                if (clock - track.seen > 40) it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACKS.clear();
    }
}
