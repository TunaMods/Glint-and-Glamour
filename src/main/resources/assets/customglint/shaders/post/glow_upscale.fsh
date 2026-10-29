#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints: upscales the half-res outline ring onto the main target. Passthrough: the bilinear
// sampler does the 2x upscale, which also softens (anti-aliases) the half-res ring. Blends over the
// scene (ring pixels carry alpha=1, everything else is the cleared 0).

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
