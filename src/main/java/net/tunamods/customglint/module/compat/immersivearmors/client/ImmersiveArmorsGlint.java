package net.tunamods.customglint.module.compat.immersivearmors.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glue for the Immersive Armors compat. IA cancels the vanilla armor layer and draws each slot as a list
 * of {@code Piece}s; every piece draws its model through {@code Piece.renderParts} (called one-or-more
 * times per piece, since {@code LayerPiece} stacks three texture layers), and each {@code renderParts}
 * picks a RenderType off the piece's own {@code isTranslucent()} / {@code isGlowing()} flags.
 *
 * <p>{@code ArmorPieceMixin} brackets one {@code Piece.render} (HEAD {@link #begin}, RETURN {@link #finish})
 * to record the wearer/stack and, on RETURN, queue the glow ring. {@code PieceRenderMixin} redirects the
 * {@code EntityModel.renderToBuffer} call inside {@code renderParts} to {@link #fanGlint}, which fans the
 * glint into that SAME draw via a {@link VertexMultiConsumer} (identical pose and vertices as the armor),
 * so the EQUAL-depth glint never z-fights. The glint RenderType's polygon offset is chosen to match IA's
 * per-piece pick (NO_LAYERING for translucent / glowing pieces, VIEW_OFFSET for plain armorCutoutNoCull).
 *
 * <p>All reflection into IA members lives here, so there is no compile or runtime dep on Immersive Armors.
 */
public final class ImmersiveArmorsGlint {
    private ImmersiveArmorsGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_PIECE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CURRENT_NO_OFFSET = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** HEAD of {@code Piece.render}: record the piece being drawn so the {@code renderParts} redirect can fan
     *  its glint, and RETURN can queue its glow ring. Only arms when the stack has glint or glow. */
    public static void begin(Object piece, LivingEntity entity, ItemStack stack) {
        CURRENT_STACK.remove();
        CURRENT_ENTITY.remove();
        CURRENT_PIECE.remove();
        if (stack == null || stack.isEmpty()) return;
        if (CustomGlint.readForRender(stack) == null && !CustomGlint.hasGlowEffect(stack)) return;
        CURRENT_STACK.set(stack);
        CURRENT_ENTITY.set(entity);
        CURRENT_PIECE.set(piece);
        // Match IA's per-piece depth offset: translucent (entityTranslucent) and IA-glowing (beaconBeam)
        // pieces draw with NO polygon offset, plain pieces with armorCutoutNoCull (VIEW_OFFSET_Z_LAYERING).
        CURRENT_NO_OFFSET.set(readBool(piece, "isTranslucent") || readBool(piece, "isGlowing"));
    }

    /** Redirect of {@code EntityModel.renderToBuffer} inside {@code renderParts}: draw the armor exactly as
     *  IA would, plus the glint fanned into the same submission. */
    public static void fanGlint(Model model, PoseStack pose, VertexConsumer base, int light, int overlay, int color) {
        ItemStack stack = CURRENT_STACK.get();
        CustomGlint.Data glint = stack == null ? null : CustomGlint.readForRender(stack);
        if (glint == null) {
            model.renderToBuffer(pose, base, light, overlay, color); // unchanged
            return;
        }
        boolean noOffset = CURRENT_NO_OFFSET.get();
        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        list.add(base);
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                // Under a pack chromatic is captured for the post-Iris overlay (see finish), not in-phase.
                if (!CustomGlintRenderer.isShaderPackActive()) {
                    RenderType crt = noOffset
                            ? CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx)
                            : CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                    if (crt != null) list.add(bufferSource.getBuffer(crt));
                }
                continue;
            }
            int[] colors = layers[layerIdx].colors().length == 0
                    ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    packColor(buf, colors[i]);
                    RenderType rt = glintRt(glint, layerIdx, buf, i, noOffset);
                    if (rt != null) list.add(bufferSource.getBuffer(rt));
                }
            } else {
                packColor(buf, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
                RenderType rt = glintRt(glint, layerIdx, buf, 0, noOffset);
                if (rt != null) list.add(bufferSource.getBuffer(rt));
            }
        }
        VertexConsumer combined = list.size() == 1
                ? base : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        model.renderToBuffer(pose, combined, light, overlay, color);
    }

    /** RETURN of {@code Piece.render}: queue the glow ring (keyed on the wearer + CAT_ARMOR so every piece
     *  and the body fold into one ring) and disarm. */
    public static void finish(PoseStack pose, int light) {
        ItemStack stack = CURRENT_STACK.get();
        LivingEntity entity = CURRENT_ENTITY.get();
        Object piece = CURRENT_PIECE.get();
        CURRENT_STACK.remove();
        CURRENT_ENTITY.remove();
        CURRENT_PIECE.remove();
        if (stack == null || entity == null || piece == null) return;
        if (!EntityGlintRender.needsArmorCapture(stack)) return;
        Model model = MODEL_ACCESS.computeIfAbsent(piece.getClass(), ImmersiveArmorsGlint::buildAccess).get(piece);
        if (model == null) return;
        ResourceLocation tex = resolveTexture(piece, stack);
        if (tex == null) return;
        if (CustomGlint.hasGlowEffect(stack)) {
            EntityGlintRender.captureModelSilhouette(entity, entity, model, tex, pose, light,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0);
        }
        // Under a pack the in-phase chromatic is hijacked; re-render the piece into the post-Iris overlay.
        if (CustomGlintRenderer.isShaderPackActive()) {
            CustomGlint.Data glint = CustomGlint.readForRender(stack);
            if (glint != null) {
                CustomGlint.Layer[] cls = glint.layers();
                for (int li = 0; li < cls.length; li++) {
                    if (CustomGlint.isChromatic(cls[li])) {
                        EntityGlintRender.captureChromaticModel(entity, model, tex, pose, light, glint, li);
                    }
                }
            }
        }
    }

    private static void packColor(float[] buf, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( color        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /** EQUAL-depth glint RT matching the piece's polygon offset: NO_LAYERING (horse/entity factory) for
     *  translucent / IA-glowing pieces, VIEW_OFFSET_Z_LAYERING (armor factory) for plain armorCutoutNoCull. */
    private static RenderType glintRt(CustomGlint.Data glint, int layerIdx, float[] buf, int colorIdx, boolean noOffset) {
        return noOffset
                ? CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, colorIdx)
                : CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, colorIdx);
    }

    // ── Reflection into IA members (cached; no compile/runtime dep on Immersive Armors) ──────────────

    /** How to recover the drawn {@link Model} from a concrete piece class: a {@code getModel()} method
     *  (LayerPiece) or a {@code model} field (ModelPiece). {@code NONE} marks classes we can't read. */
    private static final class ModelAccess {
        static final ModelAccess NONE = new ModelAccess(null, null);
        final Method method;
        final Field field;
        ModelAccess(Method method, Field field) { this.method = method; this.field = field; }

        Model get(Object piece) {
            try {
                Object m = method != null ? method.invoke(piece) : field != null ? field.get(piece) : null;
                return m instanceof Model ? (Model) m : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }

    private static final Map<Class<?>, ModelAccess> MODEL_ACCESS = new ConcurrentHashMap<>();
    private static volatile boolean textureLookupTried = false;
    private static Method textureMethod;
    /** Sentinel for "this piece class has no such getter", so a failed lookup caches a miss instead of
     *  rescanning the class hierarchy on every frame. Any always-present Method works. */
    private static final Method NO_METHOD;
    static { Method n = null; try { n = Object.class.getMethod("hashCode"); } catch (NoSuchMethodException ignored) {} NO_METHOD = n; }
    private static volatile Method translucentMethod;
    private static volatile Method glowingMethod;

    /** Reflectively read a no-arg boolean getter off the piece (e.g. {@code isTranslucent}). The resolved
     *  {@link Method} is cached; failures fall back to {@code false} (assume the offset armor path). */
    private static boolean readBool(Object piece, String name) {
        Method m = "isTranslucent".equals(name) ? translucentMethod : glowingMethod;
        if (m == null) {
            m = findBool(piece.getClass(), name);
            if ("isTranslucent".equals(name)) translucentMethod = m; else glowingMethod = m;
        }
        if (m == NO_METHOD) return false;
        try {
            return Boolean.TRUE.equals(m.invoke(piece));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Method findBool(Class<?> pieceClass, String name) {
        for (Class<?> c = pieceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method cand : c.getDeclaredMethods()) {
                if (cand.getName().equals(name) && cand.getParameterCount() == 0
                        && (cand.getReturnType() == boolean.class || cand.getReturnType() == Boolean.class)) {
                    cand.setAccessible(true);
                    return cand;
                }
            }
        }
        return NO_METHOD;
    }

    /** Find how to read the drawn model off this piece class: prefer a no-arg {@code getModel()}
     *  (LayerPiece), fall back to a {@code model} field (ModelPiece). */
    private static ModelAccess buildAccess(Class<?> pieceClass) {
        for (Class<?> c = pieceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getModel") && m.getParameterCount() == 0
                        && Model.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return new ModelAccess(m, null);
                }
            }
        }
        for (Class<?> c = pieceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals("model") && Model.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return new ModelAccess(null, f);
                }
            }
        }
        return ModelAccess.NONE;
    }

    /** {@code Piece.getTexture(ExtendedArmorItem, boolean)} → base-layer ResourceLocation. Resolved once
     *  by scanning the piece class hierarchy for the private 2-arg {@code getTexture}. */
    private static ResourceLocation resolveTexture(Object piece, ItemStack stack) {
        Method m = textureMethod;
        if (m == null) {
            if (textureLookupTried) return null;
            textureLookupTried = true;
            for (Class<?> c = piece.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (cand.getName().equals("getTexture") && cand.getParameterCount() == 2
                            && cand.getReturnType() == ResourceLocation.class) {
                        cand.setAccessible(true);
                        textureMethod = m = cand;
                        break;
                    }
                }
                if (m != null) break;
            }
            if (m == null) return null;
        }
        try {
            return (ResourceLocation) m.invoke(piece, stack.getItem(), Boolean.FALSE);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
