#version 120

uniform sampler2D mixTexture;
uniform sampler2D stencilTexture;

void main() {
    vec4 oc = texture2D(mixTexture, gl_TexCoord[0].st);
    float s = texture2D(stencilTexture, gl_TexCoord[0].st).a;
    gl_FragColor = vec4(oc.rgb, oc.a * s);
}