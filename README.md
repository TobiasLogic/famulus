# Famulus

A Minecraft agent that takes an instruction and carries it out. Ask it for materials and it goes and
gets them. Hand it a schematic and it works out what the build needs, collects what is missing, and
places the structure.

Famulus is a Fabric client mod for **Minecraft Java 26.2**. It uses
[Baritone](https://github.com/cabaletta/baritone) for movement, mining and building.

## What it does today

* Gathers items to a target inventory count
* Runs multi step plans on its own, moving to the next task without being told
* Reads `.litematic`, `.schem` and `.schematic` files, lists the materials they need, and diffs that
  against what you are carrying
* Builds a schematic once the materials are in hand
* Stores items in a chest, or, when there is no chest, in a shulker box it places, fills, mines and
  picks back up, so it can carry its own storage
* Travels to a coordinate
* Crafts anything it knows a recipe for, using a crafting table when one is in reach and the
  inventory grid when it is not
* Mines a named block for its drop, so stone gives cobblestone and ores give what they drop
* Places a single block at a coordinate
* Turns plain language into a plan, so "get me wood and dirt for a shelter" becomes real tasks
* Stops, reports and asks for a new plan when something is genuinely stuck, instead of looping

## Requirements

| | |
| --- | --- |
| Minecraft | Java Edition 26.2 |
| Loader | Fabric 0.19.5 or newer |
| Fabric API | 0.160.0+26.2 or newer |
| Baritone | 1.19.0, the **API Fabric** build |
| Java | 25 |

## Installing

1. Install Fabric for Minecraft 26.2 and drop [Fabric API](https://modrinth.com/mod/fabric-api) into
   your `mods` folder.
2. Download `baritone-api-fabric-1.19.0.jar` from the
   [Baritone releases page](https://github.com/cabaletta/baritone/releases/tag/v1.19.0) and put it in
   `mods` as well. Use the **API** build. The standalone build obfuscates the names Famulus calls, so
   it will not work.
3. Put `famulus-fabric-26.2-0.1.0.jar` in `mods`.
4. Launch the game. Press **G** to open the panel.

Schematics go in `config/famulus/schematics/`.

### Lunar Client

Lunar Client runs Fabric mods through its Fabric add-on, so Famulus works there too. Pick a 26.2
profile, open the launcher, press the button at the bottom right, choose Mods, and drag in both
`famulus-fabric-26.2-0.1.0.jar` and the Baritone jar. Baritone is not bundled, so it has to go in
alongside.

If you would rather copy them in by hand, the folder is:

```
~/.lunarclient/profiles/<profile>/mods/fabric-26.2/
```

Lunar bundles Fabric API already. If it bundles more than one version, Fabric Loader will refuse to
start with a duplicate mod error, so keep only the newest and rename the others to end in
`.disabled`.

## Using it

Press **G** in game for the panel, which has four tabs.

**Agent** shows what it is doing and why: the current plan, how far through it is, and a running log
of every decision.

**Chat** is where you type what you want. It turns that into a list of tasks, shows you the list, and
waits for you to press Run.

**Build** lists the schematics it found, shows the materials each one needs against what you have,
and can either collect the shortfall or start building.

**Settings** holds your API key and lets you pick which model does the planning.

Commands work too, if you prefer typing:

| Command | What it does |
| --- | --- |
| `/famulus gather <item> <count>` | Hold at least `count` of an item |
| `/famulus queue <item>=<n>, <item>=<n>` | Several gather tasks as one plan |
| `/famulus materials <file>` | What a schematic needs, against what you have |
| `/famulus collect <file>` | Collect whatever a schematic is missing |
| `/famulus build <file>` | Build a schematic where you are standing |
| `/famulus travel <x> <y> <z>` | Walk to a coordinate |
| `/famulus deposit <item> <count>` | Put items into a nearby container |
| `/famulus withdraw <item> <count>` | Take items out of a nearby container |
| `/famulus craft <item> <count>` | Craft an item |
| `/famulus mine <block> <item> <count>` | Mine a block until you hold enough of its drop |
| `/famulus place <item> <x> <y> <z>` | Place one block at a coordinate |
| `/famulus status` | Current plan and recent activity |
| `/famulus stop` | Cancel everything it started |

Counts are totals, not amounts to collect. Asking for 32 oak logs when you already hold 10 gets you
22 more.

## Planning

The Chat tab needs a model. Anything that speaks the OpenAI chat completions API will do, so you can
point it at a hosted service or run something on your own machine.

In **Settings** there are presets for OpenRouter, Ollama and llama.cpp, or you can type any endpoint
and model name yourself. A local server needs no API key.

For a hosted model, paste the key into Settings. It is stored in
`config/famulus/credentials.properties` with owner only permissions, shown masked once saved, and
never written to a log. The `OPENROUTER_API_KEY` environment variable overrides it if you would
rather not keep it on disk.

None of this is required. Without a model the agent still gathers, builds and follows plans; it just
cannot write a plan for you or reconsider one when it gets stuck.

## Building from source

```bash
./scripts/fetch-baritone.sh
./gradlew build
```

The jar lands in `fabric/build/libs/`. The fetch script downloads the pinned Baritone build and
checks its hash before the build will use it.

To run the automated in game tests:

```bash
./scripts/run-client-gametest.sh
```

This opens a real client and drives it. Note that the underlying gradle task exits non zero even when
every check passes, because Baritone leaves threads running that stop the game closing cleanly. The
script tells the difference between that and a real failure.

## How it works

Three layers, each doing only what it is good at.

A **planner** turns your sentence into a structured list of tasks. It runs once at the start, never
in a loop, and whatever it returns is validated before anything acts on it. A plan that names items
the agent cannot obtain, or actions it cannot perform, is rejected rather than half attempted.

A **policy** picks the next move when a task fails: retry, go and look elsewhere, accept it and move
on, or give up and ask for a new plan. It answers in under a second and reports how confident it is,
so a shaky decision escalates instead of being acted on.

An **executor** does the actual work through Baritone, and a small task engine decides when something
is finished. That last part matters: success is measured by looking at your inventory or at the
blocks in the world, never by Baritone reporting that it stopped. Baritone stops for lots of reasons,
including failure.

## Limitations

Worth knowing before you expect too much:

* The gather command covers logs, dirt, sand and red sand. For anything else use mine, which takes
  the block and its drop explicitly and does not go through that list.
* Storing items works with a chest in reach, and with a shulker box when there is none. Both are
  tested in a live game.
* Interacting with entities is not implemented. A plan that needs it is refused rather than half
  attempted.
* Baritone leaves behind any blocks it pillars up on to reach something. Fine for a solid structure,
  a problem for anything with redstone in it.
* Baritone does not give up searching for a block that is not there, so a task for something
  unavailable takes a couple of minutes to fail rather than seconds.
* Building is wired up and covered by tests of the task engine and the schematic reader, but it has
  not yet been run against a real blueprint in a live game. Treat it as untested in practice.

## License

LGPL-3.0. See [LICENSE](LICENSE), which incorporates the GNU GPL v3.0 in [LICENSE.GPL](LICENSE.GPL).

Baritone is a separate dependency under the same license and is not redistributed here. Famulus calls
its public API and copies none of its source.
