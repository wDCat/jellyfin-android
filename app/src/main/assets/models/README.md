# Web-owned mpv enhancement shaders

`buildMpvShaderPlan(effects, { source, target })` translates the existing Web
menu's effect references to ordered `{ id, source }` entries. The desktop bridge
only caches their contents and sets mpv's shader list. Algorithms, model weights,
CRT controls, stage selection and resolution normalization remain in Web.

`shaders/` contains the exact original 76 GLSL files, pinned and SHA-256 checked
against `source-lock.json` (copied from NijiLucid's reference source lock). Their
upstream licenses are retained in `licenses/`. CuNNy is LGPL-3.0-or-later; other
model families are MIT. These readable original sources, the lock, and adaptation
code are shipped under `licenses/nijilucid-mpv/` so corresponding source is
available alongside the deployed application.

At runtime the exporter adapts scheduling and texture names, without changing
model weights: optional f16 branches select the upstream portable f32 fallback,
large fragment passes use compute to avoid OpenGL vertex attribute limits,
every selected stage runs on `MAIN`; each instance receives its
own saved texture namespace; upstream target-dependent skip conditions are
removed. Luma models receive BT.709 luma extracted from the current RGB image and
are recomposed into RGB before the next effect. This is necessary because mpv's
native `LUMA` hook precedes `MAIN` regardless of shader list order. Intermediate
linear resizes follow NijiLucid's chain compiler, with a final resize to the
requested target. The CRT GLSL is a direct port of the existing WGSL, using the
same normalized settings.

mpv's color conversion and GPU arithmetic can differ from browser WebGPU.
Identical model weights do not establish pixel-identical rendering. The plan
builder rejects unknown effects, invalid dimensions, and bridge size limits
instead of silently removing requested stages.
