#version 120

uniform sampler2D textureIn;

uniform vec2 texelSize;

uniform vec3 convX = vec3(-1.0,  0.0, 0.5);
uniform vec3 convY = vec3( 0.0, -1.0, 0.5);
uniform vec3 radConvX = vec3(1.0, 1.0, 1.0);
uniform vec3 radConvY = vec3(1.0, 1.0, 1.0);

void main() {
    vec2 texCoord = gl_TexCoord[0].st;

    vec3 x = texCoord.x * radConvX;
    vec3 y = texCoord.y * radConvY;

    x += convX * texelSize.x - (radConvX - 1.0) * 0.5;
    y += convY * texelSize.y - (radConvY - 1.0) * 0.5;

    float r = texture2D(textureIn, vec2(x.x, y.x)).r;
    float g = texture2D(textureIn, vec2(x.y, y.y)).g;
    float b = texture2D(textureIn, vec2(x.z, y.z)).b;
    float a = texture2D(textureIn, texCoord).a;

    gl_FragColor = vec4(r, g, b, 1.0);
}
