#version 330 core

layout(location = 0) in vec3 a_Pos;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec2 a_UV;
layout(location = 3) in vec4 a_Light;

uniform mat4 u_ViewProj;
uniform vec3 u_CameraPos;

out vec4 v_Color;
out vec2 v_UV;

void main() {
    vec3 worldPos = a_Pos - u_CameraPos;
    gl_Position = u_ViewProj * vec4(worldPos, 1.0);
    v_Color = a_Color;
    v_UV = a_UV;
}
