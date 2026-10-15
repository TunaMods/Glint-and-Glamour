Glint & Glamour: developer documentation
Minecraft 1.20.1 / Forge 47.x, MIT license (attribution required)
================================================================================

Per-item animated enchantment glint with color, timing, and scale control. Works
on held items, armor, elytra, horse armor, and any LivingEntity. Glints can also
project an independently-animated colored outline through the "glowing" flag.
NBT-driven; 56 built-in designs (54 texture PNGs, vanilla, and the procedural
Chromatic), extensible via data packs.


================================================================================
  Two artifacts
================================================================================

  glint-and-glamour-api-<ver>.jar   (modid: customglint_api)
      Rendering pipeline + Java API. No wand, recipes, or /glint command.
      Bundle this via jarJar.

  Glint-and-Glamour-<ver>.jar       (modid: customglint + customglint_api)
      Full standalone download. Adds the Glint Wand, the Glint Table block,
      Glint Trim / Glow Trim / Tear items, Glint Bag, Trim Powder, Rainbow
      Dye, recipes, advancements, shared server blueprints, the /glint
      command, loot modifiers, and JEI integration. The api jar is nested
      inside via META-INF/jarjar/.

Mod developers depend on the api coord. The full jar is the user-facing
Modrinth / CurseForge download.


================================================================================
  Bundling the api (jarJar)
================================================================================

In build.gradle:

    repositories {
        maven {
            name = "TunaMods Glint & Glamour"
            url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/1.20.1/mcmodsrepo"
        }
    }

    dependencies {
        compileOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.8.0")
        runtimeOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.8.0")

        jarJar(group: 'net.tunamods.customglint', name: 'glint-and-glamour-api',
               version: '[1.8.0,2.0)') {
            jarJar.ranged(it, '[1.8.0,2.0)')
        }
    }

    jarJar.enable()

The branch in that url picks the Minecraft version. Use 1.20.1 for a Forge
1.20.1 build, or swap it for 1.21.1 to get the NeoForge 1.21.1 api. The
coordinate is the same on every branch, so only the url changes.

Every release stays up, so an older pin keeps resolving. Versions through
1.6.0 were published as custom-glint-api; 1.7.0 and later are
glint-and-glamour-api.

Pin both versions to the latest released api when you update. Build with
`./gradlew jarJar`; output at build/libs/yourmod-<version>-all.jar.

If multiple mods bundle the api, Forge dedupes to a single shared copy.

Required for any mod consuming mixin refmaps in dev (runClient / runServer).
Without these, SRG-targeted hooks silently fail to resolve against the
dev-mapped bytecode. Production builds are unaffected.

    runs {
        configureEach {
            property 'mixin.env.remapRefMap', 'true'
            property 'mixin.env.refMapRemappingFile',
                     "${projectDir}/build/createSrgToMcp/output.srg"
        }
    }

Alternative: declare as a hard / soft dep instead of bundling.

    [[dependencies.yourmodid]]
        modId="customglint_api"
        mandatory=true
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

NBT tag key is always "customglint", whether the api is bundled in your jar
or loaded from the standalone.


================================================================================
  Item API
================================================================================

All on net.tunamods.customglint.common.CustomGlint.

  CustomGlint.write(stack, design, colors, speed, interpolate, scale, simultaneous);
  CustomGlint.write(stack, design, colors, speed, interpolate, scale, simultaneous,
                    scrollDir, scrollOffset);                  // + scroll animation
  CustomGlint.write(stack, new CustomGlint.Layer[]{ ... });   // multi-layer
  CustomGlint.write(stack, design, colors);                   // shorthand
  CustomGlint.write(stack, design, color);                    // single-color shorthand

