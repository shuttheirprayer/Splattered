package com.misanthropy.splattered.client;

import com.misanthropy.splattered.SplatteredConfig;
import com.misanthropy.splattered.Splattered;
import com.misanthropy.splattered.BloodType;
import com.misanthropy.splattered.BloodTypes;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class Wounds {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation STEVE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final ResourceLocation[] FAILED = new ResourceLocation[0];
    private static final ResourceLocation[] PENDING = new ResourceLocation[0];
    private static final Map<ResourceLocation, ResourceLocation[][]> CACHE = new HashMap<>();
    private static final ResourceLocation[][] PLAYER = new ResourceLocation[BloodType.ALL.length][];
    private static final double RANGE = 32.0;
    private static long lastBuild;

    private Wounds() {
    }

    static final class Layer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

        Layer(RenderLayerParent<T, M> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack stack, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbAmount, float partial, float age, float netHead, float pitch) {
            if (!SplatteredConfig.ENABLED.get()) return;
            M model = this.getParentModel();
            boolean player = model instanceof PlayerModel<?>;
            if (Corpses.pending(entity.getId())) Corpses.capture(entity, model, stack, this.getTextureLocation(entity), player);
            if (entity.isInvisible() || SplatteredConfig.MILD.get()) return;
            float max = entity.getMaxHealth();
            float health = entity.getHealth();
            if (max <= 0.0F || health >= max * 0.92F) return;
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (entity.distanceToSqr(cam) > RANGE * RANGE) return;
            BloodType type = BloodTypes.of(entity);
            if (!type.stains()) return;
            int level = Mth.clamp((int) ((1.0F - health / max) * 4.0F - 0.3F), 0, 3);
            ResourceLocation wound = texture(this.getTextureLocation(entity), type, level, player);
            if (wound == null) return;
            model.renderToBuffer(stack, buffers.getBuffer(RenderType.entityCutoutNoCull(wound)), light, LivingEntityRenderer.getOverlayCoords(entity, 0.0F), 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    static ResourceLocation texture(ResourceLocation base, BloodType type, int level, boolean player) {
        ResourceLocation[][] byType = player ? PLAYER : CACHE.computeIfAbsent(base, k -> new ResourceLocation[BloodType.ALL.length][]);
        int slot = type.ordinal();
        ResourceLocation[] built = byType[slot];
        if (built == null) {
            long now = Util.getMillis();
            if (now - lastBuild < 20L) return null;
            lastBuild = now;
            byType[slot] = PENDING;
            ResourceLocation source = player ? STEVE : base;
            String key = (player ? "player" : base.toString()) + "#" + slot;
            CompletableFuture.supplyAsync(() -> paint(source, type, player, key), Util.backgroundExecutor())
                    .thenAcceptAsync(images -> byType[slot] = register(source, type, images), Minecraft.getInstance());
            return null;
        }
        return built.length == 0 ? null : built[Mth.clamp(level, 0, built.length - 1)];
    }

    private static ResourceLocation[] register(ResourceLocation base, BloodType type, NativeImage[] images) {
        if (images == null) return FAILED;
        ResourceLocation[] out = new ResourceLocation[images.length];
        String path = "wounds/" + base.getNamespace() + "/" + base.getPath().replace(".png", "") + "_" + type.ordinal();
        for (int k = 0; k < images.length; k++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, path + "_" + k);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(images[k]));
            out[k] = id;
        }
        return out;
    }

    private static NativeImage[] paint(ResourceLocation base, BloodType type, boolean player, String key) {
        NativeImage source;
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(base);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open()) {
                source = NativeImage.read(in);
            }
        } catch (Exception e) {
            LOGGER.debug("Gore wound mask unavailable for {}", base, e);
            return null;
        }
        try {
            int w = source.getWidth();
            int h = source.getHeight();
            if (w > 512 || h > 512 || w < 16 || h < 16) return null;
            boolean[] mask = new boolean[w * h];
            int open = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean solid = (source.getPixelRGBA(x, y) >>> 24) > 127;
                    if (player && outer(x, y, w, h)) solid = false;
                    mask[y * w + x] = solid;
                    if (solid) open++;
                }
            }
            if (open < 32) return null;
            NativeImage[] images = new NativeImage[4];
            for (int k = 0; k < 4; k++) {
                images[k] = new NativeImage(w, h, true);
                images[k].fillRect(0, 0, w, h, 0);
            }
            float scale = w / 64.0F;
            int[] shades = shades(type);
            RandomSource random = RandomSource.create(key.hashCode());
            int wounds = Mth.clamp(open / 70, 6, 60);
            for (int n = 0; n < wounds; n++) {
                int at = -1;
                for (int tries = 0; tries < 24 && at < 0; tries++) {
                    int i = random.nextInt(w * h);
                    if (mask[i]) at = i;
                }
                if (at < 0) continue;
                int from = Math.min(3, (int) (random.nextFloat() * 4.0F));
                int cx = at % w;
                int cy = at / w;
                boolean gash = random.nextBoolean();
                for (int k = from; k < 4; k++) {
                    RandomSource shape = RandomSource.create(key.hashCode() * 31L + n);
                    if (gash) gash(images[k], mask, w, h, cx, cy, scale, shades, shape);
                    else splotch(images[k], mask, w, h, cx, cy, scale, shades, shape);
                }
            }
            return images;
        } catch (RuntimeException e) {
            LOGGER.debug("Gore wound build failed for {}", base, e);
            return null;
        } finally {
            source.close();
        }
    }

    private static boolean outer(int x, int y, int w, int h) {
        int px = x * 64 / w;
        int py = y * 64 / h;
        if (py < 16) return px >= 32;
        if (py < 32) return false;
        if (py < 48) return true;
        return px < 16 || px >= 48;
    }

    private static int[] shades(BloodType type) {
        int c = type.wet;
        return new int[]{abgr(c, 1.0F), abgr(c, 0.7F), abgr(c, 0.39F)};
    }

    private static int abgr(int c, float k) {
        int r = Math.min(255, (int) ((c >> 16 & 255) * k));
        int g = Math.min(255, (int) ((c >> 8 & 255) * k));
        int b = Math.min(255, (int) ((c & 255) * k));
        return 0xFF000000 | b << 16 | g << 8 | r;
    }

    private static void set(NativeImage image, boolean[] mask, int w, int h, int x, int y, int color, boolean over) {
        if (x < 0 || y < 0 || x >= w || y >= h || !mask[y * w + x]) return;
        if (!over && (image.getPixelRGBA(x, y) >>> 24) != 0) return;
        image.setPixelRGBA(x, y, color);
    }

    private static void gash(NativeImage image, boolean[] mask, int w, int h, int cx, int cy, float scale, int[] shades, RandomSource random) {
        int length = Math.max(2, Math.round((2 + random.nextInt(4)) * scale));
        int dx = random.nextInt(3) - 1;
        int dy = dx == 0 ? 1 : random.nextInt(3) - 1;
        if (random.nextBoolean()) {
            dx = 1;
            dy = random.nextInt(3) - 1;
        }
        for (int i = -length / 2; i <= length / 2; i++) {
            int x = cx + dx * i;
            int y = cy + dy * i;
            set(image, mask, w, h, x - dy, y + dx, shades[0], false);
            set(image, mask, w, h, x + dy, y - dx, shades[0], false);
            set(image, mask, w, h, x, y, shades[2], true);
        }
        drip(image, mask, w, h, cx, cy + 1, scale, shades, random);
    }

    private static void splotch(NativeImage image, boolean[] mask, int w, int h, int cx, int cy, float scale, int[] shades, RandomSource random) {
        float r = (1.2F + 1.4F * random.nextFloat()) * Math.max(1.0F, scale);
        int reach = Mth.ceil(r);
        for (int y = -reach; y <= reach; y++) {
            for (int x = -reach; x <= reach; x++) {
                float d = Mth.sqrt(x * x + y * y) / r;
                if (d > 1.0F) continue;
                int shade = d < 0.45F ? 2 : d < 0.8F ? 1 : 0;
                set(image, mask, w, h, cx + x, cy + y, shades[shade], true);
            }
        }
        int drips = random.nextInt(3);
        for (int i = 0; i < drips; i++) drip(image, mask, w, h, cx + random.nextInt(reach * 2 + 1) - reach, cy + reach, scale, shades, random);
    }

    private static void drip(NativeImage image, boolean[] mask, int w, int h, int x, int y, float scale, int[] shades, RandomSource random) {
        int length = Math.round((1 + random.nextInt(5)) * Math.max(1.0F, scale));
        for (int i = 0; i < length; i++) {
            if (y + i >= h || !mask[(y + i) * w + x]) break;
            set(image, mask, w, h, x, y + i, shades[i == length - 1 ? 2 : 1], true);
        }
    }
}
