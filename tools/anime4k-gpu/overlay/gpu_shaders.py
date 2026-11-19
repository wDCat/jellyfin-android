"""Generate GL fragment shaders for the four Web CNNx2S convolution passes."""
import sys
from pathlib import Path

import numpy as np

from weights import read_shader


def glsl(values):
    return ', '.join(str(np.float32(value)) for value in values)


def shader(weights, bias):
    lines = [
        'precision highp float;',
        'uniform highp sampler2D features;',
        'uniform vec2 texel;',
        'void main() {',
        f'    vec4 result = vec4({glsl(bias)});',
    ]
    channels = weights.shape[-1]
    for y in range(3):
        for x in range(3):
            tap = f'tap{x}{y}'
            # GL rows start at the bottom; shader weights use top-down image coordinates.
            pos = f'(gl_FragCoord.xy + vec2({x - 1}.0, {1 - y}.0)) * texel'
            lines.append(f'    vec4 {tap} = texture2D(features, {pos});')
            for start in range(0, channels, 4):
                matrix = np.zeros((4, 4), dtype=np.float32)
                block = weights[:, y, x, start:start+4].T
                matrix[:len(block)] = block
                value = tap if channels == 3 else f'max({"-" if start else ""}{tap}, 0.0)'
                lines.append(f'    result += mat4({glsl(matrix.ravel())}) * {value};')
    return '\n'.join([*lines, '    gl_FragColor = result;', '}', ''])


if __name__ == '__main__':
    source, output = map(Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    for i, stage in enumerate(('conv2dtf', 'conv2d1tf', 'conv2d2tf', 'conv2dlasttf')):
        weights, bias = read_shader(source / (stage + '.wgsl'), 3 if i == 0 else 8)
        (output / f'pass{i}.frag').write_text(shader(weights, bias))
