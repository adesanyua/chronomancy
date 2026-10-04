#version 150

// ACCELERATED ZONE + TIME PARADOX — пространственный пост-эффект, зеркальный time_dilation.fsh.
//
// Поле замедления — тёплый выцветший медный купол-СФЕРА. Зона ускорения — его противоположность:
// КУБ, внутри которого картинка холодная, перенасыщенная и «спешит» (вертикальные росчерки,
// цветовой сдвиг по вертикали). Маска — попадание луча пикселя в куб (ray-box), без текстуры глубины.
// Нижняя половина куба закопана в землю, поэтому красится только его часть выше центра.
//
// Вспышка парадокса — расходящаяся сфера: рябь-преломление по её силуэту, внутри — полосы, в
// которых картинка то «замедленная» медная, то «ускоренная» голубая, и короткая вспышка в центре.
//
// Упаковка объёма (mat4, максимум 4):
//   col0.xyz = центр относительно камеры; col0.w = полусторона куба / полный радиус вспышки;
//   col1.x   = сила (зона: доля ускорения 0..1; вспышка: яркость 0..1);
//   col1.y   = вид: 1 — зона, 2 — вспышка парадокса;
//   col1.z   = прогресс вспышки 0..1; col1.w = текущий радиус её оболочки;
//   col2.xy  = экранные координаты центра вспышки (0..1); col2.z = 1, если центр перед камерой.

uniform sampler2D DiffuseSampler;

uniform mat4 InvViewProj;
uniform mat4 Volume0;
uniform mat4 Volume1;
uniform mat4 Volume2;
uniform mat4 Volume3;
uniform float VolumeCount;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

const vec3 C_DEEP = vec3(0.10, 0.35, 1.00);
const vec3 C_SKY  = vec3(0.55, 0.90, 1.00);
const vec3 C_GOLD = vec3(1.00, 0.78, 0.36);

// палитра поля замедления (та же, что в time_dilation.fsh) — нужна полосам парадокса
const vec3 C_SHADOW    = vec3(0.204, 0.149, 0.090);
const vec3 C_DARK_COPP = vec3(0.431, 0.278, 0.145);
const vec3 C_SAND      = vec3(0.714, 0.545, 0.275);
const vec3 C_HIGHLIGHT = vec3(0.890, 0.820, 0.604);

