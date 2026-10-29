#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints GUI item-glint vertex shader. Draws the animated glint LIVE over a glinted item's
// cached atlas icon, so the base icon can stay cached (not re-baked every frame) while the glint still
// scrolls. See GuiRendererMixin / GuiItemGlintRenderState. Pairs with core/gui_item_glint.fsh.
//
// Per-layer animation params ride the vertex attributes (the batched GUI path has no per-draw uniform
// hook): UV1 = the two scroll scalars f,f1 (wall-clock, computed CPU-side so the GUI scroll matches the
// in-hand glint exactly), UV2 = packed (patternScale, guiScale). UV0 = the atlas slot coords (used both
// to sample the silhouette mask and to reconstruct item-local coords). Color = the animated layer colour.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in ivec2 UV1;
layout(location = 3) in ivec2 UV2;
layout(location = 4) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float vGuiScale;
layout(location = 3) out vec2 vScroll;
layout(location = 4) out float vPS;
layout(location = 5) out float vAtlasMode;   // 1 = Sampler1 is the shared design atlas (sample cell vCellIndex); 0 = single design
layout(location = 6) out float vCellIndex;   // atlas cell index for this layer's design (only meaningful when vAtlasMode == 1)

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    // UV1 = the 2D scroll vector (design-UV, wrapped to 0..1, direction + speed already resolved CPU-side
    // in GuiRendererMixin so the GUI drift matches the in-hand glint exactly).
    vScroll   = vec2(UV1) / 16000.0;
    vPS       = float(UV2.x) / 4096.0;     // patternScale
    // UV2.y bit layout: bits 0-6 guiScale, bit 7 atlas-mode flag, bits 8-15 atlas cell index. Sharing the
    // design atlas (atlas-mode) lets every glinted icon's glint glyph carry one TextureSetup and batch.
    int p2    = UV2.y;
    vGuiScale = float(p2 & 127);           // guiScale, for the exact slot UV size
    vAtlasMode = float((p2 >> 7) & 1);
    vCellIndex = float((p2 >> 8) & 255);
}
