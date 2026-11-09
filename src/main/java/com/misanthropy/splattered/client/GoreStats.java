package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.BloodType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, value = Dist.CLIENT)
public final class GoreStats {

    private static long renderNs;
    private static long renderFrames;
    private static long tickNs;
    private static long ticks;

    private GoreStats() {
    }

    static void frame(long ns) {
        renderNs += ns;
        renderFrames++;
    }

    static void tick(long ns) {
        tickNs += ns;
        ticks++;
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("splattered")
                .then(Commands.literal("stats").executes(context -> {
                    say(String.format(Locale.ROOT, "Gore: %d drops, %d chunks, %d stained faces. Draw %.1f us per frame over %d frames, tick %.1f us over %d ticks.",
                            GoreFx.size(), Chunks.size() + Corpses.size(), Stains.size(),
                            renderFrames == 0 ? 0.0 : renderNs / 1000.0 / renderFrames, renderFrames,
                            ticks == 0 ? 0.0 : tickNs / 1000.0 / ticks, ticks));
                    renderNs = renderFrames = tickNs = ticks = 0;
                    return 1;
                }))
                .then(Commands.literal("test").then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(BloodType.ALL).map(t -> t.name().toLowerCase(Locale.ROOT)), builder))
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "type").toUpperCase(Locale.ROOT);
                            BloodType type = Arrays.stream(BloodType.ALL).filter(t -> t.name().equals(name)).findFirst().orElse(BloodType.RED);
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.player == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                                say("Look at a block first.");
                                return 0;
                            }
                            Vec3 eye = mc.player.getEyePosition();
                            Vec3 at = eye.add(hit.getLocation().subtract(eye).scale(0.6));
                            GoreFx.spray(at, hit.getLocation().subtract(eye), 9.0F, type);
                            return 1;
                        }))));
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
    }
}
