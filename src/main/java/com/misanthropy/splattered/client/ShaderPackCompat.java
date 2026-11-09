package com.misanthropy.splattered.client;

import java.lang.reflect.Method;
import com.misanthropy.splattered.SplatteredConfig;
import net.minecraft.Util;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

@OnlyIn(Dist.CLIENT)
public final class ShaderPackCompat {

    private static final long REFRESH_MILLIS = 1000L;

    private static boolean initialized;
    private static Object api;
    private static Method inUse;
    private static boolean cached;
    private static long checkedAt;
    private static boolean checkedOnce;

    private ShaderPackCompat() {
    }

    public static boolean isShaderPackActive() {
        long now = Util.getMillis();
        if (!checkedOnce || now - checkedAt >= REFRESH_MILLIS) {
            checkedOnce = true;
            checkedAt = now;
            cached = query();
        }
        return cached;
    }

    public static boolean reducedVisuals() {
        return SplatteredConfig.REDUCED_VISUALS.get() || isShaderPackActive() || Minecraft.getInstance().options.graphicsMode().get() == GraphicsStatus.FAST;
    }

    private static boolean query() {
        if (!initialized) {
            initialized = true;
            if (ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris")) {
                try {
                    Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, ShaderPackCompat.class.getClassLoader());
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    inUse = null;
                }
            }
        }
        if (inUse == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(inUse.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException e) {
            inUse = null;
            return false;
        }
    }
}
