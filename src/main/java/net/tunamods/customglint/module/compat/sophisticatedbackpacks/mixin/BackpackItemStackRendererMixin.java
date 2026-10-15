package net.tunamods.customglint.module.compat.sophisticatedbackpacks.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: BackpackItemStackRenderer (BEWLR) iterates renderPasses from its
 * BakedModel and calls MultiBufferSource.getBuffer(RenderType) directly per pass, bypassing
 * ItemRenderer.getFoilBuffer, so ItemRendererMixin never wraps the consumer with our glint
 * layers. The outline already works because it's the generic post-process silhouette driven from
 * ItemRenderer.render at the BEWLR boundary, independent of getFoilBuffer.
 *
 * At RETURN of renderByItem we re-resolve the baked model the same way SB did and submit it
 * to ItemRenderer.renderModelLists with a VertexMultiConsumer of our glint render types. SB
 * does not push/pop pose inside renderByItem, so the pose state at RETURN matches what the
 * model was rendered in, so no extra translate is needed.
 *
 * isItem=false on forGlint (3D BEWLR scale 1.0), same as the troll weapon path.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackItemStackRenderer", remap = false)
public class BackpackItemStackRendererMixin {

    // Backpack BEWLR fills more screen than a typical 3D item, so the default isItem=false
    // scale (1.0) makes each design tile look huge. Multiply the user's patternScale by this
    // constant locally so the design still tiles relative to their NBT setting.
    private static final float CG_BACKPACK_PATTERN_SCALE = 32.0f;

    @Inject(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply_named(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        cg_apply(stack, pose, buffer, light, overlay);
    }

    private static void cg_apply(ItemStack stack, PoseStack pose, MultiBufferSource buffer,
            int light, int overlay) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return;

        ItemRenderer ir = Minecraft.getInstance().getItemRenderer();
        BakedModel model = ir.getModel(stack, null, Minecraft.getInstance().player, 0);
        if (model == null) return;

        CustomGlint.Layer[] orig = glint.layers();
        CustomGlint.Layer[] layers = new CustomGlint.Layer[orig.length];
        for (int i = 0; i < orig.length; i++) {
            CustomGlint.Layer l = orig[i];
            layers[i] = new CustomGlint.Layer(l.design(), l.colors(), l.speed(), l.interpolate(),
                    l.patternScale() * CG_BACKPACK_PATTERN_SCALE, l.simultaneous());
        }
        CustomGlint.Data scaled = new CustomGlint.Data(layers);
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            if (CustomGlint.isChromatic(layers[li])) {
                // Chromatic has no PNG, so forGlint skipped it and the backpack showed no chromatic slick.
                // Off-pack, draw the in-phase 3D-item chromatic slick at the special-item scale; under a pack
                // that program is hijacked and ItemRendererMixin's special-item capture (the backpack is an
                // isCustomRenderer BEWLR item) drains it post-pass, the same path that traces the glow ring.
                if (!CustomGlintRenderer.isShaderPackActive()) {
                    RenderType rt = CustomGlintRenderer.forChromaticSpecialGlint(scaled, li);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
                continue;
            }
            CustomGlintRenderer.fanLayerBuffers(list, buffer, scaled, li,
                    (l, c, i) -> CustomGlintRenderer.forGlint(scaled, l, c, false, i));
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        ir.renderModelLists(model, stack, light, overlay, pose, combined);
    }
}
