Glint & Glamour - developer documentation
Minecraft 26.1.2 / NeoForge 26.1.2 - MIT license (attribution required)
================================================================================

Per-item animated enchantment glint with color, timing, and scale control. Works
on held items, armor, elytra, horse armor, and any LivingEntity. Glints can also
project a colored glow outline through the "glowing" flag. Component-driven;
56 built-in designs (including a procedural chromatic one), extensible via data
packs.


================================================================================
  Two artifacts
================================================================================

  glint-and-glamour-api-<ver>.jar   (modid: customglint_api)
      Rendering pipeline + Java API. No wand, recipes, or /glint command.
      Bundle this via jarJar.

  Glint-and-Glamour-<ver>.jar       (modid: customglint + customglint_api)
      Full standalone download. Adds wand, Glint Trim / Glow Trim / Tear
      items, recipes, /glint command, loot modifiers, JEI integration.
      The api jar is nested inside via META-INF/jarjar/.

Mod developers depend on the api coord. The full jar is the user-facing
Modrinth / CurseForge download.


================================================================================
  Bundling the api (jarJar)
================================================================================

In build.gradle:

    repositories {
        maven {
            name = "TunaMods Glint & Glamour"
            url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/26.1.2/mcmodsrepo"
        }
    }

    dependencies {
        jarJar(implementation("net.tunamods.customglint:glint-and-glamour-api")) {
            version {
                strictly "[1.9.0,2.0)"
                prefer "1.9.0"
            }
        }
    }

This compiles your mod against the api and embeds it in your jar. ModDevGradle
nests jarJar dependencies automatically - there is no jarJar.enable() call and
no separate jarJar task. Build with `./gradlew build`; the api is packed into
the normal build/libs/yourmod-<version>.jar (no -all classifier).

Pin the version to the latest released api when you update. If multiple mods
bundle the api, NeoForge dedupes to a single shared copy.

NeoForge runs on official Mojang mappings, so there is no SRG/refmap remapping
step to configure for mixins in dev.

Alternative: declare as a hard / soft dep instead of bundling.

    [[dependencies.yourmodid]]
        modId="customglint_api"
        type="required"
        versionRange="[1.7,)"
        ordering="NONE"
        side="BOTH"


================================================================================
  Server safety
================================================================================

  net.tunamods.customglint.common.*         - server-safe (glint API, registries,
                                              entity events, networking).
  net.tunamods.customglint.common.client.*  - client-only (rendering pipeline).
                                              Never reference from
                                              server-reachable code.

Mixing the two will ClassNotFoundException on a dedicated server.

Glint state is stored in typed registries owned by the api jar, so the ids are
the same whether the api is bundled in your jar or loaded from the standalone:

  customglint:glint           - item data component (CustomGlintComponents.GLINT)
  customglint:entity_glint    - synced entity attachment (ENTITY_GLINT)

Both carry a CustomGlint.GlintState (layers + glow flags), codec-serialized.


================================================================================
  Item API
================================================================================

All on net.tunamods.customglint.common.CustomGlint.

  CustomGlint.write(stack, design, colors, speed, interpolate, scale, simultaneous);
  CustomGlint.write(stack, new CustomGlint.Layer[]{ ... });   // multi-layer
  CustomGlint.write(stack, design, colors);                   // shorthand
  CustomGlint.write(stack, design, color);                    // single-color shorthand

  boolean has = CustomGlint.has(stack);
  CustomGlint.Data data = CustomGlint.read(stack);
  CustomGlint.remove(stack);

  // Pre-glinted ItemStack
  ItemStack s = CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE,
                                    new int[]{CustomGlint.PURPLE});

  // Glowing outline
  CustomGlint.setGlowing(stack, true);
  CustomGlint.isGlowing(stack);

  // Independent outline colors. When set, the outline cycles through this
  // list instead of tracking glint layer 0.
  CustomGlint.setGlowColors(stack, new int[]{CustomGlint.RED, CustomGlint.WHITE});
  CustomGlint.getGlowColors(stack);
  CustomGlint.hasGlowColors(stack);
  CustomGlint.clearGlowColors(stack);


