#version 150

// Glow-outline composite. Per-object id-aware, per-category thickness, single pass, constant thickness
// (no depth). The mask (glow_silhouette) packs a per-object KEY plus visibility into alpha:
//   0        : empty (no silhouette here).
//   1..127   : in-shape but occluded. Not used yet; the silhouette currently marks everything visible.
//   128..255 : VISIBLE, key = v - 128.
// The key is (category << 5) | id: top 2 bits = category, low 5 = id. rgb is that object's glow colour.
//
// A pixel rings iff a VISIBLE silhouette of a DIFFERENT id is within that source's CATEGORY thickness.
// That is the outer edge of every object plus the boundary wherever two objects overlap, each category
// its own width. Sampler0 (mask) MUST be NEAREST: alpha is a packed classifier, not a colour.

uniform sampler2D Sampler0;
// Kernel radius for THIS pass = the max thickness of the categories drained into the mask this pass.
// Set per drain by GlowOutlineRenderer (world items = 3, first-person held = 7). A source farther than a
// pixel's own ring width can never ring it, so the world drain searches only radius 3 (≈29 taps) instead
// of the FP drain's radius 7 (≈149 taps), a ~5x cut with no change to the world ring. The per-source
// THICKNESS[] below still bounds each category's actual ring, so an over-large radius only wastes taps.
uniform int SearchRadius;
// Distance-thinning multiplier for THIS composite pass (1.0 = full thickness). Set per object/cluster by
// GlowOutlineRenderer from the object's camera distance, so a far (small-on-screen) object gets a
// proportionally thinner ring instead of a constant pixel ring that reads as fatter the farther it is.
uniform float ThicknessScale;

// Mask DEPTH (front-most silhouette depth per pixel; the mask RT writes depth with LEQUAL). Sampled here
// for RING OCCLUSION, NOT scene occlusion: it is the MASK's own depth attachment, a DIFFERENT target from
// the main colour buffer this pass writes, so there is no read/write-the-same-target feedback (that was
// the old crackle from sampling the MAIN depth here). ProjA/ProjB linearise it to eye distance (blocks)
// the same way the silhouette pass did: ProjMat[2][2] / ProjMat[3][2] of the world/hand projection.
uniform sampler2D Sampler1;
uniform float ProjA;
uniform float ProjB;
// Restrict ring SOURCES to this outline id (key & 31), or -1 for any. The drain composites one pass per id
// far→near so a nearer object's whole outline layers over a farther one's; this filter is what makes each
// pass draw only its own object's ring.
uniform int TargetId;
// 0 = world: round (Euclidean) ring per CATEGORY thickness, with the morphological-opening anti-speckle
// guard. 1 = GUI: ring reach is exactly SearchRadius texels (set per-icon to a sub-icon-pixel thickness),
// SQUARE (Chebyshev) so corners stay crisp, and the opening guard is skipped (the GUI silhouette is clean,
// and the guard would erode a thin ring). Kept off the world path so 3D items are unaffected.
uniform int GuiMode;

in vec2 texCoord;

out vec4 fragColor;

// Per-category outline thickness, in texels (~screen px). index = category:
//   [0] entity   [1] armor   [2] item (3rd-person held / dropped)   [3] held (first-person)
const float THICKNESS[4] = float[](4.0, 4.0, 3.0, 7.0);

// A behind object's ring is eaten where a nearer silhouette covers the ring pixel by at least this many
// BLOCKS, so e.g. the body/armor ring doesn't draw around an elytra worn in front of it, while the
// elytra keeps its own ring. The elytra sits 0.125 blocks ahead of the body, so a 0.10 bias catches it.
const float RING_OCCLUSION_BIAS = 0.10;

