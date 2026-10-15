package net.tunamods.customglint.module.compat.mekanism.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue for the Mekanism special-armor compat. Mekanism draws the MekaSuit, Jetpacks, Free Runners (and
 * their armored variants) and the Scuba tank/mask through {@code ICustomArmor.render} instead of the
 * vanilla armor layer, so our core {@code HumanoidArmorLayerMixin} never sees them and the plain Hazmat
 * suit (a vanilla {@code ArmorItem}) is the only piece that glints on its own.
 *
 * <p>Every one of those models gets its draw buffer from
 * {@code ItemRenderer.getFoilBufferDirect}: the MekaSuit's baked quads call it directly, the Java-model
 * pieces via {@code MekanismJavaModel.getVertexConsumer} (which always routes through it).
 * {@code MekanismArmorGlintMixin} arms this glue for the span of one {@code ICustomArmor.render} (so only
 * Mekanism special armor is touched, never vanilla
 * armor), and {@code FoilBufferMixin} calls {@link #wrap} on that method's return value while armed,
 * fanning the same geometry across our glint render types and, when glowing, a record-only silhouette.
 * {@link #flush} queues the recorded silhouettes as one glow ring keyed on the wearer + {@code CAT_ARMOR}
 * so it folds into the body ring. All references are vanilla types, so there is no dep on Mekanism.
 */
public final class MekanismArmorGlint {
    private MekanismArmorGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<List<Pending>> PENDING = ThreadLocal.withInitial(ArrayList::new);

    /** A recorded silhouette awaiting its glow-ring queue, with the texture its draw traced against. */
    private static final class Pending {
        final EntityGlintRender.CapturingModelConsumer cap;
        final ResourceLocation tex;
        Pending(EntityGlintRender.CapturingModelConsumer cap, ResourceLocation tex) {
            this.cap = cap; this.tex = tex;
        }
    }

    /** Arm for one {@code ICustomArmor.render}. Every {@code getFoilBufferDirect} call until {@link #flush}
     *  belongs to this piece, so {@link #wrap} may fan its glint without catching unrelated draws. */
    public static void arm(LivingEntity entity, ItemStack stack) {
        CURRENT_STACK.set(stack);
        CURRENT_ENTITY.set(entity);
    }

    public static boolean isArmed() {
        return CURRENT_STACK.get() != null;
    }

    /** Wrap the armor foil buffer so the same mesh also feeds our glint render types and, when glowing, a
     *  record-only silhouette. Returns {@code base} unchanged when the stack has no glint/glow. */
    public static VertexConsumer wrap(VertexConsumer base, RenderType rt) {
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
                    // NO_LAYERING variant off-pack: Mekanism's armor RenderTypes set no polygon offset.
                    if (!CustomGlintRenderer.isShaderPackActive()) {
                        RenderType crt = CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx);
                        if (crt != null) list.add(bufferSource.getBuffer(crt));
                    }
                    continue;
                }
                int[] colors = layers[layerIdx].colors().length == 0
                        ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        packColor(buf, colors[i]);
                        RenderType glintRt = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, i);
                        if (glintRt != null) list.add(bufferSource.getBuffer(glintRt));
                    }
                } else {
                    packColor(buf, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
                    RenderType glintRt = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, 0);
                    if (glintRt != null) list.add(bufferSource.getBuffer(glintRt));
                }
            }
        }

        if (EntityGlintRender.needsArmorCapture(stack)) {
            EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
            cap.delegate = null;
            PENDING.get().add(new Pending(cap, GlowOutlineRenderer.resolveRenderTypeTexture(rt)));
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

    /** Queue every recorded silhouette from this piece as one glow ring (wearer + CAT_ARMOR, so it folds
     *  into the body ring) and disarm. Called at {@code ICustomArmor.render} RETURN. The captured vertices
     *  are camera-relative (the pose is baked in), so the world drain replays them under the world matrices
     *  exactly like the vanilla armor tee, with no model-view matrix threaded through. */
    public static void flush() {
        List<Pending> pending = PENDING.get();
        LivingEntity entity = CURRENT_ENTITY.get();
        ItemStack stack = CURRENT_STACK.get();
        CURRENT_STACK.set(null);
        CURRENT_ENTITY.set(null);
        if (pending.isEmpty()) return;
        try {
            if (entity == null || stack == null) return;
            for (Pending p : pending) {
                EntityGlintRender.flushArmorCapture(p.cap, entity, stack, p.tex, GlowOutlineRenderer.CAT_ARMOR);
            }
        } finally {
            pending.clear();
        }
    }
}
