#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints GUI procedural-chromatic vertex shader. The GUI analog of core/chromatic.vsh: draws the
// live oil-slick over a glinted item's cached atlas icon (so the base icon stays cached). The batched GUI
// path has no per-draw uniform hook, so the per-layer payload rides the vertex attributes instead of the
// TextureMat the world path uses:
//   UV1.x = seed*256 (per-trim seed; /256 here for the 0..256 range core/chromatic uses)
//   UV1.y = colour count (0 => rainbow fallback; 1..8 => palette texels in Sampler1)
//   UV2.x = patternScale*4096   UV2.y = guiScale (low 7 bits) | speed*16 (high bits)
// UV0 = the atlas slot coords (silhouette mask + item-local reconstruction). Pairs with gui_chromatic.fsh.

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
layout(location = 3) out float vSeed;
layout(location = 4) out float vCount;
layout(location = 5) out float vPS;
layout(location = 6) out float vSpeed;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    vSeed     = float(UV1.x) / 256.0;
    vCount    = float(UV1.y);
    vPS       = float(UV2.x) / 4096.0;
    vGuiScale = float(UV2.y & 127);
    vSpeed    = float(UV2.y >> 7) / 16.0;   // morph speed (matches the world chromatic's TextureMat[2][0])
}
