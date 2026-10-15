package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Custom per-equipment glint, drawn in the 26.1 deferred submit-node pipeline.
 *
 * <p><b>26.1 unified all equipment rendering through {@link EquipmentLayerRenderer#renderLayers}.</b>
 * The old dedicated layers ({@code HumanoidArmorLayer}, {@code ElytraLayer}, {@code HorseArmorLayer})
 * no longer draw directly, humanoid armor ({@code HumanoidArmorLayer}), elytra/capes
 * ({@code WingsLayer}, {@code LayerType.WINGS}), and barding/animal armor
 * ({@code SimpleEquipmentLayer}, {@code LayerType.HORSE_BODY}/{@code WOLF_BODY}/…) all funnel into this
 * one method, and every layer is submitted with {@code RenderTypes.armorCutoutNoCull} (EQUAL depth +
 * {@code VIEW_OFFSET_Z_LAYERING}). So a single mixin here replaces the three old layer mixins, and the
 * dedicated horse-armor {@code NO_LAYERING} path the 1.21.1 build used for barding is gone from the armor
 * side, {@link CustomGlintRenderer#forArmorGlint} (VIEW_OFFSET_Z) is correct for every equipment layer here.
 * (The {@code NO_LAYERING} render state itself still exists in {@link CustomGlintRenderer#forEntityBodyGlint},
 * which now backs entity-body draws rather than barding.) The {@code WingsLayer} {@code (0,0,0.125)} elytra offset
 * is already on the {@code PoseStack} by the time {@code renderLayers} runs, so our glint inherits it.
 *
 * <p>Each glint layer/colour is queued as its own {@code submitModel} node reusing the equipment
 * {@code model} that drew the armor. The node's {@code tintedColor} becomes the model's per-vertex
 * colour (read by {@code customglint:core/glint_color}); the deferred {@code ModelFeatureRenderer}
 * draws each RenderType in its own serialized batch, so the per-layer shared-buffer flush that bit the
 * item path does not apply here. Outline/glow is a separate later pass; these nodes pass outlineColor 0.
 */
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerRendererMixin {

    private static final String RENDER_LAYERS =
        "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
            + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;"
            + "Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "ILnet/minecraft/resources/Identifier;II)V";

    /**
     * Suppresses vanilla's enchantment foil ({@code armorEntityGlint}) on a piece that carries our own
     * glint, so the two don't stack, our glint replaces the look, matching the item path.
     */
    @ModifyExpressionValue(
        method = RENDER_LAYERS,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z"),
        require = 0
    )
    private boolean cg_suppressVanillaFoil(boolean original, @Local(argsOnly = true) ItemStack itemStack) {
        return original && CustomGlint.readForRender(itemStack) == null;
    }

    @Inject(method = RENDER_LAYERS, at = @At("RETURN"), require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_equipmentGlint(EquipmentClientInfo.LayerType layerType, ResourceKey assetId, Model model,
            Object state, ItemStack itemStack, PoseStack poseStack, SubmitNodeCollector collector,
            int lightCoords, Identifier playerTextureOverride, int outlineColor, int order,
            CallbackInfo ci, @Local(ordinal = 0) List<?> layers) {
        if (layers.isEmpty()) return;
        // One component fetch covers glint + both glow flags below (each accessor would otherwise re-fetch
        // the GlintState component), this fires per equipment layer per wearer per frame.
        var glintState = CustomGlint.readStateForRender(itemStack);
        CustomGlint.Data glint = glintState.data();
        // Elytra/capes (WINGS) are thin double-sided meshes AND the two folded wings overlap along the spine.
        // In phase the EQUAL depth test already keeps only the nearest surface, but the post-shader overlay
        // has no such test, so it gets a depth pre-pass (forWingDepthPrepass) + a LEQUAL wing pass, which
        // keeps the nearest surface per pixel and stops the additive glint doubling into a bright spine seam.
        // Back-face culling is NOT the answer here (it eats the mirrored wing's faces, killing the glint on
        // the underside), see the note on GlintPipelines.CHROMATIC_OVERLAY_WING. The wing glow also needs its
        // OWN isolated ring so it doesn't fuse into the wearer's body outline.
        boolean isWings = layerType == EquipmentClientInfo.LayerType.WINGS;

        if (glint != null) {
            CustomGlint.Layer[] gl = glint.layers();
            for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
                int[] colors = gl[layerIdx].colors();
                if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
                // Under an active shader pack NOTHING draws in-phase correctly: Iris replaces our program, so
                // chromatic goes flat white (the reported elytra bug) and normal glint goes SOLID (opaque
                // gbuffer program). Queue BOTH for the post-Iris overlay drain, cut out against the equipment
                // texture. See EntityGlintRender.queueGlintOverlayModel / drainChromaticOverlays.
                boolean chroma = CustomGlint.isChromatic(gl[layerIdx]);
                if (CustomGlintRenderer.isShaderPackActive()) {
                    Identifier tex = cg_equipTexture(layers, layerType, itemStack, playerTextureOverride);
                    // Depth pre-pass RT for the two overlapping folded wings (null for flat armor); the drain
                    // primes the isolated depth with it so the LEQUAL wing pass drops the farther wing.
                    RenderType wingDepth = isWings && tex != null ? CustomGlintRenderer.forWingDepthPrepass(tex) : null;
                    if (chroma) {
                        RenderType rt = tex == null ? null : CustomGlintRenderer.forArmorGlintOverlay(glint, layerIdx, tex, isWings);
                        if (rt != null) EntityGlintRender.queueChromaticModel(model, state, poseStack.last(), rt, lightCoords, false, wingDepth);
                    } else if (gl[layerIdx].simultaneous()) {
                        for (int i = 0; i < colors.length; i++) {
                            RenderType rt = tex == null ? null : CustomGlintRenderer.forArmorGlintOverlayNormal(glint, layerIdx, i, tex, isWings);
                            if (rt != null) EntityGlintRender.queueGlintOverlayModel(model, state, poseStack.last(), rt, lightCoords,
                                    CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]), false, wingDepth);
                        }
                    } else {
                        int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                        RenderType rt = tex == null ? null : CustomGlintRenderer.forArmorGlintOverlayNormal(glint, layerIdx, 0, tex, isWings);
                        if (rt != null) EntityGlintRender.queueGlintOverlayModel(model, state, poseStack.last(), rt, lightCoords, color, false, wingDepth);
                    }
                } else if (gl[layerIdx].simultaneous() && !chroma) {
                    for (int i = 0; i < colors.length; i++) {
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, i);
                        if (rt != null) cg_submit(collector, model, state, poseStack, rt, lightCoords,
                                CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, 0);
                    if (rt != null) cg_submit(collector, model, state, poseStack, rt, lightCoords, color);
                }
            }
        }

        // Glow outline, independent of the glint (a Glow-Trimmed armor piece with no glint still
        // outlines). Queued for the AfterWeather drain (the same mask + composite as entities).
        // The first layer's texture drives the silhouette alpha-discard so the ring follows the real
        // armor shape; the model + state are re-posed via setupAnim at drain (matching the armor body
        // draw), so multi-wearer scenes don't share a stale pose. Covers humanoid armor, elytra/capes
        // (WINGS), and barding (HORSE_BODY/WOLF_BODY), all funnel through renderLayers.
        boolean glowing = glintState.glowing();
        int[] glowColors = glintState.glowColors();
        if (glowing || glowColors.length > 0) {
            Identifier tex = cg_equipTexture(layers, layerType, itemStack, playerTextureOverride);
            if (tex != null) {
                EntityGlintRender.queueArmorOutline(model, state, poseStack.last(), tex, lightCoords,
                        glint, glowing, glowColors, glintState.glowSpeed(), glintState.glowInterp(), isWings);
            }
        }
    }

    /** The equipment layer's resolved texture (player-skin override, then the layer's own texture, then the
     *  Forge armor-texture hook for modded armor). Its alpha drives the cutout silhouette for both the glow
     *  outline and the post-Iris chromatic overlay. Returns null when the ordinal-captured {@code layers}
     *  local doesn't hold {@link EquipmentClientInfo.Layer}s, so a rebound {@code @Local(ordinal=0)} degrades
     *  the cosmetic passes instead of throwing on the render thread. */
    private static Identifier cg_equipTexture(List<?> layers, EquipmentClientInfo.LayerType layerType,
            ItemStack itemStack, Identifier playerTextureOverride) {
        if (layers.isEmpty() || !(layers.get(0) instanceof EquipmentClientInfo.Layer first)) return null;
        Identifier tex = first.usePlayerTexture() && playerTextureOverride != null
                ? playerTextureOverride : first.getTextureLocation(layerType);
        return ClientHooks.getArmorTexture(itemStack, layerType, first, tex);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cg_submit(SubmitNodeCollector collector, Model model, Object state, PoseStack poseStack,
            RenderType rt, int lightCoords, int argb) {
        int color = (argb >>> 24) == 0 ? (argb | 0xFF000000) : argb;
        collector.submitModel(model, state, poseStack, rt, lightCoords, OverlayTexture.NO_OVERLAY,
                color, null, 0, null);
    }
}
