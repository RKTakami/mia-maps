#version 460
layout(binding = 1) uniform sampler2DArray blockTextureArray;

layout(location = 0) in vec2 inUV;
layout(location = 1) flat in uint inLayerId;
layout(location = 2) in vec3 inTint;
layout(location = 3) flat in vec3 inNormal;

layout(location = 0) out vec4 outColor;

void main() {
    // 1. Hardware-repeat the UV within this specific 2D Array layer
    vec2 tiledUV = fract(inUV);
    vec4 texColor = texture(blockTextureArray, vec3(tiledUV, float(inLayerId)));

    if (texColor.a < 0.1) discard; // Cutout transparency (leaves, grass)

    // 2. Apply directional lighting & biome tint
    float light = max(dot(inNormal, normalize(vec3(0.3, 0.8, 0.5))), 0.25);
    outColor = vec4(texColor.rgb * inTint * light, texColor.a);
}
