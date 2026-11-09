package com.misanthropy.splattered;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Splattered.MOD_ID)
public final class BloodTypes {

    private static final Map<EntityType<?>, BloodType> CACHE = new ConcurrentHashMap<>();
    private static final TagKey<EntityType<?>>[] TAGS = tags();
    private static final Object[][] WORDS = {
            {BloodType.BONE, Set.of("skeleton", "skeletal", "bone", "bones", "skull", "lich", "bonewheel")},
            {BloodType.NONE, Set.of("golem", "construct", "automaton", "robot", "sentinel", "mech", "turret", "statue", "gargoyle", "elemental", "totem", "drone", "armor", "armour", "stand", "vex", "allay", "dummy", "mannequin", "scarecrow")},
            {BloodType.ECTO, Set.of("ghost", "spirit", "wraith", "phantom", "specter", "spectre", "soul", "wisp", "banshee", "poltergeist", "shade", "apparition")},
            {BloodType.SLIME, Set.of("slime", "ooze", "jelly", "blob", "gel", "goo", "sludge")},
            {BloodType.MAGMA, Set.of("magma", "blaze", "ember", "inferno", "lava", "flame", "fire", "molten", "cinder", "ignis")},
            {BloodType.SCULK, Set.of("sculk", "warden")},
            {BloodType.ENDER, Set.of("ender", "enderman", "endermite", "void", "end", "shulker")},
            {BloodType.INK, Set.of("squid", "octopus", "kraken", "cuttlefish", "ink")},
            {BloodType.ICHOR, Set.of("spider", "scorpion", "ant", "beetle", "mantis", "wasp", "bee", "hornet", "fly", "tick", "insect", "centipede", "cockroach", "roach", "termite", "locust", "moth", "larva", "maggot", "worm", "leech", "mosquito", "crab")},
            {BloodType.ROTTEN, Set.of("zombie", "zombified", "ghoul", "rotten", "undead", "mummy", "drowned", "husk", "corpse", "revenant", "zoglin", "rotting", "decayed")},
            {BloodType.SAP, Set.of("creeper", "plant", "treant", "ent", "mandrake", "flower", "vine", "sprout", "mushroom", "shroom", "fungus", "root")},
    };

    private BloodTypes() {
    }

    @SuppressWarnings("unchecked")
    private static TagKey<EntityType<?>>[] tags() {
        TagKey<EntityType<?>>[] out = new TagKey[BloodType.ALL.length];
        for (BloodType type : BloodType.ALL) {
            out[type.ordinal()] = TagKey.create(Registries.ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Splattered.MOD_ID, "blood/" + type.name().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    public static BloodType of(Entity entity) {
        BloodType known = CACHE.get(entity.getType());
        if (known != null) return known;
        BloodType found = resolve(entity);
        CACHE.put(entity.getType(), found);
        return found;
    }

    private static BloodType resolve(Entity entity) {
        EntityType<?> type = entity.getType();
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (id != null && SplatteredConfig.blacklisted(id)) return BloodType.NONE;
        if (entity instanceof Player) return BloodType.RED;
        for (BloodType blood : BloodType.ALL) {
            if (type.is(TAGS[blood.ordinal()])) return blood;
        }
        if (!(entity instanceof LivingEntity living)) return BloodType.NONE;
        if (id != null) {
            String[] parts = id.getPath().split("[_/.]");
            for (Object[] row : WORDS) {
                @SuppressWarnings("unchecked") Set<String> words = (Set<String>) row[1];
                for (String part : parts) {
                    if (words.contains(part)) return (BloodType) row[0];
                }
            }
        }
        MobType mob = living.getMobType();
        if (mob == MobType.UNDEAD) return BloodType.ROTTEN;
        if (mob == MobType.ARTHROPOD) return BloodType.ICHOR;
        return BloodType.RED;
    }

    @SubscribeEvent
    public static void onTags(TagsUpdatedEvent event) {
        CACHE.clear();
    }

    static void clearCache() {
        CACHE.clear();
    }
}
