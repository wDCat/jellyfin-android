#!/usr/bin/env python3
"""Turn NijiLucid conv shaders into NNAPI graphs. Unparsed effects stay on the GPU."""
import json
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WEB = ROOT.parent / "jellyfin-web" / "src" / "lib" / "nijilucid" / "engines"
NUM = r"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?"
LUMA = (0.2126, 0.7152, 0.0722, 0.0)
CONV, DEPTH, CRELU, PRELU, ADD, CONCAT, CLAMP = range(1, 8)
COMP_DIRECT, COMP_BROADCAST, COMP_CHROMA, COMP_RESTORE = 0, 1, 2, 3


def nums(text):
    return [float(v) for v in re.findall(NUM, text)]


def mat_oc_ic(values):
    table = [[0.0] * 4 for _ in range(4)]
    for col in range(4):
        for row in range(4):
            table[row][col] = values[col * 4 + row]
    return table


class Conv:
    def __init__(self, inc, outc, kh, kw):
        self.inc, self.outc, self.kh, self.kw = inc, outc, kh, kw
        self.w = [0.0] * (outc * kh * kw * inc)
        self.bias = [0.0] * outc
        self.relu = False
        self.prelu = None
        self.clamp = False
        self.residual = False
        self.luma_residual = False
        self.outputs = []
        self.input_bindings = None

    def put(self, oc, ky, kx, ic, value):
        if not (0 <= oc < self.outc and 0 <= ic < self.inc and 0 <= ky < self.kh and 0 <= kx < self.kw):
            raise ValueError(f"weight {oc},{ky},{kx},{ic} outside {self.outc}x{self.kh}x{self.kw}x{self.inc}")
        self.w[(((oc * self.kh + ky) * self.kw + kx) * self.inc) + ic] = value


def origin(offsets):
    xs, ys = [p[0] for p in offsets], [p[1] for p in offsets]
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def expand_luma(conv):
    if conv.inc != 1:
        return conv
    grown = Conv(4, conv.outc, conv.kh, conv.kw)
    grown.bias, grown.relu, grown.prelu = conv.bias[:], conv.relu, conv.prelu
    grown.clamp, grown.residual, grown.outputs = conv.clamp, conv.residual, conv.outputs
    grown.luma_residual = conv.luma_residual
    grown.input_bindings = conv.input_bindings
    for oc in range(conv.outc):
        for ky in range(conv.kh):
            for kx in range(conv.kw):
                weight = conv.w[((oc * conv.kh + ky) * conv.kw + kx)]
                for ic, coeff in enumerate(LUMA):
                    grown.put(oc, ky, kx, ic, weight * coeff)
    return grown


