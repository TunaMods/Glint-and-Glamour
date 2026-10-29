package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes a glinted item's GUI icon to the LIVE glint overlay instead of baking the glint into the cached
 * atlas slot, so the icon no longer re-renders every frame just to animate the glint.
 *
 * <p>Our flat-item glint is only submitted (see {@code SubmitNodeCollectionMixin.submitItem}) when the
 * layer's {@code FoilType != NONE}.
 * {@code CuboidItemModelWrapper.update} derives the foil type from {@code ItemStack.hasFoil()} and, when
 * foiled, calls {@code output.setAnimated()}, which makes the GUI item atlas discard + re-bake that icon
 * EVERY frame ({@code GuiItemAtlas} / {@code DynamicAtlasAllocator}). With dozens of glinted items on screen
 * (the creative tab) that is the frame drop.
 *
 * <ul>
 *   <li><b>Non-GUI</b> (held / dropped / item frame): force the foil on so our glint is submitted
 *       (vanilla's own is switched off by {@code ItemRendererMixin}), those contexts re-render every frame
 *       anyway, no atlas cache.</li>
 *   <li><b>GUI, custom glint, not already enchanted</b>: do NOT force the foil, the base icon bakes once
 *       and caches, and flag the render state ({@link CgGlintHolder#customglint$setGuiGlintOverlay}) so
 *       {@code GuiRendererMixin} draws the scrolling glint live on top via {@code GuiItemGlintRenderState}.</li>
 *   <li><b>GUI, already enchanted</b>: leave vanilla's real foil (it bakes + animates itself); no overlay,
 *       so the glint isn't drawn twice.</li>
 * </ul>
 *
 * Special 3D items (trident / shield) go through {@code SpecialModelWrapper} and keep baking + animating
 * their glint, the flat screen-space overlay wouldn't match a 3D GUI model.
 */
@Mixin(CuboidItemModelWrapper.class)
public class CuboidItemModelWrapperMixin {

    @ModifyExpressionValue(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z"),
        require = 0
    )
    private boolean cg_forceFoilForGlint(boolean original,
            @Local(argsOnly = true) ItemStackRenderState output,
            @Local(argsOnly = true) ItemStack item,
            @Local(argsOnly = true) ItemDisplayContext displayContext) {
        boolean hasGlint = CustomGlint.readForRender(item) != null;
        if (hasGlint && displayContext == ItemDisplayContext.GUI && !original) {
            // Flat GUI icon: cache the plain base, scroll the glint live as an overlay.
            ((CgGlintHolder) output).customglint$setGuiGlintOverlay(true);
            return false;
        }
        // Non-GUI (draw the glint via the foil path), or an already-enchanted GUI item (vanilla foil bakes).
        return original || hasGlint;
    }
}
