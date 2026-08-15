#version 120

uniform sampler2D textureIn;

void main(void)
{
    vec2 uv = gl_TexCoord[0].st;
    vec4 pixel_color = texture2D(textureIn, uv);

    if (pixel_color.a == 0.0)
    discard;

    gl_FragColor = vec4(pixel_color.rgb / pixel_color.a, pixel_color.a);
}