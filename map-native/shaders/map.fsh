#version 330 core
in vec3 vNormal;
in vec4 vColor;
out vec4 frag;
const vec3 L = normalize(vec3(0.321, 0.919, 0.230));
const float AMBIENT = 0.4;
void main() {
    float ndotl = max(dot(normalize(vNormal), L), 0.0);
    float shade = AMBIENT + (1.0 - AMBIENT) * ndotl;
    frag = vec4(vColor.rgb * shade, 1.0);
}
