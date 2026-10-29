package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Attaches the top item's glint {@link CustomGlint.Data} to its {@link ItemStackRenderState} when the
 * render state is (re)built. {@code updateForTopItem} clears then repopulates the reused render state,
 * so setting the glint at RETURN (null when the item has none) also resets stale state from the
 * previous item drawn through the same scratch object.
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("RETURN"), require = 0)
    private void cg_attachGlint(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext,
            Level level, ItemOwner owner, int seed, CallbackInfo ci) {
        // One component read instead of read()/isGlowing()×N/getGlowColors()×N on the same stack, this
        // runs for every item render-state rebuild (mirrors EquipmentLayerRendererMixin's single readState).
        CustomGlint.GlintState state = CustomGlint.readStateForRender(item);
        CustomGlint.Data glint = state.data();
        boolean glowing = state.glowing();
        int[] glowColors = state.glowColors(); // never null; consumers null/empty-check either way
        CgGlintHolder holder = (CgGlintHolder) (Object) output;
        holder.customglint$setGlint(glint);
        // Glow rides the carrier independently of the glint: a Glow-Trimmed item with no glint still
        // outlines. The deferred item draw reads these back off the item Submit node to queue the ring.
        holder.customglint$setGlowing(glowing);
        holder.customglint$setGlowColors(glowColors);
        holder.customglint$setGlowSpeed(state.glowSpeed());
        holder.customglint$setGlowInterp(state.glowInterp());

        // A GUI-baked glinted item that no wrapper claimed for the live flat overlay bakes its glint into
        // the cached atlas icon as a single STATIC frame. Flat items (CuboidItemModelWrapperMixin) and
        // shield/trident (SpecialModelWrapperMixin) set the overlay flag and scroll the glint live on top;
        // a modded special-model item like a Sophisticated Backpack sets neither, so its baked glint never
        // moved in the inventory. Mark it animated so the atlas re-bakes each frame and the glint scrolls,
        // matching the in-hand look. Gated on the overlay flag being UNSET so overlay items keep the cheaper
        // cached path (only these baked special-model icons pay the per-frame re-bake).
        if (glint != null && displayContext == ItemDisplayContext.GUI && !holder.customglint$isGuiGlintOverlay()) {
            output.setAnimated();
        }

        // The GUI renders items into an atlas cached by getModelIdentity() (see GuiItemAtlas), the
        // glint is NOT part of vanilla's identity, so two items of the same base type + foil state
        // share one cached slot. Without this, giving a glinted item freezes the editor preview on
        // that config. Fold the glint config into the identity so each distinct look gets its own slot.
        if (glint != null || glowing) {
            output.appendModelIdentityElement(cg_identity(glint, glowing, glowColors, state.glowSpeed(), state.glowInterp()));
        }
    }

    @Unique
    private static String cg_identity(CustomGlint.Data glint, boolean glowing, int[] glowColors,
            float glowSpeed, boolean glowInterp) {
        StringBuilder sb = new StringBuilder("customglint:");
        if (glint != null) {
            for (CustomGlint.Layer l : glint.layers()) {
                sb.append(l.design()).append('@').append(l.speed()).append(',')
                  .append(l.interpolate()).append(',').append(l.patternScale()).append(',')
                  .append(l.simultaneous()).append(',').append(l.scrollDir()).append(',').append(l.scrollOffset())
                  .append(Arrays.toString(l.colors())).append(';');
            }
        }
        sb.append("glow=").append(glowing).append(Arrays.toString(glowColors))
          .append(',').append(glowSpeed).append(',').append(glowInterp);
        return sb.toString();
    }
}
