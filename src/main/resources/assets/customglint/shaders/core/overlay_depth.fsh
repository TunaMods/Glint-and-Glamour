#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints wing depth PRE-PASS fragment shader. Writes ONLY depth: the colour output vec4(0.0) is a
// no-op under the inherited GLINT blend (0² + dst = dst). The model texture's alpha is the wing cutout, so a
// rectangular elytra/cape mesh writes depth only on its real shape (threshold 0.1, matching
// core/glint_overlay.fsh / core/chromatic_overlay.fsh). The overlay drain runs this into the isolated
// target's depth BEFORE the wing colour pass, so that pass (now LEQUAL) keeps only the nearest of the two
// folded, overlapping wings per pixel. That collapses the additive bright seam down the spine that the biased
// in-shader occlusion can't (two near-coincident surfaces), matching the off-shader EQUAL-depth path.

uniform sampler2D Sampler0;   // model texture; its alpha is the wing cutout silhouette

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }
    fragColor = vec4(0.0);
}
