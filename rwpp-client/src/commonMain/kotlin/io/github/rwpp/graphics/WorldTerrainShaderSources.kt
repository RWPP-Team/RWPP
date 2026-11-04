/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal object WorldTerrainShaderSources {
    internal val VERTEX_SHADER = """
        #version 130
        varying vec4 v_color;
        varying vec2 v_texCoords;
        varying vec2 v_worldPosition;
        uniform vec2 u_cacheWorldOrigin;
        uniform vec2 u_cacheUvWorldScale;
        void main() {
          gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
          v_color = gl_Color;
          v_texCoords = vec2(gl_MultiTexCoord0);
          v_worldPosition = v_texCoords * u_cacheUvWorldScale + u_cacheWorldOrigin;
        }
    """.trimIndent()
    internal val FRAGMENT_SHADER =
        DesktopWorldWaterSurfaceEnhancements.FRAGMENT_SHADER.substringBefore("        void main()".trimStart()) + """
        // Mask RGBA: water coverage, signed coast distance, cliff marker, lava.
        uniform sampler2D u_terrainMask;
        uniform vec2 u_maskWorldScale;
        uniform vec2 u_maskExtent;
        uniform vec2 u_maskTexel;
        uniform vec2 u_maskWorldOrigin;
        uniform vec2 u_sourceTexel;
        uniform vec2 u_cacheUvWorldScale;
        uniform float u_cacheScale;
        uniform float u_lightStrength;
        uniform float u_waveStrength;
        uniform float u_wetStrength;
        uniform float u_terrainHighlights;
        float luma(vec3 c) { return dot(c, vec3(0.2126,0.7152,0.0722)); }
        vec4 terrainMask(vec2 p) {
          vec2 uv = clamp((p-u_maskWorldOrigin) * u_maskWorldScale, u_maskTexel * 0.5,
                          u_maskExtent - u_maskTexel * 0.5);
          return texture2D(u_terrainMask, uv);
        }
        float lavaAt(vec2 p) {
          return clamp((terrainMask(p).a-0.5019608)*2.007874,0.0,1.0);
        }
        // Scene-space rays run after sprites have been drawn, in the full-world post pass.
        // Terrain cache blocks supply waves/material relief only, avoiding reflection seams.
        void main() {
          vec4 original = texture2D(u_texture,v_texCoords);
          vec2 world = v_worldPosition;
          vec4 tags = terrainMask(world);
          vec3 tag = tags.rgb;
          float lavaHere = clamp((tags.a-0.5019608)*2.007874,0.0,1.0);
          float water = clamp(tag.r,0.0,1.0);
          float coastDistance = (tag.g-0.5)*12.0;
          float cliff = 1.0-smoothstep(0.15,0.92,tag.b);
          // One shared sun direction for terrain, sprites and their down-right shadows.
          // Camera position, zoom and cache boundaries never alter the illumination direction.
          vec3 sun = normalize(vec3(-0.55,-0.55,0.82));
          vec3 halfSun = normalize(sun+vec3(0.0,0.0,1.0));
          vec3 land = original.rgb;
          if (water < 0.999) {
          // Stay inside the native cache gutter. Recover derivatives per world pixel so
          // rebuilding a cache at a different scale does not amplify the same relief.
          vec2 texel = u_sourceTexel;
          vec3 leftColor = texture2D(u_texture,v_texCoords-vec2(texel.x,0.0)).rgb;
          vec3 rightColor = texture2D(u_texture,v_texCoords+vec2(texel.x,0.0)).rgb;
          vec3 topColor = texture2D(u_texture,v_texCoords-vec2(0.0,texel.y)).rgb;
          vec3 bottomColor = texture2D(u_texture,v_texCoords+vec2(0.0,texel.y)).rgb;
          float left = luma(leftColor), right = luma(rightColor);
          float top = luma(topColor), bottom = luma(bottomColor);
          float interior = smoothstep(0.15,1.15,abs(coastDistance));
          vec3 neighborhood = (leftColor+rightColor+topColor+bottomColor)*0.25;
          float center = luma(original.rgb);
          // Classify a small neighbourhood rather than individual grain pixels, keeping
          // material response stable across grass, soil, stone and pale matte snow.
          float chroma = max(neighborhood.r,max(neighborhood.g,neighborhood.b))-min(neighborhood.r,min(neighborhood.g,neighborhood.b));
          float paleMatte = smoothstep(0.48,0.78,luma(neighborhood))*(1.0-smoothstep(0.08,0.22,chroma));
          float grass = smoothstep(0.025,0.12,neighborhood.g-max(neighborhood.r,neighborhood.b));
          vec3 broad = waterNoise(world*0.006+vec2(19.3,7.6));
          vec3 detail = waterNoise(world*0.021+vec2(7.1,13.8));
          float roughness = mix(1.0,0.22,paleMatte);
          float detailFade = 1.0-smoothstep(3.0,10.0,max(fwidth(world).x,fwidth(world).y));
          vec2 slope = ((broad.yz*0.30+detail.yz*0.085)*roughness
              +vec2(right-left,bottom-top)*max(u_cacheScale,0.1)
                *mix(3.5,1.65,grass)*mix(1.0,0.35,paleMatte)*detailFade)*interior;
          slope *= mix(1.0,1.25,cliff);
          // The bank uses the shared world mask, so its light-facing lip and sheltered
          // contact edge continue across cache blocks without sampling another block.
          float bank = 1.0-smoothstep(0.0,1.6,abs(coastDistance));
          if (bank>0.001) {
            vec2 dx=vec2(4.0,0.0), dy=vec2(0.0,4.0);
            vec2 coastSlope=vec2(terrainMask(world+dx).g-terrainMask(world-dx).g,
                terrainMask(world+dy).g-terrainMask(world-dy).g);
            slope -= coastSlope*1.6*bank;
          }
          vec3 normal = normalize(vec3(-slope,1.0));
          float reliefLight = clamp(dot(normal,sun)-sun.z,-0.38,0.24);
          float variation = ((broad.x-0.5)*0.22+(detail.x-0.5)*0.045)*roughness*interior;
          float cavity = clamp(luma(neighborhood)-center-0.012,0.0,0.12)
              *mix(1.3,0.55,grass)*(1.0-paleMatte*0.8)*interior;
          vec3 albedo = mix(original.rgb,neighborhood,mix(0.06,0.30,paleMatte)*interior*(1.0-cliff*0.5));
          float illumination = clamp(1.0+(variation+reliefLight*0.90-cavity-bank*0.07)*u_lightStrength,0.48,1.40);
          land = albedo*illumination;
          float wetGround = (1.0-smoothstep(0.0,3.4,max(-coastDistance,0.0)))*(1.0-water)*u_wetStrength;
          // The native shoreline artwork is the boundary. Fade the extra contact shading
          // through its sampled coverage instead of outlining whole water/cliff tiles.
          land *= 1.0-wetGround*0.085;
          float groundSpec = pow(max(dot(normal,halfSun),0.0),22.0)*wetGround;
          land += vec3(0.82,0.86,0.88)*groundSpec*0.20*u_lightStrength*u_terrainHighlights;
          // Lava radiates: a bounded warm term reaches the neighbouring ground instead of
          // ending at the tile border. Four taps keep this a local light, not a screen glow.
          vec2 spill = vec2(16.0);
          float lavaLight = (lavaAt(world+vec2(spill.x,0.0))+lavaAt(world-vec2(spill.x,0.0))
              +lavaAt(world+vec2(0.0,spill.y))+lavaAt(world-vec2(0.0,spill.y)))*0.25;
          if (lavaLight > 0.001) {
            float diagonal = (lavaAt(world+vec2(20.0,20.0))+lavaAt(world+vec2(-20.0,20.0))
                +lavaAt(world+vec2(20.0,-20.0))+lavaAt(world-vec2(20.0)))*0.25;
            float pulse = 0.90+0.10*sin(u_time*0.9+dot(world,vec2(0.013,0.017)));
            float glow = (lavaLight*0.72+diagonal*0.28)*(1.0-lavaHere)*pulse;
            land += vec3(0.82,0.24,0.035)*glow*0.30*u_lightStrength*(vec3(1.0)-land);
          }
          }

          vec3 wet = original.rgb;
          if (water > 0.001) {
            float depth = smoothstep(0.0,4.5,max(coastDistance,0.0));
            float offshore = smoothstep(0.12,1.7,max(coastDistance,0.0));
            vec3 wn = normalize(vec3(-waterSlope(world,u_time,fwidth(world))*u_waveStrength,1.0));
            // Grade all water pixels, including those inside mixed shore tiles, consistently.
            // Only the moving reflections fade at the bank; this avoids a bright blue rim.
            vec3 base = mix(vec3(luma(original.rgb)),original.rgb,mix(0.28,0.52,offshore))*mix(0.94,0.92,depth);
            float diffuse = dot(wn,sun)-sun.z;
            wet = base*(1.0+diffuse*0.85*offshore);
            float spec = pow(max(dot(wn,halfSun),0.0),48.0);
            float breakup = smoothstep(0.25,0.78,waterNoise(world*0.035+vec2(-u_time*0.016,u_time*0.009)).x);
            wet += vec3(0.84,0.89,0.92)*spec*(0.05+0.95*breakup)*offshore*0.24*u_lightStrength*min(u_waveStrength,1.0);
          }
          vec3 shaded = mix(land,wet,water);
          if (lavaHere > 0.001) {
            // World-anchored molten seams drift slowly beneath the native crust artwork.
            // Dark crust remains dark; hot cracks gain bounded orange emission, never white.
            vec3 flow = waterNoise(world*0.025+vec2(u_time*0.022,-u_time*0.013));
            float heat = 0.5+0.5*sin(dot(world,vec2(0.083,0.061))+flow.x*2.4-u_time*0.80);
            float molten = smoothstep(0.18,0.82,heat);
            float radiant = smoothstep(0.06,0.48,luma(original.rgb));
            vec3 hot = original.rgb*mix(0.88,1.0,molten);
            hot += vec3(1.0,0.23,0.015)*(0.10+0.24*molten)*radiant*(vec3(1.0)-hot);
            shaded = mix(shaded,hot,lavaHere);
          }
          gl_FragColor = vec4(shaded,original.a)*v_color;
        }
    """.trimIndent()


}
