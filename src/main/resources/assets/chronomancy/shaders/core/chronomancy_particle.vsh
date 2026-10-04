#version 150

#moj_import <minecraft:fog.glsl>

// Частицы хрономантии: цвет вершины НЕ цвет, а параметры для фрагментного шейдера
// (r — сдвиг оттенка, g — растворение, b — доля золота, a — яркость). Свет не нужен — частицы светятся сами.
in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec4 params;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    texCoord0 = UV0;
    params = Color;
}
