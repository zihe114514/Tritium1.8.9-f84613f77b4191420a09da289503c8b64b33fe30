#version 120

uniform vec2 u_size;
uniform float u_alpha;
uniform float u_control_perc;
uniform sampler2D textureIn;

void main(void)
{
    vec2 newCoords = gl_TexCoord[0].st;

    // 把uv的y轴反过来
    vec4 textureColor = texture2D(textureIn, vec2(newCoords.x, 1 - newCoords.y));

    if (textureColor.a == 0.0f)
    discard;

    // gradient alpha
    float alphaGradient = u_alpha * (1 - newCoords.y / u_control_perc);

    if (alphaGradient < 0)
    discard;

    gl_FragColor = vec4(textureColor.rgb, alphaGradient);
}