def parse_anime4k(text):
    samples = {}
    for name, body in re.findall(r"fn (go?_\d+)\([^)]*\) -> vec4f \{([\s\S]*?)\n\}", text):
        load = re.search(r"(-?)(?:anime4kTextureLoadClamped|textureLoad|textureSampleLevel)\((\w+)_tex,", body)
        if not load:
            raise ValueError(f"unrecognized sample {name}")
        source = load[2]
        activation = ("negative" if load[1] else "positive") if "max4" in body else "raw"
        samples[name] = source, activation
    body = text[text.find("fn computeMain"):]
    terms = re.findall(
        r"result(?:: vec4f)? (?:\+=|=) mat4x4<f32>\(([^)]*)\) \* (go?_\d+)\(pixel\.xy(?:, (-?\d+), (-?\d+))?\);",
        body,
    )
    if not terms or len(terms) != body.count("mat4x4<f32>("):
        raise ValueError("unrecognized convolution")
    offsets = [(int(x or 0), int(y or 0)) for _, _, x, y in terms]
    minx, miny, kw, kh = origin(offsets)
    if (minx, miny) != (-(kw // 2), -(kh // 2)) or kw % 2 == 0 or kh % 2 == 0:
        raise ValueError("asymmetric convolution")
    sources = {}
    for source, activation in samples.values():
        sources.setdefault(source, set()).add(activation)
    bound = list(sources)
    channels = {}
    inc = 0
    for source in bound:
        if sources[source] == {"raw"}:
            channels[source, "raw"] = inc
            inc += 4
        elif sources[source] == {"positive", "negative"}:
            channels[source, "positive"] = inc
            channels[source, "negative"] = inc + 4
            inc += 8
        else:
            raise ValueError(f"unsupported activation for {source}")
    conv = Conv(inc, 4, kh, kw)
    for (coeffs, go, _, _), (x, y) in zip(terms, offsets):
        block = channels[samples[go]]
        for row, cols in enumerate(mat_oc_ic(nums(coeffs))):
            for col, value in enumerate(cols):
                conv.put(row, y - miny, x - minx, block + col, value)
    bias = re.findall(rf"result \+= vec4f\(({NUM}(?:, {NUM}){{3}})\)", body)
    if bias:
        conv.bias = nums(bias[-1])[:4]
    output = re.search(r"var (\w+)_tex: texture_storage_2d", text)
    if not output:
        raise ValueError("missing convolution output")
    conv.outputs = [output[1]]
    return conv, bound, sources


def fold_crelu(conv):
    folded = Conv(conv.inc, 2 * conv.outc, conv.kh, conv.kw)
    folded.w = conv.w + [-weight for weight in conv.w]
    folded.bias = conv.bias + [-bias for bias in conv.bias]
    folded.relu = True
    folded.outputs = conv.outputs
    return folded


def pack_rgb_heads(heads):
    first = heads[0]
    if any((conv.inc, conv.kh, conv.kw, conv.outc) != (first.inc, first.kh, first.kw, 4) for conv in heads):
        raise ValueError("incompatible RGB heads")
    packed = Conv(first.inc, 12, first.kh, first.kw)
    group_size = len(first.w) // first.outc
    packed.w = []
    packed.bias = []
    for lane in range(4):
        for conv in heads:
            packed.w.extend(conv.w[lane * group_size:(lane + 1) * group_size])
            packed.bias.append(conv.bias[lane])
    packed.outputs = ["packed_rgb"]
    return packed


def parse_legacy_shuffle(text):
    lanes = re.findall(r"if \(lane == (\d)u\) \{([^}]+)\}", text)
    if len(lanes) != 4:
        return None
    conv = Conv(8, 4, 1, 1)
    for lane, body in lanes:
        terms = re.findall(r"result \+= dot\(vec4f\(([^)]*)\), r([01])\);", body)
        if len(terms) != 2:
            return None
        for weights, index in terms:
            for channel, weight in enumerate(nums(weights)):
                conv.put(int(lane), 0, 0, int(index) * 4 + channel, weight)
    conv.clamp = True
    return conv


def parse_generated(text):
    if "let lane" in text and "result +=" not in text and "r0 +=" not in text:
        return "shuffle"
    body = text[text.find("fn computeMain"):]
    # A variable may be reassigned. Record each multiply against the latest assignment by scanning lines.
    samples = {}
    channel_of = {}
    next_channel = 0
    taps = []
    results = set()
    for line in body.splitlines():
        assigned = re.match(
            r"\s*(\w+)\s*=\s*(sample_\w+)\(pixel\.xy, vec2i\((-?\d+), (-?\d+)\)(?:, vec2i\((-?\d+), (-?\d+)\))?",
            line,
        )
        if assigned:
            var, fn, x, y, lane_x, lane_y = assigned.groups()
            key = (fn, int(lane_x or 0), int(lane_y or 0))
            if key not in channel_of:
                width = 1 if fn.endswith("_f32") or fn.endswith("luma") else 4
                channel_of[key] = next_channel
                next_channel += width
            samples[var] = (channel_of[key], int(x), int(y), 1 if fn.endswith("_f32") else 4)
            continue
        term = re.search(rf"(result\d*|r\d+) \+= (?:mat4x4<f32>\(([^)]*)\)|vec4f\(([^)]*)\)) \* (\w+)", line)
        if not term:
            continue
        dest, matrix, vector, var = term.groups()
        if var not in samples and not re.fullmatch(r"inp_\d+_\d+_\d+", var):
            continue
        results.add(dest)
        taps.append((dest, matrix, vector, var))
    inp_terms = re.findall(rf"(result\d*) \+= (?:mat4x4<f32>\(([^)]*)\)|vec4f\(([^)]*)\)) \* (inp_\d+_\d+_\d+)", body)
    for dest, matrix, vector, var in inp_terms:
        results.add(dest)
        taps.append((dest, matrix or None, vector, var))
    if not taps:
        inline_vec = re.findall(
            rf"(result\d*|r\d+) \+= vec4f\(([^)]*)\) \* (sample_\w+)\([^)]*vec2i\((-?\d+), (-?\d+)\)[^)]*\)\.x",
            body,
        )
        inline_mat = re.findall(
            rf"(result\d*|r\d+) \+= mat4x4<f32>\(([^)]*)\) \* (sample_\w+)\(pixel\.xy, vec2i\((-?\d+), (-?\d+)\)\)",
            body,
        )
        if not inline_vec and not inline_mat:
            return None
        return parse_inline(body, inline_vec, inline_mat)
    offsets = []
    groups = set()
    for _, _, _, var in taps:
        if var.startswith("inp_"):
            _, g, x, y = var.split("_")
            offsets.append((int(x) - 1, int(y) - 1))
            groups.add(int(g))
        else:
            _, x, y, _ = samples[var]
            offsets.append((x, y))
    minx, miny, kw, kh = origin(offsets)
    inc = 4 * (max(groups) + 1) if groups else max(1, next_channel)
    order = sorted(results, key=lambda name: int(re.search(r"\d+", name).group(0)) if re.search(r"\d+", name) else 0)
    base = {name: i * 4 for i, name in enumerate(order)}
    conv = Conv(inc, 4 * len(results), kh, kw)
    if channel_of and not groups:
        conv.input_bindings = list(dict.fromkeys(
            fn.removeprefix("sample_").removesuffix("_vec4").removesuffix("_f32")
            for fn, _, _ in channel_of
        ))
    conv.residual = "textureLoad(input0_tex" in text and "+ textureLoad(input1_tex" in text
    prelu = re.search(rf"vec4f\(({NUM}(?:, {NUM}){{3}})\) \* min\(result, vec4f\(0\.0\)\)", body)
    if prelu:
        conv.prelu = nums(prelu.group(1))[:4]
    elif re.search(r"max\(result\d*, vec4f\(0\.0\)\)", body):
        conv.relu = True
    if re.search(r"clamp\(r\d+, vec4f\(0\.0\), vec4f\(1\.0\)\)", body):
        conv.clamp = True
    conv.luma_residual = "result + vec4f(sample_LUMA" in body
    for dest, matrix, vector, var in taps:
        if var.startswith("inp_"):
            _, g, x, y = var.split("_")
            ic, kx, ky = int(g) * 4, int(x) - 1 - minx, int(y) - 1 - miny
        else:
            ic, x, y, _ = samples[var]
            kx, ky = x - minx, y - miny
        if matrix:
            for row, cols in enumerate(mat_oc_ic(nums(matrix))):
                for col, value in enumerate(cols):
                    conv.put(base[dest] + row, ky, kx, ic + col, value)
        else:
            for i, value in enumerate(nums(vector)[:4]):
                conv.put(base[dest] + i, ky, kx, ic, value)
    for dest, coeffs in re.findall(rf"(?:var )?(result\d*|r\d+)(?:: vec4f)? = vec4f\(({NUM}(?:, {NUM}){{3}})\)", body):
        if dest in base:
            for i, value in enumerate(nums(coeffs)[:4]):
                conv.bias[base[dest] + i] = value
    for dest, coeffs in re.findall(rf"(r\d+|result\d*) \+= vec4f\(({NUM}(?:, {NUM}){{3}})\);", body):
        if dest in base:
            for i, value in enumerate(nums(coeffs)[:4]):
                conv.bias[base[dest] + i] += value
    return conv


def parse_inline(body, inline_vec, inline_mat):
    results = {row[0] for row in inline_vec + inline_mat}
    offsets = [(int(x), int(y)) for *_, x, y in inline_vec + inline_mat]
    minx, miny, kw, kh = origin(offsets)
    scalars = []
    vectors = []
    for _, _, fn, _, _ in inline_vec:
        if fn not in scalars:
            scalars.append(fn)
    for _, _, fn, _, _ in inline_mat:
        if fn not in vectors:
            vectors.append(fn)
    inc = len(scalars) + 4 * len(vectors)
    order = sorted(results, key=lambda name: int(re.search(r"\d+", name).group(0)) if re.search(r"\d+", name) else 0)
    base = {name: i * 4 for i, name in enumerate(order)}
    conv = Conv(inc, 4 * len(results), kh, kw)
    conv.input_bindings = [fn.removeprefix("sample_") for fn in scalars + vectors]
    prelu = re.search(rf"vec4f\(({NUM}(?:, {NUM}){{3}})\) \* min\(result, vec4f\(0\.0\)\)", body)
    if prelu:
        conv.prelu = nums(prelu.group(1))[:4]
    elif re.search(r"max\(result\d*, vec4f\(0\.0\)\)", body):
        conv.relu = True
    for dest, coeffs, fn, x, y in inline_vec:
        ic = scalars.index(fn)
        for i, value in enumerate(nums(coeffs)[:4]):
            conv.put(base[dest] + i, int(y) - miny, int(x) - minx, ic, value)
    for dest, coeffs, fn, x, y in inline_mat:
        ic = len(scalars) + vectors.index(fn) * 4
        for row, cols in enumerate(mat_oc_ic(nums(coeffs))):
            for col, value in enumerate(cols):
                conv.put(base[dest] + row, int(y) - miny, int(x) - minx, ic + col, value)
    bias = re.findall(rf"var result(\d*)(?:: vec4f)? = vec4f\(({NUM}(?:, {NUM}){{3}})\)", body)
    for tag, coeffs in bias:
        dest = "result" + tag
        if dest in base:
            for i, value in enumerate(nums(coeffs)[:4]):
                conv.bias[base[dest] + i] = value
    return conv


def f32s(values):
    return struct.pack(f"<{len(values)}f", *values)


def pack(model_id, ops, composite, scale):
    name = model_id.encode()
    blob = bytearray(b"JNPU")
    blob += struct.pack("<6I", 1, composite, 4, ops[-1][2], scale, len(ops))
    blob += struct.pack("<H", len(name)) + name
    for typ, inc, outc, kh, kw, fuse, inputs, payload in ops:
        blob += struct.pack("<8I", typ, inc, outc, kh, kw, fuse, len(inputs), len(payload) // 4)
        blob += struct.pack(f"<{len(inputs)}I", *inputs) + payload
    return bytes(blob)


def emit(ops, typ, inc, outc, kh, kw, fuse, inputs, payload):
    ops.append((typ, inc, outc, kh, kw, fuse, inputs, payload))
    return len(ops)


def luma_broadcast(outc):
    conv = Conv(4, outc, 1, 1)
    for oc in range(outc):
        for ic, coeff in enumerate(LUMA):
            conv.put(oc, 0, 0, ic, coeff)
    return conv


def assemble(model_id, seq, composite, scale):
    tensors, ops, last = {}, [], 0
    concatenations = {}

    def channels(src):
        return 4 if src == 0 else ops[src - 1][2]

    for kind, conv, bound in seq:
        if kind == "shuffle":
            src = tensors.get(bound[0], last)
            inc = channels(src)
            if inc % 4:
                return None, f"shuffle channels {inc}"
            last = emit(ops, DEPTH, inc, inc // 4, 2, 2, 0, [src], b"")
            continue
        names = [name for name in bound if not (conv.luma_residual and name == "LUMA")]
        srcs = []
        for name in names:
            srcs.append(0 if name in ("LUMA", "MAIN", "input") else tensors.get(name))
            if srcs[-1] is None:
                return None, f"missing {name}"
        if conv.residual:
            if len(srcs) < 2:
                return None, "residual"
            last = emit(ops, ADD, conv.inc, conv.inc, 1, 1, 0, srcs[:2], b"")
            srcs = [last]
        elif len(srcs) > 1:
            total = sum(channels(src) for src in srcs)
            if total != conv.inc:
                return None, f"{conv.outputs}: bindings have {total} channels, convolution expects {conv.inc}"
            key = tuple(srcs)
            if key not in concatenations:
                while len(srcs) > 1:
                    group, srcs = srcs[:8], srcs[8:]
                    channels_in_group = sum(channels(src) for src in group)
                    joined = emit(ops, CONCAT, channels_in_group, channels_in_group, 1, 1, 0, group, b"")
                    srcs.insert(0, joined)
                concatenations[key] = srcs[0]
            srcs = [concatenations[key]]
        if len(srcs) != 1 or channels(srcs[0]) != conv.inc:
            return None, f"{conv.outputs}: convolution input size mismatch"
        if conv.prelu:
            # PReLU(x) is relu(x) - alpha * relu(-x). Fold both
            # signs into the preceding convolution and mix them per channel.
            signed = fold_crelu(conv)
            last = emit(ops, CONV, signed.inc, signed.outc, signed.kh, signed.kw, 1, srcs,
                        f32s(signed.w + signed.bias))
            mix = Conv(signed.outc, conv.outc, 1, 1)
            for channel, alpha in enumerate(conv.prelu):
                mix.put(channel, 0, 0, channel, 1.0)
                mix.put(channel, 0, 0, channel + conv.outc, -alpha)
            last = emit(ops, CONV, mix.inc, mix.outc, 1, 1, 0, [last], f32s(mix.w + mix.bias))
        else:
            last = emit(ops, CONV, conv.inc, conv.outc, conv.kh, conv.kw, int(conv.relu), srcs,
                        f32s(conv.w + conv.bias))
        if conv.clamp:
            last = emit(ops, CLAMP, conv.outc, conv.outc, 1, 1, 0, [last], b"")
        if conv.luma_residual:
            broadcast = luma_broadcast(conv.outc)
            added = emit(ops, CONV, 4, conv.outc, 1, 1, 0, [0], f32s(broadcast.w + broadcast.bias))
            last = emit(ops, ADD, conv.outc, conv.outc, 1, 1, 0, [last, added], b"")
        for output in conv.outputs:
            tensors[output] = last
    return (pack(model_id, ops, composite, scale), None) if ops else (None, "empty")


def shader_file(index: Path, symbol: str):
    match = re.search(rf"import {symbol} from '\./shaders/([^']+)'", index.read_text())
    return None if not match else index.parent / "shaders" / match.group(1)


def parse_index_model(model_id, index, kind, scale_hint):
    text = index.read_text()
    stages = re.findall(
        r'name: "([^"]+)"[\s\S]*?shaderWGSL: (\w+),[\s\S]*?bindings: \[([^\]]*)\][\s\S]*?outputName: "([^"]+)"',
        text,
    )
    if not stages:
        return None, "no stages"
    seq, scale = [], scale_hint
    luma_upscale_residual = False
    for name, symbol, bindings, output in stages:
        path = shader_file(index, symbol)
        text_shader = path.read_text()
        legacy_shuffle = parse_legacy_shuffle(text_shader) if "let lane" in text_shader else None
        parsed = legacy_shuffle or parse_generated(text_shader)
        bound = re.findall(r'"([^"]+)"', bindings)
        luma_upscale_residual |= " + sample_original_luma(" in text_shader
        if isinstance(parsed, Conv) and "result + vec4f(sample_LUMA" in text_shader:
            parsed.luma_residual = True
        if isinstance(parsed, Conv) and parsed.inc == 1 and set(bound) <= {"LUMA", "MAIN", "input"}:
            parsed = expand_luma(parsed)
        if parsed == "shuffle" or "pixelshuff" in name or "out-shuffle" in name and parsed == "shuffle":
            seq.append(("shuffle", None, bound[:1]))
            scale = 2
            continue
        if not isinstance(parsed, Conv):
            return None, f"unparsed {path.name}"
        if parsed.input_bindings and not parsed.residual:
            if any(source not in bound for source in parsed.input_bindings):
                return None, f"{path.name}: shader uses unexpected bindings {parsed.input_bindings}"
            bound = parsed.input_bindings
        parsed.outputs = [output]
        seq.append(("conv", parsed, bound))
        if legacy_shuffle:
            seq.append(("shuffle", None, [output]))
            scale = 2
        if "pixelshuff" in name or output.startswith("__FINAL") and "2" in name:
            scale = max(scale, 1)
        if path and "outBase + vec2i(1, 0)" in path.read_text() and "vec2i(2, 2)" in path.read_text():
            seq.append(("shuffle", None, [output]))
            scale = 2
    composite = COMP_CHROMA if kind == "luma" and scale == 2 else COMP_DIRECT
    if luma_upscale_residual:
        composite = COMP_BROADCAST
    return assemble(model_id, seq, composite, scale)


def artcnn_models():
    mapping = {
        "C4F16": "artcnn/Upscale/C4F16",
        "C4F16DS": "artcnn/Upscale/C4F16_DS",
        "C4F16DN": "artcnn/Upscale/C4F16_DN",
        "C4F32": "artcnn/Upscale/C4F32",
        "C4F32DS": "artcnn/Upscale/C4F32_DS",
        "C4F32DN": "artcnn/Upscale/C4F32_DN",
    }
    models = []
    for folder, model_id in mapping.items():
        root = WEB / "artcnn" / "pipelines" / "upscale" / folder
        if (root / "shaders" / "stage0.wgsl").is_file():
            models.append((model_id, root))
    return models


def parse_artcnn(model_id, root):
    seq = []
    for i in range(7):
        parsed = parse_generated((root / "shaders" / f"stage{i}.wgsl").read_text())
        if not isinstance(parsed, Conv):
            return None, f"stage{i}"
        if i == 0:
            parsed = expand_luma(parsed)
        parsed.outputs = [f"stage{i}"]
        parsed.residual = i == 6
        bound = ["LUMA"] if i == 0 else ["stage0", "stage5"] if i == 6 else [f"stage{i - 1}"]
        seq.append(("conv", parsed, bound))
    seq.append(("shuffle", None, ["stage6"]))
    return assemble(model_id, seq, COMP_CHROMA, 2)


def generated_ids():
    metas = {}
    for models in WEB.glob("*/generated/models.ts"):
        for block in re.finditer(r"\{[^{}]*\}", models.read_text(), re.S):
            ident, directory = re.search(r'"id": "([^"]+)"', block.group(0)), re.search(r'"directory": "([^"]+)"', block.group(0))
            if ident and directory:
                metas[directory.group(1)] = ident.group(1)
    found = []
    for index in list((WEB / "acnet" / "generated").glob("*/index.ts")) + list((WEB / "cunny" / "generated").glob("*/index.ts")):
        if index.parent.name in metas:
            found.append((metas[index.parent.name], index, "luma"))
    return found


def anime4k_models():
    catalog = (WEB / "anime4k" / "catalog.ts").read_text()
    found = []
    for model_id, key in re.findall(r"id: '(anime4k/[^']+)'[\s\S]*?key: '([^']+)'", catalog):
        if key in ("DoG", "BilateralMean", "ClampHighlights"):
            continue
        for candidate in (WEB / "anime4k" / "pipelines").rglob(key):
            if (candidate / "graph.ts").is_file():
                found.append((model_id, candidate))
                break
    return found


def parse_anime4k_model(model_id, folder):
    text = (folder / "graph.ts").read_text()
    imports = dict(re.findall(r"import (\w+) from '\./shaders/([^']+)'", text))
    upscale = "/Upscale/" in model_id
    half_residual = "fragmentWGSL: overlayHalfResidualWGSL" in text
    if "fragmentWGSL:" in text and not half_residual:
        return None, "multi-resolution render stages"
    head_list = re.search(r"headShaders: \[([^\]]+)\]", text)
    head_one = re.search(r"headShader: (\w+)", text)
    if head_list:
        head_names = re.findall(r"\w+", head_list[1])
    elif head_one:
        head_names = [head_one[1]]
    elif half_residual:
        head_names = ["outputWGSL"]
    else:
        return None, "no model heads"
    try:
        by_name, by_output, activation = {}, {}, {}
        for symbol, file in imports.items():
            if symbol == "overlayHalfResidualWGSL":
                continue
            conv, bound, sources = parse_anime4k((folder / "shaders" / file).read_text())
            by_name[symbol] = conv
            by_output[conv.outputs[0]] = conv, bound
            for source, modes in sources.items():
                if source in activation and modes != activation[source]:
                    raise ValueError(f"inconsistent activation for {source}")
                activation[source] = modes

        heads = [by_name[name] for name in head_names]
        if len(heads) == 3 and upscale:
            bound = by_output[heads[0].outputs[0]][1]
            if any(by_output[head.outputs[0]][1] != bound for head in heads):
                raise ValueError("different RGB head bindings")
            head = pack_rgb_heads(heads)
            by_output[head.outputs[0]] = head, bound
        elif len(heads) == 1:
            head = heads[0]
        else:
            raise ValueError("unexpected number of model heads")

        seq, emitted = [], {"MAIN"}

        def visit(output):
            if output in emitted:
                return
            conv, bound = by_output[output]
            for source in bound:
                visit(source)
            if activation.get(output) == {"positive", "negative"}:
                conv = fold_crelu(conv)
            seq.append(("conv", conv, bound))
            emitted.add(output)

        if activation.get("MAIN") != {"raw"}:
            raise ValueError("unexpected frame input activation")
        if half_residual:
            head.w = [weight * 0.5 for weight in head.w]
            head.bias = [bias * 0.5 for bias in head.bias]
        visit(head.outputs[0])
        if upscale:
            seq.append(("shuffle", None, head.outputs))
        composite = COMP_BROADCAST if upscale and len(heads) == 1 else COMP_RESTORE
        return assemble(model_id, seq, composite, 2 if upscale else 1)
    except (KeyError, ValueError) as error:
        return None, str(error)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/npu-graphs")
    out.mkdir(parents=True, exist_ok=True)
    catalog, failed = {}, []
    jobs = [("generated", item) for item in generated_ids()]
    jobs += [("art", item) for item in artcnn_models()]
    jobs += [("anime", item) for item in anime4k_models()]
    for kind, item in jobs:
        if kind == "generated":
            model_id, index, family = item
            blob, err = parse_index_model(model_id, index, family, 2 if "Upscale" in model_id else 1)
        elif kind == "art":
            blob, err = parse_artcnn(*item)
            model_id = item[0]
        else:
            blob, err = parse_anime4k_model(*item)
            model_id = item[0]
        name = model_id.replace("/", "_") + ".bin"
        if err:
            failed.append((model_id, err))
            (out / name).unlink(missing_ok=True)
            continue
        (out / name).write_bytes(blob)
        scale = struct.unpack_from("<I", blob, 20)[0]
        catalog[model_id] = {"file": name, "bytes": len(blob), "scale": scale}
    (out / "catalog.json").write_text(json.dumps(catalog, indent=2))
    print(f"converted {len(catalog)} failed {len(failed)}")
    for model_id, err in failed:
        print(f"  FAIL {model_id}: {err}")


if __name__ == "__main__":
    main()
