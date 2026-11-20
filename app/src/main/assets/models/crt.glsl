//!DESC NijiLucid CRT Retro (ported from engines/core/shaders/crt.wgsl)
//!HOOK MAIN
//!BIND MAIN
//!WIDTH @w@
//!HEIGHT @h@
vec3 sampleColor(vec2 uv) { return MAIN_tex(uv).rgb; }
vec4 hook() {
    vec2 size = vec2(@w@.0, @h@.0);
    vec2 screen = MAIN_pos;
    vec2 centered = screen * 2.0 - 1.0;
    vec2 warped = centered * (vec2(1.0) + @curvature@ * centered.yx * centered.yx);
    vec2 uv = warped * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0, 0.0, 0.0, 1.0);
    vec2 texel = MAIN_pt;
    float offset = texel.x * @chromaticAberration@;
    vec3 color = vec3(sampleColor(uv + vec2(offset, 0.0)).r, sampleColor(uv).g, sampleColor(uv - vec2(offset, 0.0)).b);
    vec2 radius = texel * @glowRadius@;
    vec3 glow = (sampleColor(uv + vec2(radius.x, 0.0)) + sampleColor(uv - vec2(radius.x, 0.0)) + sampleColor(uv + vec2(0.0, radius.y)) + sampleColor(uv - vec2(0.0, radius.y))) * 0.25;
    float lines = min(@scanlineCount@, min(MAIN_size.y, size.y * 0.5));
    float scanline = 1.0 - @scanlineStrength@ * (0.5 - 0.5 * cos(uv.y * lines * 6.28318530718));
    ivec2 cell = ivec2(floor(screen * size) / @maskSize@);
    bool shadow = @maskType@ > 0.5 && @maskType@ < 1.5;
    int stripe = (cell.x + (shadow ? (cell.y / 2) % 2 : 0)) % 3;
    float dim = 1.0 - @maskStrength@;
    vec3 phosphor = stripe == 0 ? vec3(1.0, dim, dim) : (stripe == 2 ? vec3(dim, dim, 1.0) : vec3(dim, 1.0, dim));
    if (shadow && cell.y % 3 == 2) phosphor *= 1.0 - @maskStrength@ * 0.5;
    if (@maskType@ > 1.5) phosphor = vec3(1.0);
    float vignette = max(0.0, 1.0 - @vignette@ * dot(centered * centered, centered * centered));
    vec3 softened = mix(color, glow, @glowStrength@);
    float luma = dot(softened, vec3(0.2126, 0.7152, 0.0722));
    vec3 saturated = mix(vec3(luma), softened, @saturation@);
    vec3 graded = (saturated - 0.5) * @contrast@ + 0.5;
    return vec4(clamp(graded * scanline * phosphor * vignette * @brightness@, 0.0, 1.0), 1.0);
}
