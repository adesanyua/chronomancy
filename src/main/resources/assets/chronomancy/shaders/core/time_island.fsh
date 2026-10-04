#version 150

#moj_import <fog.glsl>

// Островок времени в испытании разлома: пятно на земле, внутри которого личное время кастера снова
// идёт (лечение, голод, мана). Всё в нём говорит «здесь время течёт», в отличие от застывшего мира
// вокруг: от центра к краю бегут круги, по кругу идёт секундная стрелка. Палитра — мятно-бирюзовая:
// не золото ударов босса и не синева разломов. Внешнее кольцо — дуга: сколько её осталось, столько
// островок ещё проживёт. Смешивание обычное (альфа).
//
// texCoord0 — положение точки в долях радиуса круга (на краю длина равна 1).
// Вершинный цвет несёт не цвет: r — яркость (плавное появление и исчезновение), g — доля оставшегося
// срока (1 .. 0), b — радиус в блоках, делённый на 16, a — яркость земли под кругом (0 тёмная .. 1 светлая).
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

const vec3 MINT  = vec3(0.50, 1.00, 0.80);
const vec3 TEAL  = vec3(0.12, 0.70, 0.62);
const vec3 DEEP  = vec3(0.04, 0.36, 0.38);
const vec3 LIGHT = vec3(0.88, 1.00, 0.95);
const float TAU = 6.2831853;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, vec2(0.5));
    if (tex.a < 0.1) {
        discard;
    }
    float strength = vertexColor.r;
    float left = vertexColor.g;
    float radius = vertexColor.b * 16.0;
    float onBright = vertexColor.a;
    float t = PhaseTime;

    // на светлой земле (снег, песок) светлая мята пропадает — линии темнеют, заливка плотнее
    vec3 bodyLo = mix(TEAL, vec3(0.03, 0.42, 0.40), onBright);
    vec3 bodyHi = mix(MINT, vec3(0.06, 0.58, 0.50), onBright);
    vec3 lineCol = mix(LIGHT, vec3(0.01, 0.22, 0.24), onBright);
    vec3 shadeCol = mix(DEEP, vec3(0.02, 0.26, 0.28), onBright);
    float fillGain = 1.0 + 1.2 * onBright;

    vec2 p = floor(texCoord0 * radius * 16.0) / (radius * 16.0);
    float rho = length(p);
    if (rho > 1.0) {
        discard;
    }
    float edge = (1.0 - rho) * radius;                 // до края, в блоках
    float turn = fract(atan(p.x, -p.y) / TAU);         // от «двенадцати часов», по часовой стрелке

    // --- круги от центра к краю: время течёт ---
    float wave = fract(rho * radius * 0.85 - t * 0.55);
    float ripple = smoothstep(0.0, 0.10, wave) * (1.0 - smoothstep(0.10, 0.34, wave));
    ripple *= smoothstep(0.05, 0.25, rho) * (1.0 - smoothstep(0.80, 0.96, rho));

    // --- секундная стрелка: полный оборот за четыре секунды ---
    float hand = fract(t / 4.0);
    float d = abs(fract(turn - hand + 0.5) - 0.5) * TAU * rho * radius;
    float needle = (1.0 - smoothstep(0.05, 0.13, d)) * step(0.05, rho) * step(rho, 0.84);
    float trail = fract(hand - turn);                   // сразу за стрелкой — тающий след
    float sweep = (1.0 - smoothstep(0.0, 0.22, trail)) * step(rho, 0.88) * 0.5;

    // --- деления: двенадцать ---
    float hour = turn * 12.0;
    float mark = abs(fract(hour + 0.5) - 0.5) / 12.0 * TAU * rho * radius;
    float ticks = (1.0 - smoothstep(0.06, 0.12, mark)) * step(0.80, rho) * step(rho, 0.90);

    // --- внешнее кольцо: дуга оставшегося срока, остальное — бледный след ---
    float ring = 1.0 - smoothstep(0.0, 0.08, abs(rho - 0.95) * radius);
    float alive = step(turn, left);
    float arc = ring * (0.25 + 0.75 * alive);
    float hub = 1.0 - smoothstep(0.16, 0.30, rho * radius);

    // --- край: мягкое свечение и редкие искры ---
    float glow = exp(-edge * 2.2);
    float spark = hash(vec2(floor(turn * 140.0 + t * 3.0), 3.0));
    float crest = (1.0 - smoothstep(0.0, 0.16, edge)) * (0.15 + 0.85 * spark * spark) * (0.4 + 0.6 * alive);
    float shade = smoothstep(0.10, 0.22, edge) * (1.0 - smoothstep(0.34, 0.70, edge));

    // последняя секунда срока: рисунок мигает — пора выходить
    float ending = 1.0 - smoothstep(0.0, 0.12, left);
    float blink = mix(1.0, 0.55 + 0.45 * sin(t * 18.0), ending);

    float lines = max(max(needle, ticks * 0.8), max(arc, hub * 0.9));
    float a = (0.10 + 0.07 * sin(t * 1.7) + 0.20 * ripple + sweep * 0.35) * fillGain + 0.10 * onBright
            + 0.30 * glow + 0.70 * crest + 0.25 * shade + 0.85 * lines;
    vec3 col = mix(bodyLo, bodyHi, clamp(0.30 + 0.55 * ripple + 0.8 * glow + sweep, 0.0, 1.0));
    col = mix(col, shadeCol, shade * 0.7);
    col = mix(col, lineCol, clamp(lines + crest * 1.1, 0.0, 1.0));

    a = clamp(a * blink, 0.0, 0.94) * strength * ColorModulator.a;
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, a);
}
