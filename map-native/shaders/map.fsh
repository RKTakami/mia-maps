#version 330 core
in vec3 vNormal;
in vec4 vColor;
in float vCut;
out vec4 frag;
const vec3 L = normalize(vec3(0.321, 0.919, 0.230));
const float AMBIENT = 0.4;
void main() {
    // Between the camera and the cutaway plane. Discarding rather than depth-failing means the
    // terrain behind it draws normally instead of being occluded by something invisible.
    if (vCut < 0.0) discard;
    float ndotl = max(dot(normalize(vNormal), L), 0.0);
    float shade = AMBIENT + (1.0 - AMBIENT) * ndotl;
    // .bgr: the cell colour is an ARGB int uploaded as little-endian bytes, so the attribute arrives
    // as (B,G,R,A); swizzle back to RGB. (The CPU path relies on NativeImage's ARGB->native convert.)
    frag = vec4(vColor.bgr * shade, 1.0);
}
