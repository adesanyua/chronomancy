#version 150

#moj_import <fog.glsl>

// Rift Maker: переливающаяся «оболочка фазы». Модель рисуется второй раз, чуть раздутой по нормалям.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 viewNormal;
out vec3 viewPos;

void main() {
    vec3 pos = Position + normalize(Normal) * 0.035;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = Color;
    texCoord0 = UV0;
    viewNormal = normalize(mat3(ModelViewMat) * Normal);
    viewPos = (ModelViewMat * vec4(pos, 1.0)).xyz;
}
