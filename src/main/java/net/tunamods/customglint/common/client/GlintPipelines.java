package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;

import java.util.function.Supplier;

import net.tunamods.customglint.common.CustomGlint;

/**
 * The 26.1 render-pipeline foundation for the glint backend. Split out of
 * {@link CustomGlintRenderer} so the immutable GPU building blocks live in one place and can be reused
 * by every {@code forXxx} RenderType factory.
 *
 * <p>The 1.20-era {@code RenderStateShard}/{@code CompositeState}
 * model is gone; GPU state is now an immutable {@link RenderPipeline} wrapped by a {@link RenderSetup}
 * and finalized with {@link RenderType#create(String, RenderSetup)}. Per-RenderType setup runnables
 * (the old {@code setShaderColor}/{@code setTextureMatrix} hooks) no longer exist, color rides the
 * vertex color (custom {@code customglint:core/glint_color} shader, POSITION_TEX_COLOR), animation
 * rides a per-draw {@link TextureTransform} supplier, and per-call-site depth state is set via
 * {@link DepthStencilState} (the stencil two-pass was removed).
 */
public final class GlintPipelines {
    private GlintPipelines() {}

    /** Custom colored-glint shader (vanilla core/glint plus a per-vertex Color so simultaneous
     *  multi-color layers batch in one draw). Files live at
     *  {@code assets/customglint/shaders/core/glint_color.{vsh,fsh}}. */
    public static final Identifier GLINT_COLOR_SHADER =
            CustomGlint.res("core/glint_color");

    /** Custom GUI item-outline FRAGMENT shader: emits the flat per-vertex colour masked by the bound
     *  texture's alpha (pairs with vanilla {@code core/position_tex_color.vsh}). Used by
     *  {@link #GUI_ITEM_OUTLINE} for the inventory-icon glow halo. File at
     *  {@code assets/customglint/shaders/core/gui_item_outline.fsh}. */
    public static final Identifier GUI_ITEM_OUTLINE_SHADER =
            CustomGlint.res("core/gui_item_outline");

    /**
     * GUI blit pipeline for the inventory-icon glow halo. Derived from vanilla {@code GUI_TEXTURED}
     * (2D pose, POSITION_TEX_COLOR, TRANSLUCENT blend, Sampler0) with only the fragment shader swapped
     * to {@link #GUI_ITEM_OUTLINE_SHADER}, so a {@code BlitRenderState} of the item's atlas slot draws
     * the slot's silhouette in a flat colour (the blit's vertex colour = the glow colour) rather than the
     * item texture tinted. {@code GuiRendererMixin} blits this at 1-px offsets behind the real item.
     */
    public static final RenderPipeline GUI_ITEM_OUTLINE = RenderPipelines.GUI_TEXTURED.toBuilder()
            .withLocation(CustomGlint.res("pipeline/gui_item_outline"))
            .withFragmentShader(GUI_ITEM_OUTLINE_SHADER)
            .build();

    /** Custom GUI item-GLINT shader (vertex + fragment): draws the animated glint live over a glinted
     *  item's cached icon so the base icon stays cached instead of re-baking every frame. Files at
     *  {@code assets/customglint/shaders/core/gui_item_glint.{vsh,fsh}}. */
    public static final Identifier GUI_ITEM_GLINT_SHADER =
            CustomGlint.res("core/gui_item_glint");

    /** Vertex format for {@link #GUI_ITEM_GLINT}. Stock elements only: UV1/UV2 carry the per-layer
     *  animation payload (scroll scalars f,f1 and packed patternScale,guiScale) since the batched GUI
     *  draw path has no per-draw uniform hook. UV0 = atlas slot coords, Color = the animated layer colour. */
    public static final VertexFormat GUI_GLINT_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Color", VertexFormatElement.COLOR)
            .build();

    /**
     * Live GUI glint overlay pipeline. Derived from vanilla {@code GUI_TEXTURED} (2D pose, no depth) with
     * the custom vertex+fragment shader, the two-UV-payload vertex format, a second sampler ({@code Sampler1}
     * = the grayscale design; {@code Sampler0} = the cached item slot), and {@link BlendFunction#GLINT} so
     * the overlay squares + adds onto the icon exactly like the world glint. {@code GuiRendererMixin} emits
     * one {@code GuiItemGlintRenderState} per glint layer/colour as a glyph, so it draws on top of the icon.
     */
    public static final RenderPipeline GUI_ITEM_GLINT = RenderPipelines.GUI_TEXTURED.toBuilder()
            .withLocation(CustomGlint.res("pipeline/gui_item_glint"))
            .withVertexShader(GUI_ITEM_GLINT_SHADER)
            .withFragmentShader(GUI_ITEM_GLINT_SHADER)
            .withVertexFormat(GUI_GLINT_FORMAT, VertexFormat.Mode.QUADS)
            .withSampler("Sampler1")
            .withColorTargetState(new ColorTargetState(BlendFunction.GLINT))
            .build();

    /** Custom GUI procedural-chromatic shader (vertex + fragment): the GUI analog of {@link #CHROMATIC_SHADER},
     *  drawing the live oil-slick over a glinted item's cached icon. Files at
     *  {@code assets/customglint/shaders/core/gui_chromatic.{vsh,fsh}}. */
    public static final Identifier GUI_CHROMATIC_SHADER = CustomGlint.res("core/gui_chromatic");

    /**
     * Live GUI chromatic-overlay pipeline. Like {@link #GUI_ITEM_GLINT} (GUI_TEXTURED base, the two-UV
     * payload vertex format, {@code Sampler1}, {@link BlendFunction#GLINT}) but with the procedural-chromatic
     * shader pair: {@code Sampler0} = the cached item slot (silhouette mask), {@code Sampler1} = the palette
     * strip. {@code GuiItemChromaticRenderState} emits one glyph per chromatic glint layer.
     */
    public static final RenderPipeline GUI_ITEM_CHROMATIC = RenderPipelines.GUI_TEXTURED.toBuilder()
            .withLocation(CustomGlint.res("pipeline/gui_item_chromatic"))
            .withVertexShader(GUI_CHROMATIC_SHADER)
            .withFragmentShader(GUI_CHROMATIC_SHADER)
            .withVertexFormat(GUI_GLINT_FORMAT, VertexFormat.Mode.QUADS)
            .withSampler("Sampler1")
            .withColorTargetState(new ColorTargetState(BlendFunction.GLINT))
            .build();

