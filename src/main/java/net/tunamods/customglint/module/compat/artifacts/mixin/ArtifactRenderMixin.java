package net.tunamods.customglint.module.compat.artifacts.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.artifacts.client.ArtifactGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only Artifacts compat (26.1). Worn artifacts (belts, necklaces, gloves, boots, …) draw through
 * their own {@code ArtifactRenderer} via Curios, bypassing the vanilla {@code EquipmentLayerRenderer} the
 * core {@code EquipmentLayerRendererMixin} covers. Every worn artifact (base and the glowing variants that
 * add a full-bright overlay) funnels through the shared static
 * {@code ArtifactRenderer.renderModelWithFoil(model, state, pose, collector, texture, light, hasFoil)}.
 *
 * <p>We arm {@link ArtifactGlint} with the wearer's {@link ItemStack} for the span of one
 * {@code ArtifactRenderer.render} (the humanoid overload, where all worn draws originate and which the
 * generic overload delegates to), so {@link ArtifactGlint#fan} runs only for artifacts. The fan fires on
 * the first {@code renderModelWithFoil} of that span (the base-texture draw), submitting our glint onto the
 * same model/state/pose and queuing the glow silhouette. First-person hand artifacts take a separate
 * {@code renderFirstPersonArm} → {@code renderModelPartWithFoil} path that is intentionally left untouched
 * (deferred, matching the branch's first-person-hand state).
 *
 * <p>Soft compat: {@code @Pseudo} + {@code remap = false} + {@code require = 0}, no compile-time Artifacts
 * dependency, no-ops when Artifacts is absent or refactors these entry points.
 */
@Pseudo
@Mixin(targets = "artifacts.client.item.renderer.ArtifactRenderer", remap = false)
public class ArtifactRenderMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;"
            + "Lnet/minecraft/client/model/HumanoidModel;"
            + "ILcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    private static final String RENDER_MODEL_WITH_FOIL =
            "renderModelWithFoil(Lnet/minecraft/client/model/Model;"
            + "Ljava/lang/Object;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/resources/Identifier;IZ)V";

    @Inject(method = RENDER, at = @At("HEAD"), require = 0, remap = false)
    private void cg_armArtifact(ItemStack stack, HumanoidRenderState state, HumanoidModel<?> model, int color,
            PoseStack pose, SubmitNodeCollector collector, int outline, CallbackInfo ci) {
        ArtifactGlint.arm(stack);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_disarmArtifact(ItemStack stack, HumanoidRenderState state, HumanoidModel<?> model, int color,
            PoseStack pose, SubmitNodeCollector collector, int outline, CallbackInfo ci) {
        ArtifactGlint.disarm();
    }

    @Inject(method = RENDER_MODEL_WITH_FOIL, at = @At("HEAD"), require = 0, remap = false)
    private static void cg_fanArtifact(Model model, Object state, PoseStack pose, SubmitNodeCollector collector,
            Identifier texture, int light, boolean hasFoil, CallbackInfo ci) {
        if (!ArtifactGlint.isArmed()) return;
        ArtifactGlint.fan(model, state, pose, collector, texture, light);
    }

    /**
     * Suppresses vanilla's enchantment foil ({@code armorEntityGlint}, submitted at {@code order(1)}) on an
     * artifact that carries our own glint, so the two don't stack: our glint replaces the look, matching the
     * armor and item paths. Off when unarmed (non-artifact callers) or when the stack has no custom glint.
     */
    @ModifyVariable(method = RENDER_MODEL_WITH_FOIL, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private static boolean cg_suppressVanillaFoil(boolean hasFoil) {
        if (!hasFoil || !ArtifactGlint.isArmed()) return hasFoil;
        return CustomGlint.readForRender(ArtifactGlint.current()) == null;
    }
}
