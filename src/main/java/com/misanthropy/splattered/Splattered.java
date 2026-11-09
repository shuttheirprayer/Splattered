package com.misanthropy.splattered;

import com.misanthropy.splattered.api.TimeHook;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Splattered.MOD_ID)
public class Splattered {
    public static final String MOD_ID = "splattered";

    private static TimeHook time = new TimeHook() {
    };

    public Splattered() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SplatteredConfig.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener((ModConfigEvent event) -> BloodTypes.clearCache());
    }

    public static TimeHook time() {
        return time;
    }

    public static void setTime(TimeHook hook) {
        time = hook == null ? new TimeHook() {
        } : hook;
    }
}