    /** Silhouette shader for the isolated glow target: writes a per-fragment distance falloff into the
     *  mask's alpha (read by the composite as a 0..1 thickness scale) so the ring thins with distance like
     *  the old geometry-dilated ring did. */
    public static final Identifier GLOW_SILHOUETTE_SHADER =
            CustomGlint.res("core/glow_silhouette");

    /**
     * Base colored-glint pipeline. Derived from vanilla {@link RenderPipelines#GLINT} so it inherits
     * the matrices/fog/globals uniform blocks, then: custom shader, vertex format with color,
     * {@code BlendFunction.GLINT} (carried over from the vanilla glint color target), depth test
     * EQUAL with no depth write, cull off. Depth/stencil/layering that vary per call site are
     * applied on the derived pipelines / setups, not here.
     */
    public static final RenderPipeline GLINT_COLOR = RenderPipelines.GLINT.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_color"))
            .withVertexShader(GLINT_COLOR_SHADER)
            .withFragmentShader(GLINT_COLOR_SHADER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false))
            .withCull(false)
            .build();

    /** {@link #GLINT_COLOR} with {@link CompareOp#LESS_THAN_OR_EQUAL} depth (still no write) for entity LAYER
     *  surfaces (sheep wool, slime outer cube, saddles, …). Those layers are drawn by a DIFFERENT pipeline than
     *  our glint and sit flush on / translucent over the base body, so an EQUAL test flickers per-fragment on
     *  the ~1 ULP depth difference between the two rasterisations (the same failure {@link #GLINT_BLOCK} hit).
     *  LEQUAL is deterministic (never flickers); paired with a toward-camera {@code VIEW_OFFSET_Z} nudge at the
     *  call site it sits the glint just in front of the layer surface. The base body keeps {@link #GLINT_COLOR}
     *  (EQUAL): it rasterises identically to its own draw, so EQUAL is stable and tighter there. */
    public static final RenderPipeline GLINT_LEQUAL = GLINT_COLOR.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_lequal"))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    // ── Procedural chromatic glint ───────────────────────────────────────────────

    /** Custom procedural-chromatic shader (vertex + fragment): synthesises an oil-slick from value-noise
     *  instead of sampling a design texture; Sampler1 carries the up-to-8-colour palette. Files at
     *  {@code assets/customglint/shaders/core/chromatic.{vsh,fsh}}. */
    public static final Identifier CHROMATIC_SHADER = CustomGlint.res("core/chromatic");

    /** Chromatic pipeline, {@link #GLINT_COLOR} with the chromatic shader pair and a second sampler
     *  ({@code Sampler1} = the palette strip; {@code Sampler0} stays bound to a 1×1 white dummy the shader
     *  never reads, satisfying the inherited binding). Same GLINT blend / EQUAL-no-write depth / no-cull. */
    public static final RenderPipeline CHROMATIC = GLINT_COLOR.toBuilder()
            .withLocation(CustomGlint.res("pipeline/chromatic"))
            .withVertexShader(CHROMATIC_SHADER)
            .withFragmentShader(CHROMATIC_SHADER)
            .withSampler("Sampler1")
            .build();

    /** Block-glint shader pair (vertex + fragment): {@link #GLINT_COLOR} plus a second sampler
     *  ({@code Sampler1} = the block atlas) that the fragment shader alpha-tests to mask the glint to the
     *  block model's cutout shape. Files at {@code assets/customglint/shaders/core/glint_block.{vsh,fsh}}. */
    public static final Identifier GLINT_BLOCK_SHADER = CustomGlint.res("core/glint_block");

    /**
     * Glint pipeline for block-model entity layers (mooshroom mushrooms, snow-golem pumpkin). Two differences
     * from {@link #GLINT_COLOR}:
     * <ul>
     * <li><b>{@link CompareOp#LESS_THAN_OR_EQUAL} depth</b> (no write) + a {@code VIEW_OFFSET_Z} bias at the
     *     call site. The block is drawn by a DIFFERENT pipeline, so {@code EQUAL} flickers per-fragment on the
     *     ~1 ULP depth difference between the two rasterisations; LEQUAL + a toward-camera nudge sits the glint
     *     just in front instead.</li>
     * <li><b>{@code Sampler1} = the block atlas + the {@link #GLINT_BLOCK_SHADER} pair</b>, which alpha-tests
     *     the block texture so the glint is masked to the cutout silhouette. With EQUAL gone, depth no longer
     *     does the cutout masking (it would tile over the whole quad plane), so the shader does it.</li>
     * </ul>
     */
    public static final RenderPipeline GLINT_BLOCK = GLINT_COLOR.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_block"))
            .withVertexShader(GLINT_BLOCK_SHADER)
            .withFragmentShader(GLINT_BLOCK_SHADER)
            .withSampler("Sampler1")
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    /** Chromatic block-glint shader pair: {@link #CHROMATIC} plus a THIRD sampler ({@code Sampler2} = the
     *  block atlas; Sampler1 is already the palette) the fragment shader alpha-tests to mask the oil-slick to
     *  the block's cutout shape. Files at {@code assets/customglint/shaders/core/chromatic_block.{vsh,fsh}}. */
    public static final Identifier CHROMATIC_BLOCK_SHADER = CustomGlint.res("core/chromatic_block");

    /** Chromatic counterpart of {@link #GLINT_BLOCK} for chromatic glint on block-model layers: LEQUAL depth
     *  (no write) + the {@link #CHROMATIC_BLOCK_SHADER} pair + {@code Sampler2} = the block atlas, so the
     *  oil-slick is masked to the block's cutout shape exactly like {@link #GLINT_BLOCK} does for normal glint. */
    public static final RenderPipeline CHROMATIC_BLOCK = CHROMATIC.toBuilder()
            .withLocation(CustomGlint.res("pipeline/chromatic_block"))
            .withVertexShader(CHROMATIC_BLOCK_SHADER)
            .withFragmentShader(CHROMATIC_BLOCK_SHADER)
            .withSampler("Sampler2")
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    // ── Procedural chromatic POST-IRIS overlay ───────────────────────────────────
    //
    // Under an active shader pack the chromatic glint can't draw in-phase: Iris replaces our procedural
    // program with one of its own (the glint goes flat white, the chromatic analog of the GLINT_COLOR
    // white bug IrisCompat fixes for normal glint, except here there is no pack program that can recreate
    // a procedural oil-slick, so assignPipeline can't help). Instead, under a pack the chromatic layer is
    // queued (EntityGlintRender.queueChromaticModel) and re-rendered AFTER Iris finishes the frame, onto an
    // isolated target, then composited back. The overlay pipeline samples the committed scene depth itself
    // for occlusion (DepthSampler) instead of relying on a GPU depth test against Iris's gbuffer, see
    // core/chromatic_overlay.{vsh,fsh}.

    /** Custom procedural-chromatic OVERLAY shader (vertex + fragment): the post-Iris counterpart of
     *  {@link #CHROMATIC_SHADER}, with a per-fragment scene-depth occlusion test. Files at
     *  {@code assets/customglint/shaders/core/chromatic_overlay.{vsh,fsh}}. */
    public static final Identifier CHROMATIC_OVERLAY_SHADER = CustomGlint.res("core/chromatic_overlay");

    /** Post-Iris chromatic overlay pipeline, {@link #CHROMATIC} with the overlay shader pair, an extra
     *  {@code DepthSampler} (the scene depth, sampled in-shader for occlusion), and depth test {@code ALWAYS}
     *  with no write: occlusion is decided by the shader, and the geometry is re-rendered into our OWN target
     *  (whose depth attachment is unrelated to the sampled scene depth), so the GPU depth test is not used. */
    public static final RenderPipeline CHROMATIC_OVERLAY = CHROMATIC.toBuilder()
            .withLocation(CustomGlint.res("pipeline/chromatic_overlay"))
            .withVertexShader(CHROMATIC_OVERLAY_SHADER)
            .withFragmentShader(CHROMATIC_OVERLAY_SHADER)
            .withSampler("DepthSampler")
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build();

    /** LOOSE-occlusion fragment shader for the chromatic overlay: shares {@link #CHROMATIC_OVERLAY_SHADER}'s
     *  vertex stage, swaps only the fragment for a flat generous bias (no fwidth slope). File at
     *  {@code assets/customglint/shaders/core/chromatic_overlay_loose.fsh}. */
    public static final Identifier CHROMATIC_OVERLAY_LOOSE_SHADER = CustomGlint.res("core/chromatic_overlay_loose");

    /** {@link #CHROMATIC_OVERLAY} with the loose fragment shader, for TRANSLUCENT entity-layer shells (slime
     *  outer cube) whose committed scene depth is re-sorted every frame under Iris. The chromatic twin of
     *  {@link #GLINT_OVERLAY_LOOSE}: the standard variant's slope-scaled bias self-occludes that wobble and
     *  drops the slick out per-face, this flat 0.10-block bias absorbs it while still occluding against
     *  clearly nearer opaque geometry. Same samplers/blend/depth as {@link #CHROMATIC_OVERLAY}; only the
     *  fragment program differs. */
    public static final RenderPipeline CHROMATIC_OVERLAY_LOOSE = CHROMATIC_OVERLAY.toBuilder()
            .withLocation(CustomGlint.res("pipeline/chromatic_overlay_loose"))
            .withFragmentShader(CHROMATIC_OVERLAY_LOOSE_SHADER)
            .build();

    /** {@link #CHROMATIC_OVERLAY} for thin double-sided equipment (elytra wings, capes) drawn post-Iris:
     *  depth test {@link CompareOp#LESS_THAN_OR_EQUAL} (still no write) against the isolated target's OWN
     *  depth. The two folded wings overlap along the spine at near-coincident depth, which the in-shader
     *  scene occlusion (a biased inequality) can't separate, so both draw and the additive slick doubles into
     *  a bright seam. The overlay drain primes the isolated depth with the nearest surface first ({@link
     *  #WING_DEPTH}), and this LEQUAL then keeps only that nearest surface per pixel. (World occlusion is
     *  still the fragment shader's DepthSampler test.)
     *
     *  <p>TRIED back-face culling here instead of the depth pre-pass: it de-doubles, but the elytra mesh has
     *  a MIRRORED right wing ({@code CubeListBuilder.mirror()} swaps the box corners, so its winding is
     *  reversed, which is why vanilla draws elytra {@code noCull}). Culling therefore eats the wrong faces
     *  and the wing's underside loses its glint entirely. Do not re-enable cull on any wing pipeline. */
    public static final RenderPipeline CHROMATIC_OVERLAY_WING = CHROMATIC_OVERLAY.toBuilder()
            .withLocation(CustomGlint.res("pipeline/chromatic_overlay_wing"))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    // ── Normal-glint POST-IRIS overlay ───────────────────────────────────────────
    //
    // Under an active shader pack a NORMAL (non-chromatic) glint layer can't draw in-phase either: Iris
    // substitutes our GLINT_COLOR program for one of its own, and every gbuffer entity program it can pick
    // is OPAQUE (EMISSIVE_ENTITIES replaces the surface rather than adding onto it; IrisCompat picks it to
    // keep our per-vertex colour, but the trade is that the design paints SOLID over the item). The glint
    // program (ARMOR_GLINT) does blend additively but Iris rewrites gl_Color→ColorModulator there, dropping
    // our multi-colour. So no single Iris program gives colour AND translucency; instead the layer is queued
    // (EntityGlintRender.queueGlintOverlayXxx) and re-rendered AFTER Iris finishes the frame onto an isolated
    // target with OUR shader + OUR GLINT blend, then composited back, exactly the chromatic overlay path,
    // reusing the same drain + composite (CHROMATIC_COMPOSITE_PIPE). Off the shader path this never runs;
    // normal glint draws in-phase as before.

    /** Custom NORMAL-glint OVERLAY shader (vertex + fragment): the post-Iris counterpart of
     *  {@link #GLINT_COLOR_SHADER}, sampling the scrolling grayscale design (Sampler0) tinted by the
     *  per-vertex colour, cut out against a model/atlas texture (Sampler1), with a per-fragment scene-depth
     *  occlusion test. Files at {@code assets/customglint/shaders/core/glint_overlay.{vsh,fsh}}. */
    public static final Identifier GLINT_OVERLAY_SHADER = CustomGlint.res("core/glint_overlay");

    /** Post-Iris normal-glint overlay pipeline: {@link #GLINT_COLOR} (GLINT blend, POSITION_TEX_COLOR, no
     *  cull) with the overlay shader pair, a {@code Sampler1} (the cutout texture), a {@code DepthSampler}
     *  (scene depth, sampled in-shader for occlusion), and depth test {@code ALWAYS} with no write (the
     *  geometry re-renders into our OWN target, so the GPU depth test isn't used; occlusion is the shader's). */
    public static final RenderPipeline GLINT_OVERLAY = GLINT_COLOR.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_overlay"))
            .withVertexShader(GLINT_OVERLAY_SHADER)
            .withFragmentShader(GLINT_OVERLAY_SHADER)
            .withSampler("Sampler1")
            .withSampler("DepthSampler")
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build();

    /** LOOSE-occlusion fragment shader for the overlay: shares {@link #GLINT_OVERLAY_SHADER}'s vertex stage,
     *  swaps only the fragment for a flat generous bias (no fwidth slope). File at
     *  {@code assets/customglint/shaders/core/glint_overlay_loose.fsh}. */
    public static final Identifier GLINT_OVERLAY_LOOSE_SHADER = CustomGlint.res("core/glint_overlay_loose");

    /** {@link #GLINT_OVERLAY} with the loose fragment shader, for TRANSLUCENT entity-layer shells (slime outer
     *  cube) whose committed scene depth is re-sorted every frame under Iris. The tight variant's ~mm floor
     *  self-occludes that wobble and drops the shell out per-face; this flat 0.10-block bias absorbs it while
     *  still occluding against clearly nearer opaque geometry. Same samplers/blend/depth as {@link
     *  #GLINT_OVERLAY}; only the fragment program differs. */
    public static final RenderPipeline GLINT_OVERLAY_LOOSE = GLINT_OVERLAY.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_overlay_loose"))
            .withFragmentShader(GLINT_OVERLAY_LOOSE_SHADER)
            .build();

    /** {@link #GLINT_OVERLAY} for thin double-sided equipment (elytra wings, capes) drawn post-Iris: depth
     *  test {@link CompareOp#LESS_THAN_OR_EQUAL} (no write) against the isolated target's OWN depth. The two
     *  folded wings overlap along the spine at near-coincident depth that the biased in-shader occlusion can't
     *  split, so the overlay drain first primes the isolated depth with the nearest surface ({@link
     *  #WING_DEPTH}) and this LEQUAL keeps only that one per pixel instead of additively doubling the seam.
     *  (World occlusion is still the fragment shader's DepthSampler test.) Cull stays OFF, see the mirrored-
     *  wing note on {@link #CHROMATIC_OVERLAY_WING}. Quad items use it too, so a 3D item (a backpack) hides
     *  its own far faces, which the scene-depth test can't under Iris's squashed hand depth. */
    public static final RenderPipeline GLINT_OVERLAY_WING = GLINT_OVERLAY.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_overlay_wing"))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    // ── Wing depth pre-pass (folded-elytra spine-seam fix, shader path only) ──────────────────────

    /** Depth-only wing pre-pass shader (vertex + fragment): writes depth on the wing cutout shape and nothing
     *  to colour. Files at {@code assets/customglint/shaders/core/overlay_depth.{vsh,fsh}}. */
    public static final Identifier OVERLAY_DEPTH_SHADER = CustomGlint.res("core/overlay_depth");

    /**
     * Wing depth PRE-PASS pipeline. Re-rendered first in the overlay drain to prime the isolated target's
     * depth with the NEAREST wing surface, so the wing colour pass ({@link #GLINT_OVERLAY_WING} / {@link
     * #CHROMATIC_OVERLAY_WING}, LEQUAL) drops every farther overlapping surface instead of additively doubling
     * it into a bright spine seam.
     * <ul>
     * <li><b>{@link CompareOp#LESS_THAN_OR_EQUAL} depth + WRITE</b> against the isolated target's own depth
     *     (cleared to far each frame): keeps the minimum (nearest) wing depth per pixel, order-independently.</li>
     * <li><b>Cull OFF</b>, matching the colour pass, so the mirrored wing's faces seed the depth too (see the
     *     note on {@link #CHROMATIC_OVERLAY_WING}). With no cull the pre-pass sees BOTH faces of a wing and
     *     keeps the nearer one, which is what culling was there to do, so nothing is lost by dropping it.</li>
     * <li><b>{@link #OVERLAY_DEPTH_SHADER}</b> alpha-tests {@code Sampler0} (the equipment texture) to the
     *     wing silhouette and outputs {@code vec4(0)}, a no-op under the inherited GLINT blend, so this pass
     *     never touches colour. gl_Position is bit-identical to both colour-pass vertex shaders, so their
     *     LEQUAL test against this depth is exact.</li>
     * </ul>
     */
    public static final RenderPipeline WING_DEPTH = GLINT_COLOR.toBuilder()
            .withLocation(CustomGlint.res("pipeline/wing_depth"))
            .withVertexShader(OVERLAY_DEPTH_SHADER)
            .withFragmentShader(OVERLAY_DEPTH_SHADER)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .build();

    /** Fragment shader for the chromatic overlay composite (passthrough blit; blend is on the pipeline).
     *  File at {@code assets/customglint/shaders/post/chromatic_composite.fsh}. */
    public static final Identifier CHROMATIC_COMPOSITE_SHADER = CustomGlint.res("post/chromatic_composite");

    /** Composites the isolated overlay target back onto the main target. Blend is ADDITIVE, NOT GLINT: the
     *  overlay pipeline already applied the GLINT square once when it rendered into the isolated target, so the
     *  target holds {@code src²}. Compositing that with GLINT would square it AGAIN ({@code src⁴}), much more
     *  contrasty/vibrant than the single-square in-phase draw (the "chromatic too vibrant under shaders"
     *  report). A plain additive blend adds {@code src²} straight onto the scene, exactly matching the in-phase
     *  {@code src² + dst}. screenquad vertex + the passthrough fragment above; driven via {@code
     *  RenderPass.setPipeline}, so it must be registered through {@code RegisterRenderPipelinesEvent}. */
    public static final RenderPipeline CHROMATIC_COMPOSITE_PIPE = RenderPipeline.builder()
            .withLocation(CustomGlint.res("pipeline/chromatic_composite"))
            .withVertexShader(CustomGlint.res("core/screenquad"))
            .withFragmentShader(CHROMATIC_COMPOSITE_SHADER)
            .withSampler("InSampler")
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    /** CLAMP + NEAREST: no wrap past the edges, no interpolation between texels. What every binding read by
     *  exact texel wants, palette strips, the block-atlas cutout mask, and the scene-depth sample. */
    private static GpuSampler clampNearest() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    /** CLAMP + NEAREST sampler for the palette strip, colours are read by exact {@code texelFetch}, so no
     *  wrap or interpolation is wanted. */
    public static GpuSampler paletteSampler() {
        return clampNearest();
    }

    /** REPEAT + LINEAR sampler for the scrolling, tiling grayscale glint texture. LINEAR (not NEAREST):
     *  the animation scrolls the design via a texture matrix, and with NEAREST a slow scroll only changes
     *  the sampled texel when it crosses a texel boundary, smooth in the high-res world view (the steps
     *  spread across many pixels) but visibly CHOPPY at low speed in the small GUI item slot (few pixels,
     *  synchronized steps). LINEAR interpolates between texels so sub-texel movement is smooth at any
     *  resolution and speed, matching vanilla's glint (which also filters). Wrap/filter live on the
     *  per-binding {@link GpuSampler} now (was {@code glTexParameteri} on the texture). */
    public static GpuSampler glintSampler() {
        return RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
    }

    /**
     * Builds a chromatic {@link RenderType}: {@code whiteTex} on Sampler0 (dummy), {@code paletteTex} on
     * Sampler1, the given layering, and the seed/speed/count-packed animation matrix from
     * {@link #chromaticMatrix}.
     */
    public static RenderType chromaticType(String name, Identifier whiteTex, Identifier paletteTex,
                                           LayeringTransform layering, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(CHROMATIC)
                .withTexture("Sampler0", whiteTex, GlintPipelines::glintSampler)
                .withTexture("Sampler1", paletteTex, GlintPipelines::paletteSampler)
                .setLayeringTransform(layering)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /**
     * Builds a post-Iris chromatic OVERLAY {@link RenderType} on {@link #CHROMATIC_OVERLAY}: {@code modelTex}
     * on Sampler0 (the model texture, its alpha drives the cutout silhouette, so a rectangular elytra/armor
     * mesh rings only the real wing/armor shape), {@code paletteTex} on Sampler1, {@code sceneDepth} on
     * DepthSampler (the per-frame holder bound by {@code CustomGlintRenderer.bindSceneDepth}),
     * {@link LayeringTransform#NO_LAYERING} (it draws to its own target, not over the model), and the
     * seed/speed/count-packed animation matrix.
     */
    public static RenderType chromaticOverlayType(String name, Identifier modelTex, Identifier paletteTex,
                                                  Identifier sceneDepth, Supplier<Matrix4f> animation) {
        return chromaticOverlayType(name, modelTex, paletteTex, sceneDepth, animation, false);
    }

    /** {@code wing=true} routes onto {@link #CHROMATIC_OVERLAY_WING} for thin double-sided equipment (elytra
     *  wings), so the additive slick doesn't double where the two wing faces overlap. */
    public static RenderType chromaticOverlayType(String name, Identifier modelTex, Identifier paletteTex,
                                                  Identifier sceneDepth, Supplier<Matrix4f> animation, boolean wing) {
        return chromaticOverlayType(wing ? CHROMATIC_OVERLAY_WING : CHROMATIC_OVERLAY, name, modelTex, paletteTex,
                sceneDepth, animation);
    }

    /** LOOSE-occlusion counterpart of {@link #chromaticOverlayType} on {@link #CHROMATIC_OVERLAY_LOOSE}, for
     *  translucent entity-layer shells (slime outer cube). Identical payload; only the fragment shader's bias
     *  differs. */
    public static RenderType chromaticOverlayLooseType(String name, Identifier modelTex, Identifier paletteTex,
                                                       Identifier sceneDepth, Supplier<Matrix4f> animation) {
        return chromaticOverlayType(CHROMATIC_OVERLAY_LOOSE, name, modelTex, paletteTex, sceneDepth, animation);
    }

    private static RenderType chromaticOverlayType(RenderPipeline pipeline, String name, Identifier modelTex,
                                                   Identifier paletteTex, Identifier sceneDepth, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", modelTex)   // model texture: the cutout alpha-test silhouette
                .withTexture("Sampler1", paletteTex, GlintPipelines::paletteSampler)
                .withTexture("DepthSampler", sceneDepth, GlintPipelines::clampNearest)
                .setLayeringTransform(LayeringTransform.NO_LAYERING)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /**
     * Builds a post-Iris NORMAL-glint OVERLAY {@link RenderType} on {@link #GLINT_OVERLAY}: {@code grayDesign}
     * on Sampler0 (the scrolling grayscale pattern, animated by {@code animation} exactly like the in-phase
     * RT), {@code cutoutTex} on Sampler1 (its alpha cuts the glint to the real model/sprite silhouette; pass
     * a white dummy for a full-shape fill), {@code sceneDepth} on DepthSampler (the per-frame holder bound by
     * {@code CustomGlintRenderer.bindSceneDepth}), and {@link LayeringTransform#NO_LAYERING} (it draws to its
     * own target). The per-layer glint colour rides the vertices (the drain passes it as tintedColor), so it
     * is not baked into the RenderType, mirroring the in-phase glint. Counterpart of {@link
     * #chromaticOverlayType} for non-procedural designs.
     */
    public static RenderType glintOverlayType(String name, Identifier grayDesign, Identifier cutoutTex,
                                              Identifier sceneDepth, Supplier<Matrix4f> animation) {
        return glintOverlayType(GLINT_OVERLAY, name, grayDesign, cutoutTex, sceneDepth, animation);
    }

    /** {@code wing=true} routes onto {@link #GLINT_OVERLAY_WING} for thin double-sided equipment (elytra
     *  wings), so the additive glint doesn't double where the two wing faces overlap. */
    public static RenderType glintOverlayType(String name, Identifier grayDesign, Identifier cutoutTex,
                                              Identifier sceneDepth, Supplier<Matrix4f> animation, boolean wing) {
        return glintOverlayType(wing ? GLINT_OVERLAY_WING : GLINT_OVERLAY, name, grayDesign, cutoutTex, sceneDepth, animation);
    }

    /** LOOSE-occlusion counterpart of {@link #glintOverlayType} on {@link #GLINT_OVERLAY_LOOSE}, for
     *  translucent entity-layer shells (slime outer cube). Identical payload; only the fragment shader's bias
     *  differs. */
    public static RenderType glintOverlayLooseType(String name, Identifier grayDesign, Identifier cutoutTex,
                                                   Identifier sceneDepth, Supplier<Matrix4f> animation) {
        return glintOverlayType(GLINT_OVERLAY_LOOSE, name, grayDesign, cutoutTex, sceneDepth, animation);
    }

    private static RenderType glintOverlayType(RenderPipeline pipeline, String name, Identifier grayDesign,
                                               Identifier cutoutTex, Identifier sceneDepth, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", grayDesign, GlintPipelines::glintSampler)
                .withTexture("Sampler1", cutoutTex)   // model/atlas texture: the cutout alpha-test silhouette
                .withTexture("DepthSampler", sceneDepth, GlintPipelines::clampNearest)
                .setLayeringTransform(LayeringTransform.NO_LAYERING)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /** Depth-only pre-pass {@link RenderType} for folded elytra/cape wings on {@link #WING_DEPTH}: {@code
     *  cutout} (the equipment texture) on Sampler0 drives the wing-shape alpha-test. It writes depth, not
     *  colour, so no design/animation/scene depth is needed and the normal and chromatic wing colour passes
     *  share it (depth depends only on geometry + cutout). Runs first in the overlay drain, into the isolated
     *  target's depth, so the wing colour pass LEQUAL-tests against it. */
    public static RenderType wingDepthType(String name, Identifier cutout) {
        RenderSetup setup = RenderSetup.builder(WING_DEPTH)
                .withTexture("Sampler0", cutout)
                .setLayeringTransform(LayeringTransform.NO_LAYERING)
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /** Chromatic block-model-layer RenderType on {@link #CHROMATIC_BLOCK}: {@code whiteTex} on Sampler0
     *  (dummy), {@code paletteTex} on Sampler1, and {@code blockAtlas} on Sampler2 (the cutout mask the
     *  fragment shader alpha-tests). The block analog of {@link #chromaticType}. */
    public static RenderType chromaticBlockType(String name, Identifier whiteTex, Identifier paletteTex,
                                                Identifier blockAtlas, LayeringTransform layering, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(CHROMATIC_BLOCK)
                .withTexture("Sampler0", whiteTex, GlintPipelines::glintSampler)
                .withTexture("Sampler1", paletteTex, GlintPipelines::paletteSampler)
                .withTexture("Sampler2", blockAtlas, GlintPipelines::clampNearest)
                .setLayeringTransform(layering)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /**
     * The chromatic animation matrix: the pattern-scale on the noise UV, taken about the UV CENTRE exactly
     * like {@link #itemAnimationMatrix} / {@link #armorAnimationMatrix} do for the texture designs. Scaling
     * from the raw origin instead pins the noise to the (0,0) corner of the surface, so turning the scale
     * knob looked like the slick was growing out of the item's top-left corner rather than getting denser in
     * place. The per-layer payload the immutable pipeline can't pass as a uniform is packed into spare matrix
     * slots the 2D UV transform never touches, {@code m20}=morph speed, {@code m21}=colour count,
     * {@code m23}=per-trim seed. The visible flow is driven by {@code GameTime} in the shader, so this matrix
     * itself need not animate.
     */
    public static Matrix4f chromaticMatrix(double speed, float patternScale, int colorCount, float seedPacked) {
        float s = Math.max(0.0001f, patternScale); // floor: a 0 scale collapses the UV transform to a point
        Matrix4f m = new Matrix4f().translation(0.5f, 0.5f, 0.0f);
        m.scale(s, s, 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        m.m20((float) speed);
        m.m21((float) colorCount);
        m.m23(seedPacked);
        return m;
    }

    /**
     * Builds a glint {@link RenderType} off {@link #GLINT_COLOR} for the given grayscale texture,
     * layering transform, and animation matrix supplier. Item vs armor vs horse/mount differ only in
     * the {@code layering} (item/mount: {@link LayeringTransform#NO_LAYERING}; humanoid/elytra armor:
     * {@link LayeringTransform#VIEW_OFFSET_Z_LAYERING} to match {@code armor_cutout_no_cull}).
     */
    public static RenderType glintType(String name, Identifier grayTexture,
                                       LayeringTransform layering, Supplier<Matrix4f> animation) {
        return glintType(name, GLINT_COLOR, grayTexture, layering, animation);
    }

    /** As {@link #glintType(String, Identifier, LayeringTransform, Supplier)} but on a specific glint
     *  pipeline. */
    public static RenderType glintType(String name, RenderPipeline pipeline, Identifier grayTexture,
                                       LayeringTransform layering, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", grayTexture, GlintPipelines::glintSampler)
                .setLayeringTransform(layering)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /** Block-model-layer glint RenderType on {@link #GLINT_BLOCK}: {@code grayTexture} on Sampler0 (the glint
     *  design) and {@code blockAtlas} on Sampler1 (the cutout mask the fragment shader alpha-tests). NEAREST
     *  on the mask so the cutout edge stays crisp. */
    public static RenderType blockGlintType(String name, Identifier grayTexture, Identifier blockAtlas,
                                            LayeringTransform layering, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(GLINT_BLOCK)
                .withTexture("Sampler0", grayTexture, GlintPipelines::glintSampler)
                .withTexture("Sampler1", blockAtlas, GlintPipelines::clampNearest)
                .setLayeringTransform(layering)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    // ── Item-glint animation (forGlint variant) ─────────────────────────────────

    /** Unit UV-drift vector for a {@link CustomGlint} {@code SCROLL_*} direction. The pattern *appears* to
     *  move opposite to the UV drift, so these are negated from the screen direction: e.g. East (pattern
     *  moves right) drifts UV left (-U). Texture-V runs DOWN, so North (pattern up) drifts +V.
     *  {@code SCROLL_STATIC} returns (0,0). */
    public static float[] scrollUnit(int scrollDir) {
        return switch (scrollDir) {
            case CustomGlint.SCROLL_E  -> SU_E;
            case CustomGlint.SCROLL_NE -> SU_NE;
            case CustomGlint.SCROLL_N  -> SU_N;
            case CustomGlint.SCROLL_NW -> SU_NW;
            case CustomGlint.SCROLL_W  -> SU_W;
            case CustomGlint.SCROLL_SW -> SU_SW;
            case CustomGlint.SCROLL_S  -> SU_S;
            case CustomGlint.SCROLL_SE -> SU_SE;
            default -> SU_STATIC; // SCROLL_STATIC
        };
    }

    // Shared immutable unit-drift vectors (callers only read them), avoids a per-draw float[] allocation,
    // since the animation-matrix suppliers evaluate scrollUnit every frame. 0.70710677 = 1/√2, the
    // diagonal component that keeps the diagonal directions unit length.
    private static final float[] SU_E = {-1f, 0f}, SU_NE = {-0.70710677f, 0.70710677f},
            SU_N = {0f, 1f}, SU_NW = {0.70710677f, 0.70710677f}, SU_W = {1f, 0f},
            SU_SW = {0.70710677f, -0.70710677f}, SU_S = {0f, -1f}, SU_SE = {-0.70710677f, -0.70710677f},
            SU_STATIC = {0f, 0f};

    /** The item-glint scroll matrix: a plain centre-scale plus an additive drift along {@code scrollDir}
     *  at the wall-clock rate (or a frozen {@code scrollOffset} when {@code scrollDir == SCROLL_STATIC}).
     *  The motif orientation is fixed (no rotation) so changing direction only changes the drift, not the
     *  pattern, and it matches the GUI overlay shader (gui_item_glint.fsh) exactly. {@code scaleU/scaleV}
     *  are atlas-calibrated. (Earlier builds baked the vanilla three-rotation glint scroll here, which gives
     *  a richer "flow" but cannot be separated into an independent drift direction.) */
    public static Matrix4f itemAnimationMatrix(double speed, float scaleU, float scaleV, float patternScale,
                                               int colorIdx, int colorCount, int scrollDir, float scrollOffset) {
        float phase = (float) colorIdx / Math.max(1, colorCount);
        float[] scroll = scrollVector(speed, phase, scrollDir, scrollOffset);
        // Drift first, then scale about the texture centre (0.5, 0.5). Centre-pivot scaling keeps the pattern
        // from sliding off as patternScale grows (was most visible drifting a shield's glint downward).
        Matrix4f m = new Matrix4f().translation(scroll[0], scroll[1], 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * patternScale, scaleV * patternScale, 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        return m;
    }

    /** Armor / horse-armor / entity-body scroll matrix: the directional-drift form of {@link
     *  #itemAnimationMatrix} (so the {@code scrollDir}/{@code scrollOffset} controls work on worn armor and
     *  entities the same as on flat items), but scaled by {@code patternScale} alone, these surfaces sample
     *  their own 0..1 model UV, so there is no block-atlas scaleU/scaleV calibration. Replaces the old
     *  rotation-only glint matrix, which ignored the scroll direction entirely. */
    public static Matrix4f armorAnimationMatrix(double speed, float patternScale, int colorIdx, int colorCount,
                                                int scrollDir, float scrollOffset) {
        float phase = (float) colorIdx / Math.max(1, colorCount);
        float[] scroll = scrollVector(speed, phase, scrollDir, scrollOffset);
        Matrix4f m = new Matrix4f().translation(scroll[0], scroll[1], 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(patternScale);
        m.translate(-0.5f, -0.5f, 0.0f);
        return m;
    }

    /** The animated UV drift shared by the item and armor scroll matrices, returned as {@code {x, y}}.
     *  Timing mirrors vanilla's enchantment-glint cadence (GlintTexture): wall-clock scaled 8x by speed, then
     *  folded through a 110000 ms slow carrier and a 30000 ms fast wobble that sum into the drift. */
    private static float[] scrollVector(double speed, float phase, int scrollDir, float scrollOffset) {
        if (scrollDir == CustomGlint.SCROLL_STATIC) {
            // No animation, so each color of a simultaneous layer would otherwise sample the SAME UV and
            // stack exactly on top of one another. Spread them by their phase (same per-color offset the
            // animated path folds into f) so the colors fan out across the pattern instead of overlapping.
            return new float[]{scrollOffset + phase, 0.0f};
        }
        long t = (long) (Util.getMillis() * 8.0 * speed);
        float f  = (float) (t % 110000L) / 110000.0F + phase;
        float f1 = (float) (t % 30000L)  /  30000.0F;
        float[] dir = scrollUnit(scrollDir);
        return new float[]{(f + f1) * dir[0], (f + f1) * dir[1]};
    }

    // ── Isolated glow-outline pipeline (parallel framegraph outline target) ──────────────────────
    //
    // Replaces the dead-end per-pixel-depth stencil band ring the 1.20.1/1.21.1 builds used (the stencil
    // two-pass outline, removed in 26.1). That ring flickered/dropped at silhouette edges because the
    // dilated band's depth is unusable there regardless of bias, confirmed dead end. Two stages:
    //  1. GLOW_MASK_PIPE, ONE render of each glowing model into our own isolated half-res mask target,
    //     depth test ALWAYS_PASS (marks the whole outer shape). core/glow_silhouette decides occlusion
    //     per-fragment by sampling the full-res scene depth (DepthSampler) and encodes shape + visibility
    //     + object id into alpha. Collapses the earlier two passes (full-shape + visible) and
    //     the separate depth-downsample pass into one, halving per-mob CPU emit and GPU silhouette fill.
    //  2. GLOW_COMPOSITE_ID_PIPE, one fullscreen pass that turns the mask into rings (post/glow_outline_id),
    //     per-object id-aware so adjacent/overlapping glows keep separate outlines instead of merging. The
    //     mask packs object id + visibility into alpha; the composite rings a pixel where a DIFFERENT
    //     object's visible silhouette is within reach.

    /** SINGLE combined-mask pipeline (replaces the old separate GLOW_SILHOUETTE + GLOW_SHAPE passes).
     *  One model render per mob produces both shape and per-fragment visibility, encoded in alpha by
     *  core/glow_silhouette, occlusion against the WORLD is decided in-shader by sampling the full-res
     *  scene depth (DepthSampler) and writing a visibility flag in alpha.
     *
     *  Depth state: LESS_THAN_OR_EQUAL + depth WRITE against the mask target's OWN depth (cleared to far
     *  each frame, separate from the sampled scene depth). This is purely an INTER-MOB early-Z optimization
     *  for the "cram space of glowing mobs" case: without it the old ALWAYS_PASS mask fully rasterized
     *  every mob behind every other mob (no early rejection) → enormous full-res overdraw. With it, only
     *  the front-most mob per pixel runs the fragment shader; the hidden ones are early-Z rejected. It does
     *  NOT change the result: a nearer mob only suppresses pixels that are interior to its own silhouette,
     *  which the composite discards anyway, and the nearer mob's world-visibility flag is the correct one
     *  there (anything behind it is occluded by it). DepthSampler is added on top of OUTLINE_NO_CULL's
     *  Sampler0; output target is overridden at draw time via RenderSystem.outputColor/DepthTextureOverride
     *  to our mask target. */
    public static final RenderPipeline GLOW_MASK_PIPE = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glow_mask"))
            .withVertexShader(GLOW_SILHOUETTE_SHADER)
            .withFragmentShader(GLOW_SILHOUETTE_SHADER)
            .withSampler("DepthSampler")
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /** Combined glow-mask RenderType for {@link #GLOW_MASK_PIPE}: Sampler0 = the entity texture (drives
     *  the silhouette alpha-discard), DepthSampler = the full-res scene depth (bound by Identifier via a
     *  per-frame-updated holder, see CustomGlintRenderer.bindSceneDepth) so the shader can do the
     *  per-fragment occlusion test. Clamp/nearest on the depth so edge UVs don't wrap. */
    public static RenderType glowMaskType(String name, Identifier texture, Identifier sceneDepth) {
        RenderSetup setup = RenderSetup.builder(GLOW_MASK_PIPE)
                .withTexture("Sampler0", texture)
                .withTexture("DepthSampler", sceneDepth, GlintPipelines::clampNearest)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /** Bilinear upscale of the half-res ring onto the main target (blended). */
    public static final RenderPipeline GLOW_UPSCALE_PIPE = RenderPipeline.builder()
            .withLocation(CustomGlint.res("pipeline/glow_upscale"))
            .withVertexShader(CustomGlint.res("core/screenquad"))
            .withFragmentShader(CustomGlint.res("post/glow_upscale"))
            .withSampler("InSampler")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    /** Per-object id-aware ring composite, single fullscreen pass (replaces the separable glow_dilate
     *  pair). Reads the mask (per-object id + visibility packed in alpha by core/glow_silhouette) and rings
     *  each object separately, so adjacent/overlapping glows don't fuse into one union ring. screenquad
     *  vertex + post/glow_outline_id fragment; driven via RenderPass.setPipeline, so it must be registered
     *  through RegisterRenderPipelinesEvent, see CustomGlintClientInit. */
    public static final RenderPipeline GLOW_COMPOSITE_ID_PIPE = RenderPipeline.builder()
            .withLocation(CustomGlint.res("pipeline/glow_outline_id"))
            .withVertexShader(CustomGlint.res("core/screenquad"))
            .withFragmentShader(CustomGlint.res("post/glow_outline_id"))
            .withSampler("MaskSampler")
            .withSampler("DepthSampler")       // scene depth at the RING pixel, for the ring-occlusion test
            .withSampler("MaskDepthSampler")   // the mask target's OWN depth, for the source's distance
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();
}
