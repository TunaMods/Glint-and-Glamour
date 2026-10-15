package net.tunamods.customglint.module.compat.epicfight.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-only helper for the Epic Fight worn-armor glint + glow. Epic Fight renders the patched player/mob
 * armor through its own {@code WearableItemLayer} (a skinned {@code SkinnedMesh} deformed to the animated
 * skeleton) instead of vanilla {@code HumanoidArmorLayer}, so our core {@code HumanoidArmorLayerMixin} never
 * fires on the animated wearer; the armor glint shows on a (vanilla-rendered) armor stand but vanishes on
 * the player.
 *
 * <p>The seam is deliberately Epic-Fight-type-free (an earlier attempt to capture the {@code SkinnedMesh} /
 * {@code Armature} / {@code OpenMatrix4f[]} args of {@code renderArmor} failed: {@code @Coerce Object} isn't
 * honored for an {@code @Inject} that resolves real classpath types). Instead we mirror the GeckoLib armor
 * compat: record the piece's stack from a vanilla-param hook, then wrap the vanilla {@code MultiBufferSource}
 * argument of {@code renderArmor}. Epic Fight's {@code SkinnedMesh.draw} pulls its buffer from that source
 * via {@code getBuffer(rt)}, so the wrapper tees the same mesh walk into our glint RenderTypes (fanned) and a
 * record-only silhouette buffer (glow) with zero reference to Epic Fight's own types.
 *
 * <p>{@code SkinnedMesh.draw} runs the RenderType through {@code EpicFightRenderTypes.getTriangulated}, so the
 * armor is a TRIANGLES-mode {@code armor_*} draw (not QUADS). We fan glint through the TRIANGLES armor RT
 * {@link CustomGlintRenderer#forArmorGlintTriangles} (a QUADS RT fed a triangle stream shatters into facets)
 * and queue the glow silhouette via {@link GlowOutlineRenderer#queueModelOutlineTriangles}.
 *
 * <p>Known gap: Epic Fight's optional GPU compute-skinning ("activate compute shader") transforms vertices on
 * the GPU and never calls {@code getBuffer}, so the wrapper sees nothing there. That path is off by default.
 */
public final class EpicFightArmorGlint {
    private EpicFightArmorGlint() {}

    /** Per-piece context: the stack + wearer from {@code getArmorModel} HEAD, and a once-per-piece guard so a
     *  two-layer armor (dyed leather draws two material layers) only glints/captures once. Render thread only. */
    private static final class Ctx {
        LivingEntity entity;
        ItemStack stack;
        EquipmentSlot slot;
        boolean drawn;
    }
    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    // The silhouette recorder for the current piece's glow, held between the wrap (which tees into it) and the
    // renderArmor RETURN flush (which queues it once the mesh walk has populated it). Null = nothing to queue.
    private static final ThreadLocal<PendingGlow> PENDING = new ThreadLocal<>();

    private static final class PendingGlow {
        final EntityGlintRender.CapturingModelConsumer cap;
        final ResourceLocation tex;
        final int color;
        final int key;
        PendingGlow(EntityGlintRender.CapturingModelConsumer cap, ResourceLocation tex, int color, int key) {
            this.cap = cap; this.tex = tex; this.color = color; this.key = key;
        }
    }

    // The chromatic silhouette recorder for the current piece, held from the wrap to the renderArmor RETURN
    // (shader-pack path only). The captured triangle mesh is queued once per chromatic layer for the overlay.
    private static final ThreadLocal<PendingChroma> PENDING_CHROMA = new ThreadLocal<>();

    private static final class PendingChroma {
        final EntityGlintRender.CapturingModelConsumer cap;
        final ResourceLocation tex;
        final CustomGlint.Data glint;
        final int[] layerIdxs;
        PendingChroma(EntityGlintRender.CapturingModelConsumer cap, ResourceLocation tex,
                      CustomGlint.Data glint, int[] layerIdxs) {
            this.cap = cap; this.tex = tex; this.glint = glint; this.layerIdxs = layerIdxs;
        }
    }

    /** {@code getArmorModel} HEAD: remember this piece's wearer/stack/slot and clear the once-per-piece guard. */
    public static void beginPiece(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        Ctx c = CTX.get();
        c.entity = entity;
        c.stack = stack;
        c.slot = slot;
        c.drawn = false;
    }

    /** {@code renderArmor} {@code @ModifyVariable}: wrap the buffer source so the armor mesh walk also feeds
     *  our glint RTs (+ a glow silhouette recorder). Returns {@code original} unchanged when this piece has no
     *  glint/glow or has already been handled (later material layers of the same piece). */
    public static MultiBufferSource wrapBuffers(MultiBufferSource original) {
        Ctx c = CTX.get();
        if (c.drawn || c.stack == null || c.entity == null
                || c.stack.isEmpty() || !(c.stack.getItem() instanceof ArmorItem)) {
            return original;
        }
        CustomGlint.Data glint = CustomGlint.readForRender(c.stack);
        boolean glow = CustomGlint.hasGlowEffect(c.stack) && !c.entity.isInvisible();
        if (glint == null && !glow) return original;
        return new ArmorGlintSource(original, c, glint, glow);
    }

    /** {@code renderArmor} RETURN: queue the glow silhouette + any deferred chromatic slick captured during
     *  the mesh walk (once per piece). */
    public static void flushGlow() {
        PendingGlow p = PENDING.get();
        if (p != null) {
            PENDING.remove();
            if (p.cap.count > 0) {
                // All of one wearer's armor + its body share the entity's glow id, so the figure composes as
                // ONE ring. The armor draw is triangulated, so the recorded vertices are a triangle list.
                GlowOutlineRenderer.queueModelOutlineTriangles(p.cap.data, p.cap.count, p.tex,
                        p.color, p.key, GlowOutlineRenderer.CAT_ARMOR, 0);
            }
        }
        PendingChroma pc = PENDING_CHROMA.get();
        if (pc != null) {
            PENDING_CHROMA.remove();
            if (pc.cap.count > 0) {
                // Same captured triangle mesh, queued per chromatic layer (each layer = its own seed/palette).
                for (int layerIdx : pc.layerIdxs) {
                    GlowOutlineRenderer.queueChromaticModel(pc.cap.data, pc.cap.count, pc.tex,
                            pc.glint, layerIdx, true);
                }
            }
        }
    }

    /** The base armor-layer texture (layer 0 defines the full shape), through the Forge hook so modded armor
     *  textures resolve. Drives the silhouette's alpha discard. Mirrors the core {@code HumanoidArmorLayerMixin}. */
    private static ResourceLocation armorTexture(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) return null;
        var layers = armorItem.getMaterial().value().layers();
        if (layers.isEmpty()) return null;
        boolean inner = slot == EquipmentSlot.LEGS; // matches HumanoidArmorLayer.usesInnerModel
        return ClientHooks.getArmorTexture(entity, stack, layers.get(0), inner, slot);
    }

    /** Unpack an ARGB colour into the shader-colour buffer, RGB premultiplied by alpha (the glint RTs
     *  drive alpha through the transparency state, so slot 3 stays 1.0). */
    private static void packColor(float[] buf, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( color        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /**
     * Wrapping {@code MultiBufferSource} installed for one {@code renderArmor} call. On the first buffer
     * request that carries an armor texture it returns a {@link VertexMultiConsumer} of the real armor buffer
     * plus our TRIANGLES-mode glint buffers (and, when the piece glows, a record-only silhouette recorder),
     * marks the piece handled, and stashes the recorder for the RETURN flush. Any other request forwards
     * straight through.
     */
    private static final class ArmorGlintSource implements MultiBufferSource {
        private final MultiBufferSource delegate;
        private final Ctx ctx;
        private final CustomGlint.Data glint;
        private final boolean glow;
        private boolean handled;

        ArmorGlintSource(MultiBufferSource delegate, Ctx ctx, CustomGlint.Data glint, boolean glow) {
            this.delegate = delegate;
            this.ctx = ctx;
            this.glint = glint;
            this.glow = glow;
        }

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            if (handled) return delegate.getBuffer(rt);
            // Epic Fight's renderArmor draws only the armor mesh; its RT carries the armor texture. Only the
            // armor draw has a texture, so forward every other buffer request untouched.
            if (GlowOutlineRenderer.resolveRenderTypeTexture(rt) == null) return delegate.getBuffer(rt);
            handled = true;
            ctx.drawn = true;

            List<VertexConsumer> list = new ArrayList<>();
            // Acquire the glint (fixed) buffers BEFORE the base, matching GlintWrappingBufferSource: requesting
            // the base first could switch a non-fixed RT's lastState and end its batch prematurely.
            List<Integer> chromaPackLayers = new ArrayList<>();
            if (glint != null) buildGlintConsumers(list, chromaPackLayers);

            // Chromatic under a shader pack: capture the mesh once into a recorder and queue it per chromatic
            // layer at RETURN for the post-Iris overlay drain (the in-phase triangle chromatic RT is hijacked).
            if (!chromaPackLayers.isEmpty()) {
                ResourceLocation tex = armorTexture(ctx.entity, ctx.stack, ctx.slot);
                if (tex != null) {
                    EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
                    list.add(cap);
                    PENDING_CHROMA.set(new PendingChroma(cap, tex, glint,
                            chromaPackLayers.stream().mapToInt(Integer::intValue).toArray()));
                }
            }

            if (glow) {
                ResourceLocation tex = armorTexture(ctx.entity, ctx.stack, ctx.slot);
                if (tex != null) {
                    EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
                    list.add(cap);
                    PENDING.set(new PendingGlow(cap, tex,
                            CustomGlintRenderer.resolveGlowColor(ctx.stack),
                            GlowOutlineRenderer.glowKeyFor(ctx.entity, GlowOutlineRenderer.CAT_ARMOR)));
                }
            }

            VertexConsumer base = delegate.getBuffer(rt);
            if (list.isEmpty()) return base;
            list.add(base);
            return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        }

        /** Append one TRIANGLES-mode glint VertexConsumer per layer/color, mirroring the core armor glint.
         *  Chromatic layers under a shader pack are collected into {@code chromaPackLayers} instead (the
         *  in-phase chromatic program is hijacked under a pack) so the caller can defer them to the post-Iris
         *  overlay drain. */
        private void buildGlintConsumers(List<VertexConsumer> list, List<Integer> chromaPackLayers) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            boolean pack = CustomGlintRenderer.isShaderPackActive();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    if (pack) {
                        chromaPackLayers.add(layerIdx); // deferred to the post-Iris overlay (see getBuffer)
                    } else {
                        RenderType crt = CustomGlintRenderer.forChromaticArmorGlintTriangles(glint, layerIdx);
                        if (crt != null) list.add(delegate.getBuffer(crt));
                    }
                    continue;
                }
                int[] colors = layers[layerIdx].colors().length == 0
                        ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        packColor(buf, colors[i]);
                        RenderType rt = CustomGlintRenderer.forArmorGlintTriangles(glint, layerIdx, buf, i);
                        if (rt != null) list.add(delegate.getBuffer(rt));
                    }
                } else {
                    packColor(buf, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
                    RenderType rt = CustomGlintRenderer.forArmorGlintTriangles(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(delegate.getBuffer(rt));
                }
            }
        }
    }
}
