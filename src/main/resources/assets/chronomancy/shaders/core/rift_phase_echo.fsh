#version 150

#moj_import <fog.glsl>

// Остаточная копия (эхо-силуэт заклинаний: Rewind, Time Walk, Backtrack, рывки сквозь время).
// В отличие от оболочки (аддитивное свечение только по краям, почти невидимое днём) копия
// рисуется обычным альфа-смешиванием и плотно: узнаваемая текстура сущности, перекрашенная в
// переливающееся «время» (синий -> голубой -> золотой), яркий контур, бегущие полосы и искры.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float PhaseTime;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

const vec3 DEEP  = vec3(0.10, 0.35, 1.00);
const vec3 SKY   = vec3(0.55, 0.90, 1.00);
const vec3 GOLD  = vec3(1.00, 0.78, 0.36);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 iridescence(float h) {
    h = fract(h) * 3.0;
    if (h < 1.0) return mix(DEEP, SKY, h);
    if (h < 2.0) return mix(SKY, GOLD, h - 1.0);
    return mix(GOLD, DEEP, h - 2.0);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float rim = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 1.4);

    float t = PhaseTime;
    vec2 ts = vec2(textureSize(Sampler0, 0)) / 128.0;
    float band = 0.5 + 0.5 * sin(texCoord0.y * 90.0 * ts.y - t * 4.0 + texCoord0.x * 25.0 * ts.x);
    vec3 tint = iridescence(t * 0.12 + texCoord0.y * 2.5 + rim * 0.5);

    // Рисунок текстуры остаётся читаемым: яркость пикселя модулирует цвет «времени».
    float luma = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
    vec3 col = tint * (0.40 + 0.95 * luma);
    col += SKY * rim * 0.65 + tint * band * 0.12;

    vec2 cell = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    float spark = step(0.982, hash(cell + floor(t * 6.0)));
    col += spark * 0.9;

    float a = (0.58 + 0.32 * rim + 0.10 * band) * vertexColor.a * ColorModulator.a;
    a = max(a, spark * 0.85 * vertexColor.a * ColorModulator.a);
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(min(col, vec3(1.0)), clamp(a, 0.0, 1.0));
}
