#version 150

#moj_import <fog.glsl>

// Альфа цвета вершины = 1 - прогресс растворения. Текстура исчезает попиксельно по шуму
// (шаг шума = пиксель текстуры), край растворения светится голубым (без учёта освещения).
// Copper = 1: часть красится медью по яркости, как у копий Rift (шейдер copper_echo).
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float PhaseTime;
uniform float Copper;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

const vec3 COPPER_DARK  = vec3(0.20, 0.08, 0.04);
const vec3 COPPER_LOW   = vec3(0.50, 0.23, 0.11);
const vec3 COPPER_MID   = vec3(0.78, 0.44, 0.21);
const vec3 COPPER_HIGH  = vec3(0.96, 0.70, 0.42);
const vec3 COPPER_SHINE = vec3(1.00, 0.90, 0.70);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 copperRamp(float t) {
    if (t < 0.25) return mix(COPPER_DARK, COPPER_LOW, t / 0.25);
    if (t < 0.55) return mix(COPPER_LOW, COPPER_MID, (t - 0.25) / 0.30);
    if (t < 0.85) return mix(COPPER_MID, COPPER_HIGH, (t - 0.55) / 0.30);
    return mix(COPPER_HIGH, COPPER_SHINE, (t - 0.85) / 0.15);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    float progress = 1.0 - vertexColor.a;
    float n = hash(floor(texCoord0 * vec2(textureSize(Sampler0, 0))));
    if (n < progress) {
        discard;
    }
    vec4 color = vec4(tex.rgb * vertexColor.rgb * ColorModulator.rgb, 1.0);
    if (Copper > 0.5) {
        float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
        lum = clamp(0.12 + 0.95 * pow(lum, 0.85), 0.0, 1.0);
        vec3 copper = copperRamp(lum);
        float rim = pow(1.0 - clamp(abs(dot(normalize(viewNormal), normalize(-viewPos))), 0.0, 1.0), 2.0);
        color.rgb = min(copper + COPPER_SHINE * 0.22 * rim, vec3(1.0));
    }
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    float edge = progress + 0.16;
    if (progress > 0.01 && n < edge) {
        float k = (edge - n) / 0.16;
        float flicker = 0.85 + 0.15 * sin(PhaseTime * 20.0 + n * 40.0);
        color.rgb = mix(vec3(0.25, 0.65, 1.0), vec3(0.85, 0.97, 1.0), k) * flicker;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