float cg_eyeDist(float raw) { return ProjB / (raw * 2.0 - 1.0 + ProjA); }

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    // The kernel always steps one framebuffer texel; the ring is measured outward from the icon's ACTUAL
    // silhouette edge so it hugs with no gap. World rings span CATEGORY thickness; GUI rings span exactly
    // SearchRadius texels (set per-icon to a fraction of an icon-pixel), so they read thin (~1 screen px)
    // and crisp, matching the 26.1 GUI outline rather than a full blocky icon-pixel.
    vec2 step = texel;
    vec2 ctr = texCoord;
    bool gui = GuiMode != 0;

    int vP = int(texture(Sampler0, ctr).a * 255.0 + 0.5);
    int keyP = vP >= 128 ? vP - 128 : vP;

    // Interior early-out: a pixel inside the target's own silhouette can never ring. The kernel only takes
    // target-id sources, and a source with this pixel's own id is skipped, so for a target-id pixel every tap
    // is rejected whatever else overlaps the box. Skipping it here is pixel-identical and saves the whole
    // kernel on the silhouette's interior. Placed before the ringDist sample so the discard costs one mask tap.
    if (keyP != 0 && (keyP & 31) == TargetId) { discard; }

    float ringDist = cg_eyeDist(texture(Sampler1, ctr).r); // this pixel's own silhouette depth (or far)

    // RETURN ON FIRST qualifying source (matches the 26.1.2 composite). The drain runs ONE pass per outline
    // id (TargetId filters sources to a single id), so every qualifying source in this pass carries that id's
    // glow colour, and the first valid source gives the IDENTICAL ring decision and colour as scanning the whole
    // kernel for the nearest one did, just without the wasted taps. Inter-object layering (elytra over helmet)
    // is already handled by the per-id far→near pass ORDER, not by a nearest-pick inside one pass. For a close
    // / screen-filling entity this is the dominant win: a band pixel returns after its first hit instead of
    // evaluating ~40 sources × the opening guard. The guard now runs only for the candidate about to ring, so
    // the Sodium anti-speckle behaviour is preserved at a fraction of the cost.

    // GUI: clean silhouette (no Sodium speckle) → use a SQUARE (Chebyshev) reach bounded by SearchRadius
    // (skip the Euclidean cutoff, which would drop diagonal cells and notch corners) and SKIP the
    // morphological-opening guard (it would erode a thin ring at sword-tip-like single source cells).
    float maxR2 = float(SearchRadius * SearchRadius);
    // GUI: per-pass square reach = ThicknessScale * SearchRadius texels, so each icon rings to its OWN
    // (possibly thinned) width even though all icons share the kernel bound. World ignores it (uses THICKNESS).
    float guiReach = float(SearchRadius) * ThicknessScale;
    for (int dx = -SearchRadius; dx <= SearchRadius; dx++) {
        for (int dy = -SearchRadius; dy <= SearchRadius; dy++) {
            float d2 = float(dx * dx + dy * dy);
            if (gui) { if (float(max(abs(dx), abs(dy))) > guiReach + 0.001) continue; }
            else if (d2 > maxR2) continue;
            vec2 srcUV = ctr + vec2(float(dx), float(dy)) * step;
            vec4 n = texture(Sampler0, srcUV);
            int vN = int(n.a * 255.0 + 0.5);
            if (vN < 128) continue;                   // visible silhouettes are the only ring sources
            int keyN = vN - 128;
            if (TargetId >= 0 && (keyN & 31) != TargetId) continue; // this pass rings only its own id
            if ((keyN & 31) == (keyP & 31)) continue; // same identity -> not a boundary
            if (!gui) {
                float r = THICKNESS[keyN >> 5] * ThicknessScale;
                if (d2 > r * r) continue;             // world: round ring bounded by THAT source's width
            }
            float srcDist = cg_eyeDist(texture(Sampler1, srcUV).r);
            // Ring occlusion: skip this ring where the ring pixel's own silhouette is NEARER than the source's
            // by more than the bias. A different object is in front here, so the source's ring would wrongly
            // paint around/over it (the armor ring behind an elytra).
            //
            // Gated on keyP != 0: an EMPTY pixel has no silhouette, so it has no depth to compare and must
            // never occlude. It reads the mask's CLEAR value, which only linearises to "far" because 1 + ProjA
            // lands near zero for an ordinary projection. Under a shader pack the hand draws through a
            // z-squashed projection (z scaled by 0.125); that divisor stops being near zero, cg_eyeDist(1.0)
            // comes back a small NEGATIVE distance, and every empty pixel claims to sit in front of the item.
            // A ring is drawn on exactly those pixels, so the whole first-person ring vanished.
            if (keyP != 0 && ringDist < srcDist - RING_OCCLUSION_BIAS) continue;
            if (!gui) {
                // Reject isolated specks (morphological opening). A lone stray "visible" texel (sub-pixel
                // coverage flicker at a convex corner under Sodium's reduced immediate-mode vertex
                // precision) would otherwise balloon into a round bead just outside the real outline. A
                // genuine silhouette source sits in a solid shape, so require >= 2 of its 8 neighbours to
                // also be silhouette. 8-cell (not 4-cell) keeps thin DIAGONAL features. World-only: the GUI
                // silhouette is clean and this guard would erode its ring (see above).
                // Only the boolean support>=2 matters, so stop sampling once two neighbours are found.
                // a genuine source (in a solid shape) hits 2 in the first taps; only true specks pay all 8.
                int support = 0;
                support += int(texture(Sampler0, srcUV + vec2( step.x, 0.0)).a > 0.002);
                support += int(texture(Sampler0, srcUV + vec2(-step.x, 0.0)).a > 0.002);
                if (support < 2) {
                    support += int(texture(Sampler0, srcUV + vec2(0.0,  step.y)).a > 0.002);
                    support += int(texture(Sampler0, srcUV + vec2(0.0, -step.y)).a > 0.002);
                }
                if (support < 2) {
                    support += int(texture(Sampler0, srcUV + vec2( step.x,  step.y)).a > 0.002);
                    support += int(texture(Sampler0, srcUV + vec2( step.x, -step.y)).a > 0.002);
                    support += int(texture(Sampler0, srcUV + vec2(-step.x,  step.y)).a > 0.002);
                    support += int(texture(Sampler0, srcUV + vec2(-step.x, -step.y)).a > 0.002);
                }
                if (support < 2) continue;            // speck → keep scanning for a real source
            }
            fragColor = vec4(n.rgb, 1.0);             // first valid source rings this pixel
            return;
        }
    }
    discard;
}
