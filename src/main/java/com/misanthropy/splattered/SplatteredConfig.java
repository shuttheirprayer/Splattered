package com.misanthropy.splattered;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class SplatteredConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue REDUCED_VISUALS;
    public static final ForgeConfigSpec.BooleanValue MILD;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.comment("Off removes every gore effect: blood, stains, wounds and gibs").define("enabled", true);
        REDUCED_VISUALS = builder.comment("Halves gore particle counts and skips the costliest effects").define("reducedVisuals", false);
        MILD = builder.comment("Some blood on hits and the floor, but no gibs, pools, trails or wounds on mobs").define("mild", false);
        BLACKLIST = builder.comment("Entities that never bleed. Entity IDs like \"minecraft:zombie\", or \"modid:*\" for a whole mod")
                .defineListAllowEmpty("blacklist", List.of(), o -> o instanceof String s && ResourceLocation.isValidResourceLocation(s.replace("*", "x")));
        SPEC = builder.build();
    }

    private SplatteredConfig() {
    }

    public static boolean blacklisted(ResourceLocation id) {
        if (!SPEC.isLoaded()) return false;
        List<? extends String> list = BLACKLIST.get();
        return list.contains(id.toString()) || list.contains(id.getNamespace() + ":*");
    }
}
