"""Read CNNx2S convolution coefficients for GPU shader generation."""
import re

import numpy as np


def read_shader(path, channels):
    source = path.read_text()
    weights = np.zeros((4, 3, 3, channels), dtype=np.float32)
    taps = re.findall(r'mat4x4<f32>\(([^)]+)\) \* go_(\d)\(pixel.xy, (-?\d), (-?\d)\)', source)
    expected = 9 if channels == 3 else 18
    assert len(taps) == expected and len({tap[1:] for tap in taps}) == expected
    for matrix, branch, x, y in taps:
        start = int(branch) * 4
        count = min(4, channels - start)
        # WGSL matrices are column major. Return weights as [out, y, x, in].
        columns = np.fromstring(matrix, sep=',', dtype=np.float32).reshape(4, 4)
        weights[:, int(y) + 1, int(x) + 1, start:start + count] = columns[:count].T
    bias = re.search(r'result \+= vec4f\(([^)]+)\)', source)
    return weights, np.fromstring(bias[1], sep=',', dtype=np.float32)
