package com.misanthropy.splattered;

import com.misanthropy.splattered.client.GoreFx;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GoreNetwork {

    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, "gore"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private GoreNetwork() {
    }

    @SubscribeEvent
    public static void onSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> CHANNEL.messageBuilder(S2CGore.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .decoder(S2CGore::new)
                .encoder(S2CGore::toBytes)
                .consumerMainThread(S2CGore::handle)
                .add());
    }

    public record S2CGore(int entity, double x, double y, double z, float ox, float oy, float oz, byte dx, byte dy, byte dz, float amount, byte flags, int seed) {

        public static final byte HEAVY = 1;
        public static final byte KILL = 2;
        public static final byte BURST = 4;
        public static final byte MELEE = 8;

        public S2CGore(@NonNull FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readFloat(), buf.readByte(), buf.readInt());
        }

        public void toBytes(@NonNull FriendlyByteBuf buf) {
            buf.writeVarInt(this.entity);
            buf.writeDouble(this.x);
            buf.writeDouble(this.y);
            buf.writeDouble(this.z);
            buf.writeFloat(this.ox);
            buf.writeFloat(this.oy);
            buf.writeFloat(this.oz);
            buf.writeByte(this.dx);
            buf.writeByte(this.dy);
            buf.writeByte(this.dz);
            buf.writeFloat(this.amount);
            buf.writeByte(this.flags);
            buf.writeInt(this.seed);
        }

        public void handle(@NonNull Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> GoreFx.onPacket(this)));
            context.setPacketHandled(true);
        }
    }
}
