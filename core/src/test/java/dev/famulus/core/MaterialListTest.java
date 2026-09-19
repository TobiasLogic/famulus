package dev.famulus.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialListTest {
    private static Map<String, Integer> counts(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void reportsWhatIsMissingAgainstWhatIsHeld() {
        MaterialList list = MaterialList.of(
                counts("minecraft:cobblestone", 1284, "minecraft:oak_planks", 320),
                counts("minecraft:oak_planks", 32));
        assertEquals(2, list.distinctItems());
        assertEquals(1604, list.totalNeeded());
        assertEquals(1284 + 288, list.totalShortfall());
        assertFalse(list.isSatisfied());
    }

    @Test
    void surplusNeverBecomesANegativeShortfall() {
        MaterialList list = MaterialList.of(
                counts("minecraft:cobblestone", 10),
                counts("minecraft:cobblestone", 400));
        assertEquals(0, list.totalShortfall());
        assertTrue(list.isSatisfied());
        assertTrue(list.shortfalls().isEmpty());
        assertEquals(1, list.distinctItems(), "A satisfied item is still part of the blueprint");
    }

    @Test
    void anItemNotHeldAtAllCountsAsFullyMissing() {
        MaterialList list = MaterialList.of(counts("minecraft:hopper", 12), Map.of());
        assertEquals(12, list.totalShortfall());
        assertEquals(12, list.requirements().get(0).shortfall());
        assertEquals(0, list.requirements().get(0).have());
    }

    @Test
    void orderIsStableAndLargestShortfallFirst() {
        MaterialList list = MaterialList.of(
                counts("minecraft:hopper", 12, "minecraft:cobblestone", 1284,
                        "minecraft:oak_planks", 320, "minecraft:redstone", 12),
                counts("minecraft:oak_planks", 32));
        assertEquals(List.of("minecraft:cobblestone", "minecraft:oak_planks",
                        "minecraft:hopper", "minecraft:redstone"),
                list.requirements().stream().map(MaterialRequirement::itemId).toList(),
                "Equal shortfalls must break ties by item id so the order never wobbles");
    }

    @Test
    void satisfiedItemsSortAfterEverythingStillMissing() {
        MaterialList list = MaterialList.of(
                counts("minecraft:stone", 5, "minecraft:dirt", 100),
                counts("minecraft:stone", 64));
        assertEquals("minecraft:dirt", list.requirements().get(0).itemId());
        assertEquals("minecraft:stone", list.requirements().get(1).itemId());
    }

    @Test
    void separatesWhatCanBeGatheredFromWhatCannot() {
        MaterialList list = MaterialList.of(
                counts("minecraft:oak_log", 64, "minecraft:hopper", 12, "minecraft:dirt", 30),
                Map.of());
        Set<String> supported = Set.of("minecraft:oak_log", "minecraft:dirt");
        assertEquals(List.of("minecraft:oak_log", "minecraft:dirt"),
                list.gatherable(supported::contains).stream().map(MaterialRequirement::itemId).toList());
        assertEquals(List.of("minecraft:hopper"),
                list.unobtainable(supported::contains).stream().map(MaterialRequirement::itemId).toList());
    }

    @Test
    void anAlreadyHeldUnobtainableItemDoesNotBlockABuild() {
        MaterialList list = MaterialList.of(
                counts("minecraft:hopper", 12), counts("minecraft:hopper", 12));
        assertTrue(list.unobtainable(id -> false).isEmpty());
        assertTrue(list.isSatisfied());
    }

    @Test
    void zeroAndNegativeBlueprintCountsAreDroppedRatherThanRejected() {
        MaterialList list = MaterialList.of(
                counts("minecraft:air", 0, "minecraft:stone", -4, "minecraft:dirt", 8), Map.of());
        assertEquals(1, list.distinctItems());
        assertEquals("minecraft:dirt", list.requirements().get(0).itemId());
    }

    @Test
    void anImpossibleInventoryCountIsClampedRatherThanCrashing() {
        MaterialList list = MaterialList.of(
                counts("minecraft:dirt", 8), counts("minecraft:dirt", -5));
        assertEquals(0, list.requirements().get(0).have());
        assertEquals(8, list.totalShortfall());
    }

    @Test
    void stacksAreRoundedUpBecauseAPartialStackStillNeedsATrip() {
        assertEquals(0, new MaterialRequirement("minecraft:dirt", 10, 10).shortfallStacks());
        assertEquals(1, new MaterialRequirement("minecraft:dirt", 1, 0).shortfallStacks());
        assertEquals(1, new MaterialRequirement("minecraft:dirt", 64, 0).shortfallStacks());
        assertEquals(2, new MaterialRequirement("minecraft:dirt", 65, 0).shortfallStacks());
        assertEquals(21, new MaterialRequirement("minecraft:cobblestone", 1284, 0).shortfallStacks());
    }

    @Test
    void anEmptyBlueprintIsSatisfiedRatherThanAnError() {
        MaterialList list = MaterialList.of(Map.of(), Map.of());
        assertTrue(list.isSatisfied());
        assertEquals(0, list.distinctItems());
        assertEquals(0, list.totalNeeded());
    }

    @Test
    void requirementsValidateTheirOwnFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialRequirement("cobblestone", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialRequirement("minecraft:dirt", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialRequirement("minecraft:dirt", 1, -1));
    }

    @Test
    void theListIsImmutableOnceBuilt() {
        MaterialList list = MaterialList.of(counts("minecraft:dirt", 8), Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> list.requirements().add(new MaterialRequirement("minecraft:stone", 1, 0)));
    }
}
