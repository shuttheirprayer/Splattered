package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

// I ain't sfx artist bro don't shame me.. though I will get some official sounds sometime.
enum GoreSounds {
    HIT(SoundEvents.SLIME_SQUISH_SMALL, "fromtheshadows:goresound"),
    SPLAT(SoundEvents.SLIME_SQUISH_SMALL, "goety:tropical_slime_small_squish"),
    BURST(SoundEvents.SLIME_DEATH, "irons_spellbooks:spell.sacrifice.blood_explosion", "goety:tropical_slime_large_death"),
    DRIP_HEAVY(own("gore.drip_heavy")),
    DRIP_LIGHT(own("gore.drip_light"));

    private static final double RANGE = 24.0;
    private static final int SPLATS_PER_SECOND = 6;
    private static final int DRIPS_PER_SECOND = 4;
    private static long second;
    private static int splats;
    private static int drips;

    private final SoundEvent fallback;
    private final String[] ids;
    private @Nullable List<SoundEvent> found;

    GoreSounds(SoundEvent fallback, String... ids) {
        this.fallback = fallback;
        this.ids = ids;
    }

    private static SoundEvent own(String path) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, path));
    }

    private SoundEvent pick(RandomSource random) {
        if (this.found == null) {
            this.found = Arrays.stream(this.ids)
                    .map(ResourceLocation::tryParse)
                    .filter(id -> id != null && ForgeRegistries.SOUND_EVENTS.containsKey(id))
                    .map(ForgeRegistries.SOUND_EVENTS::getValue)
                    .toList();
        }
        return this.found.isEmpty() ? this.fallback : this.found.get(random.nextInt(this.found.size()));
    }

    void play(ClientLevel level, double x, double y, double z, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized() || camera.getPosition().distanceToSqr(x, y, z) > RANGE * RANGE) return;
        if (this == SPLAT || this == DRIP_HEAVY || this == DRIP_LIGHT) {
            long now = Util.getMillis() / 1000L;
            if (now != second) {
                second = now;
                splats = 0;
                drips = 0;
            }
            if (this == SPLAT ? ++splats > SPLATS_PER_SECOND : ++drips > DRIPS_PER_SECOND) return;
        }
        level.playLocalSound(x, y, z, pick(level.random), SoundSource.NEUTRAL, volume, pitch, false);
    }
}
