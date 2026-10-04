#version 150

#moj_import <fog.glsl>

// Удар Rift Maker, накладывающий стазис. Круг на земле — циферблат: двенадцать делений по краю и
// стрелка, которая за время замаха делает полный оборот; заметённый ею сектор светлее — сколько
// времени осталось, видно без слов. В момент удара циферблат вспыхивает и «застывает»: рисунок
// перестаёт двигаться, выцветает в бледное золото и гаснет. Смешивание обычное (альфа).
//
// texCoord0 — положение точки в долях радиуса круга (на краю длина равна 1).
// Вершинный цвет несёт не цвет: r — ход фазы (0..1), g — 0 замах / 1 удар,
// b — радиус круга в блоках, делённый на 16, a — яркость земли под кругом (0 тёмная .. 1 светлая).
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

const vec3 GOLD   = vec3(1.00, 0.80, 0.30);
const vec3 COPPER = vec3(0.86, 0.45, 0.14);
const vec3 DEEP   = vec3(0.55, 0.23, 0.06);
const vec3 HOT    = vec3(1.00, 0.95, 0.72);
const vec3 FROZEN = vec3(1.00, 0.97, 0.86);
const float TAU = 6.2831853;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, vec2(0.5));
    if (tex.a < 0.1) {
        discard;
    }
    // Землю под рисунком шейдер не видит, поэтому её яркость приходит в альфе вершин (0 — тёмная,
    // 1 — светлая: снег, песок). На светлой рисунок темнеет: янтарная заливка плотнее, а линии — не
    // светлые, а тёмно-бурые. Иначе золото на снегу просто пропадает.
    float onBright = vertexColor.a;
    vec3 bodyLo = mix(COPPER, vec3(0.66, 0.33, 0.05), onBright);
    vec3 bodyHi = mix(GOLD, vec3(0.82, 0.50, 0.08), onBright);
    vec3 lineCol = mix(HOT, vec3(0.25, 0.09, 0.01), onBright);
    vec3 shadeCol = mix(DEEP, vec3(0.40, 0.16, 0.02), onBright);
    float fillGain = 1.0 + 1.1 * onBright;
    vec3 frozenCol = mix(FROZEN, vec3(0.40, 0.31, 0.20), onBright);
    float progress = vertexColor.r;
    float impact = step(0.5, vertexColor.g);
    float radius = vertexColor.b * 16.0;
    // после удара время на циферблате стоит: всё, что двигалось, замирает
    float t = PhaseTime * (1.0 - impact);

    vec2 p = floor(texCoord0 * radius * 16.0) / (radius * 16.0);
    float rho = length(p);
    if (rho > 1.0) {
        discard;
    }
    float edge = (1.0 - rho) * radius;                 // до края, в блоках
    // угол от «двенадцати часов», по часовой стрелке, 0..1
    float turn = fract(atan(p.x, -p.y) / TAU);

    // --- деления: двенадцать, каждое третье длиннее ---
    float hour = turn * 12.0;
    float mark = abs(fract(hour + 0.5) - 0.5) / 12.0 * TAU * rho * radius;      // до ближайшего деления, в блоках
    float longMark = step(abs(mod(floor(hour + 0.5), 3.0)), 0.5);
    float inner = mix(0.84, 0.74, longMark);
    float ticks = (1.0 - smoothstep(0.07, 0.13, mark)) * step(inner, rho) * step(rho, 0.95);

    // --- стрелка: за замах делает полный оборот и приходит на «двенадцать» ---
    float hand = mix(progress, 1.0, impact);
    float d = abs(fract(turn - hand + 0.5) - 0.5) * TAU * rho * radius;           // до стрелки, в блоках
    float needle = (1.0 - smoothstep(0.06, 0.16, d)) * step(0.06, rho) * step(rho, 0.90);
    float needleGlow = exp(-d * 2.2) * step(rho, 0.92) * 0.5;
    // заметённый сектор
    float swept = step(turn, hand) * step(rho, 0.95);

    // --- кольца и ось ---
    float ringA = 1.0 - smoothstep(0.0, 0.07, abs(rho - 0.95) * radius);
    float ringB = 1.0 - smoothstep(0.0, 0.05, abs(rho - 0.70) * radius);
    float hub = 1.0 - smoothstep(0.25, 0.40, rho * radius);

    // --- край: свечение, искры, тёмная полоса ---
    float glow = exp(-edge * 2.4);
    float spark = hash(vec2(floor(turn * 170.0 - t * 7.0), 1.0));
    float crest = (1.0 - smoothstep(0.0, 0.19, edge)) * (0.12 + 0.88 * spark * spark);
    float shade = smoothstep(0.12, 0.25, edge) * (1.0 - smoothstep(0.38, 0.75, edge));

    float dial = max(max(ticks, needle), max(ringA * 0.8, max(ringB * 0.45, hub)));
    float a;
    vec3 col;
    if (impact < 0.5) {
        // замах: чем ближе удар, тем чаще мигает край
        float blink = 0.6 + 0.4 * sin(t * (12.0 + 18.0 * progress));
        float fill = 0.07 + 0.16 * swept + 0.05 * progress;
        a = fill * fillGain + 0.10 * onBright + (0.34 * glow + 0.90 * crest + 0.30 * shade) * blink + 0.85 * dial + needleGlow;
        col = mix(bodyLo, bodyHi, clamp(0.35 + 0.4 * swept + glow * 0.8, 0.0, 1.0));
        col = mix(col, shadeCol, shade * 0.75);
        col = mix(col, lineCol, clamp(dial + crest * 1.2 + needleGlow, 0.0, 1.0));
    } else {
        // удар: вспышка, затем застывший бледный циферблат гаснет
        float flash = 1.0 - smoothstep(0.0, 0.22, progress);
        float fade = 1.0 - smoothstep(0.45, 1.0, progress);
        float front = smoothstep(0.0, 0.30, progress) * 1.15;
        float ring = (1.0 - smoothstep(0.0, 0.08, abs(rho - front))) * (1.0 - smoothstep(0.25, 0.35, progress));
        a = (0.30 + 0.85 * dial + 0.6 * crest + 0.35 * glow + 0.25 * shade) * fade + 0.55 * flash + 0.9 * ring;
        col = mix(bodyHi, frozenCol, clamp(0.55 + 0.45 * flash + dial * 0.5, 0.0, 1.0));
        col = mix(col, shadeCol, shade * 0.5 * (1.0 - flash));
        col = mix(col, frozenCol, clamp(ring + dial * 0.6, 0.0, 1.0));
    }
    a = clamp(a, 0.0, 0.96) * ColorModulator.a;
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, a);
}
