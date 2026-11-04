/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal object WorldPostShaderSources {
    internal val FRAGMENT_SHADER: String = """
        #version 130
        precision mediump float;
        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform float u_sunStrength;
        uniform float u_materialPass;
        uniform float u_postSSR;
        uniform float u_postTraceSteps;
        uniform float u_surfaceHighlights;
        uniform float u_dynamicBloom;
        uniform float u_wetStrength;
        uniform sampler2D u_sceneMaterials;
        uniform sampler2D u_spriteCoverage;
        uniform float u_separateSpriteCoverage;
        uniform vec2 u_spriteUvScale;
        uniform vec2 u_materialUvScale;
        uniform vec2 u_worldPerUv;
        uniform vec2 u_sceneExtent;

        vec3 sampleWorld(vec2 uv) {
          vec2 halfTexel=0.5/vec2(textureSize(u_texture,0));
          vec2 extent=u_materialPass>0.5 ? u_sceneExtent : vec2(1.0);
          return texture2D(u_texture,clamp(uv,halfTexel,max(halfTexel,extent-halfTexel))).rgb;
        }

        float luminance(vec3 color) {
          return dot(color, vec3(0.2126, 0.7152, 0.0722));
        }

        vec3 materialAt(vec2 uv) {
          vec3 tags=texture2D(u_sceneMaterials,clamp(uv,vec2(0.0),u_sceneExtent)*u_materialUvScale).rgb;
          if(u_separateSpriteCoverage>0.5) {
            float coverage=texture2D(u_spriteCoverage,clamp(uv,vec2(0.0),u_sceneExtent)*u_spriteUvScale).a;
            tags=mix(vec3(tags.rg,0.0),vec3(0.0,0.5,1.0),coverage);
          }
          return tags;
        }
        float unitAt(vec2 uv) { return materialAt(uv).b; }
        vec3 sceneReflection(vec2 uv,vec2 direction,float water,float unit,out float confidence) {
          confidence=0.0;
          if(u_postSSR<0.5) return vec3(0.0);
          vec2 ray=length(direction)>0.035 ? normalize(direction) : normalize(vec2(-0.55,-0.55));
          vec3 color=vec3(0.0); float sum=0.0;
          for(int i=1;i<=8;i++) {
            float n=float(i);
            if(n>u_postTraceSteps) break;
            vec2 sampleUv=uv+ray*(3.5*n+0.65*n*n)/u_worldPerUv;
            if(sampleUv.x<0.0 || sampleUv.y<0.0 || sampleUv.x>u_sceneExtent.x || sampleUv.y>u_sceneExtent.y) break;
            float targetUnit=unitAt(sampleUv);
            float targetWater=materialAt(sampleUv).r;
            float hit=water>0.5 ? max(targetUnit,1.0-targetWater) : max(targetUnit,targetWater);
            float weight=smoothstep(0.2,0.8,hit)*(1.0-n/(u_postTraceSteps+1.0));
            color+=sampleWorld(sampleUv)*weight; sum+=weight;
          }
          confidence=clamp(sum*0.9,0.0,1.0);
          return sum>0.0001 ? color/sum : vec3(0.0);
        }
        vec3 lightSurface(vec2 uv,vec3 center,vec3 north,vec3 south,vec3 east,vec3 west) {
          if(u_materialPass<0.5) return center;
          vec3 tag=materialAt(uv);
          float unit=clamp(tag.b,0.0,1.0), water=clamp(tag.r,0.0,1.0)*(1.0-unit);
          float coast=(tag.g-0.5)*12.0;
          float wetGround=(1.0-smoothstep(0.0,3.4,max(-coast,0.0)))*(1.0-water)*(1.0-unit)*u_wetStrength;
          vec2 gradient=vec2(luminance(west)-luminance(east),luminance(north)-luminance(south));
          vec3 normal=normalize(vec3(gradient*1.25,1.0));
          vec3 halfSun=normalize(normalize(vec3(-0.55,-0.55,0.82))+vec3(0.0,0.0,1.0));
          float brightSurface=smoothstep(0.06,0.55,luminance(center));
          float chroma=max(center.r,max(center.g,center.b))-min(center.r,min(center.g,center.b));
          float mineralSheen=(1.0-smoothstep(0.05,0.22,chroma))*smoothstep(0.24,0.72,luminance(center));
          float dryGroundGloss=mineralSheen*(1.0-water)*(1.0-unit)*0.10;
          float gloss=(wetGround*0.52+dryGroundGloss)*brightSurface;
          float spec=pow(max(dot(normal,halfSun),0.0),14.0);
          vec3 result=center;
          if(u_surfaceHighlights>0.5) result+=vec3(0.94,0.92,0.84)*spec*gloss*0.42*u_sunStrength;
          if(u_postSSR>0.5 && (water>0.1 || wetGround>0.02)) {
            float hit;
            vec3 reflection=sceneReflection(uv,reflect(vec3(0.0,0.0,-1.0),normal).xy,water,unit,hit);
            float reflectivity=water*0.32+wetGround*0.26;
            result=mix(result,reflection,clamp(reflectivity*hit,0.0,0.42));
          }
          return result;
        }
        float bloomEnergy(vec2 uv) {
          vec3 source=sampleWorld(uv);
          float coverage=u_materialPass>0.5 ? unitAt(uv) : 0.0;
          return max(luminance(source)-0.70,0.0)*(1.0-coverage);
        }
        vec4 rwppLightWorld(vec2 sceneUv) {
          vec2 uv = clamp(sceneUv, vec2(0.0), vec2(1.0));
          vec2 texel = 1.0 / vec2(textureSize(u_texture, 0));
          vec3 center = sampleWorld(uv);
          vec3 sourceCenter = center;
          // Sprite art has no physical normals/materials. Preserve it instead of inferring
          // a glossy surface from team colours or terrain underneath an airborne unit.
          float spriteCoverage=u_materialPass>0.5 ? clamp(unitAt(uv),0.0,1.0) : 0.0;
          if(spriteCoverage>0.999) {
            return vec4(center,1.0);
          }
          vec3 north = sampleWorld(uv + vec2(0.0, -2.0 * texel.y));
          vec3 south = sampleWorld(uv + vec2(0.0,  2.0 * texel.y));
          vec3 east  = sampleWorld(uv + vec2( 2.0 * texel.x, 0.0));
          vec3 west  = sampleWorld(uv + vec2(-2.0 * texel.x, 0.0));

          center=lightSurface(uv,center,north,south,east,west);
          float centerLuma = luminance(center);
          float neighborhoodLuma = (luminance(north) + luminance(south) +
              luminance(east) + luminance(west)) * 0.25;

          // A gentle S-curve strengthens midtones while keeping black and white anchored.
          float contrastLuma = centerLuma +
              0.40 * centerLuma * (1.0 - centerLuma) * (2.0 * centerLuma - 1.0);

          // Sharpen only local luminance edges; clamp the high-pass term to limit ringing.
          float edge = centerLuma - neighborhoodLuma;
          float edgeWeight = smoothstep(0.012, 0.10, abs(edge));
          float sharpenedLuma = contrastLuma + clamp(edge, -0.06, 0.06) * 0.14 * edgeWeight;

          // Extract highlights per tap so a bright pixel can softly lift nearby dark pixels.
          // The threshold and cap keep this local and prevent a full-screen color cast.
          float brightTaps = max(centerLuma - 0.78, 0.0) +
              max(luminance(north) - 0.78, 0.0) + max(luminance(south) - 0.78, 0.0) +
              max(luminance(east) - 0.78, 0.0) + max(luminance(west) - 0.78, 0.0);
          // A wider second ring diffuses highlights beyond the sharp sprite edge. The
          // energy cap preserves terrain texture and white/snow instead of washing them out.
          float wideBright = max(luminance(sampleWorld(uv+vec2(6.0*texel.x,0.0)))-0.70,0.0)
              + max(luminance(sampleWorld(uv-vec2(6.0*texel.x,0.0)))-0.70,0.0)
              + max(luminance(sampleWorld(uv+vec2(0.0,6.0*texel.y)))-0.70,0.0)
              + max(luminance(sampleWorld(uv-vec2(0.0,6.0*texel.y)))-0.70,0.0);
          float sunlight = clamp(u_sunStrength,0.0,3.0);
          float bloom = min((brightTaps*0.07+wideBright*0.035)*(0.6+0.4*sunlight),0.06);
          if(u_materialPass>0.5) {
            float halo=bloomEnergy(uv)*0.08
                + (bloomEnergy(uv+vec2(4.0*texel.x,0.0))+bloomEnergy(uv-vec2(4.0*texel.x,0.0))
                  +bloomEnergy(uv+vec2(0.0,4.0*texel.y))+bloomEnergy(uv-vec2(0.0,4.0*texel.y)))*0.10
                + (bloomEnergy(uv+texel*9.0)+bloomEnergy(uv-texel*9.0))*0.06;
            bloom=u_dynamicBloom>0.5 ? min(halo*sunlight,0.11) : 0.0;
          }
          // Compress exposure near white instead of clipping snow/stone texture. Keep
          // shadow contrast from the terrain pass while still lighting unit sprites.
          float exposed = clamp(sharpenedLuma,0.0,1.0);
          float exposure = 1.0+(sunlight-0.45)*0.30;
          float baseLuma = exposed*exposure/(1.0+exposed*(exposure-1.0));
          float outputLuma = baseLuma+bloom*(1.0-baseLuma);

          // Scale RGB together to retain the source hue. Cap by channel headroom so highlights
          // never clip a single channel and white stays white.
          float sourceMax = max(center.r, max(center.g, center.b));
          float scale = outputLuma / max(centerLuma, 0.0001);
          scale = min(scale, 1.0 / max(sourceMax, 0.0001));
          vec3 detailed = centerLuma > 0.0001 ? center * scale : vec3(outputLuma);
          detailed=mix(detailed,sourceCenter,spriteCoverage);
          return vec4(clamp(detailed, vec3(0.0), vec3(1.0)), 1.0);
        }
        void main() {
          gl_FragColor = rwppLightWorld(v_texCoords) * v_color;
        }
    """.trimIndent()

    /** Native post_displacement coordinates and neutral value, followed by the same world lighting.
     * screenBase is the raw world FBO, not the already lit screen: apply lighting exactly once.
     * Material lookups use the displaced UV too, so water/sprite coverage follows the wave.
     */
    internal val DISPLACEMENT_FRAGMENT_SHADER: String = FRAGMENT_SHADER
        .substringBefore("void main() {")
        .replace(Regex("\\bu_texture\\b"), "screenBase") + """
        uniform sampler2D u_texture;
        uniform vec2 screenBaseSize;
        uniform vec2 u_resolution;
        uniform float u_offsetBy;
        uniform float u_uiScaling;
        void main() {
          vec2 usedScreenSize=u_resolution/screenBaseSize;
          vec2 screenUV=gl_FragCoord.xy/(u_resolution*u_uiScaling);
          screenUV.y=1.0-screenUV.y;
          screenUV*=usedScreenSize;
          vec4 spriteColor=texture2D(u_texture,v_texCoords);
          vec2 screenOffset=u_offsetBy*(spriteColor.xy-vec2(128.0/255.0))*spriteColor.a*v_color.a;
          vec2 offsetScreenUV=clamp(screenUV+screenOffset,vec2(0.0),usedScreenSize);
          gl_FragColor=rwppLightWorld(offsetScreenUV);
        }
    """.trimIndent()
}
