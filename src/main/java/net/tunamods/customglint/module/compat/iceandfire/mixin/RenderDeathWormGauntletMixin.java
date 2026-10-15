package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition. CE's DeathwormGauntletRenderer
 * implements uranus's {@code DynamicItemRenderer} (method {@code render(...)}, not BEWLR
 * {@code renderByItem}) and grabs its VertexConsumer via {@code MultiBufferSource.getBuffer}
 * directly, bypassing ItemRendererMixin's getFoilBuffer hook. At RETURN of {@code render} we
 * re-render the gauntlet model with our glint render types, re-applying the {@code (0.5, 0.5, 0.5)}
 * translate IaF uses (inside a pushPose/popPose, already unwound at RETURN).
 *
 * Outline is handled by the post-process {@link net.tunamods.customglint.common.client.GlowOutlineRenderer}
 * at the ItemRenderer.render BEWLR boundary, independent of this glint re-render: uranus dispatches
 * through the vanilla BlockEntityWithoutLevelRenderer, so the item reads as {@code isCustomRenderer()==true}
 * and gets the standard glow silhouette.
 *
 * The model stays a static {@code MODEL} field in CE; {@code f.get(renderer)} reads it regardless.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.item.DeathwormGauntletRenderer", remap = false)
public class RenderDeathWormGauntletMixin {

    private static volatile Model CG_MODEL;

    private static Model cg_getModel(Object renderer, String fieldName) {
        Model m = CG_MODEL;
        if (m != null) return m;
        try {
            Field f = renderer.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            CG_MODEL = (Model) f.get(renderer);
            return CG_MODEL;
        } catch (Throwable t) {
            return null;
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return;
        Model model = cg_getModel(this, "MODEL");
        if (model == null) return;

        CustomGlint.Layer[] layers = glint.layers();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            if (CustomGlint.isChromatic(layers[li])) {
                // Chromatic has no PNG, so forGlint skipped it and the gauntlet showed no chromatic. Off-pack, draw
                // the in-phase 3D-item chromatic slick; under a pack that program is hijacked and the special-item
                // capture in ItemRendererMixin drains it post-pass (the same path that traces the glow ring).
                if (!CustomGlintRenderer.isShaderPackActive()) {
                    RenderType rt = CustomGlintRenderer.forChromaticSpecialGlint(glint, li);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
                continue;
            }
            CustomGlintRenderer.fanLayerBuffers(list, buffer, glint, li,
                    (l, c, i) -> CustomGlintRenderer.forGlint(glint, l, c, false, i));
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        model.renderToBuffer(pose, combined, light, overlay, 0xFFFFFFFF);
        pose.popPose();
    }
}
