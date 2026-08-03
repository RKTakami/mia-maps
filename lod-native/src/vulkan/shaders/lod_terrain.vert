#version 460
layout(location = 0) in vec3 inPos;
layout(location = 1) in uint inLayerId;
layout(location = 2) in uint inNormalFace;
layout(location = 3) in vec3 inTint;

layout(location = 0) out vec2 outUV;
layout(location = 1) flat out uint outLayerId;
layout(location = 2) out vec3 outTint;
layout(location = 3) flat out vec3 outNormal;

layout(binding = 0, std140) uniform CameraUBO {
    mat4 projection;
    mat4 view;
} ubo;

const vec3 FACES[6] = vec3[6](
    vec3( 1, 0, 0), vec3(-1, 0, 0),
    vec3( 0, 1, 0), vec3( 0,-1, 0),
    vec3( 0, 0, 1), vec3( 0, 0,-1)
);

void main() {
    outNormal = FACES[inNormalFace % 6u];
    outLayerId = inLayerId;
    outTint = inTint;

    // Derive UV coordinates directly from world position based on face axis:
    if (inNormalFace <= 1u) {
        outUV = inPos.zy; // X-facing wall
    } else if (inNormalFace <= 3u) {
        outUV = inPos.xz; // Top / Bottom floor
    } else {
        outUV = inPos.xy; // Z-facing wall
    }

    gl_Position = ubo.projection * ubo.view * vec4(inPos, 1.0);
}
