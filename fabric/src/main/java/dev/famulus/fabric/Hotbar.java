package dev.famulus.fabric;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public final class Hotbar {
    public enum Result { SELECTED, MOVING, ABSENT }

    private Hotbar() {}

    public static Result select(Minecraft client, String itemId) {
        return select(client, stack -> !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId));
    }

    public static Result select(Minecraft client, Predicate<ItemStack> wanted) {
        if (client.player == null) {
            return Result.ABSENT;
        }
        Inventory inventory = client.player.getInventory();
        int hotbar = Inventory.getSelectionSize();
        for (int slot = 0; slot < hotbar; slot++) {
            if (wanted.test(inventory.getItem(slot))) {
                inventory.setSelectedSlot(slot);
                return Result.SELECTED;
            }
        }
        if (client.player.containerMenu != client.player.inventoryMenu) {
            return Result.ABSENT;
        }
        for (int slot = hotbar; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (!wanted.test(inventory.getItem(slot))) {
                continue;
            }
            int destination = freeSlot(inventory);
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId,
                    slot, destination, ContainerInput.SWAP, client.player);
            inventory.setSelectedSlot(destination);
            return Result.MOVING;
        }
        return Result.ABSENT;
    }

    private static int freeSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return inventory.getSelectedSlot();
    }
}
