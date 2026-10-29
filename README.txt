Glint & Glamour developer documentation
Minecraft 1.21.1 / NeoForge 21.x. MIT license (attribution required)
================================================================================

Per-item animated enchantment glint with color, timing, and scale control. Works
on held items, armor, elytra, horse armor, and any LivingEntity. Glints can also
project a colored stencil outline through the "glowing" flag. NBT-driven; 55
built-in designs, extensible via data packs.


================================================================================
  Two artifacts
================================================================================

  glint-and-glamour-api-<ver>.jar   (modid: customglint_api)
      Rendering pipeline + Java API. No wand, recipes, or /glint command.
      Bundle this via jarJar.

  Glint-and-Glamour-<ver>.jar        (modid: customglint + customglint_api)
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
            url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/1.21.1/mcmodsrepo"
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
nests jarJar dependencies automatically. There is no jarJar.enable() call and
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

  net.tunamods.customglint.common.*         - server-safe (NBT API, registries,
                                              entity events, networking).
  net.tunamods.customglint.common.client.*  - client-only (rendering pipeline).
                                              Never reference from
                                              server-reachable code.

Mixing the two will ClassNotFoundException on a dedicated server.

The custom_data key is always "customglint", whether the api is bundled in your
jar or loaded from the standalone.


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
  import net.tunamods.customglint.common.entity.EntityGlintEvents;

  // Per-instance writes run server-side. Broadcast after each mutation.
  CustomGlint.writeEntity(living, layers);
  CustomGlint.setEntityGlowColors(living, colors);
  CustomGlint.setEntityGlowing(living, true);
  EntityGlintEvents.broadcast(living);

  CustomGlint.readEntity(living);
  CustomGlint.hasEntity(living);
  CustomGlint.removeEntity(living);
  CustomGlint.clearEntityGlowColors(living);

  // Type-wide auto-apply (every spawn of this EntityType carries the glint)
  CustomGlint.registerEntityGlint(EntityType.ALLAY, CustomGlint.SHIMMER,
      new int[]{CustomGlint.CYAN, CustomGlint.WHITE});

Sync:
  - All mutating calls run server-side. EntityGlintEvents.broadcast(living)
    pushes the current tag to every tracking client.
  - The api jar registers a PlayerEvent.StartTracking listener that re-syncs
    late joiners automatically.
  - State lives on the entity's PersistentData under the "customglint" key.
  - The networking channel (customglint_api:main) ships in the api jar, so
    mods that bundle the api get entity sync with no extra wiring.

Tag-level helpers for packets, NBT files, and snapshot / restore:

  CompoundTag tag = CustomGlint.entityGlintTag(living);   // or itemGlintTag(stack)
  CustomGlint.writeEntityTag(living, tag);
  CustomGlint.writeItemTag(stack, tag);                   // symmetric for items
  CustomGlint.Data layers   = CustomGlint.fromTag(tag);
  boolean          glowing  = CustomGlint.tagGlowing(tag);
  int[]            glowCols = CustomGlint.tagGlowColors(tag);
  CompoundTag      fresh    = CustomGlint.toTag(layers);

Client-side mutations (writeEntity / setEntityGlowing / setEntityGlowColors called
on the client, for preview UIs, replay viewers, or entities reconstructed from
stored NBT) render immediately without waiting for a server broadcast. If you need to
force a re-sync after manually editing the entity's persistent NBT, call the
client-only helper:

  EntityGlintRender.refreshClientCache(living);     // client-only


================================================================================
  Constants
================================================================================

Colors: CustomGlint.RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE,
PURPLE, MAGENTA, PINK, BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK. Custom hex via
CustomGlint.color("FFD700"). The alpha byte is a brightness multiplier
(0xFF full, 0x00 invisible); blend mode is additive.

Designs: 55 ResourceLocation constants on CustomGlint (e.g. WAVE, SPARKLE,
AURORA). Iterate with CustomGlint.PATTERNS.

Iteration arrays: CustomGlint.ALL_COLORS (16), VIBRANT_COLORS (11), PATTERNS (55).


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
  - Iris / Oculus shader-pack outline path.
  - Atlas-calibrated pattern scale on non-square block atlases.
  - Texture and RenderType eviction on resource pack reload.

NOT wired automatically: BEWLR renderers that bypass the vanilla item-foil
buffer and call MultiBufferSource.getBuffer(RenderType) themselves. Drive the
pipeline directly via the hooks below if your mod ships one.


================================================================================
  Specifically supported mods
================================================================================

