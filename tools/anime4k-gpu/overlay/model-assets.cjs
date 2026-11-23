// Use the same catalog, shader adaptation, CRT template and pinned sources as jellyfin-web.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const Module = require('node:module');
const splitWidePasses = require('./split-wide-passes.cjs');
const [root, output] = process.argv.slice(2).map(p => path.resolve(p));
const base = path.join(root, 'src/lib/nijilucid');
const out = path.join(output, 'models');
async function main() {
    const esbuild = require(require.resolve('esbuild', { paths: [root] }));
    const bundle = await esbuild.build({
        stdin: { contents: `export * from './core/effects/registry'; export * from './mpv';
            export * from './mpv/sources'; export * from './engines/core/crt-settings';`, resolveDir: base },
        bundle: true, write: false, platform: 'node', format: 'cjs',
        plugins: [{ name: 'pinned-glsl', setup(build) {
            build.onResolve({ filter: /\.glsl\?raw$/ }, args => ({
                path: path.resolve(args.resolveDir, args.path.slice(0, -4)), namespace: 'shader'
            }));
            build.onLoad({ filter: /.*/, namespace: 'shader' }, args => ({
                contents: fs.readFileSync(args.path, 'utf8'), loader: 'text'
            }));
        }}]
    });
    const mod = new Module('catalog');
    mod._compile(bundle.outputFiles[0].text, 'catalog.cjs');
    const api = mod.exports;
    fs.mkdirSync(out, { recursive: true });
    const lock = JSON.parse(fs.readFileSync(path.join(base, 'mpv/source-lock.json')));
    for (const [family, entry] of Object.entries(lock.targets)) for (const file of entry.includedFiles) {
        if (!file.path.endsWith('.glsl')) continue;
        const data = fs.readFileSync(path.join(base, 'mpv/shaders', family, file.path));
        if (crypto.createHash('sha256').update(data).digest('hex') !== file.sha256) throw Error(file.path);
    }
    const catalog = {};
    for (const model of api.listEffectDescriptors(true)) {
        if (model.backendId === 'core') continue;
        const loader = api.sourceLoaders[model.backendId + ':' + model.key];
        if (!loader) throw Error('Missing source: ' + model.id);
        const file = model.backendId + '-' + model.key + '.glsl';
        let shader = api.adaptMpvShader((await loader()).default, 0, model.backendId !== 'anime4k');
        if (model.id.includes('/C4F32') || model.id === 'cunny/Upscale/DS/8x32') shader = splitWidePasses(shader);
        if (model.id.startsWith('acnet/Upscale/Legacy/')) shader = shader.replaceAll('float result = 0;', 'float result = 0.0;');
        fs.writeFileSync(path.join(out, file), shader);
        catalog[model.id] = { file, scale: model.dimensionBehavior.scale ?? 1 };
    }
    fs.writeFileSync(path.join(out, 'catalog.json'), JSON.stringify(catalog));
    const anime4kModes = ['A', 'B', 'C', 'A+A', 'B+B', 'C+A'];
    const tiers = ['performance', 'balanced', 'quality', 'ultra'];
    const presets = Object.fromEntries(anime4kModes.map(mode => [
        mode,
        Object.fromEntries(tiers.map(tier => [
            tier, api.resolvePresetEffects('anime4k', mode, tier).map(effect => effect.id)
        ]))
    ]));
    fs.writeFileSync(path.join(out, 'presets.json'), JSON.stringify(presets));
    fs.writeFileSync(path.join(out, 'crt.json'), JSON.stringify(api.CRT_CONTROLS));
    fs.writeFileSync(path.join(out, 'crt.glsl'), api.crtShaderSource(
        Object.fromEntries(Object.keys(api.CRT_CONTROLS).map(k => [k, '@'+k+'@'])), { width:'@w@',height:'@h@' }));
    for (const file of ['licenses','source-lock.json','README.md']) {
        fs.cpSync(path.join(base, 'mpv', file), path.join(out, file), { recursive: true });
    }
    fs.copyFileSync(path.join(base, 'licenses/LGPL-3.0-or-later.txt'), path.join(out, 'licenses/LGPL-3.0-or-later.txt'));
    console.log('Packaged ' + Object.keys(catalog).length + ' GPU models.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
