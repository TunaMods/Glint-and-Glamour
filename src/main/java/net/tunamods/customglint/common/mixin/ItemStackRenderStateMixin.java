package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the per-item glint on the reusable {@link ItemStackRenderState} scratch object and publishes it
 * into {@link GlintCarrier#SUBMIT_GLINT} for the duration of {@code submit(...)} so each freshly built
 * {@code ItemFeatureRenderer.Submit} node can carry it to the deferred draw. The glint is attached during
 * {@code ItemModelResolver.updateForTopItem} (see {@code ItemModelResolverMixin}); it is null for items
 * with no custom glint.
 */
@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin implements CgGlintHolder {

    @Unique private CustomGlint.Data customglint$glint;
    @Unique private boolean customglint$glowing;
    @Unique private int[] customglint$glowColors;
    @Unique private float customglint$glowSpeed = 1.0f;
    @Unique private boolean customglint$glowInterp = true;
    @Unique private boolean customglint$guiGlintOverlay;

    @Override
    public CustomGlint.Data customglint$getGlint() {
        return this.customglint$glint;
    }

    @Override
    public void customglint$setGlint(CustomGlint.Data glint) {
        this.customglint$glint = glint;
    }

    @Override
    public boolean customglint$isGlowing() {
        return this.customglint$glowing;
    }

    @Override
    public void customglint$setGlowing(boolean glowing) {
        this.customglint$glowing = glowing;
    }

    @Override
    public int[] customglint$getGlowColors() {
        return this.customglint$glowColors;
    }

    @Override
    public void customglint$setGlowColors(int[] glowColors) {
        this.customglint$glowColors = glowColors;
    }

    @Override
    public float customglint$getGlowSpeed() {
        return this.customglint$glowSpeed;
    }

    @Override
    public void customglint$setGlowSpeed(float speed) {
        this.customglint$glowSpeed = speed;
    }

    @Override
    public boolean customglint$getGlowInterp() {
        return this.customglint$glowInterp;
    }

    @Override
    public void customglint$setGlowInterp(boolean interp) {
        this.customglint$glowInterp = interp;
    }

    @Override
    public boolean customglint$isGuiGlintOverlay() {
        return this.customglint$guiGlintOverlay;
    }

    @Override
    public void customglint$setGuiGlintOverlay(boolean overlay) {
        this.customglint$guiGlintOverlay = overlay;
    }

    // ItemStackRenderState is a reused scratch object; updateForTopItem clears it before rebuilding. The
    // glint/glow fields are overwritten every item by ItemModelResolverMixin, but the overlay flag is only
    // SET (by CuboidItemModelWrapperMixin, for flat GUI items), so it must be cleared here or it would
    // leak to the next item drawn through the same scratch state.
    @Inject(method = "clear", at = @At("RETURN"), require = 0)
    private void cg_clearOverlayFlag(CallbackInfo ci) {
        this.customglint$guiGlintOverlay = false;
    }

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void cg_submitHead(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
            int outlineColor, CallbackInfo ci) {
        // Save/restore (push/pop) rather than set/remove so a nested cross-instance submit restores this
        // item's context on return instead of clearing it. Fresh per-item token so all of a special item's
        // sub-model submits share one outline group.
        GlintCarrier.pushSubmit(this.customglint$glint, this.customglint$glowing, this.customglint$glowColors,
                new Object(), this.customglint$glowSpeed, this.customglint$glowInterp);
    }

    @Inject(method = "submit", at = @At("RETURN"), require = 0)
    private void cg_submitReturn(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
            int outlineColor, CallbackInfo ci) {
        GlintCarrier.popSubmit();
    }
}
