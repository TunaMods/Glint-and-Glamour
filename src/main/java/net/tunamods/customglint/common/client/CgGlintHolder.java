package net.tunamods.customglint.common.client;

import net.tunamods.customglint.common.CustomGlint;
import org.jspecify.annotations.Nullable;

/**
 * Carrier interface mixed onto {@code ItemStackRenderState} and {@code ItemFeatureRenderer.Submit}
 * so the per-item glint {@link CustomGlint.Data} can travel from render-state extraction (where the
 * {@code ItemStack} is known) to the deferred foil draw in {@code ItemFeatureRenderer.prepareSubmit}
 * (where only the {@code ItemFeatureRenderer.Submit} node is available). The 1.21.5+ submit-node rework decoupled the
 * draw from the stack, so the glint must ride the node rather than a frame-scoped {@code ThreadLocal}.
 *
 * <p>Lives outside the {@code common.mixin} package because Mixin reserves that package and forbids
 * direct references to non-mixin classes within it.
 */
public interface CgGlintHolder {
    /** Null when the item carries no glint, which is the common case. */
    CustomGlint.@Nullable Data customglint$getGlint();

    void customglint$setGlint(CustomGlint.@Nullable Data glint);

    /**
     * The item's glow state, carried alongside the glint so the deferred item draw can decide whether
     * to emit a glow outline (see {@code ItemRendererMixin}). Glow is independent of the glint
     * ({@link CustomGlint#isGlowing}/{@link CustomGlint#getGlowColors}): a Glow-Trimmed item with no
     * glint still outlines, so it can't be derived from the glint {@link CustomGlint.Data}.
     */
    default boolean customglint$isGlowing() { return false; }

    default void customglint$setGlowing(boolean glowing) {}

    default int[] customglint$getGlowColors() { return null; }

    default void customglint$setGlowColors(int[] glowColors) {}

    default float customglint$getGlowSpeed() { return 1.0f; }

    default void customglint$setGlowSpeed(float speed) {}

    default boolean customglint$getGlowInterp() { return true; }

    default void customglint$setGlowInterp(boolean interp) {}

    /**
     * True when this item's glint is drawn as a LIVE GUI overlay rather than baked into the cached atlas
     * icon. Set by {@code CuboidItemModelWrapperMixin} for flat items rendered in the {@code GUI} context:
     * it stops forcing the foil/animated flag so the base icon caches once, and {@code GuiRendererMixin}
     * draws the scrolling glint on top via {@link GuiItemGlintRenderState}. Special 3D items (trident /
     * shield, via {@code SpecialModelWrapperMixin}) keep baking + animating their glint, so this stays
     * false for them and no overlay is emitted (which would double the glint).
     */
    default boolean customglint$isGuiGlintOverlay() { return false; }

    default void customglint$setGuiGlintOverlay(boolean overlay) {}
}
