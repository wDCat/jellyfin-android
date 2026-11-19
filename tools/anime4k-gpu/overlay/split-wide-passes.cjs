// Each upstream work item computes eight independent output vec4s. Compiling that
// monolithic kernel can take longer than the clip on mobile GL. Dispatch each
// output separately and restore the upstream packed layout for the next layer.
module.exports = function splitWidePasses(source) {
    return source.split(/(?=^\/\/!DESC)/m).map((pass, layer) => {
        const stores = [...pass.matchAll(/^\s*imageStore\(out_image, (?:output_base|opos) \+ ivec2\((\d+), (\d+)\), (.*)\);$/gm)];
        if (stores.length !== 8 || pass.length < 60000) return pass;
        const save = pass.match(/^\/\/!SAVE (\w+)/m)[1];
        const desc = pass.match(/^\/\/!DESC (.+)/m)[1];
        const [width, height] = ['WIDTH', 'HEIGHT'].map(k => pass.match(new RegExp('^//!' + k + ' (.+)', 'm'))[1].trim());
        const local = pass.match(/^\/\/!COMPUTE \d+ \d+ (\d+) (\d+)/m);
        const px = Math.max(...stores.map(s => Number(s[1]))) + 1;
        const py = Math.max(...stores.map(s => Number(s[2]))) + 1;
        const planes = stores.map((s, n) => ({
            name: `NIJI_0_GROUP_${layer}_${n}`, x: Number(s[1]), y: Number(s[2]),
            result: s[3].match(/\b(?:result|r)\d+\b/)[0]
        }));
        const stages = planes.map(plane => {
            let shader = pass.replace('//!SAVE ' + save, '//!SAVE ' + plane.name)
                .replace(/^\/\/!COMPUTE .+$/m, `//!COMPUTE ${local[1]} ${local[2]} ${local[1]} ${local[2]}`)
                .replace(/^\/\/!WIDTH .+$/m, '//!WIDTH ' + width + ' ' + px + ' /')
                .replace(/^\/\/!HEIGHT .+$/m, '//!HEIGHT ' + height + ' ' + py + ' /')
                .replace(/^\s*((?:result|r)\d+) \+= [^;]+;$/gm, (line, result) => result === plane.result ? line : '');
            for (const store of stores) {
                shader = shader.replace(store[0], Number(store[1]) === plane.x && Number(store[2]) === plane.y
                    ? '\n    imageStore(out_image, ivec2(gl_GlobalInvocationID.xy), ' + store[3] + ');' : '');
            }
            return shader;
        });
        stages.push(`//!DESC ${desc} pack
//!HOOK MAIN
//!COMPUTE 8 8
${planes.map(p => '//!BIND ' + p.name).join('\n')}
//!SAVE ${save}
//!WIDTH ${width}
//!HEIGHT ${height}
//!COMPONENTS 4
void hook() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(out_image)))) return;
    ivec2 src = p / ivec2(${px}, ${py});
    int lane = p.x % ${px} + (p.y % ${py}) * ${px};
    vec4 value = vec4(0.0);
    ${planes.map(t => `if (lane == ${t.x+t.y*px}) value = ${t.name}_mul * texelFetch(${t.name}_raw, src, 0);`).join('\n    ')}
    imageStore(out_image, p, value);
}
`);
        return stages.join('\n');
    }).join('');
};
