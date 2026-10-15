package net.tunamods.customglint.common.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks special-renderer items (shield, trident) with a custom glint as animated, so their GUI/inventory
 * icon re-renders each frame and the glint scrolls.
 *
 * <p>{@code SpecialModelWrapper.update} only calls {@code ItemStackRenderState.setAnimated()} when the item
 * is enchanted ({@code ItemStack.hasFoil()}); a glinted-but-unenchanted item is therefore cached as a single
 * static frame in the GUI item atlas, so its glint never moves. Unlike quad items, which route through
 * {@code CuboidItemModelWrapperMixin} forcing the foil on, we must not force the foil here: the trident's
 * foil draws through a 4-arg {@code getFoilBuffer} and the shield's through a separate {@code entityGlint}
 * node, so forcing it would draw vanilla's glint on top of ours (see {@code SubmitNodeStorageMixin}, which
 * already draws our glint geometry directly). Calling {@code setAnimated()} alone gives the animation without
 * the doubled foil. In the world the held/dropped item re-renders every frame anyway, so this only affects
 * the cached GUI icon.
 */
@Mixin(SpecialModelWrapper.class)
public class SpecialModelWrapperMixin {

    @Inject(method = "update", at = @At("RETURN"), require = 0)
    private void cg_animateGlint(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed, CallbackInfo ci) {
        if (item.isEmpty() || CustomGlint.readForRender(item) == null) return;
        if (displayContext == ItemDisplayContext.GUI) {
            // The 3D GUI-atlas bake doesn't draw our special-item glint into the cached icon (the deferred
            // submit hook that glints held/world shields never fires during the atlas render). Rather than
            // re-bake every frame with no glint, cache the base statically and draw the scrolling glint LIVE
            // as the flat GUI overlay, exactly like a flat item's icon (GuiRendererMixin.cg_itemGlintOverlay).
            // The overlay masks to the icon's rendered silhouette (slot alpha), so a shield/trident icon reads
            // the glint over its own shape and at the same flat-item scale as every other GUI icon.
            ((CgGlintHolder) output).customglint$setGuiGlintOverlay(true);
        } else {
            // Held / dropped / frame: re-render each frame (the world submit path draws the glint directly).
            output.setAnimated();
        }
    }
}
