package net.tunamods.customglint.common.client;

import net.tunamods.customglint.common.CustomGlint;

import java.util.ArrayDeque;

/**
 * Thread-locals carrying the item-submit context. {@link #SUBMIT_GLINT} is set during
 * {@code ItemStackRenderState.submit(...)} (extraction) so every {@code ItemFeatureRenderer.Submit} node created
 * inside that synchronous call captures the item's glint; the deferred draw reads it back off the node.
 *
 * <p>Lives outside the {@code common.mixin} package because Mixin reserves that package and forbids
 * direct references to non-mixin classes within it.
 */
public final class GlintCarrier {
    public static final ThreadLocal<CustomGlint.Data> SUBMIT_GLINT = new ThreadLocal<>();

    /**
     * Item glow state published during {@code ItemStackRenderState.submit(...)} so each
     * {@code ItemFeatureRenderer.Submit} node captures it for the deferred glow-outline draw. Glow is independent of
     * the glint, so it rides the carrier separately (see {@link CgGlintHolder}). No DRAW-side thread
     * local is needed: the item draw ({@code ItemFeatureRenderer.prepareSubmit}) reads the glow back
     * straight off the {@code ItemFeatureRenderer.Submit} node, which is in scope there.
     */
    public static final ThreadLocal<Boolean> SUBMIT_GLOWING = new ThreadLocal<>();
    public static final ThreadLocal<int[]> SUBMIT_GLOW_COLORS = new ThreadLocal<>();
    public static final ThreadLocal<Float> SUBMIT_GLOW_SPEED = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> SUBMIT_GLOW_INTERP = new ThreadLocal<>();

    /**
     * A fresh identity token per {@code ItemStackRenderState.submit} call, used as the outline GROUP key
     * for special-renderer items (shield, trident): one such item submits several model/part nodes
     * (base + patterns + foil), and keying them all by this token merges them into a single ring instead
     * of compositing the same silhouette several times. Distinct items get distinct tokens → distinct
     * rings. Read by {@code SubmitNodeCollectionMixin} during the special renderer's submit.
     */
    public static final ThreadLocal<Object> SUBMIT_TOKEN = new ThreadLocal<>();

    /** Saved outer-submit context, so a nested {@code submit(...)} restores (not clears) the enclosing
     *  item's glint/glow when it returns. */
    private record SubmitFrame(CustomGlint.Data glint, Boolean glowing, int[] glowColors, Object token,
                               Float glowSpeed, Boolean glowInterp) {}
    private static final ThreadLocal<ArrayDeque<SubmitFrame>> SUBMIT_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    /** Stands in for "nothing was bound" at the outermost pop, so the restore path has no null branch. */
    private static final SubmitFrame EMPTY_FRAME = new SubmitFrame(null, null, null, null, null, null);

    private GlintCarrier() {}

    /**
     * Publishes one item's submit context for the duration of its {@code submit(...)}, saving whatever was
     * already bound. Pairs with {@link #popSubmit()} at RETURN. The save/restore (rather than a plain
     * set/remove) keeps nesting safe: if a special/BEWLR-style renderer resolves and submits a sub-item
     * while an outer item's submit is still on the stack, the inner pop restores the outer item's context
     * instead of wiping it. Without that, the rest of the outer item's geometry would lose its glint/glow.
     */
    public static void pushSubmit(CustomGlint.Data glint, boolean glowing, int[] glowColors, Object token,
            float glowSpeed, boolean glowInterp) {
        SUBMIT_STACK.get().push(new SubmitFrame(SUBMIT_GLINT.get(), SUBMIT_GLOWING.get(), SUBMIT_GLOW_COLORS.get(),
                SUBMIT_TOKEN.get(), SUBMIT_GLOW_SPEED.get(), SUBMIT_GLOW_INTERP.get()));
        SUBMIT_GLINT.set(glint);
        SUBMIT_GLOWING.set(glowing);
        SUBMIT_GLOW_COLORS.set(glowColors);
        SUBMIT_TOKEN.set(token);
        SUBMIT_GLOW_SPEED.set(glowSpeed);
        SUBMIT_GLOW_INTERP.set(glowInterp);
    }

    /** Restores the context saved by the matching {@link #pushSubmit}; clears to null at the outermost frame. */
    public static void popSubmit() {
        ArrayDeque<SubmitFrame> stack = SUBMIT_STACK.get();
        SubmitFrame prev = stack.isEmpty() ? EMPTY_FRAME : stack.pop();
        setOrRemove(SUBMIT_GLINT, prev.glint());
        setOrRemove(SUBMIT_GLOWING, prev.glowing());
        setOrRemove(SUBMIT_GLOW_COLORS, prev.glowColors());
        setOrRemove(SUBMIT_TOKEN, prev.token());
        setOrRemove(SUBMIT_GLOW_SPEED, prev.glowSpeed());
        setOrRemove(SUBMIT_GLOW_INTERP, prev.glowInterp());
    }

    /** Null means "was unbound", so the local is removed rather than set to a null value. */
    private static <T> void setOrRemove(ThreadLocal<T> tl, T value) {
        if (value == null) tl.remove(); else tl.set(value);
    }

    /**
     * Per-frame reset. The HEAD injects that set these locals pair with RETURN injects that
     * clear them, but a RETURN inject does not fire if the wrapped render method throws. A thrown frame
     * would otherwise leave stale glint/glow state bound to the render thread and poison the next frame.
     * Called once at frame start (see {@code CustomGlintClientInit}) so each frame starts clean.
     */
    public static void resetSubmitState() {
        SUBMIT_GLINT.remove();
        SUBMIT_GLOWING.remove();
        SUBMIT_GLOW_COLORS.remove();
        SUBMIT_TOKEN.remove();
        SUBMIT_GLOW_SPEED.remove();
        SUBMIT_GLOW_INTERP.remove();
        SUBMIT_STACK.get().clear();
    }
}
