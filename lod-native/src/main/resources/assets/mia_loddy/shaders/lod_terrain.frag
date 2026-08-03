#version 330 core

in vec4 v_Color;
in vec2 v_UV;

out vec4 FragColor;

void main() {
    FragColor = v_Color;
    if (FragColor.a < 0.05) {
        discard;
    }
}
