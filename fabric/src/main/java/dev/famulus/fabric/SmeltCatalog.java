package dev.famulus.fabric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SmeltCatalog {
    private static final Map<String, List<String>> INPUTS = inputs();

    private SmeltCatalog() {}

    private static Map<String, List<String>> inputs() {
        Map<String, List<String>> recipes = new LinkedHashMap<>();
        recipes.put("minecraft:iron_ingot", List.of("minecraft:raw_iron",
                "minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
        recipes.put("minecraft:gold_ingot", List.of("minecraft:raw_gold",
                "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"));
        recipes.put("minecraft:copper_ingot", List.of("minecraft:raw_copper",
                "minecraft:copper_ore", "minecraft:deepslate_copper_ore"));
        recipes.put("minecraft:netherite_scrap", List.of("minecraft:ancient_debris"));
        recipes.put("minecraft:glass", List.of("minecraft:sand", "minecraft:red_sand"));
        recipes.put("minecraft:stone", List.of("minecraft:cobblestone"));
        recipes.put("minecraft:smooth_stone", List.of("minecraft:stone"));
        recipes.put("minecraft:deepslate", List.of("minecraft:cobbled_deepslate"));
        recipes.put("minecraft:brick", List.of("minecraft:clay_ball"));
        recipes.put("minecraft:terracotta", List.of("minecraft:clay"));
        recipes.put("minecraft:nether_brick", List.of("minecraft:netherrack"));
        recipes.put("minecraft:smooth_sandstone", List.of("minecraft:sandstone"));
        recipes.put("minecraft:smooth_red_sandstone", List.of("minecraft:red_sandstone"));
        recipes.put("minecraft:smooth_quartz", List.of("minecraft:quartz_block"));
        recipes.put("minecraft:cracked_stone_bricks", List.of("minecraft:stone_bricks"));
        recipes.put("minecraft:cracked_nether_bricks", List.of("minecraft:nether_bricks"));
        recipes.put("minecraft:popped_chorus_fruit", List.of("minecraft:chorus_fruit"));
        recipes.put("minecraft:dried_kelp", List.of("minecraft:kelp"));
        recipes.put("minecraft:sponge", List.of("minecraft:wet_sponge"));
        recipes.put("minecraft:charcoal", List.of("minecraft:oak_log", "minecraft:spruce_log",
                "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log",
                "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
                "minecraft:pale_oak_log"));
        recipes.put("minecraft:cooked_porkchop", List.of("minecraft:porkchop"));
        recipes.put("minecraft:cooked_beef", List.of("minecraft:beef"));
        recipes.put("minecraft:cooked_chicken", List.of("minecraft:chicken"));
        recipes.put("minecraft:cooked_mutton", List.of("minecraft:mutton"));
        recipes.put("minecraft:cooked_rabbit", List.of("minecraft:rabbit"));
        recipes.put("minecraft:cooked_cod", List.of("minecraft:cod"));
        recipes.put("minecraft:cooked_salmon", List.of("minecraft:salmon"));
        recipes.put("minecraft:baked_potato", List.of("minecraft:potato"));
        return Map.copyOf(recipes);
    }

    public static boolean supports(String itemId) {
        return INPUTS.containsKey(itemId);
    }

    public static Set<String> items() {
        return new TreeSet<>(INPUTS.keySet());
    }

    public static List<String> inputsFor(String itemId) {
        List<String> inputs = INPUTS.get(itemId);
        if (inputs == null) {
            throw new IllegalArgumentException("Nothing in the catalog smelts into " + itemId);
        }
        return inputs;
    }

    public static String primaryInputFor(String itemId) {
        return inputsFor(itemId).get(0);
    }

    public static boolean accepts(String itemId, String inputId) {
        List<String> inputs = INPUTS.get(itemId);
        return inputs != null && inputs.contains(inputId);
    }

    public static Map<String, String> recipes() {
        Map<String, String> primary = new TreeMap<>();
        INPUTS.forEach((item, inputs) -> primary.put(item, inputs.get(0)));
        return primary;
    }
}