Each Layer carries design, colors, speed, interpolate, patternScale,
simultaneous, and a scroll direction + offset (CustomGlint.SCROLL_STATIC,
SCROLL_E, SCROLL_NE, ...). MAX_LAYERS and MAX_COLORS_PER_LAYER are both 8.
The CHROMATIC design is procedural; use CustomGlint.ensureChromaticSeeds(layers)
so each chromatic layer gets a stable random seed.

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

  // Glow animation, independent of the glint's own speed/interpolate.
  CustomGlint.setGlowAnim(stack, 1.0f, true);   // speed, smooth-blend
  CustomGlint.getGlowSpeed(stack);
  CustomGlint.getGlowInterpolate(stack);


Auto-apply registries

Register from your @Mod constructor. Do NOT use FMLCommonSetupEvent (parallel
thread pool; the registries are unsynchronized HashMaps).

  CustomGlint.registerCraftGlint(item, design, colors);
  CustomGlint.registerFishingGlint(item, design, colors);
  CustomGlint.registerMobDropGlint(item, design, colors);
  CustomGlint.registerLootGlint(lootTableRL, item, design, colors);

registerLootGlint is wired by the full standalone's loot modifier and is not
present in the bundled api jar.

The smithing table never fires ItemCraftedEvent, so a craft glint misses an
item made by smithing (a netherite upgrade). Use an item-type glint for that.


Item-type glints (1.8.0+)

Every stack of the item renders with the glint, however it was made (crafted,
smithed, /give, creative, other mods' loot), with nothing written to NBT.

  CustomGlint.registerItemGlint(Items.NETHERITE_SWORD, CustomGlint.EMBER,
      new int[]{0xFFFF5500, 0xFFFFAA00});
  CustomGlint.registerItemGlint(item, data);   // any Data: layers, chromatic
  CustomGlint.getItemGlint(item);

  - Render-time fallback: a stack's own glint always wins.
  - Glint layers only; the glow outline still comes from the stack's NBT.
  - read/has, recipes and the server never see it. Render code that reads
    glints should call CustomGlint.readCached, which includes it.
  - Register on both sides (the @Mod constructor is fine): it renders on the
    client.
  - A pack's /glint rule item (full jar) wins over your registration for the
    same item.


Glint rules (full jar, no code)

Pack authors set the same kind of thing up in-game with /glint rule, saved to
config/glint-and-glamour/rules.json:

  /glint rule loot <loot table>    item gets the glint when rolled from it
  /glint rule drop <entity type>   item gets the glint when that mob drops it
  /glint rule item                 every stack of the item shows the glint

loot and drop write NBT when the item is made (glow included); item renders
only and syncs to clients. See the Overview for the full player guide.


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
on the client: preview UIs, replay viewers, entities reconstructed from stored
NBT) render immediately without waiting for a server broadcast. If you need to
force a re-sync after manually editing the entity's persistent NBT, call the
client-only helper:

  EntityGlintRender.refreshClientCache(living);     // client-only


================================================================================
  Constants
================================================================================

