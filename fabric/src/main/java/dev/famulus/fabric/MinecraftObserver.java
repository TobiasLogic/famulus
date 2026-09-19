package dev.famulus.fabric;

import dev.famulus.core.WorldSnapshot;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class MinecraftObserver {
    private ClientLevel previousLevel;
    private long worldGeneration;

    public static Map<String, Integer> countAll(Minecraft client, Collection<String> itemIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String itemId : itemIds) {
            counts.put(itemId, 0);
        }
        if (client.player == null) {
            return counts;
        }
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (counts.containsKey(id)) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    public String worldKey(Minecraft client) {
        if (client.level != previousLevel) {
            previousLevel = client.level;
            worldGeneration++;
        }
        if (client.level == null) {
            return "disconnected";
        }
        return worldGeneration + ":" + client.level.dimension().identifier();
    }

    public WorldSnapshot observe(Minecraft client, String itemId) {
        if (client.level != previousLevel) {
            previousLevel = client.level;
            worldGeneration++;
        }
        if (client.level == null || client.player == null) {
            return new WorldSnapshot(false, false, "disconnected", 0, false);
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        int count = 0;
        boolean space = false;
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) space = true;
            else if (stack.is(item)) {
                count += stack.getCount();
                if (stack.getCount() < stack.getMaxStackSize()) space = true;
            }
        }
        String worldKey = worldGeneration + ":" + client.level.dimension().identifier();
        return new WorldSnapshot(true, client.player.isAlive() && !client.player.isRemoved(),
                worldKey, count, space);
    }
}
