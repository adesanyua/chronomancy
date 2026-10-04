#version 150

#moj_import <fog.glsl>

// Усиленный удар Rift Maker (каждый третий взмах — удар клинком о землю по кругу).
// Круг на земле показывает, куда придётся удар. На замахе: край разгорается, от босса к краю ползут
// трещины-разломы, круг наливается цветом. В момент удара: от центра к краю проходит ударная волна,
// трещины вспыхивают и гаснут. Смешивание обычное (альфа).
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

const vec3 SAND   = vec3(1.00, 0.70, 0.18);
const vec3 COPPER = vec3(0.86, 0.45, 0.14);
const vec3 DEEP   = vec3(0.55, 0.23, 0.06);
const vec3 HOT    = vec3(1.00, 0.95, 0.72);
const vec3 RIFT   = vec3(0.16, 0.57, 0.94);
const vec3 RIFT_L = vec3(0.70, 0.90, 1.00);
const float TAU = 6.2831853;

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
    vec3 riftCol = mix(RIFT, vec3(0.04, 0.20, 0.66), onBright);
    vec3 riftCore = mix(RIFT_L, vec3(0.10, 0.42, 0.95), onBright);
    float progress = vertexColor.r;
    float impact = step(0.5, vertexColor.g);
    float radius = vertexColor.b * 16.0;

    // «пиксели» мира — 16 на блок
    vec2 p = floor(texCoord0 * radius * 16.0) / (radius * 16.0);
    float rho = length(p);
    if (rho > 1.0) {
        discard;
    }
    float theta = atan(p.y, p.x);
    float edge = (1.0 - rho) * radius;                 // до края, в блоках

    // --- трещины: по лучам от босса, у каждой свой изгиб и своя длина ---
    const float RAYS = 14.0;
    float lane = theta / TAU * RAYS;
    float id = floor(lane);
    float bend = (hash(vec2(id, 3.0)) - 0.5) * 0.9 * sin(rho * 7.0 + hash(vec2(id, 5.0)) * TAU);
    float off = abs(fract(lane + bend * rho) - 0.5);
    float px = off * TAU * rho * radius / RAYS;                          // до оси трещины, в блоках
    float reach = 0.50 + 0.50 * hash(vec2(id, 9.0));
    float grown = mix(progress * 1.05, 1.0, impact) * reach;             // на замахе трещины ползут к краю
    float along = clamp(rho / max(grown, 0.01), 0.0, 1.0);
    float alive = step(0.08, rho) * (1.0 - smoothstep(grown - 0.03, grown, rho));
    float width = mix(0.20, 0.07, along);                                // у босса шире, к концу сходит на нет
    float crack = (1.0 - smoothstep(width * 0.55, width, px)) * alive;
    float core = (1.0 - smoothstep(0.0, width * 0.4, px)) * alive;
    float halo = exp(-px * 4.5) * alive * 0.45;

    // --- край круга: свечение, искры вдоль кромки, тёмная медная полоса ---
    float glow = exp(-edge * 2.4);
    float spark = hash(vec2(floor(theta * 28.0 - t * 7.0), 1.0));
    float crest = (1.0 - smoothstep(0.0, 0.19, edge)) * (0.12 + 0.88 * spark * spark);
    float shade = smoothstep(0.12, 0.25, edge) * (1.0 - smoothstep(0.38, 0.75, edge));

    float a;
    vec3 col;
    if (impact < 0.5) {
        // замах: круг наливается, край мигает всё чаще
        float blink = 0.6 + 0.4 * sin(t * (14.0 + 16.0 * progress));
        float fill = 0.06 + 0.20 * progress;
        float rim = (0.40 * glow + 0.95 * crest + 0.34 * shade) * blink;
        a = fill * fillGain + 0.10 * onBright + rim + 0.90 * crack + halo;
        col = mix(bodyLo, bodyHi, clamp(0.30 + glow * 0.8, 0.0, 1.0));
        col = mix(col, shadeCol, shade * 0.75);
        col = mix(col, lineCol, clamp(crest * 1.2, 0.0, 1.0));
        col = mix(col, riftCol, clamp(crack + halo, 0.0, 1.0));
        col = mix(col, riftCore, core);
    } else {
        // удар: волна от центра к краю, за ней — гаснущий след
        float front = 1.0 - (1.0 - progress) * (1.0 - progress);          // быстро вначале, медленно в конце
        float ring = 1.0 - smoothstep(0.0, 0.10, abs(rho - front));
        float wake = step(rho, front) * (1.0 - progress) * (0.25 + 0.45 * rho / max(front, 0.05));
        float fade = 1.0 - smoothstep(0.55, 1.0, progress);
        a = (0.95 * ring + wake * fillGain + (0.90 * crack + halo) * step(rho, front + 0.05) + 0.5 * crest + 0.3 * glow
                + 0.12 * onBright) * fade;
        col = mix(bodyLo, bodyHi, clamp(0.4 + wake, 0.0, 1.0));
        col = mix(col, riftCol, clamp(crack + halo, 0.0, 1.0));
        col = mix(col, riftCore, core);
        col = mix(col, lineCol, clamp(ring + crest * 0.8, 0.0, 1.0));
    }
    a = clamp(a, 0.0, 0.96) * ColorModulator.a;
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, a);
}
