#version 150

#moj_import <fog.glsl>

// Переливание: свечение по краям силуэта (rim), цвет медленно течёт синий -> голубой -> золотой,
// по телу бегут полосы «времени», редкие пиксельные искры. Смешивание аддитивное, альфа вершины —
// общая яркость (оболочка, усиление у «выпавшей» части, затухающие эхо-силуэты).
// Immune (0..1) — сквозь сущность сейчас проходят снаряды: палитра уходит в фиолетово-розовую,
// и светится всё тело, а не только края силуэта — стрелок видит, что стрелять бесполезно.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float PhaseTime;
uniform float Immune;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

const vec3 DEEP  = vec3(0.10, 0.35, 1.00);
const vec3 SKY   = vec3(0.55, 0.90, 1.00);
const vec3 GOLD  = vec3(1.00, 0.78, 0.36);

const vec3 VIOLET = vec3(0.56, 0.16, 1.00);
const vec3 ROSE   = vec3(1.00, 0.30, 0.82);
const vec3 LILAC  = vec3(0.84, 0.70, 1.00);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 iridescence(float h) {
    h = fract(h) * 3.0;
    if (h < 1.0) return mix(DEEP, SKY, h);
    if (h < 2.0) return mix(SKY, GOLD, h - 1.0);
    return mix(GOLD, DEEP, h - 2.0);
}

vec3 outOfReach(float h) {
    h = fract(h) * 3.0;
    if (h < 1.0) return mix(VIOLET, ROSE, h);
    if (h < 2.0) return mix(ROSE, LILAC, h - 1.0);
    return mix(LILAC, VIOLET, h - 2.0);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float rim = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 1.6);

    float t = PhaseTime;
    vec2 ts = vec2(textureSize(Sampler0, 0)) / 128.0;
    float band = 0.5 + 0.5 * sin(texCoord0.y * 90.0 * ts.y - t * 4.0 + texCoord0.x * 25.0 * ts.x);
    float hue = t * 0.12 + texCoord0.y * 2.5 + rim * 0.5;
    vec3 col = mix(iridescence(hue), outOfReach(hue), Immune);

    vec2 cell = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    float spark = step(0.985, hash(cell + floor(t * 6.0)));

    float fill = 0.10 + Immune * (0.19 + 0.07 * sin(t * 9.0));
    float a = (fill + 0.70 * rim + 0.22 * band * (0.3 + rim)) * vertexColor.a * ColorModulator.a;
    col = col + spark * 0.8;
    a = max(a, spark * 0.6 * vertexColor.a);
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, clamp(a, 0.0, 1.0));
}
