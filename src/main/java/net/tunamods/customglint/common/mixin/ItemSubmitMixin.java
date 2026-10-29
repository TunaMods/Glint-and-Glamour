package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds a glint slot to the {@code ItemFeatureRenderer.Submit} deferred-draw node and captures the value from
 * {@link GlintCarrier#SUBMIT_GLINT} at construction (which happens inside the synchronous
 * {@code ItemStackRenderState.submit(...)} call). The node is read back during the draw in
 * {@code ItemFeatureRenderer.prepareSubmit}. 26.2 builds a second Submit for the vanilla outline phase from the
 * same call; it carries the same values and is never read.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public class ItemSubmitMixin implements CgGlintHolder {

    @Unique private CustomGlint.Data customglint$glint;
    @Unique private boolean customglint$glowing;
    @Unique private int[] customglint$glowColors;
    @Unique private float customglint$glowSpeed = 1.0f;
    @Unique private boolean customglint$glowInterp = true;

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

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void cg_carryGlint(PoseStack.Pose pose, ItemDisplayContext displayContext, int lightCoords,
            int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType, CallbackInfo ci) {
        this.customglint$glint = GlintCarrier.SUBMIT_GLINT.get();
        Boolean glowing = GlintCarrier.SUBMIT_GLOWING.get();
        this.customglint$glowing = glowing != null && glowing;
        this.customglint$glowColors = GlintCarrier.SUBMIT_GLOW_COLORS.get();
        Float glowSpeed = GlintCarrier.SUBMIT_GLOW_SPEED.get();
        this.customglint$glowSpeed = glowSpeed != null ? glowSpeed : 1.0f;
        Boolean glowInterp = GlintCarrier.SUBMIT_GLOW_INTERP.get();
        this.customglint$glowInterp = glowInterp == null || glowInterp;
    }
}
