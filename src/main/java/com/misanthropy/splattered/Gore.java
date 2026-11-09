package com.misanthropy.splattered;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.IdentityHashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID)
public final class Gore {

    private static final double RANGE = 48.0;
    private static final Map<LivingEntity, Pending> PENDING = new IdentityHashMap<>();

    private Gore() {
    }

    private static final class Pending {
        Vec3 at;
        Vec3 dir;
        float amount;
        byte flags;
        boolean overkill;
    }

    public static void spray(LivingEntity victim, Vec3 at, Vec3 dir, float amount, boolean heavy) {
        if (victim.level().isClientSide || !BloodTypes.of(victim).bleeds()) return;
        Pending pending = merge(victim, at, dir, amount);
        if (heavy) pending.flags |= GoreNetwork.S2CGore.HEAVY;
    }

    public static void burst(LivingEntity victim, Vec3 dir, float power) {
        if (victim.level().isClientSide || !BloodTypes.of(victim).bleeds()) return;
        Pending pending = merge(victim, victim.getBoundingBox().getCenter(), dir, power);
        pending.flags |= GoreNetwork.S2CGore.BURST;
    }

    private static Pending merge(LivingEntity victim, Vec3 at, Vec3 dir, float amount) {
        Pending pending = PENDING.get(victim);
        if (pending == null) {
            pending = new Pending();
            pending.at = at;
            pending.dir = dir;
            pending.amount = amount;
            PENDING.put(victim, pending);
            return pending;
        }
        if (amount > pending.amount) {
            pending.at = at;
            pending.dir = dir;
        }
        pending.amount = Math.min(60.0F, Math.max(pending.amount, amount) + 0.35F * Math.min(pending.amount, amount));
        return pending;
    }

    private static boolean bloodless(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_DROWNING) || source.is(DamageTypeTags.IS_FREEZING)
                || source.is(DamageTypeTags.IS_LIGHTNING) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.STARVE) || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING)
                || source.is(DamageTypes.DRY_OUT) || source.is(DamageTypes.OUTSIDE_BORDER) || source.is(DamageTypes.GENERIC_KILL)
                || source.is(DamageTypes.SONIC_BOOM);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        float amount = event.getAmount();
        if (event.isCanceled() || amount < 0.3F || victim.level().isClientSide) return;
        DamageSource source = event.getSource();
        if (bloodless(source) || !BloodTypes.of(victim).bleeds()) return;
        Vec3[] hit = locate(victim, source);
        Pending pending = merge(victim, hit[0], hit[1], amount);
        if (amount - victim.getHealth() >= Math.max(6.0F, victim.getMaxHealth() * 0.5F)) pending.overkill = true;
        if (source.is(DamageTypeTags.IS_FALL) && amount >= victim.getHealth()) pending.overkill = true;
        if (source.is(DamageTypeTags.IS_EXPLOSION)) pending.flags |= GoreNetwork.S2CGore.HEAVY;
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != victim && !(direct instanceof Projectile) && direct == source.getEntity()) pending.flags |= GoreNetwork.S2CGore.MELEE;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || victim.level().isClientSide) return;
        Pending pending = PENDING.get(victim);
        if (pending == null) return;
        pending.flags |= GoreNetwork.S2CGore.KILL;
        if (pending.overkill || (pending.flags & GoreNetwork.S2CGore.HEAVY) != 0) pending.flags |= GoreNetwork.S2CGore.BURST;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        for (Map.Entry<LivingEntity, Pending> entry : PENDING.entrySet()) {
            send(entry.getKey(), entry.getValue());
        }
        PENDING.clear();
    }

    private static void send(LivingEntity victim, Pending pending) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        Vec3 dir = pending.dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : pending.dir.normalize();
        GoreNetwork.S2CGore packet = new GoreNetwork.S2CGore(victim.getId(), victim.getX(), victim.getY(), victim.getZ(),
                (float) (pending.at.x - victim.getX()), (float) (pending.at.y - victim.getY()), (float) (pending.at.z - victim.getZ()),
                pack(dir.x), pack(dir.y), pack(dir.z), pending.amount, pending.flags, level.random.nextInt());
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(pending.at) < RANGE * RANGE) GoreNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet);
        }
    }

    private static byte pack(double value) {
        return (byte) Mth.clamp(Math.round(value * 127.0), -127, 127);
    }

    private static Vec3[] locate(LivingEntity victim, DamageSource source) {
        AABB box = victim.getBoundingBox();
        Vec3 center = box.getCenter();
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != victim) {
            if (direct instanceof Projectile) {
                Vec3 motion = direct.getDeltaMovement();
                Vec3 dir = motion.lengthSqr() > 1.0E-4 ? motion.normalize() : center.subtract(direct.position()).normalize();
                Vec3 at = box.inflate(0.2).clip(direct.position().subtract(dir.scale(3.0)), direct.position().add(dir.scale(3.0))).orElse(nearest(box, direct.position()));
                return new Vec3[]{at, dir};
            }
            Vec3 eye = direct.getEyePosition();
            Vec3 at = box.inflate(0.15).clip(eye, eye.add(direct.getLookAngle().scale(8.0))).orElse(null);
            if (at == null) at = nearest(box, eye);
            Vec3 flat = at.subtract(eye);
            flat = new Vec3(flat.x, 0.0, flat.z);
            Vec3 dir = flat.lengthSqr() < 1.0E-4 ? direct.getLookAngle() : flat.normalize().add(0.0, 0.25, 0.0);
            return new Vec3[]{at, dir};
        }
        Vec3 from = source.getSourcePosition();
        if (from != null && from.distanceToSqr(center) > 1.0E-4) {
            Vec3 dir = center.subtract(from).normalize();
            return new Vec3[]{box.clip(from, center).orElse(center), dir};
        }
        if (source.is(DamageTypeTags.IS_FALL)) return new Vec3[]{new Vec3(center.x, box.minY + 0.1, center.z), new Vec3(0.0, -1.0, 0.0)};
        double angle = victim.getRandom().nextDouble() * Math.PI * 2.0;
        return new Vec3[]{new Vec3(center.x, box.minY + box.getYsize() * 0.7, center.z), new Vec3(Math.cos(angle), 0.3, Math.sin(angle))};
    }

    private static Vec3 nearest(AABB box, Vec3 point) {
        return new Vec3(Mth.clamp(point.x, box.minX, box.maxX), Mth.clamp(point.y, box.minY + box.getYsize() * 0.35, box.maxY), Mth.clamp(point.z, box.minZ, box.maxZ));
    }
}
