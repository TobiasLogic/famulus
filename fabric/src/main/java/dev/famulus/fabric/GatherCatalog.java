package dev.famulus.fabric;

import java.util.Set;
import java.util.TreeSet;

public final class GatherCatalog {
    private static final Set<String> SUPPORTED = Set.of(
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:pale_oak_log",
            "minecraft:dirt", "minecraft:sand", "minecraft:red_sand");

    private GatherCatalog() {}

    public static boolean supports(String itemId) { return SUPPORTED.contains(itemId); }
    public static Set<String> items() { return new TreeSet<>(SUPPORTED); }
}
