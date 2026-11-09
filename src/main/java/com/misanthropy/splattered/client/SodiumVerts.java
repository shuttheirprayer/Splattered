package com.misanthropy.splattered.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class SodiumVerts {

    private static final int CAPACITY = 1024;
    private static final int SOAK_STRIDE = 24;
    private static final long BUFFER = MemoryUtil.nmemAlloc((long) CAPACITY * ModelVertex.STRIDE);

    private static VertexFormatDescription soakFormat;
    private static VertexBufferWriter writer;
    private static boolean soak;
    private static int pending;

    private SodiumVerts() {
    }

    static boolean begin(VertexConsumer consumer, boolean soakPass) {
        soak = soakPass;
        pending = 0;
        writer = VertexBufferWriter.tryOf(consumer);
        if (soakPass && writer != null && soakFormat == null) soakFormat = VertexFormatRegistry.instance().get(DefaultVertexFormat.POSITION_COLOR_TEX);
        return writer != null;
    }

    static void end() {
        flush();
        writer = null;
    }

    static void put(float px, float py, float pz, int color, float u, float v, int light, int normal) {
        ModelVertex.write(BUFFER + (long) pending * ModelVertex.STRIDE, px, py, pz, color, u, v, OverlayTexture.NO_OVERLAY, light, normal);
        if (++pending == CAPACITY) flush();
    }

    static void putSoak(float px, float py, float pz, float u, float v) {
        long p = BUFFER + (long) pending * SOAK_STRIDE;
        MemoryUtil.memPutFloat(p, px);
        MemoryUtil.memPutFloat(p + 4, py);
        MemoryUtil.memPutFloat(p + 8, pz);
        MemoryUtil.memPutInt(p + 12, -1);
        MemoryUtil.memPutFloat(p + 16, u);
        MemoryUtil.memPutFloat(p + 20, v);
        if (++pending == CAPACITY) flush();
    }

    private static void flush() {
        if (pending == 0 || writer == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            writer.push(stack, BUFFER, pending, soak ? soakFormat : ModelVertex.FORMAT);
        }
        pending = 0;
    }
}
