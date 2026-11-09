package com.misanthropy.splattered.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class GoreVerts {

    public static boolean fast = true;
    public static boolean cull = true;

    private static final boolean AVAILABLE = ModList.get().isLoaded("embeddium");
    private static final float NORM = 1.0F / 127.0F;

    private static VertexConsumer out;
    private static boolean direct;
    private static Frustum frustum;
    private static double camX;
    private static double camY;
    private static double camZ;

    private GoreVerts() {
    }

    static void frame(Frustum current, Vec3 cam) {
        frustum = current;
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
    }

    static boolean visible(float x, float y, float z, float radius) {
        if (!cull || frustum == null) return true;
        return frustum.isVisible(new AABB(camX + x - radius, camY + y - radius, camZ + z - radius, camX + x + radius, camY + y + radius, camZ + z + radius));
    }

    static void begin(VertexConsumer consumer, boolean soakPass) {
        out = consumer;
        direct = AVAILABLE && fast && !ShaderPackCompat.isShaderPackActive() && SodiumVerts.begin(consumer, soakPass);
    }

    static void end() {
        if (direct) SodiumVerts.end();
        out = null;
        direct = false;
    }

    static int color(float r, float g, float b, float a) {
        return ((int) (a * 255.0F) & 255) << 24 | ((int) (b * 255.0F) & 255) << 16 | ((int) (g * 255.0F) & 255) << 8 | (int) (r * 255.0F) & 255;
    }

    private static int encode(float v) {
        return (int) (Mth.clamp(v, -1.0F, 1.0F) * 127.0F) & 255;
    }

    static int normal(Matrix3f m, float x, float y, float z) {
        return encode(m.m00 * x + m.m10 * y + m.m20 * z) | encode(m.m01 * x + m.m11 * y + m.m21 * z) << 8 | encode(m.m02 * x + m.m12 * y + m.m22 * z) << 16;
    }

    static void put(Matrix4f m, float x, float y, float z, int color, float u, float v, int light, int normal) {
        float px = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
        float py = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
        float pz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
        if (!direct) {
            out.vertex(px, py, pz).color(color & 255, color >>> 8 & 255, color >>> 16 & 255, color >>> 24).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                    .normal((byte) normal * NORM, (byte) (normal >> 8) * NORM, (byte) (normal >> 16) * NORM).endVertex();
            return;
        }
        SodiumVerts.put(px, py, pz, color, u, v, light, normal);
    }

    static void putSoak(Matrix4f m, float x, float y, float z, float u, float v) {
        float px = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
        float py = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
        float pz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
        if (!direct) {
            out.vertex(px, py, pz).color(255, 255, 255, 255).uv(u, v).endVertex();
            return;
        }
        SodiumVerts.putSoak(px, py, pz, u, v);
    }
}
