/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** Native semantic terrain tags are cached once and used as the water/lava material mask. */
internal data class WorldSurfaceTileTraits(
    val water: Boolean,
    val waterBridge: Boolean,
    val lava: Boolean,
    val cliff: Boolean
)

internal fun worldSurfaceMaterial(traits: WorldSurfaceTileTraits): WorldSurfaceMaterial? = when {
    traits.water && !traits.waterBridge -> WorldSurfaceMaterial.WATER
    traits.lava && !traits.cliff -> WorldSurfaceMaterial.LAVA
    else -> null
}

internal enum class WorldSurfaceMaterial(val shaderValue: Float) {
    WATER(0f), LAVA(1f)
}

internal data class WorldSurfaceTileRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

internal fun worldSurfaceScreenRect(
    tileX: Int,
    tileY: Int,
    tileWidth: Int,
    tileHeight: Int,
    cameraX: Float,
    cameraY: Float,
    zoom: Float
): WorldSurfaceTileRect = WorldSurfaceTileRect(
    left = (tileX * tileWidth - cameraX) * zoom,
    top = (tileY * tileHeight - cameraY) * zoom,
    right = ((tileX + 1) * tileWidth - cameraX) * zoom,
    bottom = ((tileY + 1) * tileHeight - cameraY) * zoom
)

/**
 * Shared world-space wave functions. Runtime terrain processing happens on the final composed
 * map cache in DesktopWorldTerrainMaterials, including banks and detail layers.
 */
internal object DesktopWorldWaterSurfaceEnhancements {
    /** Directional normal waves and restrained sky/specular response, evaluated in world space. */
    internal val FRAGMENT_SHADER = """
        #version 130
        precision mediump float;
        varying vec4 v_color;
        varying vec2 v_texCoords;
        varying vec2 v_worldPosition;
        uniform sampler2D u_texture;
        uniform vec2 u_cameraRender;
        uniform float u_zoom;
        uniform float u_time;
        uniform float u_material;

        float hashWater(vec2 p) {
          vec3 q = fract(vec3(p.xyx) * 0.1031);
          q += dot(q, q.yzx + 33.33);
          return fract((q.x + q.y) * q.z);
        }
        // Smooth noise plus analytic derivatives; no texture fetch, random-per-frame or UV warp.
        vec3 waterNoise(vec2 p) {
          vec2 i = floor(p), f = fract(p);
          vec2 u = f * f * (3.0 - 2.0 * f);
          vec2 du = 6.0 * f * (1.0 - f);
          float a = hashWater(i), b = hashWater(i + vec2(1.0, 0.0));
          float c = hashWater(i + vec2(0.0, 1.0)), d = hashWater(i + vec2(1.0));
          return vec3(mix(mix(a,b,u.x), mix(c,d,u.x), u.y),
            mix(b-a,d-c,u.y) * du.x, mix(c-a,d-b,u.x) * du.y);
        }

        // GPU Gems: separate long swells from short directional normal waves. Curved phases
        // break up the regular interference grid without moving shoreline/atlas UVs.
        vec2 waterSlope(vec2 p, float t, vec2 footprint) {
          // Long, low-amplitude swells with one quiet crossing component. Reflections are
          // produced by the normal, never by drawing a grid/stripe texture over the water.
          vec3 bend = waterNoise(p*0.012+vec2(-t*0.012,t*0.006));
          vec2 k1 = vec2(0.105,0.047);
          vec2 k2 = vec2(-0.037,0.078);
          float f1 = 1.0-smoothstep(0.8,2.2,dot(abs(k1),footprint));
          float f2 = 1.0-smoothstep(0.8,2.2,dot(abs(k2),footprint));
          return normalize(k1)*0.30*cos(dot(p,k1)+(bend.x-0.5)*3.2-t*0.82)*f1
               + normalize(k2)*0.085*cos(dot(p,k2)-t*0.48+1.7)*f2;
        }

        vec2 waterSlope(vec2 p, float t) { return waterSlope(p,t,fwidth(p)); }

        void main() {
          vec4 original = texture2D(u_texture, v_texCoords);
          vec3 color = original.rgb;
          // The draw position is already camera-relative and zoomed by the native Map path.
          // Recover world coordinates from it so every tile uses one shared phase without
          // per-tile uniforms (which would force the JavaCanvas GL batch to flush each cell).
          vec2 world = v_worldPosition;

          if (u_material < 0.5) {
            vec2 slope = waterSlope(world, u_time);
            vec3 normal = normalize(vec3(-slope, 1.0));
            // Same sun as terrain relief and the down-right unit/building shadows.
            vec3 lightDir = normalize(vec3(-0.55, -0.55, 0.82));
            vec3 halfDir = normalize(lightDir + vec3(0.0, 0.0, 1.0));
            float diffuse = dot(normal, lightDir);
          float wavelets = smoothstep(0.30, 0.82,
            waterNoise(world * 0.032 + vec2(-u_time * 0.035, u_time * 0.014)).x);
            float specular = pow(max(dot(normal, halfDir), 0.0), 128.0) * (0.12 + 0.88 * wavelets);
            float fresnel = 0.02 + 0.98 * pow(1.0 - normal.z, 5.0);
            float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
            // Neutral sky reflection and desaturated albedo: no additive saturated-blue blobs.
            vec3 albedo = mix(vec3(luma), original.rgb, 0.24) * 0.82;
            vec3 body = albedo * (0.87 + 0.22 * diffuse);
            vec3 sky = vec3(0.56, 0.64, 0.69);
            vec3 reflection = mix(body, sky, fresnel + 0.035);
            color = reflection + vec3(0.82, 0.88, 0.90) * specular * 0.20;
          } else {
            float heatA = sin(dot(world, vec2(0.041, 0.027)) + u_time * 0.74);
            float heatB = sin(dot(world, vec2(-0.018, 0.052)) - u_time * 0.53);
            float heat = clamp(0.5 + 0.28 * heatA + 0.22 * heatB, 0.0, 1.0);
            float glow = smoothstep(0.48, 0.94, heat);
            vec3 molten = mix(vec3(0.22, 0.035, 0.008), vec3(1.0, 0.30, 0.035), heat);
            color = mix(original.rgb, molten + vec3(0.45, 0.12, 0.01) * glow, original.a * 0.82);
          }

          gl_FragColor = vec4(color, original.a) * v_color;
        }
    """.trimIndent()

    /** Native plain.vert contract with world-coordinate interpolation for continuous lighting. */
    internal val VERTEX_SHADER = """
        #version 130
        varying vec4 v_color;
        varying vec2 v_texCoords;
        varying vec2 v_worldPosition;
        uniform vec2 u_cameraRender;
        uniform float u_zoom;
        void main() {
          gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
          v_color = gl_Color;
          v_texCoords = vec2(gl_MultiTexCoord0);
          v_worldPosition = gl_Vertex.xy / max(u_zoom, 0.0001) + u_cameraRender;
        }
    """.trimIndent()

}

