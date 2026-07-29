#version 330 core
layout(location=0) in vec3 aPos;    // cell units
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColor;  // normalized RGBA
uniform mat4 uMVP;
uniform float uCell;
uniform vec3 uOrigin;               // in cells
// Cutaway plane, in the same shifted-block space as the world position below. uCutOn 0 disables it.
uniform vec3 uCutOrigin;
uniform vec3 uCutNormal;
uniform float uCutOn;
out vec3 vNormal;
out vec4 vColor;
out float vCut;
void main() {
    vec3 world = (uOrigin + aPos) * uCell;
    gl_Position = uMVP * vec4(world, 1.0);
    vNormal = aNormal;
    vColor = aColor;
    // Interpolated, so the fragment stage cuts a straight edge through a triangle rather than
    // keeping or dropping it whole. 1.0 when off keeps every fragment.
    vCut = uCutOn > 0.5 ? dot(world - uCutOrigin, uCutNormal) : 1.0;
}
