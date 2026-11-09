package com.misanthropy.splattered.client;

import com.misanthropy.splattered.Splattered;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class GoreAtlas {

    static final int SIZE = 1024;
    static final int TILE = 16;
    static final int ROW = SIZE / TILE;
    static final int WHITE = 0;
    static final int MIST = 1;
    static final int MUSCLE = 2;
    static final int FAT = 3;
    static final int CLOT = 4;
    static final int BONE = 5;
    static final int RESERVED = 6;
    static final int SLOTS = 2048;
    static final float TEXEL = 1.0F / SIZE;

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, "gore_atlas");
    private static final ResourceLocation SOAK_ID = ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, "gore_soak");
    private static final int[][] MEAT = {
            {158, 40, 44}, {124, 18, 24}, {92, 10, 16}, {58, 5, 10}, {36, 2, 6},
            {206, 184, 142}, {176, 146, 104}, {214, 204, 180}, {172, 160, 134}
    };

    private static DynamicTexture texture;
    private static DynamicTexture soak;
    private static RenderType type;
    private static RenderType soakType;

    private GoreAtlas() {
    }

    static RenderType type() {
        if (type == null) init();
        return type;
    }

    static RenderType soakType() {
        if (soakType == null) init();
        return soakType;
    }

    static NativeImage image() {
        if (texture == null) init();
        return texture.getPixels();
    }

    static NativeImage soakImage() {
        if (soak == null) init();
        return soak.getPixels();
    }

    static void upload(int tile) {
        int x = tileX(tile);
        int y = tileY(tile);
        texture.bind();
        texture.getPixels().upload(0, x, y, x, y, TILE, TILE, false, false);
        soak.bind();
        soak.getPixels().upload(0, x, y, x, y, TILE, TILE, false, false);
    }

    static int tileX(int tile) {
        return (tile % ROW) * TILE;
    }

    static int tileY(int tile) {
        return (tile / ROW) * TILE;
    }

    static float u(int tile, float px) {
        return (tileX(tile) + px) * TEXEL;
    }

    static float v(int tile, float px) {
        return (tileY(tile) + px) * TEXEL;
    }

    static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    private static int abgr(int[] c, float k, float alpha) {
        int r = Mth.clamp((int) (c[0] * k), 0, 255);
        int g = Mth.clamp((int) (c[1] * k), 0, 255);
        int b = Mth.clamp((int) (c[2] * k), 0, 255);
        return Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24 | b << 16 | g << 8 | r;
    }

    private static int[] meat(int kind, int x, int y) {
        float fibre = Mth.sin(x * 0.9F + y * 0.45F + Mth.sin(y * 0.3F) * 2.0F);
        float n = hash(x, y, kind * 31 + 5);
        return switch (kind) {
            case MUSCLE -> fibre > 0.75F ? MEAT[0] : fibre > 0.1F ? MEAT[1] : fibre > -0.7F ? MEAT[2] : MEAT[3];
            case FAT -> Mth.sin(x * 0.55F) * Mth.cos(y * 0.6F + x * 0.2F) > 0.35F ? (n < 0.3F ? MEAT[6] : MEAT[5]) : fibre > 0.3F ? MEAT[1] : MEAT[2];
            case CLOT -> n < 0.08F ? MEAT[2] : fibre > 0.6F ? MEAT[3] : MEAT[4];
            default -> y % 6 == 0 && n < 0.6F ? MEAT[8] : n < 0.12F ? MEAT[1] : MEAT[7];
        };
    }

    private static void init() {
        NativeImage image = new NativeImage(SIZE, SIZE, true);
        image.fillRect(0, 0, SIZE, SIZE, 0);
        float c = (TILE - 1) * 0.5F;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                image.setPixelRGBA(tileX(WHITE) + x, tileY(WHITE) + y, 0xFFFFFFFF);
                float dx = (x - c) / (c + 0.5F);
                float dy = (y - c) / (c + 0.5F);
                float r2 = dx * dx + dy * dy;
                float soft = r2 >= 1.0F ? 0.0F : (1.0F - r2) * Mth.sqrt(1.0F - r2);
                image.setPixelRGBA(tileX(MIST) + x, tileY(MIST) + y, abgr(new int[]{255, 255, 255}, 1.0F, soft * (0.7F + 0.3F * hash(x / 3, y / 3, 23))));
                for (int kind = MUSCLE; kind <= BONE; kind++) {
                    float shade = 0.92F + 0.16F * hash(x, y, kind);
                    image.setPixelRGBA(tileX(kind) + x, tileY(kind) + y, abgr(meat(kind, x, y), shade, 1.0F));
                }
            }
        }
        texture = new DynamicTexture(image);
        NativeImage soaked = new NativeImage(SIZE, SIZE, true);
        soaked.fillRect(0, 0, SIZE, SIZE, 0);
        soak = new DynamicTexture(soaked);
        Minecraft.getInstance().getTextureManager().register(ID, texture);
        Minecraft.getInstance().getTextureManager().register(SOAK_ID, soak);
        type = RenderType.entityTranslucentCull(ID);
        soakType = Types.soak(SOAK_ID);
    }

    private static final class Types extends RenderType {

        private static final TransparencyStateShard MULTIPLY = new TransparencyStateShard("splattered_gore_multiply", () -> {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.DST_COLOR,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO,
                    GlStateManager.DestFactor.ONE);
        }, () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        });

        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }

        static RenderType soak(ResourceLocation texture) {
            return create("splattered_gore_soak", DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS, 1 << 16, false, false,
                    CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_TEX_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(MULTIPLY)
                            .setCullState(RenderStateShard.CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
    }
}
