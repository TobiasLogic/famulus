package dev.famulus.fabric;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolCheck {
    public record Verdict(boolean usable, String message, String suggestedTool) {}

    private ToolCheck() {}

    public static Verdict assess(Minecraft client, String blockList) {
        if (client.player == null) {
            return new Verdict(true, "no player to check", null);
        }
        List<String> unusable = new ArrayList<>();
        String suggestion = null;
        for (String blockId : blockList.split(",")) {
            BlockState state = stateOf(blockId);
            if (state == null) {
                continue;
            }
            if (!state.requiresCorrectToolForDrops() || bestToolSlot(client, state) >= 0) {
                return new Verdict(true, "a usable tool is in the inventory", null);
            }
            unusable.add(blockId + " needs " + requirement(state));
            if (suggestion == null) {
                suggestion = craftableFor(state);
            }
        }
        if (unusable.isEmpty()) {
            return new Verdict(true, "no tool is required", null);
        }
        return new Verdict(false, "no suitable tool: " + String.join("; ", unusable), suggestion);
    }

    public static boolean equipFor(Minecraft client, String blockList) {
        if (client.player == null) {
            return false;
        }
        for (String blockId : blockList.split(",")) {
            BlockState state = stateOf(blockId);
            if (state == null) {
                continue;
            }
            int slot = bestToolSlot(client, state);
            if (slot < 0) {
                continue;
            }
            ItemStack wanted = client.player.getInventory().getItem(slot);
            return Hotbar.select(client, stack -> stack == wanted) != Hotbar.Result.ABSENT;
        }
        return false;
    }

    public static String describeTools(Minecraft client) {
        if (client.player == null) {
            return "none";
        }
        Inventory inventory = client.player.getInventory();
        List<String> tools = new ArrayList<>();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (tools.stream().anyMatch(entry -> entry.startsWith(id + " "))) {
                continue;
            }
            tools.add(id + " " + (stack.getMaxDamage() - stack.getDamageValue())
                    + "/" + stack.getMaxDamage() + " uses left");
        }
        return tools.isEmpty() ? "none" : String.join(", ", tools);
    }

    private static BlockState stateOf(String blockId) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId.trim()));
        return block == null ? null : block.defaultBlockState();
    }

    private static int bestToolSlot(Minecraft client, BlockState state) {
        Inventory inventory = client.player.getInventory();
        int best = -1;
        float bestSpeed = 0;
        int bestRemaining = -1;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (remaining <= 1) {
                continue;
            }
            if (speed > bestSpeed || (speed == bestSpeed && remaining > bestRemaining)) {
                best = slot;
                bestSpeed = speed;
                bestRemaining = remaining;
            }
        }
        return best;
    }

    private static String craftableFor(BlockState state) {
        String kind;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            kind = "pickaxe";
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            kind = "axe";
        } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            kind = "shovel";
        } else if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            kind = "hoe";
        } else {
            return null;
        }
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return "minecraft:diamond_" + kind;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return "minecraft:iron_" + kind;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return "minecraft:stone_" + kind;
        }
        return "minecraft:wooden_" + kind;
    }

    private static String requirement(BlockState state) {
        String tier;
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            tier = "a diamond";
        } else if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            tier = "an iron";
        } else if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            tier = "a stone";
        } else {
            tier = "a wooden";
        }
        String kind;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            kind = "pickaxe";
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            kind = "axe";
        } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            kind = "shovel";
        } else if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            kind = "hoe";
        } else {
            kind = "tool";
        }
        return tier + " " + kind + " or better";
    }
}
