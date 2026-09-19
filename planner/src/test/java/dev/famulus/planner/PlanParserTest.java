package dev.famulus.planner;

import dev.famulus.core.PlannedTask;
import dev.famulus.core.PlannerException;
import dev.famulus.core.TaskPlan;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanParserTest {
    private static final Set<String> GATHERABLE =
            Set.of("minecraft:oak_log", "minecraft:dirt", "minecraft:sand");

    private static TaskPlan parse(String raw) throws PlannerException {
        return PlanParser.parse(raw, GATHERABLE);
    }

    private static PlannerException refuse(String raw) {
        return assertThrows(PlannerException.class, () -> parse(raw));
    }

    @Test
    void acceptsAWellFormedPlan() throws Exception {
        TaskPlan plan = parse("""
                {"goal":"collect wood and dirt","tasks":[
                  {"id":"t1","type":"gather","item":"minecraft:oak_log","count":32},
                  {"id":"t2","type":"gather","item":"minecraft:dirt","count":16}]}
                """);
        assertEquals("collect wood and dirt", plan.goal());
        assertEquals(2, plan.tasks().size());
        assertEquals(new PlannedTask.Gather("t1", "minecraft:oak_log", 32), plan.tasks().get(0));
    }

    @Test
    void findsTheJsonInsideCodeFencesAndCommentary() throws Exception {
        TaskPlan plan = parse("""
                Sure! Here is the plan you asked for:
                ```json
                {"goal":"wood","tasks":[{"type":"gather","item":"minecraft:oak_log","count":8}]}
                ```
                Let me know if you want changes.
                """);
        assertEquals(1, plan.tasks().size());
    }

    @Test
    void suppliesMissingTaskIdsRatherThanFailingOverThem() throws Exception {
        TaskPlan plan = parse("""
                {"goal":"wood","tasks":[
                  {"type":"gather","item":"minecraft:oak_log","count":8},
                  {"type":"gather","item":"minecraft:dirt","count":8}]}
                """);
        assertEquals("t1", plan.tasks().get(0).id());
        assertEquals("t2", plan.tasks().get(1).id());
    }

    @Test
    void addsTheVanillaNamespaceWhenAModelForgetsIt() throws Exception {
        TaskPlan plan = parse("""
                {"goal":"wood","tasks":[{"type":"gather","item":"oak_log","count":8}]}
                """);
        assertEquals("minecraft:oak_log", ((PlannedTask.Gather) plan.tasks().get(0)).itemId());
    }

    @Test
    void acceptsCountsWrittenAsStringsOrDecimals() throws Exception {
        assertEquals(32, ((PlannedTask.Gather) parse("""
                {"goal":"g","tasks":[{"type":"gather","item":"minecraft:dirt","count":"32"}]}
                """).tasks().get(0)).count());
        assertEquals(32, ((PlannedTask.Gather) parse("""
                {"goal":"g","tasks":[{"type":"gather","item":"minecraft:dirt","count":32.0}]}
                """).tasks().get(0)).count());
    }

    @Test
    void acceptsTheCommonSynonymsForGathering() throws Exception {
        for (String type : new String[] {"gather", "GATHER", " mine ", "collect"}) {
            TaskPlan plan = parse("""
                    {"goal":"g","tasks":[{"type":"%s","item":"minecraft:dirt","count":4}]}
                    """.formatted(type));
            assertInstanceOf(PlannedTask.Gather.class, plan.tasks().get(0));
        }
    }

    @Test
    void namesEveryUnobtainableItemAtOnce() {
        PlannerException refused = refuse("""
                {"goal":"farm","tasks":[
                  {"type":"gather","item":"minecraft:diamond","count":4},
                  {"type":"gather","item":"minecraft:oak_log","count":4},
                  {"type":"gather","item":"minecraft:hopper","count":2}]}
                """);
        assertTrue(refused.getMessage().contains("minecraft:diamond"));
        assertTrue(refused.getMessage().contains("minecraft:hopper"),
                "Every unobtainable item should be reported, not just the first");
        assertFalse(refused.getMessage().contains("oak_log"));
    }

    @Test
    void refusesActionsThatHaveNoExecutorYet() {
        assertTrue(refuse("""
                {"goal":"talk","tasks":[{"type":"interact","target":"minecraft:villager"}]}
                """).getMessage().contains("not implemented"));
    }

    @Test
    void acceptsABuildNowThatItHasAnExecutor() throws Exception {
        TaskPlan plan = parse("""
                {"goal":"hut","tasks":[{"type":"build","blueprint":"hut.schem","x":1,"y":2,"z":3}]}
                """);
        PlannedTask.Build build = assertInstanceOf(PlannedTask.Build.class, plan.tasks().get(0));
        assertEquals("hut.schem", build.blueprint());
        assertEquals(1, build.originX());
        assertEquals(2, build.originY());
        assertEquals(3, build.originZ());
    }
    @Test
    void refusesAnAbsurdCount() {
        PlannerException refused = refuse("""
                {"goal":"g","tasks":[{"type":"gather","item":"minecraft:dirt","count":999999}]}
                """);
        assertTrue(refused.getMessage().contains("more than an inventory holds"));
    }

    @Test
    void refusesTooManyTasks() {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < PlanParser.MAX_TASKS + 1; i++) {
            tasks.append(i > 0 ? "," : "")
                 .append("{\"type\":\"gather\",\"item\":\"minecraft:dirt\",\"count\":1}");
        }
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[" + tasks + "]}")
                .getMessage().contains("more than the"));
    }

    @Test
    void refusesMalformedAndEmptyReplies() {
        assertTrue(refuse("").getMessage().contains("nothing"));
        assertTrue(refuse("   ").getMessage().contains("nothing"));
        assertTrue(refuse("I cannot help with that.").getMessage().contains("did not return a plan"));
        assertTrue(refuse("{not json at all}").getMessage().contains("not valid JSON"));
        assertTrue(refuse("{\"tasks\":[]}").getMessage().contains("no goal"));
        assertTrue(refuse("{\"goal\":\"g\"}").getMessage().contains("no task list"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[]}").getMessage().contains("no tasks"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":\"lots\"}").getMessage().contains("no task list"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[\"gather wood\"]}")
                .getMessage().contains("not an object"));
    }

    @Test
    void refusesUnknownTaskTypesInsteadOfGuessing() {
        PlannerException refused = refuse("""
                {"goal":"g","tasks":[{"type":"enchant","item":"minecraft:dirt","count":1}]}
                """);
        assertTrue(refused.getMessage().contains("unknown type"));
        assertTrue(refused.getMessage().contains("enchant"));
    }

    @Test
    void refusesTasksMissingTheirEssentials() {
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[{\"item\":\"minecraft:dirt\",\"count\":1}]}")
                .getMessage().contains("no type"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[{\"type\":\"gather\",\"count\":1}]}")
                .getMessage().contains("names no item"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[{\"type\":\"gather\",\"item\":\"minecraft:dirt\"}]}")
                .getMessage().contains("no usable count"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[{\"type\":\"gather\",\"item\":\"minecraft:dirt\",\"count\":0}]}")
                .getMessage().contains("no usable count"));
        assertTrue(refuse("{\"goal\":\"g\",\"tasks\":[{\"type\":\"build\"}]}")
                .getMessage().contains("no blueprint"));
    }

    @Test
    void refusesAnInvalidItemIdRatherThanPrefixingNonsense() {
        assertTrue(refuse("""
                {"goal":"g","tasks":[{"type":"gather","item":"Oak Log!","count":1}]}
                """).getMessage().contains("invalid item id"));
    }

    @Test
    void refusesDuplicateTaskIds() {
        assertTrue(refuse("""
                {"goal":"g","tasks":[
                  {"id":"same","type":"gather","item":"minecraft:dirt","count":1},
                  {"id":"same","type":"gather","item":"minecraft:sand","count":1}]}
                """).getMessage().contains("not valid"));
    }

    @Test
    void anOverlongGoalIsTruncatedRatherThanRefused() throws Exception {
        TaskPlan plan = parse("""
                {"goal":"%s","tasks":[{"type":"gather","item":"minecraft:dirt","count":1}]}
                """.formatted("wood ".repeat(100)));
        assertTrue(plan.goal().length() <= 200);
    }
}