Colors: CustomGlint.RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE,
PURPLE, MAGENTA, PINK, BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK (these match
Minecraft's dye colors). Custom hex via CustomGlint.color("FFD700"). The alpha
byte is a brightness multiplier (0xFF full, 0x00 invisible); blend mode is
additive. Near-black (RGB below ~0x18) renders invisible, so a black slot
leaves a deliberate gap in a design.

Designs: 56 ResourceLocation constants on CustomGlint (e.g. WAVE, SPARKLE,
AURORA), including VANILLA and the procedural CHROMATIC. Iterate with
CustomGlint.PATTERNS.

Iteration arrays: CustomGlint.ALL_COLORS (16), VIBRANT_COLORS (11), PATTERNS (56).


================================================================================
  What the api handles automatically
================================================================================

  - Held items in every context (1st / 3rd person, GUI, ground, item frame).
  - 3D held models with custom BEWLR renderers (tridents and similar).
  - Humanoid armor including modded armor that ships its own ArmorModel
    via ForgeHooksClient.
  - Elytra worn as chestplate.
  - Horse armor (vanilla + modded).
  - Living entities (body + overlay layers as one unioned outline ring).
  - Glowing outline pass on items and all four armor surfaces above.
  - Iris / Oculus shader-pack outline path.
  - Atlas-calibrated pattern scale on non-square block atlases.
  - Texture and RenderType eviction on resource pack reload.

NOT wired automatically: BEWLR renderers that bypass ItemRenderer.getFoilBuffer
and call MultiBufferSource.getBuffer(RenderType) themselves. Drive the pipeline
directly via the hooks below if your mod ships one.


================================================================================
  Specifically supported mods
================================================================================

These integrations ship in the FULL standalone jar only, NOT in the bundled
api jar. Each targets the other mod by class name with a soft mixin (no hard
dependency) and no-ops when the mod is absent, so they never affect a modpack
that lacks them.

  Ice and Fire          - troll weapons, death worm gauntlets, dragon armor,
                          hippogryph armor, and hippocampus armor take glints
                          and glow outlines.
  Epic Knights          - armor decoration overlays (capes, tabards, trims)
  (magistuarmory)         glint; chestplate outlines trace the visible armor.
  Epic Fight            - glowing entities keep their glow outline while Epic
                          Fight renders them with its own animated meshes.
  Iron's Spells         - GeckoLib-rendered armor takes glints and glow
  ('n Spellbooks)         outlines while worn; also covers other mods whose
                          armor draws through GeckoLib's GeoArmorRenderer.
  Immersive Armors      - the mod's layered armor takes glints and glow
                          outlines while worn, even though it draws each piece
                          with its own model instead of the vanilla armor layer.
  Mekanism              - the MekaSuit, Jetpacks, Free Runners and their armored
                          variants, and the Scuba tank and mask take glints and
                          glow outlines while worn.
  Artifacts             - belts, necklaces, gloves, boots and other artifacts
                          worn in Curios slots take glints and glow outlines.
  Sophisticated          - backpacks glint across all of their render passes.
  Backpacks
  ElytraSlot            - elytra worn in the dedicated Curios slot glint like
                          a chestplate elytra.
  First-Person Model    - held-item glow outlines stay aligned in the 3.5D
                          body view.
  Punchy                - held-item glow outlines render with its first-person
                          animations.
  Gnetum                - animated glints and glow outlines don't freeze on the
                          hotbar under its HUD caching.
  Enhanced Visuals      - a glowing item no longer blacks out the screen; blood
                          and damage overlays draw correctly.
  Iris / Oculus         - glow outlines render consistently under shader packs.
  JEI                   - all Glint Trim, Glow Trim, Tear, smithing, powder,
                          and Rainbow Dye recipes/pages show in the recipe view.

Modded armor and held items that go through the normal ItemRenderer / armor
layer flow are handled automatically without a per-mod integration.


================================================================================
  Advanced rendering hooks
================================================================================

Client-only. Gate any reference with FMLEnvironment / DistExecutor.

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

  /give @p <item>{customglint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

Tag key: "customglint".
speed: 1.0 = 20 ticks / color. interpolate: 1b = smooth. simultaneous: 1b = all
colors at once. Alpha byte of each color int = brightness. Optional per-layer
scrollDir (int, 0 = static) and scrollOffset (float) drive the scroll animation;
chromatic layers also store a seed (int).

Colors in [I;...] are signed 32-bit ints. Any color with alpha >= 0x80 (i.e.
every full-brightness color) is negative in this form; the leading 0xFF makes
it exceed Integer.MAX_VALUE unsigned. Use the named constants from Java code,
the color names from /glint apply, or a hex-to-signed-int converter.

Add glowing:1b alongside layers for the colored outline:

  /give @p minecraft:diamond_sword{customglint:{glowing:1b,layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

Remove: /item replace entity @s weapon.mainhand nbt remove customglint
