package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition's Tide Trident. Its renderer (a uranus
 * {@code DynamicItemRenderer}) draws the GUI / FIXED / GROUND / NONE forms by spawning a FRESH
 * {@code tide_trident_inventory} {@link ItemStack} and re-rendering THAT through
 * {@code ItemRenderer.renderStatic}: it only copies the enchantments across, so the new stack lacks the
 * {@code customglint} tag and the flat icon drew with no glint (while the 3D held form, drawn from the
 * original stack, glinted fine).
 *
 * <p>Fix: {@link Redirect} that {@code renderStatic} call and copy the original stack's glint Data onto the
 * inventory stack first, so {@code ItemRendererMixin}'s getFoilBuffer hook fans the glint onto the flat
 * icon. Glow is unaffected; it's captured from the original stack by the generic special-BEWLR path.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.item.TideTridentItemRenderer", remap = false)
public class TideTridentRendererMixin {

    @Redirect(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"),
            require = 0)
    private void cg_carryGlint(ItemRenderer renderer, ItemStack invStack, ItemDisplayContext ctx,
            int light, int overlay, PoseStack pose, MultiBufferSource buffer, Level level, int seed,
            @Local(argsOnly = true) ItemStack original) {
        CustomGlint.Data glint = CustomGlint.readForRender(original);
        if (glint != null) CustomGlint.write(invStack, glint.layers());
        renderer.renderStatic(invStack, ctx, light, overlay, pose, buffer, level, seed);
    }
}
