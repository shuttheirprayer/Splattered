package com.misanthropy.splattered.mixin.client;

import com.misanthropy.splattered.client.Corpses;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class GoreDeathPoofMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true)
    private void splattered$skipGorePoof(byte id, CallbackInfo ci) {
        if (id == 60 && Corpses.hidden(((LivingEntity) (Object) this).getId())) ci.cancel();
    }
}