Auto-apply registries

Register from your @Mod constructor. Do NOT use FMLCommonSetupEvent (parallel
thread pool; the registries are unsynchronized HashMaps).

  CustomGlint.registerCraftGlint(item, design, colors);
  CustomGlint.registerFishingGlint(item, design, colors);
  CustomGlint.registerMobDropGlint(item, design, colors);
  CustomGlint.registerLootGlint(lootTableRL, item, design, colors);

registerLootGlint is wired by the full standalone's loot modifier and is not
present in the bundled api jar.


================================================================================
  Entity API
================================================================================

Glints and glow outlines also apply to LivingEntity instances. The renderer
unions the body model and all overlay layers (armor, capes, saddles) into one
outline ring.

  import net.tunamods.customglint.common.CustomGlint;

  // Per-instance writes run server-side; the change auto-syncs to tracking clients.
  CustomGlint.writeEntity(living, layers);
  CustomGlint.setEntityGlowColors(living, colors);
  CustomGlint.setEntityGlowing(living, true);

  CustomGlint.readEntity(living);
  CustomGlint.hasEntity(living);
  CustomGlint.removeEntity(living);
  CustomGlint.clearEntityGlowColors(living);

  // Type-wide auto-apply (every spawn of this EntityType carries the glint)
  CustomGlint.registerEntityGlint(EntityType.ALLAY, CustomGlint.SHIMMER,
      new int[]{CustomGlint.CYAN, CustomGlint.WHITE});

Sync:
  - State is a synced data attachment (CustomGlint.ENTITY_GLINT), registered by the
    api jar. Run all mutating calls server-side; NeoForge auto-syncs the attachment
    to tracking clients on every write, and to late joiners on start-tracking. No
    manual broadcast, no networking wiring, no client cache.
  - The attachment is persisted server-side and copied across player respawn.
  - Type-wide registrations (registerEntityGlint) need no per-entity storage; the
    renderer consults them as a fallback when an entity has no per-instance state.

Item<->entity transfer: copy the typed state value directly. GlintState is an
immutable record (the same value stored in the item component and the entity
attachment), so no serialization is involved.

  CustomGlint.GlintState glint = CustomGlint.readState(stack);   // item
  CustomGlint.GlintState glint = CustomGlint.readEntityState(living);
  CustomGlint.writeState(stack, glint);                          // item, EMPTY clears
  CustomGlint.writeEntityState(living, glint);                   // entity, auto-syncs

  // Capture a mob's glint onto an item in one line, no round-trip:
  CustomGlint.writeState(stack, CustomGlint.readEntityState(living));

Serialized snapshots (when you need an actual CompoundTag: stored snapshot, give
NBT, a custom packet). Item component and entity attachment share one tag shape:

  CompoundTag tag = CustomGlint.entityGlintTag(living);   // or itemGlintTag(stack)
  CustomGlint.writeEntityTag(living, tag);                // overwrite from a tag
  CustomGlint.writeItemTag(stack, tag);                   // symmetric for items
  CustomGlint.Data layers   = CustomGlint.fromTag(tag);
  boolean          glowing  = CustomGlint.tagGlowing(tag);
  int[]            glowCols = CustomGlint.tagGlowColors(tag);
  CompoundTag      fresh    = CustomGlint.toTag(layers);


================================================================================
  Constants
================================================================================

Colors: CustomGlint.RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE,
PURPLE, MAGENTA, PINK, BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK. Custom hex via
CustomGlint.color("FFD700"). The alpha byte is a brightness multiplier
(0xFF full, 0x00 invisible); blend mode is additive.

Designs: 56 Identifier constants on CustomGlint (e.g. WAVE, SPARKLE, AURORA),
including CHROMATIC - a procedural animated oil-slick that blends up to 8 colors
in-shader (give it a colors array like any other design). Iterate with
CustomGlint.PATTERNS.

