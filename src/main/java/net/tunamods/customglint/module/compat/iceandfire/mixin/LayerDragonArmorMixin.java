package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import net.tunamods.customglint.module.compat.iceandfire.client.IceAndFireArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat: LayerDragonArmor is the single convergence point for fire/ice/lightning
 * dragon armor: it composes a per-(dragonType + 4 ordinals) layered texture and renders the dragon
 * model once via RenderType.entityTranslucent(layeredTex). We capture the layered ResourceLocation
 * via @Redirect on entityTranslucent (rewriting that RT to armorCutoutNoCull) and unwrap IaF's
 * base-armor getBuffer, then at RETURN re-render MODEL with the glint RTs and draw an outline.
 *
 * Masking + body-glint isolation: the dragon body shares the same EntityModel as the armor layer
 * (IaF reuses the parent model with the layered armor texture). Two consequences when the dragon
 * itself is glinted: (1) EntityGlintRender's wrapper auto-fans the body glint onto IaF's entity_*
 * armor draw, and (2) armor and body sit at the same depth so an EQUAL-depth body glint covers the
 * armor. Both are fixed by drawing the base through {@code armorCutoutNoCull} (polygon offset nudges
 * the armor in front, depth-occluding the body glint) on the UNWRAPPED buffer (no fan), then
 * glinting with {@link CustomGlintRenderer#forArmorGlint} (EQUAL + the matching offset) which lands
 * only where the cutout base wrote depth: the armor texture's own alpha cutout is the mask, no
 * stencil pass needed. Mirrors the 1.21.1 CE dragon fix.
 *
 * Source-of-glint resolution: HEAD &gt; CHEST &gt; LEGS &gt; FEET, first stack with a custom glint wins,
 * and supplies both the animated glint and (if also glowing) the outline color. Mixed-glint
 * configurations across slots fall back to the highest-priority slot's glint.
 *
 * The parent model lookup and the glint fan-out are shared with the hippogryph/hippocampus armor
 * mixins; both live in {@link IceAndFireArmorGlint}.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.entity.layer.LayerDragonArmor", remap = false)
public class LayerDragonArmorMixin {

    private static final ThreadLocal<ResourceLocation> CG_TEX = new ThreadLocal<>();
    private static final EquipmentSlot[] CG_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // Clear any texture left over from a previous render that threw between the capture redirect and the
    // RETURN inject, otherwise the next armorless dragon (whose render never hits the redirect) would read a
    // stale CG_TEX and draw a spurious glint/outline. HEAD always runs before the redirect.
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At("HEAD"), require = 0)
    private void cg_clearTex(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        CG_TEX.remove();
    }

    // Capture layered ResourceLocation passed to entityTranslucent
    // Dual SRG/named pair because class-level remap=false leaves vanilla method descriptors as-written.

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;m_110458_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_srg(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.armorCutoutNoCull(loc);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;entityTranslucent(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_named(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.armorCutoutNoCull(loc);
    }

    // Draw IaF's base armor through the UNWRAPPED buffer (cg_capTex made the RT armorCutoutNoCull)
    // Two problems when the dragon itself is glinted: (1) EntityGlintRender wraps the buffer and
    // auto-fans every entity_* RT through the body glint, so IaF's armor draw got the body glint
    // stamped across the whole model (including empty texture space); (2) the armor reuses the
    // dragon's own model, so it sits at the SAME depth as the body and the EQUAL-depth body glint
    // draws over it. cg_capTex above swapped IaF's RT to armorCutoutNoCull (its polygon offset nudges
    // the armor in front so the body glint is depth-occluded); here we unwrap so the fan can't touch
    // it. Non-glinted dragons: unwrap returns the same buffer and the offset is sub-pixel → identical.
    // Dual SRG/named INVOKE targets, require=0 on both.

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/MultiBufferSource;m_6299_(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer cg_unwrapBase_srg(MultiBufferSource buf, RenderType rt) {
        return EntityGlintRender.unwrap(buf).getBuffer(rt);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer cg_unwrapBase_named(MultiBufferSource buf, RenderType rt) {
        return EntityGlintRender.unwrap(buf).getBuffer(rt);
    }

    // Apply glint + outline at RETURN (base already drawn via unwrap + armorCutoutNoCull)

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        ResourceLocation tex = CG_TEX.get();
        CG_TEX.remove();
        // No tex captured ⇒ IaF early-exited (no armor / dragon type unsupported) ⇒ nothing to glint.
        if (tex == null) return;

        // HEAD > CHEST > LEGS > FEET: first slot with a glint OR a glow trim wins and supplies both
        // the animated glint and (if glowing) the outline colour.
        ItemStack active = null;
        CustomGlint.Data glint = null;
        for (EquipmentSlot s : CG_SLOTS) {
            ItemStack stack = entity.getItemBySlot(s);
            CustomGlint.Data dat = CustomGlint.readCached(stack);
            if (dat != null || CustomGlint.isGlowing(stack)) { active = stack; glint = dat; break; }
        }
        if (active == null) return;
        boolean glowing = CustomGlint.isGlowing(active);

        EntityModel<?> model = IceAndFireArmorGlint.parentModel(this);
        if (model == null) return;

        // Route through the unwrapped source so the dragon's body glint (auto-fanned onto entity_*
        // RTs by EntityGlintRender) can't bleed onto the armor glint. Non-glinted dragons: unwrap
        // returns the same buffer.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);

        if (glint != null) {
            // forArmorGlint (EQUAL + armorCutoutNoCull's polygon offset) lands only where IaF's base
            // armor draw (also routed through armorCutoutNoCull) wrote depth, i.e. the layered
            // armor texture's opaque texels. The offset sits the armor (and this glint) in front of
            // the dragon body, so the body glint is depth-occluded there. No stencil mask needed:
            // the armor texture's own alpha cutout IS the mask. (Chromatic layers take the matching
            // depth twin inside drawArmorGlint; the armorCutoutNoCull they pair with is the one
            // cg_capTex above rewrote IaF's RT to.)
            IceAndFireArmorGlint.drawArmorGlint(glint, model, pose, flush, light);
        }

        // Glow outline: trace the dragon model against the composed layered armor texture (its alpha is
        // the armor shape) into the glow mask. Keyed on the dragon entity (CAT_ARMOR), so the armor ring
        // fuses with the dragon's body/entity ring into ONE connected ring.
        if (glowing) {
            EntityGlintRender.captureModelSilhouette(entity, model, tex, pose, light,
                    CustomGlintRenderer.resolveGlowColor(active), GlowOutlineRenderer.CAT_ARMOR);
        }
    }
}
