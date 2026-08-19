#version 120

uniform vec2 rectSize;
uniform vec4 color1, color2, color3, color4;
uniform float radius;
uniform float alp;

#define NOISE .5/255.0

vec3 createGradient(vec2 coords, vec3 color1, vec3 color2, vec3 color3, vec3 color4){
    vec3 color = mix(mix(color1.rgb, color2.rgb, coords.y), mix(color3.rgb, color4.rgb, coords.y), coords.x);
    color += mix(NOISE, -NOISE, fract(sin(dot(coords.xy, vec2(12.9898, 78.233))) * 43758.5453));
    return color;
}

void main() {
    vec2 st = gl_TexCoord[0].st;
    vec2 halfSize = rectSize * .5;

    // 计算纹理坐标相对于中心的位置
    vec2 center = gl_TexCoord[0].st * rectSize - rectSize * 0.5;
    // 计算边缘距离
    float distance = length(max(abs(center) - (rectSize * 0.5 - radius - 1.0), 0.0)) - radius;
    // 使用smoothstep进行抗锯齿
    float alpha = 1.0 - smoothstep(0.0, 1.0, distance);

    float smoothedAlpha =  alp * alpha;
    gl_FragColor = vec4(createGradient(st, color1.rgb, color2.rgb, color3.rgb, color4.rgb), smoothedAlpha);
}