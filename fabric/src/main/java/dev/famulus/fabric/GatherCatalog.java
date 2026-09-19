package dev.famulus.fabric;

import dev.famulus.core.PlannedTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class GatherCatalog {
    private static final Map<String, String> DROPS = drops();
    private static final Map<String, String> SOURCES = sources();

    private GatherCatalog() {}

    private static Map<String, String> drops() {
        Map<String, String> blocks = new LinkedHashMap<>();
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "pale_oak")) {
            identity(blocks, wood + "_log");
        }
        for (String plain : List.of("dirt", "coarse_dirt", "rooted_dirt", "mud", "sand", "red_sand",
                "gravel", "netherrack", "soul_sand", "soul_soil", "granite", "diorite", "andesite",
                "tuff", "calcite", "dripstone_block", "basalt", "blackstone", "sandstone",
                "red_sandstone", "obsidian", "ancient_debris", "moss_block", "magma_block")) {
            identity(blocks, plain);
        }
        blocks.put("minecraft:stone", "minecraft:cobblestone");
        blocks.put("minecraft:deepslate", "minecraft:cobbled_deepslate");
        blocks.put("minecraft:grass_block", "minecraft:dirt");
        blocks.put("minecraft:clay", "minecraft:clay_ball");
        blocks.put("minecraft:glowstone", "minecraft:glowstone_dust");
        ore(blocks, "coal", "minecraft:coal");
        ore(blocks, "iron", "minecraft:raw_iron");
        ore(blocks, "copper", "minecraft:raw_copper");
        ore(blocks, "gold", "minecraft:raw_gold");
        ore(blocks, "redstone", "minecraft:redstone");
        ore(blocks, "lapis", "minecraft:lapis_lazuli");
        ore(blocks, "diamond", "minecraft:diamond");
        ore(blocks, "emerald", "minecraft:emerald");
        blocks.put("minecraft:nether_quartz_ore", "minecraft:quartz");
        blocks.put("minecraft:nether_gold_ore", "minecraft:gold_nugget");
        blocks.put("minecraft:amethyst_cluster", "minecraft:amethyst_shard");
        return Map.copyOf(blocks);
    }

    private static void identity(Map<String, String> blocks, String name) {
        blocks.put("minecraft:" + name, "minecraft:" + name);
    }

    private static void ore(Map<String, String> blocks, String name, String item) {
        blocks.put("minecraft:" + name + "_ore", item);
        blocks.put("minecraft:deepslate_" + name + "_ore", item);
    }

    private static Map<String, String> sources() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        DROPS.forEach((block, item) -> grouped.computeIfAbsent(item, any -> new ArrayList<>()).add(block));
        Map<String, String> joined = new LinkedHashMap<>();
        grouped.forEach((item, blocks) -> joined.put(item, String.join(",", blocks)));
        return Map.copyOf(joined);
    }

    public static boolean supports(String itemId) {
        return SOURCES.containsKey(itemId);
    }

    public static Set<String> items() {
        return new TreeSet<>(SOURCES.keySet());
    }

    public static boolean isKnownBlock(String blockId) {
        return DROPS.containsKey(blockId);
    }

    public static Set<String> blocks() {
        return new TreeSet<>(DROPS.keySet());
    }

    public static String dropOf(String blockId) {
        return DROPS.get(blockId);
    }

    public static String blocksFor(String itemId) {
        String blocks = SOURCES.get(itemId);
        if (blocks == null) {
            throw new IllegalArgumentException("Nothing in the catalog drops " + itemId);
        }
        return blocks;
    }

    public static PlannedTask task(String id, String itemId, int count) {
        String blocks = blocksFor(itemId);
        return blocks.equals(itemId)
                ? new PlannedTask.Gather(id, itemId, count)
                : new PlannedTask.Mine(id, blocks, itemId, count);
    }
}
