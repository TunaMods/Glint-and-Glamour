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
 * Standalone-only compat: troll weapon BEWLR calls MultiBufferSource.getBuffer(RenderType) directly,
 * bypassing ItemRenderer.getFoilBuffer, so ItemRendererMixin never fires for glint. At RETURN of
 * renderByItem, re-renders MODEL with glint render types.
 *
 * Cannot @Shadow MODEL: the field's runtime descriptor is Lcom/.../ModelTrollWeapon; (an IaF type we
 * cannot reference at compile time since IaF is not a compileOnly dep). Shadowing as Model fails the
 * descriptor match at field resolution. We resolve MODEL via reflection lazily instead.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.tile.RenderTrollWeapon", remap = false)
public class RenderTrollWeaponMixin {

    private static volatile Model CG_MODEL;

    private static Model cg_getModel() {
        Model m = CG_MODEL;
        if (m != null) return m;
        try {
            Class<?> cls = Class.forName("com.github.alexthe666.iceandfire.client.render.tile.RenderTrollWeapon");
            Field f = cls.getDeclaredField("MODEL");
            f.setAccessible(true);
            CG_MODEL = (Model) f.get(null);
            return CG_MODEL;
        } catch (Throwable t) {
            return null;
        }
    }

    // Apply glint at RETURN. Pose has been popped by IaF, so re-apply the
    //    (0.5, -0.75, 0.5) translate it uses before rendering MODEL.

    @Inject(method = "m_108829_", at = @At("RETURN"), require = 0)
    private void cg_apply_srg(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        cg_apply(stack, pose, buffer, light, overlay);
    }

    @Inject(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply_named(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        cg_apply(stack, pose, buffer, light, overlay);
    }

    private static void cg_apply(ItemStack stack, PoseStack pose, MultiBufferSource buffer,
            int light, int overlay) {
        // Glow-outline capture re-renders the whole item through renderStatic into a record-only buffer
        // (ItemRendererMixin.cg_captureSpecialOutline), bucketing vertices by the texture each RenderType
        // draws through, so it already traces this weapon's real shape via IaF's own entitySolid(weapon
        // .TEXTURE) draw. If we drew our glint layers during that pass they'd be recorded under the SHARED
        // design texture as a full-model-hull bucket, identical for every troll weapon variant (the "all
        // show the same outline" case). Skip the glint draw under IN_OUTLINE; the base capture handles the
        // per-weapon ring. The core applyGlint does the same for vanilla items.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;

        Model model = cg_getModel();
        if (model == null) return;

        CustomGlint.Data glint = CustomGlint.readCached(stack);
        if (glint == null) return;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            // CHROMATIC has no design PNG, so forGlint returns null and the layer would silently vanish.
            // isItem=false matches the forGlint calls below: 3D BEWLR model, not a flat sprite.
            // chromaticWorldBuffer rather than buffer.getBuffer: under a shaderpack an in-phase chromatic
            // draw lands in the gbuffer and the scene composite discards it, so it defers to the replay.
            if (CustomGlint.isChromatic(layers[li])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, li, false);
                if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, crt));
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            pose.pushPose();
            try {
                pose.translate(0.5f, -0.75f, 0.5f);
                model.renderToBuffer(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            } finally {
                pose.popPose();
            }
        }
    }
}
