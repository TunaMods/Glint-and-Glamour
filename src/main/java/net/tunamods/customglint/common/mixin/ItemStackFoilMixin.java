package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the vanilla enchantment foil on any stack that renders a custom glint, so the two never draw on top
 * of each other. {@code ItemRendererMixin} already replaces the foil buffer for items, but vanilla also draws
 * the foil from places that never go through it: {@code HumanoidArmorLayer.renderGlint}, the elytra's
 * {@code getArmorFoilBuffer}, compasses, shield banner patterns, and any modded renderer that checks
 * {@code hasFoil()} itself (GeckoLib armor, for one). Every one of them asks {@code hasFoil()} first, so
 * answering false here covers them all.
 *
 * <p>Render thread only. The one non-render caller in vanilla is {@code ThrownTrident}, which copies
 * {@code hasFoil()} into synced entity data on the server; on an integrated server that runs on the server
 * thread, so it keeps its real answer. "Renders a custom glint" is {@link CustomGlint#readForRender}: the
 * stack's own glint or a type-wide item glint.
 */
@Mixin(ItemStack.class)
public class ItemStackFoilMixin {

    @Inject(method = "hasFoil()Z", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void cg_hideVanillaFoil(CallbackInfoReturnable<Boolean> cir) {
        if (RenderSystem.isOnRenderThread() && CustomGlint.readForRender((ItemStack) (Object) this) != null)
            cir.setReturnValue(false);
    }
}
