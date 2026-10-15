package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition (and any iafenvoy mod using uranus's
 * armor system). IaF player armor (copper, deathworm, dragonsteel/dragonscale, sea-serpent, troll, …)
 * no longer renders through vanilla HumanoidArmorLayer: uranus's ArmorFeatureRendererMixin injects
 * at HEAD of {@code renderArmorPiece}, dispatches to
 * {@code com.iafenvoy.uranus.client.render.armor.IArmorRendererBase#render} (a default interface
 * method), then CANCELS the vanilla path. Because uranus cancels at HEAD, {@link HumanoidArmorLayerMixin}'s
 * RETURN inject never fires for these items, so IaF armor got no glint/glow.
 *
 * IaF's concrete renderers (BasicArmorRenderer / ScaleArmorRenderer) only implement
 * {@code getHumanoidArmorModel}; neither overrides {@code render}, so the interface default is the
 * single render path and the right injection target. We @Inject at its RETURN; by then uranus has
 * already drawn the base armor through {@code armorCutoutNoCull}, so we just overlay the glint,
 * reproducing HumanoidArmorLayerMixin's glint logic.
 *
 * This mixin MUST be declared as an {@code interface}: the target is an interface, and a normal class
 * mixin fails to apply with "{@code @Mixin target type mismatch: …IArmorRendererBase is an interface …
 * SubType$Standard}" (verified in the launch log).
 *
 * The model matters: {@code render}'s argument {@code defaultModel} (LVT slot 7) is the all-visible
 * base humanoid, while the local {@code armorModel} (LVT slot 8, from {@code getHumanoidArmorModel})
 * is the one uranus configured with per-slot {@code ModelPart.visible}. Grabbing the arg lit the whole
 * body from a single piece; we capture the local via {@code @Local(ordinal = 1)} (the second
 * HumanoidModel-typed local) instead.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.uranus.client.render.armor.IArmorRendererBase", remap = false)
public interface UranusArmorRendererMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("RETURN"), require = 0)
    private void cg_drawArmor(PoseStack pose, MultiBufferSource buffer, LivingEntity entity,
            EquipmentSlot slot, int light, ItemStack stack, HumanoidModel defaultModel, CallbackInfo ci,
            @Local(ordinal = 1) HumanoidModel armorModel) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        boolean glow = CustomGlint.hasGlowEffect(stack);
        if (glint == null && !glow) return;

        // Glow outline: uranus cancels the vanilla HumanoidArmorLayer path, so the generic in-phase tee
        // never captures IaF player armor. Re-render the configured armorModel (per-slot visibility already
        // applied) traced against the armor texture uranus draws. Its getArmorTexture default is the raw
        // material layer-0 texture (IaF's concrete renderers only override getHumanoidArmorModel). Keyed
        // CAT_ARMOR + the wearer's id so all pieces + the body compose as one ring.
        if (glow) {
            ResourceLocation tex = cg_uranusArmorTexture(stack, slot == EquipmentSlot.LEGS);
            if (tex != null) {
                EntityGlintRender.captureModelSilhouette(entity, entity, armorModel, tex, pose, light,
                        CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0);
            }
        }

        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            List<VertexConsumer> list = new ArrayList<>();
            for (int li = 0; li < layers.length; li++) {
                CustomGlintRenderer.fanLayerBuffers(list, buffer, glint, li,
                        (l, c, i) -> CustomGlintRenderer.forArmorGlint(glint, l, c, i));
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                armorModel.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        }
    }

    /** The armor texture uranus's {@code IArmorRendererBase.getArmorTexture} default resolves to: the raw
     *  material layer-0 texture (inner variant for leggings). Used to trace the glow silhouette against the
     *  real armor shape via alpha-discard. Returns null when the item has no armor material layers (uranus
     *  would fall back to the opaque "missingno" placeholder, which we skip rather than ring the whole body). */
    private static ResourceLocation cg_uranusArmorTexture(ItemStack stack, boolean inner) {
        if (!(stack.getItem() instanceof ArmorItem armor)) return null;
        var layers = armor.getMaterial().value().layers();
        if (layers.isEmpty()) return null;
        return layers.get(0).texture(inner);
    }
}
