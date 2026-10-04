#version 150

// TIME DILATION FIELD — локальный сферический spatial volume (Phase B).
//
// Принципиально ОТЛИЧАЕТСЯ от World Stop: это НЕ глобальный fullscreen grade,
// а попиксельная маска по НАПРАВЛЕНИЮ ЛУЧА (ray-sphere). Направление луча
// восстанавливается изInvViewProj (чистая матричная математика, БЕЗ текстуры
// глубины — она в ванильном PostChain ненадёжна). Пиксель красится, когда его
// луч попадает в сферу И точка входа лежит ВЫШЕ экватора сферы (world Y >=
// центр). Нижняя полусфера закопана в землю, поэтому игрок видит ровно КУПОЛ —
// ту часть поля, которую действительно видит. Мир вокруг купола нетронут.
//
// Пакировка поля (по одному mat4 на поле, максимум 4 — расширяемо):
//   col0.xyz = центр поля ОТНОСИТЕЛЬНО камеры; col0.w = radius;
//   col1.x   = slowAmount = clamp(1 - temporalRate, 0, 1).
//
// ЦВЕТОВАЯ НАУКА — ОДНА НА ПРОЕКТ. Секция CHRONOMANCY PALETTE ниже — это
// математически ИДЕНТИЧНАЯ (до константы) копия grading'а из world_stop.fsh:
// EffectProgram 1.21.1 возвращает "#error Import statement not supported" на
// любой #moj_import в post-program шейдерах, поэтому физический shared include
// невозможен — дублирование точной функции является безопасной формой того же
// требования. Единственное различие Dilation vs World Stop — НЕ палитра, а
// интенсивность и МАСКА (пространственная), а не цвет.

uniform sampler2D DiffuseSampler;

uniform mat4 InvViewProj;
uniform mat4 Field0;
uniform mat4 Field1;
uniform mat4 Field2;
uniform mat4 Field3;
uniform float FieldCount;
uniform float CameraInsideStrength;
uniform float DominantSlow;

in vec2 texCoord;
out vec4 fragColor;

// ===== CHRONOMANCY PALETTE — identically copied from world_stop.fsh =========
// Палитра «старого медно-золотого снимка» (sRGB). ЗНАЧЕНИЯ ДОЛЖНЫ СОВПАДАТЬ
// с world_stop.fsh побайтово — World Stop есть визуальный эталон проекта.
const vec3 C_SHADOW    = vec3(0.204, 0.149, 0.090);
const vec3 C_DARK_COPP = vec3(0.431, 0.278, 0.145);
const vec3 C_SAND      = vec3(0.714, 0.545, 0.275);
const vec3 C_GOLD      = vec3(0.816, 0.678, 0.376);
const vec3 C_HIGHLIGHT = vec3(0.890, 0.820, 0.604);

// Ремап luminance в медную палитру градиентом (не плоской заливкой):
//   0.00-0.30 : shadow   -> dark copper   (тени остаются читаемо тёмными)
//   0.30-0.65 : dark copper -> sand       (средние тона — медь/песок)
//   0.65-1.00 : sand/brown -> pale gold   (хайлайты — старое золото)
vec3 temporalPalette(float l) {
    if (l < 0.30) {
        return mix(C_SHADOW, C_DARK_COPP, l / 0.30);
    } else if (l < 0.65) {
        return mix(C_DARK_COPP, C_SAND, (l - 0.30) / 0.35);
    } else {
        return mix(C_SAND * 0.85 + C_GOLD * 0.15, C_HIGHLIGHT, clamp((l - 0.65) / 0.35, 0.0, 1.0));
    }
}

// Полный grading World Stop'а без глобальных слоёв (vignette/chromatic/grain
// — это атмосфера полной остановки, к пространственной маске не относятся):
// luma -> desaturation 0.72 -> palette mix 0.75 -> contrast 1.08 вокруг 0.5.
// Контраст центрирован на 0.5, поэтому яркость сцены СОХРАНЯЕТСЯ.
vec3 chronomancyGrade(vec3 color) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec3 desaturated = mix(color, vec3(luma), 0.72);
    vec3 graded = mix(desaturated, temporalPalette(luma), 0.75);
    graded = (graded - 0.5) * 1.08 + 0.5;
    return graded;
}
// ===========================================================================

