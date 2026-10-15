package net.tunamods.customglint.module.compat.geckolib.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue for {@code GeoArmorRendererMixin}. {@link #stash} records the stack/wearer GeckoLib is about to
 * render (captured at {@code prepForRender}); {@link #stashTexture} records the armor texture off the
 * {@code actuallyRender} {@link RenderType} arg; {@link #wrapGlint} wraps the {@link VertexConsumer}
 * GeckoLib is about to draw the Geo mesh into, fanning the same draw across our glint render types and
 * (when glowing) a record-only silhouette capture; {@link #flush} (at {@code actuallyRender} RETURN)
 * queues that silhouette as a glow ring keyed on the wearer + {@code CAT_ARMOR}.
 *
 * <p>GeckoLib armor draws with {@code RenderType.armorCutoutNoCull} (VIEW_OFFSET_Z_LAYERING), so the
 * worn-armor glint factories ({@code forArmorGlint} / {@code forChromaticArmorGlint}) match its depth
 * offset exactly, unlike Mekanism's NO_LAYERING special armor.
 */
public final class GeckoArmorGlint {
    private GeckoArmorGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<ResourceLocation> CURRENT_TEX = new ThreadLocal<>();
    private static final ThreadLocal<EntityGlintRender.CapturingModelConsumer> PENDING_GLOW = new ThreadLocal<>();

    public static void stash(Entity entity, ItemStack stack) {
        CURRENT_STACK.set(stack);
        CURRENT_ENTITY.set(entity instanceof LivingEntity ? (LivingEntity) entity : null);
    }

    /** Record the armor texture from {@code actuallyRender}'s RenderType arg (used to trace the glow
     *  silhouette so cutout holes don't outline). Returns the type unchanged. */
    public static RenderType stashTexture(RenderType rt) {
        CURRENT_TEX.set(GlowOutlineRenderer.resolveRenderTypeTexture(rt));
        return rt;
    }

    public static VertexConsumer wrapGlint(VertexConsumer base) {
        PENDING_GLOW.set(null);
        ItemStack stack = CURRENT_STACK.get();
        if (stack == null || stack.isEmpty()) return base;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        boolean glowing = CustomGlint.hasGlowEffect(stack);
        if (glint == null && !glowing) return base;

        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        List<VertexConsumer> list = new ArrayList<>();
        list.add(base);

        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    // Under a pack chromatic is captured for the post-Iris overlay (see flush), not in-phase.
                    if (!CustomGlintRenderer.isShaderPackActive()) {
                        RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                        if (crt != null) list.add(bufferSource.getBuffer(crt));
                    }
                    continue;
                }
                int[] colors = layers[layerIdx].colors().length == 0
                        ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        packColor(buf, colors[i]);
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(bufferSource.getBuffer(rt));
                    }
                } else {
                    packColor(buf, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(bufferSource.getBuffer(rt));
                }
            }
        }

        // Record-only silhouette for the glow ring AND the post-Iris chromatic overlay, replayed at RETURN
        // against the stashed texture.
        if (EntityGlintRender.needsArmorCapture(stack)) {
            EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
            cap.delegate = null;
            PENDING_GLOW.set(cap);
            list.add(cap);
        }

        if (list.size() == 1) return base;
        return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
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

    /** Queue the recorded armor silhouette as one glow ring, keyed on the wearer + CAT_ARMOR so it
     *  composes with the body ring (matches the vanilla-armor path). The captured vertices are
     *  camera-relative (pose baked in), so no model-view matrix is threaded through. */
    public static void flush() {
        EntityGlintRender.CapturingModelConsumer cap = PENDING_GLOW.get();
        PENDING_GLOW.set(null);
        EntityGlintRender.flushArmorCapture(cap, CURRENT_ENTITY.get(), CURRENT_STACK.get(),
                CURRENT_TEX.get(), GlowOutlineRenderer.CAT_ARMOR);
    }
}