float hash1(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// «Ускоренная» картинка: насыщеннее, холоднее, контрастнее — обратное выцветшей меди поля.
vec3 accelGrade(vec3 c) {
    float l = luma(c);
    vec3 sat = mix(vec3(l), c, 1.45);
    vec3 cool = sat * vec3(0.66, 1.00, 1.34) + vec3(0.0, 0.01, 0.05);
    vec3 glow = mix(C_DEEP, C_SKY, clamp(l * 1.3, 0.0, 1.0)) * (0.35 + l);
    vec3 graded = max(mix(cool, glow, 0.24), vec3(0.0));
    // яркость сцены сохраняем (как и поле): меняются оттенок и насыщенность, а не экспозиция
    graded *= (l * 1.04 + 0.015) / max(luma(graded), 0.02);
    return (graded - 0.5) * 1.14 + 0.5;
}

vec3 copperGrade(vec3 c) {
    float l = luma(c);
    vec3 pal;
    if (l < 0.30) {
        pal = mix(C_SHADOW, C_DARK_COPP, l / 0.30);
    } else if (l < 0.65) {
        pal = mix(C_DARK_COPP, C_SAND, (l - 0.30) / 0.35);
    } else {
        pal = mix(C_SAND, C_HIGHLIGHT, clamp((l - 0.65) / 0.35, 0.0, 1.0));
    }
    vec3 graded = mix(mix(c, vec3(l), 0.72), pal, 0.75);
    return (graded - 0.5) * 1.08 + 0.5;
}

mat4 volume(int i) {
    if (i == 0) { return Volume0; }
    if (i == 1) { return Volume1; }
    if (i == 2) { return Volume2; }
    return Volume3;
}

float safeAxis(float v) {
    if (abs(v) < 0.00001) {
        return v < 0.0 ? -0.00001 : 0.00001;
    }
    return v;
}

void main() {
    // Направление луча пикселя в мире относительно камеры.
    vec3 ndc = vec3(texCoord * 2.0 - 1.0, 1.0);
    vec4 pw = InvViewProj * vec4(ndc, 1.0);
    vec3 dir = normalize(pw.xyz / max(abs(pw.w), 0.000001));
    vec3 inv = vec3(1.0 / safeAxis(dir.x), 1.0 / safeAxis(dir.y), 1.0 / safeAxis(dir.z));

    float zone = 0.0;        // маска зоны ускорения
    float interior = 0.0;    // внутренность вспышки парадокса
    float ripple = 0.0;      // рябь по силуэту оболочки
    float flash = 0.0;       // вспышка в центре
    vec2 offset = vec2(0.0); // преломление картинки рябью

    int n = int(VolumeCount + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= n) {
            break;
        }
        mat4 m = volume(i);
        vec3 c = m[0].xyz;
        float size = m[0].w;
        if (size <= 0.0) {
            continue;
        }
        float power = m[1].x;
        if (m[1].y < 1.5) {
            // ----- зона: пересечение луча с кубом (только часть выше центра) -----
            vec3 bmin = c - vec3(size, 0.0, size);
            vec3 bmax = c + vec3(size, size, size);
            vec3 t0 = bmin * inv;
            vec3 t1 = bmax * inv;
            vec3 tlo = min(t0, t1);
            vec3 thi = max(t0, t1);
            float tn = max(max(tlo.x, tlo.y), tlo.z);
            float tf = min(min(thi.x, thi.y), thi.z);
            float tnear = max(tn, 0.0);
            if (tf > tnear) {
                // тонкие срезы у рёбер и углов гаснут — силуэт куба мягкий, но читается квадратным
                float depth = smoothstep(0.0, size * 0.55, tf - tnear);
                zone = max(zone, depth * mix(0.45, 0.85, clamp(power, 0.0, 1.0)));
            }
        } else {
            // ----- вспышка парадокса: расходящаяся сфера -----
            float shell = m[1].w;
            float progress = m[1].z;
            float fade = pow(clamp(1.0 - progress, 0.0, 1.0), 1.5) * power;
            float tca = dot(c, dir);
            float d2 = max(dot(c, c) - tca * tca, 0.0);
            float d = sqrt(d2);
            bool inside = dot(c, c) < shell * shell;
            if (tca <= 0.0 && !inside) {
                continue;
            }
            float width = max(0.35, shell * 0.10);
            float band = exp(-((d - shell) * (d - shell)) / (width * width)) * fade;
            float within = inside ? 1.0 : 1.0 - smoothstep(shell * 0.85, shell, d);
            interior = max(interior, within * fade);
            ripple = max(ripple, band);
            flash = max(flash, exp(-d2 / max(0.01, size * size * 0.06))
                    * (1.0 - smoothstep(0.0, 0.30, progress)) * power);
            vec2 away = texCoord - m[2].xy;
            float len = length(away);
            if (len > 0.0001 && m[2].z > 0.5) {
                offset += (away / len) * band * 0.035;
            }
        }
    }

    if (zone <= 0.0001 && interior <= 0.0001 && ripple <= 0.0001 && flash <= 0.0001) {
        fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0);
        return;
    }

    // Цветовой сдвиг: в зоне — по вертикали (время «уносится» вверх), на ряби — вдоль преломления.
    vec2 uv = clamp(texCoord + offset, vec2(0.001), vec2(0.999));
    vec2 split = vec2(0.0, 0.0018 * zone) + offset * 0.35;
    vec3 base;
    base.r = texture(DiffuseSampler, clamp(uv + split, vec2(0.001), vec2(0.999))).r;
    base.g = texture(DiffuseSampler, uv).g;
    base.b = texture(DiffuseSampler, clamp(uv - split, vec2(0.001), vec2(0.999))).b;

    vec3 color = base;
    if (zone > 0.0001) {
        color = mix(color, clamp(accelGrade(color), 0.0, 1.0), zone);
        // росчерки спешащего времени: редкие вертикальные «кометы», летящие вверх
        float column = floor(texCoord.x * 110.0);
        float h = hash1(column);
        float y = fract(texCoord.y * 1.4 - Time * (0.9 + 1.7 * h) + h * 7.0);
        float comet = smoothstep(0.62, 0.97, y) * (1.0 - smoothstep(0.97, 1.0, y)) * step(0.86, h);
        color += C_SKY * comet * 0.12 * zone;
    }
    if (interior > 0.0001) {
        // парадокс: плавно бегущие полосы — в одних картинка медная («замедлена»), в других голубая
        float stripe = smoothstep(0.35, 0.65, fract(texCoord.y * 7.0 + texCoord.x * 2.0 - Time * 1.1));
        vec3 torn = mix(copperGrade(color), accelGrade(color), stripe);
        color = mix(color, clamp(torn, 0.0, 1.0), interior * 0.85);
    }
    color += mix(C_GOLD, C_SKY, 0.5 + 0.5 * sin(Time * 9.0 + texCoord.y * 30.0)) * ripple * 0.55;
    color += vec3(1.0, 0.96, 0.86) * flash * 0.75;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
