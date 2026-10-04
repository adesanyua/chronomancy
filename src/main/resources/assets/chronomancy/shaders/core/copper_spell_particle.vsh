#version 150

#moj_import <minecraft:fog.glsl>

// Частицы заклинаний, сжатых Timeless Book: обычные частицы из общего атласа, перекрашенные в медь.
// Свет мира не учитывается — как и у остальных частиц хрономантии, они светятся сами.
in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    texCoord0 = UV0;
    vertexColor = Color;
}