These integrations ship in the FULL standalone jar only, NOT in the bundled
api jar. Each targets the other mod by class name with a soft mixin (no hard
dependency) and no-ops when the mod is absent, so a modpack without the mod is
never affected.

  Ice and Fire          - troll weapons, death worm gauntlets, dragon armor,
                          hippogryph armor, and hippocampus armor take glints
                          and glow outlines.
  Epic Knights          - armor decoration overlays (capes, tabards, trims)
  (magistuarmory)         take glints.
  Epic Fight            - glowing entities keep their glow outline while Epic
                          Fight renders them with its own animated meshes.
  Iron's Spells         - worn armor takes glints and glow outlines. Covers any
  ('n Spellbooks)         mod whose armor draws through GeckoLib's
                          GeoArmorRenderer (Ars Nouveau and the like too).
  Immersive Armors      - the mod's layered armor takes glints and glow
                          outlines while worn, though it draws each piece with
                          its own model instead of the vanilla armor layer.
  Mekanism              - the MekaSuit, Jetpacks, Free Runners and their armored
                          variants, and the Scuba tank and mask take glints and
                          glow outlines while worn.
  Artifacts             - belts, necklaces, gloves, boots and other artifacts
                          worn in Curios slots take glints and glow outlines.
  Sophisticated         - backpacks glint across all of their render passes.
  Backpacks
  ElytraSlot            - an elytra worn in the dedicated Curios slot glints
                          like a chestplate elytra.
  First-Person Model    - held-item glow outlines stay aligned in its 3.5D
                          body view.
  JEI                   - the Glint Trim, Glow Trim, Tear, smithing, and Rainbow
                          Dye recipes and info pages show in the recipe view.

Modded armor and held items that go through the normal ItemRenderer / armor
layer flow are handled automatically, with no per-mod integration.


================================================================================
  Advanced rendering hooks
================================================================================

Client-only. Gate any reference with FMLEnvironment.dist.

  import net.tunamods.customglint.common.client.CustomGlintRenderer;

  // Glow color for an item, from its glow-color or glint NBT (white if neither)
  int argb  = CustomGlintRenderer.resolveGlowColor(stack);
  int frame = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);

  // Glint RenderType factories (cached, self-register into fixedBuffers). Pick the
  // one whose depth matches the base draw, or the EQUAL-depth glint z-fights:
  //   forGlint           - held items and BEWLR models
  //   forArmorGlint      - armorCutoutNoCull surfaces (view-offset depth)
  //   forHorseArmorGlint - entityCutoutNoCull surfaces (no polygon offset)
  RenderType rt  = CustomGlintRenderer.forGlint(glint, layerIdx, frameColor, isItem, colorIdx);
  RenderType rtA = CustomGlintRenderer.forArmorGlint(glint, layerIdx, frameColor, colorIdx);
  RenderType rtH = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);

  // Public ThreadLocals
  CustomGlintRenderer.CURRENT_ITEM_STACK   // set while an item is rendering
  CustomGlintRenderer.IN_OUTLINE           // recursion guard during outline capture
  CustomGlintRenderer.COLOR_BUF            // scratch float[4] for a frame color

The glow outline is a post-process pass, captured automatically from the vanilla
item, armor, and entity draws. There is no per-call outline draw method. A
custom renderer that bypasses the item-foil buffer drives it the way the bundled
mod compat does: fan the glint through a RenderType factory above, and route the
same mesh into GlowOutlineRenderer to capture its silhouette.


================================================================================
  Data-pack designs
================================================================================

Register custom design names at runtime via data packs. Names appear in the
wand editor and /glint apply suggestions on every client; the server syncs the
list on join and /reload.

  data/<namespace>/customglint/designs/<anything>.json:
      ["<your_modid>:mydesign", "<your_modid>:another"]

  Texture: assets/<your_modid>/textures/glint/mydesign.png

  Reference from NBT: <your_modid>:textures/glint/<name>.png


================================================================================
  NBT format
================================================================================

Item glint lives in the customglint:glint data component, a codec-typed GlintState:
the glint layers (under "glint") plus the two glow fields ("glowing", "glowColors").
With the 1.20.5+ component syntax:

  /give @p <item>[customglint:glint={glint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}}] 1

speed: 1.0 = 20 ticks / color. interpolate: 1b = smooth. simultaneous: 1b = all
colors at once. Alpha byte of each color int = brightness.

Colors in [I;...] are signed 32-bit ints. Any color with alpha >= 0x80 (i.e.
every full-brightness color) is negative in this form; the leading 0xFF makes
it exceed Integer.MAX_VALUE unsigned. Use the named constants from Java code,
the color names from /glint apply, or a hex-to-signed-int converter.

Add glowing:1b (and optionally glowColors:[I;...]) as siblings of the glint block
for the colored outline. glowColors drives the outline tint independently of the
glint layers; with glowing:1b and no glowColors the outline tracks glint layer 0:

  /give @p minecraft:diamond_sword[customglint:glint={glowing:1b,glint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}}] 1

A glow-only item (outline, no animated glint) needs no glint block at all:

  /give @p minecraft:diamond_sword[customglint:glint={glowing:1b,glowColors:[I;-65536,-1]}] 1

Remove the glint from a held item with the mod command:

  /glint remove

Or hand out a clean item by dropping the whole component: <item>[!customglint:glint].
