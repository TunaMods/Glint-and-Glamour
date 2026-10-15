package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
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
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: hippocampus armor (vanilla HorseArmorItem variants) is rendered by
 * IaF's LayerHippocampusSaddle, not vanilla HorseArmorLayer, so HorseArmorLayerMixin never
 * fires for it. Same shape as {@link LayerHippogryphArmorMixin}: pick one of three solid textures
 * by entity.getArmor() (1/2/3 = iron/gold/diamond), source the actual ItemStack from the
 * client-synced cache.
 *
 * Glint mechanics identical to the dragon/hippogryph variants: glint via forArmorGlint (armor
 * texture cutout mask, no stencil), glow outline captured here by re-rendering the parent model
 * against the armor texture (the generic in-phase tee never reaches IaF mount armor). See
 * {@link LayerDragonArmorMixin} for the full rationale.
 *
 * Armor ItemStack source: {@link MountArmorCache} (synced by EntityHippocampusArmorSyncMixin +
 * StartTracking listener; IaF's SimpleContainer doesn't sync to clients on its own).
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.entity.HippocampusEntityRenderer$LayerHippocampusSaddle", remap = false)
public class LayerHippocampusArmorMixin {

    private static final ResourceLocation CG_TEX_IRON =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippocampus/armor_iron.png");
    private static final ResourceLocation CG_TEX_GOLD =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippocampus/armor_gold.png");
    private static final ResourceLocation CG_TEX_DIAMOND =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippocampus/armor_diamond.png");

    private static volatile Method CG_GET_PARENT_MODEL;
    private static volatile Method CG_GET_ARMOR;

    private EntityModel<?> cg_getParentModel() {
        try {
            Method m = CG_GET_PARENT_MODEL;
            if (m == null) {
                Class<?> cls = Class.forName("net.minecraft.client.renderer.entity.layers.RenderLayer");
                for (Method mm : cls.getDeclaredMethods()) {
                    if (mm.getParameterCount() == 0
                            && mm.getReturnType().getName().equals("net.minecraft.client.model.EntityModel")) {
                        mm.setAccessible(true);
                        m = mm;
                        break;
                    }
                }
                CG_GET_PARENT_MODEL = m;
            }
            return m == null ? null : (EntityModel<?>) m.invoke(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int cg_getArmor(Object entity) {
        try {
            Method m = CG_GET_ARMOR;
            if (m == null) {
                m = entity.getClass().getMethod("getArmorValue");
                CG_GET_ARMOR = m;
            }
            return (int) m.invoke(entity);
        } catch (Throwable t) {
            return 0;
        }
    }

    // The saddle layer redraws the whole hippocampus model once per equipment piece (saddle, bridle, chest, and
    // the armor tier) through the wrapped buffer, so the mount's own entity glint fanned onto every one of them,
    // painting glint over the saddle and chest that don't carry it. Unwrap so those equipment draws render plain;
    // the armor's own glint is re-added below through the unwrapped buffer, and the body keeps its glint from the
    // main renderer. No-op when the mount has no entity glint (the buffer isn't wrapped then).
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/HippocampusEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer cg_unwrapEquipment(MultiBufferSource src, RenderType rt) {
        return EntityGlintRender.unwrap(src).getBuffer(rt);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/HippocampusEntity;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        int armor = cg_getArmor(entity);
        if (armor == 0) return;
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;

        ResourceLocation tex;
        switch (armor) {
            case 1: tex = CG_TEX_IRON; break;
            case 2: tex = CG_TEX_GOLD; break;
            case 3: tex = CG_TEX_DIAMOND; break;
            default: return;
        }

        ItemStack stack = MountArmorCache.get(entity.getId());
        boolean glow = CustomGlint.hasGlowEffect(stack);
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null && !glow) return;

        EntityModel<?> model = cg_getParentModel();
        if (model == null) return;

        // Glow outline: re-render the parent model traced against the armor texture (alpha-discard →
        // only the armored texels), keyed CAT_ARMOR + the mount's id so it folds into the mount's body
        // ring when both glow. The IaF mount armor doesn't render through any vanilla layer, so the
        // generic in-phase tee never captures it; this is the only capture point for it.
        if (glow) {
            EntityGlintRender.captureModelSilhouette(entity, entity, model, tex, pose, light,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0);
        }
        if (glint == null) return;

        // Draw the base armor through the UNWRAPPED buffer with armorCutoutNoCull, then glint via
        // forArmorGlint, the same fix that LayerDragonArmorMixin uses. Hippocampus armor reuses the
        // body model at the SAME depth, so the EQUAL-depth body glint drew over the armor (the armor
        // glint then read as covering the whole entity). armorCutoutNoCull's polygon offset nudges
        // the armor in front of the body so the body glint is depth-occluded there, and forArmorGlint
        // (EQUAL + the matching offset, masked by the armor texture's own alpha cutout) lands only on
        // this armor's opaque texels. Routed through the unwrapped buffer so the wrapper can't re-fan
        // the body glint onto the armor.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);
        model.renderToBuffer(pose, flush.getBuffer(RenderType.armorCutoutNoCull(tex)),
                light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

        CustomGlint.Layer[] layers = glint.layers();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            if (CustomGlint.isChromatic(layers[li])) {
                // Chromatic has no PNG, so forArmorGlint returns null for it (armor showed no chromatic at all).
                // Off-pack, draw the in-phase chromatic cutout RT (armor texture alpha as the mask). Under a pack
                // that program is hijacked, so capture the model and queue the post-Iris chromatic overlay against
                // the armor texture; that drain is queued after the mount body's, so it overwrites the body's
                // chromatic in the armor region instead of the body glint bleeding across the armor.
                if (CustomGlintRenderer.isShaderPackActive()) {
                    EntityGlintRender.captureChromaticModel(entity, model, tex, pose, light, glint, li);
                } else {
                    RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, li);
                    if (crt != null) list.add(flush.getBuffer(crt));
                }
                continue;
            }
            CustomGlintRenderer.fanLayerBuffers(list, flush, glint, li,
                    (l, col, i) -> CustomGlintRenderer.forArmorGlint(glint, l, col, i));
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
    }
}
