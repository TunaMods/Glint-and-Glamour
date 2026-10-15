package net.tunamods.customglint.module.compat.epicknights.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsGlintRT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat: Epic Knights renders armor attachments (plumes, surcoats, crowns)
 * via {@code ArmorDecorationLayer} which calls {@code ItemRenderer.getArmorFoilBuffer} directly,
 * so {@code HumanoidArmorLayerMixin} never fires for them. The base armor already has glint
 * applied by the core mixin; this fills in each decoration piece.
 *
 * Captures the ItemStack on the slot in {@code renderPiece} via ThreadLocal so the per-decoration
 * {@code renderDecoration} call can look up its glint data without referencing EK's internal
 * {@code ArmorDecorationItem.DecorationInfo} type (not on our compile classpath).
 *
 * Uses {@code EpicKnightsGlintRT#applyDecorationGlint} rather than {@code CustomGlintRenderer.forArmorGlint}: a
 * plain EQUAL armor glint leaves the decoration glint invisible (EK's decoration pass writes depth that doesn't
 * match), and an unmasked LEQUAL bleeds through the decoration's transparent texels. applyDecorationGlint clips
 * the glint to the decoration's opaque texels instead: per-fragment off pack, via a depth self-mask under a
 * shader pack.
 */
@Pseudo
@Mixin(targets = "com.magistuarmory.client.render.entity.layer.ArmorDecorationLayer", remap = false)
public class ArmorDecorationLayerMixin {

    private static final ThreadLocal<ItemStack> CG_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CG_ENTITY = new ThreadLocal<>();

    /** Last parts array drawn, so a back-to-back repeat of the same decoration draw is skipped (see cg_applyGlint). */
    private static final ThreadLocal<ModelPart[]> CG_LAST_PARTS = new ThreadLocal<>();

    @Inject(method = "renderPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;I)V",
            at = @At("HEAD"), require = 0)
    private void cg_capStack(PoseStack pose, MultiBufferSource buf, LivingEntity entity,
            EquipmentSlot slot, int light, CallbackInfo ci) {
        CG_STACK.set(entity.getItemBySlot(slot));
        CG_ENTITY.set(entity);
        CG_LAST_PARTS.remove();
    }

    @Inject(method = "renderPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;I)V",
            at = @At("RETURN"), require = 0)
    private void cg_clearStack(PoseStack pose, MultiBufferSource buf, LivingEntity entity,
            EquipmentSlot slot, int light, CallbackInfo ci) {
        CG_STACK.remove();
        CG_ENTITY.remove();
        CG_LAST_PARTS.remove();
    }

    /**
     * Dyeable decorations (e.g. ceremonial helm's default big_plume) trigger TWO renderDecoration
     * calls per iteration: a colored base (the IIIZ int-color overload) + an overlay (the IIZ
     * no-color overload, which delegates to IIIZ). Both bottom out in the IIIZ draw and fire our
     * RETURN inject. applyDecorationGlint already unions the base + sibling overlay, so letting the second call
     * fire too would draw the same glint again and double-brighten it. Skip when the same parts array fires twice
     * back-to-back.
     *
     * 1.21 note: EK's renderDecoration lost its old (float r, g, b) tint overload; color is now a
     * single packed int, matching the vanilla renderToBuffer change. Inject the IIIZ overload (the
     * one that actually draws via getArmorFoilBuffer); the IIZ overload delegates into it. The old
     * FFFZ descriptor matched nothing and silently no-opped, so decorations got no glint/glow.
     */
    @Inject(method = "renderDecoration(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIIZ[Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("RETURN"), require = 0)
    private void cg_applyGlint(PoseStack pose, MultiBufferSource buffer, int light, int overlay,
            int color, boolean hasFoil, ModelPart[] parts, ResourceLocation texture,
            CallbackInfo ci) {
        if (CG_LAST_PARTS.get() == parts) return;
        CG_LAST_PARTS.set(parts);
        ItemStack stack = CG_STACK.get();
        if (stack == null || stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        boolean glowing = CustomGlint.hasGlowEffect(stack);
        if (glint == null && !glowing) return;
        // Capture the glow-outline silhouette for the decoration (no generic tee reaches EK's
        // ModelPart-based draw); runs for a glow-only decoration too, not just glinted ones.
        LivingEntity entity = CG_ENTITY.get();
        if (glowing && entity != null) {
            EpicKnightsGlintRT.captureDecorationOutline(entity, pose, light, parts, texture,
                    CustomGlintRenderer.resolveGlowColor(stack));
        }
        if (glint != null) {
            EpicKnightsGlintRT.applyDecorationGlint(entity, pose, buffer, light, overlay, parts, texture, glint);
        }
    }
}
