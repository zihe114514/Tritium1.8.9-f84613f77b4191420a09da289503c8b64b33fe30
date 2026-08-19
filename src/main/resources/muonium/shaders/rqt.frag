#version 120

uniform vec2 u_size;
uniform vec2 u_offset;
uniform vec2 u_scale;
uniform float u_radius;
uniform float u_alpha;
uniform sampler2D textureIn;

void main(void)
{
    vec2 newCoords = u_offset + gl_TexCoord[0].st * u_scale;

    vec4 textureColor = texture2D(textureIn, newCoords);

    if (textureColor.a == 0.0f)
        discard;

    // 计算纹理坐标相对于中心的位置
    vec2 center = gl_TexCoord[0].st * u_size - u_size * 0.5;
    // 计算边缘距离
    float distance = length(max(abs(center) - (u_size * 0.5 - u_radius - 1.0), 0.0)) - u_radius;
    // 使用smoothstep进行抗锯齿

    float alpha = 1.0 - smoothstep(0.0, 1.0, distance);

    gl_FragColor = vec4(textureColor.rgb, u_alpha * alpha);

}