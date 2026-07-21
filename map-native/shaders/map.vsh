#version 330 core
layout(location=0) in vec3 aPos;    // cell units
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColor;  // normalized RGBA
uniform mat4 uMVP;
uniform float uCell;
uniform vec3 uOrigin;               // in cells
out vec3 vNormal;
out vec4 vColor;
void main() {
    vec3 world = (uOrigin + aPos) * uCell;
    gl_Position = uMVP * vec4(world, 1.0);
    vNormal = aNormal;
    vColor = aColor;
}