Iteration arrays: CustomGlint.ALL_COLORS (16), VIBRANT_COLORS (11), PATTERNS (56).


================================================================================
  What the api handles automatically
================================================================================

  - Held items in every context (1st / 3rd person, GUI, ground, item frame).
  - 3D held models with custom BEWLR renderers (tridents and similar).
  - Humanoid armor including modded armor that ships its own ArmorModel
    via the NeoForge client armor hooks.
  - Elytra worn as chestplate.
  - Horse armor (vanilla + modded).
  - Living entities (body + overlay layers as one unioned outline ring).
  - Glowing outline pass on items and all four armor surfaces above.
  - Iris shader-pack path (custom glint pipelines are reassigned to the active
    pack so glint renders instead of drawing white).
  - Atlas-calibrated pattern scale on non-square block atlases.
  - Texture and RenderType eviction on resource pack reload.

There are no public render hooks in this version. Set glint or glow data with the
CustomGlint API above and the renderer draws it everywhere on the automatic list,
including 3D models with custom BEWLR renderers, which the submit-node pipeline
picks up with no per-mod code. A renderer that bypasses that pipeline (drawing
straight to its own RenderType) is not covered by the api; supporting one needs a
dedicated mixin, which ships in the standalone mod, not the bundled api jar.


================================================================================
  Specifically supported mods
================================================================================

These integrations ship in the FULL standalone jar only, NOT in the bundled
api jar. Each targets the other mod by class name with a soft mixin (no hard
dependency) and no-ops when the mod is absent, so they never affect a modpack
that lacks them.

  Artifacts             - belts, necklaces, gloves, boots and other artifacts
                          worn in Curios slots take glints and glow outlines.
  Sophisticated          - backpacks take glints and glow outlines in hand
  Backpacks               (including first person), dropped, in the inventory,
                          and worn on the back.
  JEI                   - Glint Trim, Glow Trim, and Tear items show their
                          information pages in the recipe view.

Modded armor and held items that go through the normal item / armor draw flow
are handled automatically without a per-mod integration.


================================================================================
  Data-pack designs
================================================================================

Register custom design names at runtime via data packs. Names appear in the
wand editor and /glint apply suggestions on every client; the server syncs the
list on join and /reload.

  data/<namespace>/customglint/designs/<anything>.json:
      ["<your_modid>:mydesign", "<your_modid>:another"]

  Texture: assets/<your_modid>/textures/glint/mydesign.png

  Reference in glint data: <your_modid>:textures/glint/<name>.png


================================================================================
  Component format
================================================================================

Item glint is its own data component, customglint:glint, holding a GlintState:
the layer list under a "glint" key plus the glow flags as siblings. It is no
longer stuffed into minecraft:custom_data. With the 1.20.5+ component syntax:

  /give @p <item>[customglint:glint={glint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}}] 1

speed: 1.0 = 20 ticks / color. interpolate: 1b = smooth. simultaneous: 1b = all
colors at once. Alpha byte of each color int = brightness. Layer fields other
than design and colors are optional and default to the above.

Colors in [I;...] are signed 32-bit ints. Any color with alpha >= 0x80 (i.e.
every full-brightness color) is negative in this form; the leading 0xFF makes
it exceed Integer.MAX_VALUE unsigned. Use the named constants from Java code,
the color names from /glint apply, or a hex-to-signed-int converter.

Add glowing:1b as a sibling of glint for the colored outline:

  /give @p minecraft:diamond_sword[customglint:glint={glowing:1b,glint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}}] 1

Independent outline colors go in a glowColors sibling array, e.g.
glowColors:[I;-65536,-1]; when present the outline cycles through them instead
of tracking glint layer 0.

Remove the glint from a held item with the mod command:

  /glint remove

Or hand out a clean item by dropping the whole component: <item>[!customglint:glint].
