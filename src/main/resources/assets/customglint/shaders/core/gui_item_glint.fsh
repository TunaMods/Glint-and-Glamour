#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints GUI item-glint fragment shader. Emits the scrolling glint over a glinted item's cached
// icon, masked to the item silhouette (Sampler0 = the cached atlas slot, Sampler1 = the grayscale design).
// Order on top of the opaque icon is guaranteed by GuiRendererMixin adding this as a GLYPH (glyphs draw
// after the icon's sorted element in the same node). Drawn with BlendFunction.GLINT, exactly like the
// world/in-hand glint, so the look matches.

#include <minecraft:dynamictransforms.glsl>
// Globals carries GlintAlpha, the vanilla glint-strength dimmer; bound for GUI draws by
// RenderSystem.bindDefaultUniforms, same as the world glint.
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;   // cached item slot, the silhouette mask
uniform sampler2D Sampler1;   // grayscale glint design: single design (REPEAT), or the shared atlas (CLAMP)

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float vGuiScale;
layout(location = 3) in vec2 vScroll;   // 2D scroll vector (design-UV), direction+speed/static already resolved CPU-side
layout(location = 4) in float vPS;
layout(location = 5) in float vAtlasMode; // 1 = Sampler1 is the shared design atlas; 0 = a single REPEAT design texture
layout(location = 6) in float vCellIndex; // atlas cell for this design when vAtlasMode == 1

layout(location = 0) out vec4 fragColor;

// Shared GUI design atlas layout — MUST match CustomGlintRenderer.GUI_ATLAS_* constants. Designs pack into a
// square grid of 64px cells, each with a GUTTER-px wrapped border so fract() tiling stays seamless under
// LINEAR (the bound sampler CLAMPs; the wrap is the gutter, not the hardware). The grid dimension is dynamic
// (sized CPU-side to the design count so data-pack designs atlas too) and recovered here from the atlas
// texture size, so no uniform is needed: the atlas is square with dim = grid * STRIDE, hence grid = dim/STRIDE.
const float ATLAS_CONTENT = 64.0;
const float ATLAS_GUTTER  = 4.0;
const float ATLAS_STRIDE  = ATLAS_CONTENT + 2.0 * ATLAS_GUTTER; // 72

// Maps a (tiled) design coordinate onto this layer's atlas cell. duv is unbounded (the scroll wraps it);
// fract() folds it into the cell, then the content sub-rect (inside the gutter) is addressed.
vec2 atlasUV(vec2 duv) {
    float dim  = float(textureSize(Sampler1, 0).x);   // square atlas: dim = grid * STRIDE
    int   grid = int(dim / ATLAS_STRIDE + 0.5);        // cells per side, recovered exactly
    int   ci   = int(vCellIndex + 0.5);
    float col  = float(ci - (ci / grid) * grid);       // ci % grid
    float row  = float(ci / grid);                     // ci / grid
    vec2 origin = (vec2(col, row) * ATLAS_STRIDE + ATLAS_GUTTER) / dim;
    return origin + fract(duv) * (ATLAS_CONTENT / dim);
}

// Silhouette alpha threshold (same role as gui_item_outline's EDGE).
const float EDGE = 0.1;
// Apparent design size on the icon: BIGGER = bigger motifs (fewer repeats). It's the inverse of the
// tiling factor (k = patternScale / GLINT_SIZE), so the knob reads the intuitive way now. The 64x64
// source caps crispness at large sizes. Pushing this much past ~3 magnifies the texels (soft/blocky).
const float GLINT_SIZE = 3.0;

// Sample coord for the design + scroll. The world's forGlint samples V twice as densely as U (scaleU =
// 8*atlasW/1024, scaleV = 8*atlasH/512 → effective spriteW/128 : spriteH/64 = 1:2 once the atlas factor
// cancels), which is what keeps diamond motifs as diamonds. That ratio is INTRINSIC, not atlas-derived —
// halving U here reproduces it on any atlas size. (The earlier atlasW/atlasH multiplier only matched a
// since-reverted symmetric /2048 world formula and read 1:1 on the square atlas → squares.)
// The scroll vector is supplied per-vertex (direction/speed, or a frozen static offset, resolved CPU-side).
vec2 glintUV(vec2 p, vec2 scroll, float ps) {
    float k = ps / GLINT_SIZE;
    return vec2(p.x * k * 0.5, p.y * k) + scroll;
}

void main() {
    // Item-local (0..1 over the 16px icon, v measured downward) from the atlas slot coord. s is the slot
    // UV size, derived EXACTLY from guiScale (the slot is always 16*guiScale atlas texels). Same scheme
    // as gui_item_outline, no fwidth/drift.
    float s = 16.0 * vGuiScale / float(textureSize(Sampler0, 0).x);
    float vv = 1.0 - texCoord0.y;
    vec2 origin = vec2(floor(texCoord0.x / s), floor(vv / s)) * s;
    vec2 itemLocal = vec2((texCoord0.x - origin.x) / s, (vv - origin.y) / s);

    // Only draw over the item itself: the cached slot's alpha is the silhouette.
    if (texture(Sampler0, texCoord0).a < EDGE) {
        discard;
    }

    vec2 duv = glintUV(itemLocal, vScroll, vPS);
    // Atlas mode: every glinted icon shares one design atlas so the glint glyphs batch into one draw; the
    // design is selected by cell index. Single-design mode samples the bound design directly (REPEAT).
    vec2 suv = (vAtlasMode > 0.5) ? atlasUV(duv) : duv;
    vec4 design = texture(Sampler1, suv) * vertexColor;
    if (design.a < 0.004) {
        discard;   // design texture's own alpha decides the glint SHAPE
    }
    // Match core/glint_color exactly: design renders at full brightness (alpha is shape only), scaled by
    // the colour's own alpha AND GlintAlpha (the world's `fade` with no fog in the GUI). Without GlintAlpha
    // the glint was far too bright — the blown-out highlights merged into a blob that also read as oversized.
    // GLINT blend (SRC_COLOR, ONE) then squares + adds onto the icon.
    fragColor = vec4(design.rgb * vertexColor.a * GlintAlpha, design.a);
}
