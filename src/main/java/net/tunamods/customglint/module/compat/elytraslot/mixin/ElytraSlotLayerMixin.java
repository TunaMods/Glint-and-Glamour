package net.tunamods.customglint.module.compat.elytraslot.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only ElytraSlot compat. ElytraSlot adds a dedicated Curios slot (id {@code elytra},
 * or sometimes {@code back} when its compatibility provider routes through Curios' back slot)
 * and renders the equipped elytra via its own {@code ElytraSlotLayer}. Vanilla's
 * {@code ElytraLayer} (which our {@code ElytraLayerMixin} hooks) only checks the chestplate
 * slot, so the glint never fires for Curios-slot elytras.
 *
 * Inject at TAIL of {@code ElytraSlotLayer.lambda$render$0} (the per-elytra render body invoked
 * from {@code Optional.ifPresent}). The lambda has just executed: {@code pushPose} →
 * {@code translate(0, 0, 0.125)} → {@code copyPropertiesTo} → {@code setupAnim} →
 * {@code renderToBuffer} → {@code popPose}. At TAIL the pose is restored, so we re-apply the same
 * (0, 0, 0.125) offset before drawing our glint / outline. The {@code elytraModel} field still
 * holds the setupAnim state from the prior call (model rotations aren't pose state), so there's no
 * need to re-run setupAnim.
 *
 * {@code ElytraRenderResult} is referenced only via {@code @Coerce Object} + reflection so this
 * compat class has zero compile/runtime dep on ElytraSlot. Same soft-compat pattern as our other
 * standalone-only mixins.
 */
@Pseudo
@Mixin(targets = "com.illusivesoulworks.elytraslot.client.ElytraSlotLayer", remap = false)
public class ElytraSlotLayerMixin {

    @Shadow private ElytraModel<?> elytraModel;

    private static volatile Method CG_STACK_M;
    private static volatile Method CG_TEXTURE_M;

    // Is Sodium's entity render path on the classpath this run? Gates the per-buffer glint draw below.
    private static final boolean SODIUM_PRESENT =
            cg_classPresent("net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer");

    private static boolean cg_classPresent(String fqn) {
        try { Class.forName(fqn, false, ElytraSlotLayerMixin.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    @Inject(method = "lambda$render$0", at = @At("TAIL"), require = 0, remap = false)
    private void cg_elytraSlotGlint(LivingEntity entity, PoseStack poseStack,
            float limbSwing, float limbSwingAmount, float partialTick,
            float ageInTicks, float netHeadYaw,
            MultiBufferSource buffer, int packedLight,
            @Coerce Object result, CallbackInfo ci) {
        if (result == null) return;
        ItemStack stack;
        ResourceLocation tex;
        try {
            Method sm = CG_STACK_M;
            if (sm == null) { sm = result.getClass().getMethod("stack"); CG_STACK_M = sm; }
            stack = (ItemStack) sm.invoke(result);
            Method tm = CG_TEXTURE_M;
            if (tm == null) { tm = result.getClass().getMethod("texture"); CG_TEXTURE_M = tm; }
            tex = (ResourceLocation) tm.invoke(result);
        } catch (Throwable t) {
            return;
        }
        if (stack == null || stack.isEmpty()) return;

        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        boolean glowing = CustomGlint.hasGlowEffect(stack);
        if (glint == null && !glowing) return;

        // Off pack the textured glint draws through forElytraGlint (chromatic through forElytraChromaticGlint):
        // NEW_ENTITY RTs that ride Sodium's renderCuboid alongside the base elytra, so they quantize identically
        // and don't z-fight the way forArmorGlint's POSITION_TEX path did under Sodium. Both test EQUAL against the
        // base elytra's own cutout depth (no camera-ward bias), so where the closed wings overlap at the center
        // only the nearest wing's glint matches and the far one fails: no additive seam. Under a shader pack that
        // program is hijacked, so keep forArmorGlint in-phase there (the chromatic layers defer to the overlay drain).
        boolean pack = CustomGlintRenderer.isShaderPackActive();
        boolean cutout = !pack && tex != null;

        VertexConsumer combined = null;
        List<VertexConsumer> list = new ArrayList<>();
        List<Integer> chromaPackLayers = new ArrayList<>();
        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    // Under a shader pack chromatic can't draw in-phase; capture the elytra for the post-Iris
                    // overlay drain instead (see EntityGlintRender.captureChromaticModel).
                    if (pack) {
                        chromaPackLayers.add(layerIdx);
                    } else {
                        RenderType crt = cutout
                                ? CustomGlintRenderer.forElytraChromaticGlint(glint, layerIdx)
                                : CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                        if (crt != null) list.add(buffer.getBuffer(crt));
                    }
                    continue;
                }
                CustomGlintRenderer.fanLayerBuffers(list, buffer, glint, layerIdx, (l, c, i) -> cutout
                        ? CustomGlintRenderer.forElytraGlint(glint, l, c, i)
                        : CustomGlintRenderer.forArmorGlint(glint, l, c, i));
            }
            if (!list.isEmpty() && !(cutout && SODIUM_PRESENT)) {
                combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            }
        }
        // Lambda popped the pose before returning, so re-apply ElytraSlot's (0, 0, 0.125) offset for both
        // the glint draw and the glow-outline capture. elytraModel's setupAnim from the prior render call is
        // preserved (model state, not pose), so the re-rendered silhouette matches the drawn wings.
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.0f, 0.125f);
        if (cutout && SODIUM_PRESENT && !list.isEmpty()) {
            // Sodium coplanar z-fight (mirrors HorseArmorLayerMixin): a combined VertexMultiConsumer breaks
            // Sodium's renderCuboid fast-path ("does not support optimized vertex writing"), so the glint would
            // fall to the vanilla per-vertex path while the elytra stays on renderCuboid and the two coplanar
            // meshes z-fight. Per-buffer walks keep every layer on renderCuboid alongside the elytra.
            for (VertexConsumer vc : list) {
                elytraModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        } else if (combined != null) {
            elytraModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        // Under a shader pack the in-phase chromatic program is hijacked, so capture the elytra per chromatic
        // layer for the post-Iris overlay drain (matches the vanilla ElytraLayerMixin path).
        if (!chromaPackLayers.isEmpty() && tex != null) {
            for (int li : chromaPackLayers) {
                EntityGlintRender.captureChromaticModel(entity, elytraModel, tex, poseStack, packedLight, glint, li);
            }
        }
        // Vanilla's ElytraLayer captures the glow outline via an in-phase tee; ElytraSlot's own layer never
        // does, so a Curios-slot elytra had no ring. Capture it here (record-only re-render), keyed by the
        // elytra STACK like the vanilla path so it keeps its own CAT_ARMOR ring.
        if (glowing && tex != null) {
            EntityGlintRender.captureModelSilhouette(entity, stack, elytraModel, tex, poseStack, packedLight,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 1);
        }
        poseStack.popPose();
    }
}