// Возвращает (center, radius, slow) i-го поля через out-параметры
// (GLSL 150 не умеет динамическую индексацию массива uniform, поэтому разворачиваем).
void getField(int i, out vec3 center, out float radius, out float slow) {
    mat4 m;
    if (i == 0)      { m = Field0; }
    else if (i == 1) { m = Field1; }
    else if (i == 2) { m = Field2; }
    else             { m = Field3; }
    center = m[0].xyz;
    radius = m[0].w;
    slow   = m[1].x;
}

// Художественный mapping физической силы замедления в визуальную интенсивность
// grade: слабое замедление — заметная, но лёгкая sepia; максимальное — близко к
// World Stop, но ВСЕГДА меньше его (0.80 < 1.0), plus World Stop сверху держит
// vignette/chromatic/grain и полный strength.
float dilationVisualStrength(float slowAmount) {
    return mix(0.40, 0.80, clamp(slowAmount, 0.0, 1.0));
}

void main() {
    vec3 base = texture(DiffuseSampler, texCoord).rgb;

    // Направление луча в camera-relative world (far-plane NDC через InvViewProj).
    // Нормируем — длина не важна, важна угловая направленность.
    vec3 ndc = vec3(texCoord * 2.0 - 1.0, 1.0);
    vec4 pw = InvViewProj * vec4(ndc, 1.0);
    vec3 dir = normalize(pw.xyz / max(abs(pw.w), 1e-6));

    // ===== ПРОСТРАНСТВЕННАЯ МАСКА (ray-sphere, БЕЗ глубины): луч попал в сферу
    //       И точка входа выше экватора => это ВИДИМЫЙ КУПОЛ (верхняя полусфера),
    //       нижняя закопана в землю => не красим. Небо внутри силуэта купола тоже
    //       красится (луч входит в верхнюю полусферу) — плотный медный купол. =====
    float strength = 0.0;
    int n = int(FieldCount + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= n) break;
        vec3 fc; float fr, fs;
        getField(i, fc, fr, fs);
        if (fr <= 0.0) {
            continue;
        }

        float tca = dot(fc, dir);                    // проекция центра на луч
        if (tca <= 0.0) {
            continue;                                // сфера позади камеры
        }
        float d2  = dot(fc, fc) - tca * tca;         // перпенд. дистанция луч->центр^2
        float r2  = fr * fr;
        if (d2 >= r2) {
            continue;                                // луч миновал сферу
        }
        float thc   = sqrt(r2 - d2);
        float entry = tca - thc;                     // передняя граница сферы на луче
        float entryY = dir.y * entry;                // world-relative Y точки входа
        // Купол: красим только выше экваториальной плоскости (Y центра). Мягкий
        // переход вокруг экватора — на ширину ~5% радиуса.
        float dome = smoothstep(fc.y - fr * 0.05, fc.y + fr * 0.05, entryY);
        // Мягкое затухание к силуэту шара (не к плоскому краю объёма).
        float edge = 1.0 - smoothstep(r2 * 0.85, r2, d2);
        strength = max(strength, dome * edge * dilationVisualStrength(fs));
    }

    // Камера внутри поля: near-global boost (тот же mapping по DominantSlow),
    // покрывает весь экран при входе. Различие с наружным видом — только в
    // маске, НЕ в цвете (одна и та же chronomancyGrade).
    float insideBoost = CameraInsideStrength * dilationVisualStrength(DominantSlow);
    strength = max(strength, insideBoost);
    strength = clamp(strength, 0.0, 1.0);

    if (strength <= 0.0001) {
        fragColor = vec4(base, 1.0);
        return;
    }

    // Итог — та же композиция, что в World Stop: лерп оригинала к полному
    // grade по силе эффекта (у World Stop это EffectStrength, здесь — маска).
    vec3 graded = chronomancyGrade(base);
    vec3 result = mix(base, clamp(graded, 0.0, 1.0), strength);
    fragColor = vec4(result, 1.0);
}
