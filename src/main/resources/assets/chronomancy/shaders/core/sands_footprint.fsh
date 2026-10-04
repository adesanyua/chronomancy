#version 150

#moj_import <fog.glsl>

// След струи Sands of Time у Rift Maker — пятно на земле, внутри которого песок заденет игрока.
// Граница не чертится линией: к краю пятно разгорается, по кромке бегут искры, а от неё внутрь
// расходится рябь. Внутри — песок, текущий от босса. Смешивание обычное (альфа), чтобы пятно
// читалось и на снегу, и на тёмном камне.
//
// texCoord0 — положение точки относительно босса, в блоках (x, z).
// Вершинный цвет несёт не цвет: r — расстояние до края пятна (0..1 = 0..2 блока),
// g — 0 на замахе, 1 пока песок идёт, b — насколько область «налилась» (замах),
// a — яркость земли под пятном (0 тёмная .. 1 светлая).
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

const vec3 SAND   = vec3(1.00, 0.70, 0.18);
const vec3 COPPER = vec3(0.86, 0.45, 0.14);
const vec3 HOT    = vec3(1.00, 0.95, 0.72);
const vec3 DEEP   = vec3(0.55, 0.23, 0.06);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, vec2(0.5));
    if (tex.a < 0.1) {
        discard;
    }
    float t = PhaseTime;
    // Землю под рисунком шейдер не видит, поэтому её яркость приходит в альфе вершин (0 — тёмная,
    // 1 — светлая: снег, песок). На светлой рисунок темнеет: янтарная заливка плотнее, а линии — не
    // светлые, а тёмно-бурые. Иначе золото на снегу просто пропадает.
    float onBright = vertexColor.a;
    vec3 bodyLo = mix(COPPER, vec3(0.66, 0.33, 0.05), onBright);
    vec3 bodyHi = mix(SAND, vec3(0.82, 0.50, 0.08), onBright);
    vec3 lineCol = mix(HOT, vec3(0.25, 0.09, 0.01), onBright);
    vec3 shadeCol = mix(DEEP, vec3(0.40, 0.16, 0.02), onBright);
    float fillGain = 1.0 + 1.1 * onBright;
    float flowing = vertexColor.g;
    float ramp = vertexColor.b;

    // всё считается по «пикселям» мира — 16 на блок, как у текстур игры
    vec2 p = floor(texCoord0 * 16.0) / 16.0;
    float edge = floor(vertexColor.r * 2.0 * 16.0) / 16.0;     // до края, в блоках
    float rho = length(p);
    float theta = atan(p.y, p.x);

    // --- внутри: песок течёт от босса ---
    float band = 0.5 + 0.5 * sin(rho * 1.7 - t * 7.0);
    float fill = mix(0.08 + 0.20 * ramp, 0.20 + 0.14 * band, flowing);
    // отдельные песчинки: ячейки в полярных координатах, сносятся наружу
    vec2 cell = vec2(floor(theta * 22.0), floor(rho * 5.0 - t * 9.0 * flowing));
    float grain = step(0.90, hash(cell)) * (0.35 + 0.65 * flowing);
    float sparkle = grain * (0.6 + 0.4 * sin(t * 13.0 + hash(cell + 3.0) * 6.2832));

    // --- граница: без линии ---
    // свечение: чем ближе к краю, тем ярче
    float glow = exp(-edge * 2.4);
    // рябь: кольца, расходящиеся от кромки внутрь, с неровной фазой вдоль края
    float wobble = hash(vec2(floor(theta * 9.0), 7.0));
    float ripple = 0.5 + 0.5 * sin(edge * 16.0 - t * 8.0 + wobble * 6.2832);
    ripple = smoothstep(0.55, 1.0, ripple) * exp(-edge * 1.5);
    // кромка: не обводка, а искры, бегущие вдоль края (координата вдоль него — угол плюс дальность)
    float along = (theta * 6.0 + rho * 1.5) * 4.0;
    float spark = hash(vec2(floor(along - t * 7.0), 1.0));
    float crest = (1.0 - smoothstep(0.0, 0.19, edge)) * (0.12 + 0.88 * spark * spark);
    // тёмная медная полоса сразу за кромкой: на снегу и песке границу держит она, на тёмном — искры
    float shade = smoothstep(0.12, 0.25, edge) * (1.0 - smoothstep(0.38, 0.75, edge));
    // на замахе граница мигает
    float blink = mix(0.55 + 0.45 * sin(t * 20.0), 1.0, flowing);

    float rim = (0.34 * glow + 0.50 * ripple + 0.90 * crest + 0.30 * shade) * blink;
    float a = clamp(fill * fillGain + 0.10 * onBright + 0.5 * sparkle + rim, 0.0, 0.96) * ColorModulator.a;

    vec3 col = mix(bodyLo, bodyHi, clamp(0.30 + band * 0.35 + glow * 0.8, 0.0, 1.0));
    col = mix(col, shadeCol, shade * 0.75 * (1.0 - ripple));
    col = mix(col, lineCol, clamp(crest * 1.2 + ripple * 0.55 + sparkle * 0.8, 0.0, 1.0));
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, a);
}
