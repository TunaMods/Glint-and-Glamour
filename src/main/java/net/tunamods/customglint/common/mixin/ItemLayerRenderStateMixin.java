package net.tunamods.customglint.common.mixin;

import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Turns vanilla's foil off for a special-renderer item (shield, trident) that carries our glint, so our glint
 * replaces the look instead of stacking on it. 26.3 bakes the enchant glint into the item's own surface
 * ({@code RenderTypes.entitySolidGlint}) chosen from the {@code hasFoil} flag this call passes, rather than
 * submitting a separate foil node we could drop, so the flag is cleared at the source and vanilla submits the
 * plain base. Our glint itself is submitted by {@code SubmitNodeCollectionMixin}. The item-glint context is
 * live here because this runs inside {@code ItemStackRenderState.submit}.
 */
@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public class ItemLayerRenderStateMixin {

    @ModifyArg(method = "submit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/special/SpecialModelRenderer;submit(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V"),
            index = 5, require = 0)
    private boolean cg_noVanillaFoilUnderOurGlint(boolean hasFoil) {
        return hasFoil && GlintCarrier.SUBMIT_GLINT.get() == null;
    }
}
