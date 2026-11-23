#include <android/NeuralNetworks.h>
#include <android/log.h>
#include <android/sharedmem.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "NpuEnhancement", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NpuEnhancement", __VA_ARGS__)

enum { OP_CONV = 1, OP_DEPTH = 2, OP_CRELU = 3, OP_PRELU = 4, OP_ADD = 5, OP_CONCAT = 6, OP_CLAMP = 7 };

typedef struct {
    uint32_t type, in_c, out_c, kh, kw, fuse, ninputs, nfloats;
    uint32_t inputs[8];
    const float *payload;
} GraphOp;

typedef struct {
    ANeuralNetworksMemory *memory;
    int fd;
} WeightMemory;

typedef struct {
    ANeuralNetworksModel *model;
    ANeuralNetworksCompilation *compilation;
    ANeuralNetworksMemory *in_mem;
    ANeuralNetworksMemory *out_mem;
    WeightMemory *weights;
    int in_fd, out_fd;
    void *in_ptr, *out_ptr;
    uint32_t weight_count;
    int32_t added_ops[512];
    bool fused_relu[512], fused_unit_clamp[512];
    uint32_t added_count;
    int width, height, out_w, out_h, channels, scale, composite;
    float output_scale;
    int output_zero;
    char name[128];
    char device[64];
} NpuGraph;

static int add_op(NpuGraph *graph, int32_t code, uint32_t n, const uint32_t *inputs,
                  uint32_t nout, const uint32_t *outputs) {
    if (graph->added_count == sizeof(graph->added_ops) / sizeof(graph->added_ops[0])) {
        return ANEURALNETWORKS_BAD_DATA;
    }
    int rc = ANeuralNetworksModel_addOperation(graph->model, code, n, inputs, nout, outputs);
    if (rc == ANEURALNETWORKS_NO_ERROR) graph->added_ops[graph->added_count++] = code;
    return rc;
}

static const char *op_name(int32_t code) {
    switch (code) {
        case ANEURALNETWORKS_ADD: return "ADD";
        case ANEURALNETWORKS_CONCATENATION: return "CONCATENATION";
        case ANEURALNETWORKS_CONV_2D: return "CONV_2D";
        case ANEURALNETWORKS_DEPTH_TO_SPACE: return "DEPTH_TO_SPACE";
        case ANEURALNETWORKS_MAXIMUM: return "MAXIMUM";
        case ANEURALNETWORKS_MINIMUM: return "MINIMUM";
        case ANEURALNETWORKS_MUL: return "MUL";
        case ANEURALNETWORKS_PRELU: return "PRELU";
        case ANEURALNETWORKS_RELU: return "RELU";
        case ANEURALNETWORKS_SUB: return "SUB";
        default: return "OTHER";
    }
}

static int check(int rc, const char *what) {
    if (rc == ANEURALNETWORKS_NO_ERROR) return 1;
    LOGE("%s failed (%d)", what, rc);
    return 0;
}

static int prefer_device(ANeuralNetworksDevice **out, char *name, size_t name_cap) {
    uint32_t count = 0;
    if (!check(ANeuralNetworks_getDeviceCount(&count), "getDeviceCount")) return 0;
    const char *selected_name = NULL;
    for (uint32_t i = 0; i < count; i++) {
        ANeuralNetworksDevice *dev = NULL;
        const char *dev_name = NULL;
        if (ANeuralNetworks_getDevice(i, &dev) != ANEURALNETWORKS_NO_ERROR) continue;
        if (ANeuralNetworksDevice_getName(dev, &dev_name) != ANEURALNETWORKS_NO_ERROR || !dev_name) continue;
        int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        ANeuralNetworksDevice_getType(dev, &type);
        LOG("nnapi device %u %s (type %d)", i, dev_name, type);
        if (strcmp(dev_name, "qti-dsp") == 0) {
            *out = dev;
            selected_name = dev_name;
            break;
        }
        if (!selected_name && type == ANEURALNETWORKS_DEVICE_ACCELERATOR &&
            (strcmp(dev_name, "qti-htp") == 0 || strcmp(dev_name, "qti-hta") == 0)) {
            *out = dev;
            selected_name = dev_name;
        }
    }
    if (!selected_name) return 0;
    snprintf(name, name_cap, "%s", selected_name);
    return 1;
}

static const float ACT_SCALE = 1.f / 16.f;
static const int32_t ACT_ZP = 128;
static const float UNIT_SCALE = 1.f / 255.f;
static bool compilation_failed;

static int add_activation(ANeuralNetworksModel *model, uint32_t *index, const uint32_t *dims, uint32_t rank,
                          bool unit_range) {
    ANeuralNetworksOperandType type = {
        .type = ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, .dimensionCount = rank, .dimensions = dims,
        .scale = unit_range ? UNIT_SCALE : ACT_SCALE, .zeroPoint = unit_range ? 0 : ACT_ZP,
    };
    int rc = ANeuralNetworksModel_addOperand(model, &type);
    if (rc != ANEURALNETWORKS_NO_ERROR) return 0;
    *index = (*index) + 1;
    return 1;
}

static int add_tensor(ANeuralNetworksModel *model, uint32_t *index, const uint32_t *dims, uint32_t rank) {
    return add_activation(model, index, dims, rank, false);
}

static int add_scalar(ANeuralNetworksModel *model, int32_t *type_code, uint32_t *index, int32_t value, int is_float, float fvalue) {
    ANeuralNetworksOperandType type = {.type = is_float ? ANEURALNETWORKS_FLOAT32 : ANEURALNETWORKS_INT32};
    if (ANeuralNetworksModel_addOperand(model, &type) != ANEURALNETWORKS_NO_ERROR) return 0;
    if (is_float) {
        if (ANeuralNetworksModel_setOperandValue(model, *index, &fvalue, sizeof(fvalue)) != ANEURALNETWORKS_NO_ERROR) return 0;
    } else if (ANeuralNetworksModel_setOperandValue(model, *index, &value, sizeof(value)) != ANEURALNETWORKS_NO_ERROR) {
        return 0;
    }
    (void)type_code;
    *index = (*index) + 1;
    return 1;
}

static int const_typed(NpuGraph *graph, ANeuralNetworksModel *model, uint32_t *cursor, ANeuralNetworksOperandType type,
                        const void *data, size_t bytes) {
    uint32_t op = *cursor;
    if (ANeuralNetworksModel_addOperand(model, &type) != ANEURALNETWORKS_NO_ERROR) return 0;
    *cursor = op + 1;
    if (bytes <= ANEURALNETWORKS_MAX_SIZE_OF_IMMEDIATELY_COPIED_VALUES) {
        return ANeuralNetworksModel_setOperandValue(model, op, data, bytes) == ANEURALNETWORKS_NO_ERROR;
    }
    size_t alloc = bytes < 4096 ? 4096 : bytes;
    int fd = ASharedMemory_create("npu-w", alloc);
    if (fd < 0) return 0;
    void *ptr = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (ptr == MAP_FAILED) {
        close(fd);
        return 0;
    }
    memcpy(ptr, data, bytes);
    munmap(ptr, bytes);
    ANeuralNetworksMemory *mem = NULL;
    if (ANeuralNetworksMemory_createFromFd(bytes, PROT_READ, fd, 0, &mem) != ANEURALNETWORKS_NO_ERROR) {
        close(fd);
        return 0;
    }
    graph->weights[graph->weight_count++] = (WeightMemory){mem, fd};
    return ANeuralNetworksModel_setOperandValueFromMemory(model, op, mem, 0, bytes) == ANEURALNETWORKS_NO_ERROR;
}

static int build(NpuGraph *graph, const uint8_t *blob, size_t blob_size, int width, int height, ANeuralNetworksDevice *device) {
    if (blob_size < 30 || memcmp(blob, "JNPU", 4) != 0) return 0;
    const uint8_t *p = blob + 4;
    const uint8_t *end = blob + blob_size;
    uint32_t version, composite, in_c, out_c, scale, op_count;
    memcpy(&version, p, 4); p += 4;
    memcpy(&composite, p, 4); p += 4;
    memcpy(&in_c, p, 4); p += 4;
    memcpy(&out_c, p, 4); p += 4;
    memcpy(&scale, p, 4); p += 4;
    memcpy(&op_count, p, 4); p += 4;
    uint16_t name_len = 0;
    memcpy(&name_len, p, 2); p += 2;
    if (p + name_len > end || version != 1 || in_c != 4 || !op_count || op_count > 512 ||
        width <= 0 || height <= 0) return 0;
    snprintf(graph->name, sizeof(graph->name), "%.*s", name_len, (const char *)p);
    p += name_len;
    GraphOp *ops = calloc(op_count, sizeof(*ops));
    if (!ops) return 0;
    for (uint32_t i = 0; i < op_count; i++) {
        if (p + 32 > end) { free(ops); return 0; }
        memcpy(&ops[i].type, p, 32); p += 32;
        if (ops[i].ninputs > 8 || p + ops[i].ninputs * 4 + ops[i].nfloats * 4 > end) { free(ops); return 0; }
        memcpy(ops[i].inputs, p, ops[i].ninputs * 4); p += ops[i].ninputs * 4;
        ops[i].payload = (const float *)p;
        p += ops[i].nfloats * 4;
    }
    if (ANeuralNetworksModel_create(&graph->model) != ANEURALNETWORKS_NO_ERROR) { free(ops); return 0; }
    graph->weights = calloc(2 * op_count, sizeof(*graph->weights));
    uint32_t cursor = 0;
    uint32_t *tensor_op = calloc(op_count + 1, sizeof(uint32_t));
    uint32_t *tensor_h = calloc(op_count + 1, sizeof(uint32_t));
    uint32_t *tensor_w = calloc(op_count + 1, sizeof(uint32_t));
    uint32_t *tensor_c = calloc(op_count + 1, sizeof(uint32_t));
    bool tensor_unit_range[513] = {false};
    if (!graph->weights || !tensor_op || !tensor_h || !tensor_w || !tensor_c) goto fail;
    uint32_t in_dims[4] = {1, (uint32_t)height, (uint32_t)width, 4};
    if (!add_tensor(graph->model, &cursor, in_dims, 4)) goto fail;
    tensor_op[0] = 0;
    tensor_h[0] = height;
    tensor_w[0] = width;
    tensor_c[0] = 4;
    uint32_t pad = 0, stride = 0, fuse_none = 0, fuse_relu = 0, axis = 0, block = 0, zero = 0, one = 0;
    int32_t unused = 0;
    if (!add_scalar(graph->model, &unused, &cursor, ANEURALNETWORKS_PADDING_SAME, 0, 0)) goto fail;
    pad = cursor - 1;
    if (!add_scalar(graph->model, &unused, &cursor, 1, 0, 0)) goto fail;
    stride = cursor - 1;
    if (!add_scalar(graph->model, &unused, &cursor, ANEURALNETWORKS_FUSED_NONE, 0, 0)) goto fail;
    fuse_none = cursor - 1;
    if (!add_scalar(graph->model, &unused, &cursor, ANEURALNETWORKS_FUSED_RELU, 0, 0)) goto fail;
    fuse_relu = cursor - 1;
    if (!add_scalar(graph->model, &unused, &cursor, 3, 0, 0)) goto fail;
    axis = cursor - 1;
    if (!add_scalar(graph->model, &unused, &cursor, 2, 0, 0)) goto fail;
    block = cursor - 1;
    uint32_t neg_dims[4] = {1, 1, 1, 1};
    ANeuralNetworksOperandType qconst = {
        .type = ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, .dimensionCount = 4, .dimensions = neg_dims,
        .scale = ACT_SCALE, .zeroPoint = ACT_ZP,
    };
    uint8_t qzero = (uint8_t)ACT_ZP;
    uint8_t qone = (uint8_t)(ACT_ZP + (int)(1.f / ACT_SCALE));
    if (!const_typed(graph, graph->model, &cursor, qconst, &qzero, 1)) goto fail;
    zero = cursor - 1;
    if (!const_typed(graph, graph->model, &cursor, qconst, &qone, 1)) goto fail;
    one = cursor - 1;

    for (uint32_t i = 0; i < op_count; i++) {
        GraphOp *op = &ops[i];
        uint32_t src = op->ninputs ? op->inputs[0] : 0;
        if (!op->ninputs) goto fail;
        for (uint32_t k = 0; k < op->ninputs; k++) {
            if (op->inputs[k] > i) goto fail;
        }
        uint32_t h = tensor_h[src], w = tensor_w[src], c = tensor_c[src];
        uint32_t produced = 0;
        bool unit_range = false;
        if (op->type == OP_CONV) {
            uint32_t fdims[4] = {op->out_c, op->kh, op->kw, op->in_c};
            uint32_t bdims[1] = {op->out_c};
            size_t nweights = (size_t)op->out_c * op->kh * op->kw * op->in_c;
            if (op->in_c != c || op->nfloats != nweights + op->out_c) {
                LOGE("%s op %u CONV has input channels %u, expected %u", graph->name, i, c, op->in_c);
                goto fail;
            }
            float max_abs = 1e-6f;
            for (size_t wi = 0; wi < nweights; wi++) max_abs = fmaxf(max_abs, fabsf(op->payload[wi]));
            float wscale = max_abs / 127.f;
            float bscale = (tensor_unit_range[src] ? UNIT_SCALE : ACT_SCALE) * wscale;
            // A quantized [0,1] result saturates to the same range as WGSL clamp.
            // Fuse only when the unclamped convolution has no other consumers.
            if (i + 1 < op_count && ops[i + 1].type == OP_CLAMP &&
                ops[i + 1].inputs[0] == i + 1 && bscale < UNIT_SCALE) {
                unit_range = true;
                for (uint32_t later = i + 2; later < op_count; later++) {
                    for (uint32_t arg = 0; arg < ops[later].ninputs; arg++) {
                        if (ops[later].inputs[arg] == i + 1) unit_range = false;
                    }
                }
            }
            uint8_t *qw = malloc(nweights);
            int32_t *qb = malloc(op->out_c * sizeof(int32_t));
            if (!qw || !qb) {
                free(qw);
                free(qb);
                goto fail;
            }
            for (size_t wi = 0; wi < nweights; wi++) {
                int v = (int)lrintf(op->payload[wi] / wscale) + 128;
                qw[wi] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
            }
            for (uint32_t bi = 0; bi < op->out_c; bi++) qb[bi] = (int32_t)lrintf(op->payload[nweights + bi] / bscale);
            ANeuralNetworksOperandType filter_type = {
                .type = ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, .dimensionCount = 4, .dimensions = fdims,
                .scale = wscale, .zeroPoint = 128,
            };
            ANeuralNetworksOperandType bias_type = {
                .type = ANEURALNETWORKS_TENSOR_INT32, .dimensionCount = 1, .dimensions = bdims,
                .scale = bscale, .zeroPoint = 0,
            };
            uint32_t filter = cursor;
            int filter_ok = const_typed(graph, graph->model, &cursor, filter_type, qw, nweights);
            uint32_t bias = cursor;
            int bias_ok = filter_ok && const_typed(graph, graph->model, &cursor, bias_type, qb,
                                                   op->out_c * sizeof(int32_t));
            free(qw);
            free(qb);
            if (!bias_ok) goto fail;
            uint32_t out_dims[4] = {1, h, w, op->out_c};
            produced = cursor;
            if (!add_activation(graph->model, &cursor, out_dims, 4, unit_range)) goto fail;
            uint32_t inputs[] = {tensor_op[src], filter, bias, pad, stride, stride, op->fuse ? fuse_relu : fuse_none};
            if (!check(add_op(graph, ANEURALNETWORKS_CONV_2D, 7, inputs, 1, &produced), "add CONV")) goto fail;
            graph->fused_relu[graph->added_count - 1] = op->fuse != 0;
            graph->fused_unit_clamp[graph->added_count - 1] = unit_range;
            c = op->out_c;
        } else if (op->type == OP_DEPTH) {
            uint32_t out_dims[4] = {1, h * 2, w * 2, op->out_c};
            unit_range = tensor_unit_range[src];
            if (!add_activation(graph->model, &cursor, out_dims, 4, unit_range)) goto fail;
            uint32_t inputs[] = {tensor_op[src], block};
            uint32_t out_index = cursor - 1;
            if (!check(add_op(graph, ANEURALNETWORKS_DEPTH_TO_SPACE, 2, inputs, 1, &out_index), "add DEPTH")) goto fail;
            h *= 2; w *= 2; c = op->out_c;
            produced = out_index;
        } else if (op->type == OP_CRELU) {
            uint32_t pos_dims[4] = {1, h, w, c};
            uint32_t neg_dims_c[4] = {1, h, w, c};
            if (!add_tensor(graph->model, &cursor, pos_dims, 4)) goto fail;
            uint32_t pos = cursor - 1;
            uint32_t relu_in[] = {tensor_op[src]};
            if (!check(add_op(graph, ANEURALNETWORKS_RELU, 1, relu_in, 1, &pos), "add RELU")) goto fail;
            if (!add_tensor(graph->model, &cursor, neg_dims_c, 4)) goto fail;
            uint32_t negated = cursor - 1;
            // Same-scale subtraction. A quantized multiply by -1 is rejected by this HTP.
            uint32_t sub_in[] = {zero, tensor_op[src], fuse_none};
            if (!check(add_op(graph, ANEURALNETWORKS_SUB, 3, sub_in, 1, &negated), "add SUB")) goto fail;
            if (!add_tensor(graph->model, &cursor, neg_dims_c, 4)) goto fail;
            uint32_t neg_relu = cursor - 1;
            uint32_t relu_neg[] = {negated};
            if (!check(add_op(graph, ANEURALNETWORKS_RELU, 1, relu_neg, 1, &neg_relu), "add RELU")) goto fail;
            uint32_t both[4] = {1, h, w, c * 2};
            if (!add_tensor(graph->model, &cursor, both, 4)) goto fail;
            produced = cursor - 1;
            uint32_t cat[] = {pos, neg_relu, axis};
            if (!check(add_op(graph, ANEURALNETWORKS_CONCATENATION, 3, cat, 1, &produced), "add CONCAT")) goto fail;
            c *= 2;
        } else if (op->type == OP_PRELU) {
            uint32_t alpha_dims[4] = {1, 1, 1, op->out_c};
            uint8_t *qa = malloc(op->out_c);
            if (!qa) goto fail;
            for (uint32_t ai = 0; ai < op->out_c; ai++) {
                int v = (int)lrintf(op->payload[ai] / ACT_SCALE) + ACT_ZP;
                qa[ai] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
            }
            ANeuralNetworksOperandType alpha_type = {
                .type = ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, .dimensionCount = 4, .dimensions = alpha_dims,
                .scale = ACT_SCALE, .zeroPoint = ACT_ZP,
            };
            uint32_t alpha = cursor;
            int alpha_ok = const_typed(graph, graph->model, &cursor, alpha_type, qa, op->out_c);
            free(qa);
            if (!alpha_ok) goto fail;
            uint32_t dims[4] = {1, h, w, c};
            produced = cursor;
            if (!add_tensor(graph->model, &cursor, dims, 4)) goto fail;
            uint32_t prelu_in[] = {tensor_op[src], alpha};
            if (!check(add_op(graph, ANEURALNETWORKS_PRELU, 2, prelu_in, 1, &produced), "add PRELU")) goto fail;
        } else if (op->type == OP_ADD || op->type == OP_CONCAT) {
            if (op->ninputs < 2) goto fail;
            uint32_t dims[4] = {1, h, w, op->type == OP_ADD ? c : op->out_c};
            if (!add_tensor(graph->model, &cursor, dims, 4)) goto fail;
            produced = cursor - 1;
            uint32_t inputs[9];
            uint32_t n = op->ninputs;
            for (uint32_t k = 0; k < n; k++) inputs[k] = tensor_op[op->inputs[k]];
            if (op->type == OP_ADD) {
                inputs[2] = fuse_none;
                if (!check(add_op(graph, ANEURALNETWORKS_ADD, 3, inputs, 1, &produced), "add ADD")) goto fail;
            } else {
                inputs[n] = axis;
                if (!check(add_op(graph, ANEURALNETWORKS_CONCATENATION, n + 1, inputs, 1, &produced), "add CONCAT")) goto fail;
                c = op->out_c;
            }
        } else if (op->type == OP_CLAMP && tensor_unit_range[src]) {
            produced = tensor_op[src];
            unit_range = true;
        } else if (op->type == OP_CLAMP) {
            uint32_t dims[4] = {1, h, w, c};
            if (!add_tensor(graph->model, &cursor, dims, 4)) goto fail;
            uint32_t floored = cursor - 1;
            uint32_t max_in[] = {tensor_op[src], zero};
            if (!check(add_op(graph, ANEURALNETWORKS_MAXIMUM, 2, max_in, 1, &floored), "add MAX")) goto fail;
            if (!add_tensor(graph->model, &cursor, dims, 4)) goto fail;
            produced = cursor - 1;
            uint32_t min_in[] = {floored, one};
            if (!check(add_op(graph, ANEURALNETWORKS_MINIMUM, 2, min_in, 1, &produced), "add MIN")) goto fail;
        } else {
            goto fail;
        }
        tensor_op[i + 1] = produced;
        tensor_h[i + 1] = h;
        tensor_w[i + 1] = w;
        tensor_c[i + 1] = c;
        tensor_unit_range[i + 1] = unit_range;
        (void)out_c;
    }
    {
        uint32_t input_index = 0;
        uint32_t output_index = tensor_op[op_count];
        if (ANeuralNetworksModel_identifyInputsAndOutputs(graph->model, 1, &input_index, 1, &output_index) != ANEURALNETWORKS_NO_ERROR) goto fail;
        if (ANeuralNetworksModel_finish(graph->model) != ANEURALNETWORKS_NO_ERROR) goto fail;
        bool supported[sizeof(graph->added_ops) / sizeof(graph->added_ops[0])];
        const ANeuralNetworksDevice *devs[] = {device};
        int support_rc = ANeuralNetworksModel_getSupportedOperationsForDevices(graph->model, devs, 1, supported);
        if (support_rc != ANEURALNETWORKS_NO_ERROR) {
            LOGE("%s support query failed (%d)", graph->name, support_rc);
            goto fail;
        }
        int blocked = 0;
        for (uint32_t i = 0; i < graph->added_count; i++) {
            if (supported[i]) continue;
            LOGE("%s unsupported operation %u: %s", graph->name, i, op_name(graph->added_ops[i]));
            blocked = 1;
        }
        if (blocked) goto fail;
        if (!check(ANeuralNetworksCompilation_createForDevices(graph->model, devs, 1, &graph->compilation),
                   "create compilation")) goto fail;
        ANeuralNetworksCompilation_setPreference(graph->compilation, ANEURALNETWORKS_PREFER_FAST_SINGLE_ANSWER);
        if (!check(ANeuralNetworksCompilation_finish(graph->compilation), "finish compilation")) {
            compilation_failed = true;
            goto fail;
        }
        graph->width = width;
        graph->height = height;
        graph->out_w = tensor_w[op_count];
        graph->out_h = tensor_h[op_count];
        graph->channels = tensor_c[op_count];
        graph->output_scale = tensor_unit_range[op_count] ? UNIT_SCALE : ACT_SCALE;
        graph->output_zero = tensor_unit_range[op_count] ? 0 : ACT_ZP;
        graph->scale = graph->out_w / width;
        graph->composite = (int)composite;
        size_t in_bytes = (size_t)width * height * 4;
        size_t out_bytes = (size_t)graph->out_w * graph->out_h * graph->channels;
        graph->in_fd = ASharedMemory_create("npu-in", in_bytes);
        graph->out_fd = ASharedMemory_create("npu-out", out_bytes);
        if (graph->in_fd < 0 || graph->out_fd < 0) goto fail;
        graph->in_ptr = mmap(NULL, in_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, graph->in_fd, 0);
        graph->out_ptr = mmap(NULL, out_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, graph->out_fd, 0);
        if (graph->in_ptr == MAP_FAILED || graph->out_ptr == MAP_FAILED) goto fail;
        if (ANeuralNetworksMemory_createFromFd(in_bytes, PROT_READ, graph->in_fd, 0, &graph->in_mem) != ANEURALNETWORKS_NO_ERROR) goto fail;
        if (ANeuralNetworksMemory_createFromFd(out_bytes, PROT_READ | PROT_WRITE, graph->out_fd, 0, &graph->out_mem) != ANEURALNETWORKS_NO_ERROR) goto fail;
    }
    free(ops); free(tensor_op); free(tensor_h); free(tensor_w); free(tensor_c);
    LOG("compiled %s %dx%d -> %dx%d ch=%d on %s", graph->name, width, height, graph->out_w, graph->out_h, graph->channels, graph->device);
    return 1;
fail:
    free(ops); free(tensor_op); free(tensor_h); free(tensor_w); free(tensor_c);
    return 0;
}

static void destroy(NpuGraph *graph) {
    if (!graph) return;
    if (graph->compilation) ANeuralNetworksCompilation_free(graph->compilation);
    if (graph->model) ANeuralNetworksModel_free(graph->model);
    if (graph->in_mem) ANeuralNetworksMemory_free(graph->in_mem);
    if (graph->out_mem) ANeuralNetworksMemory_free(graph->out_mem);
    if (graph->in_ptr && graph->in_ptr != MAP_FAILED) munmap(graph->in_ptr, (size_t)graph->width * graph->height * 4);
    if (graph->out_ptr && graph->out_ptr != MAP_FAILED) munmap(graph->out_ptr, (size_t)graph->out_w * graph->out_h * graph->channels);
    if (graph->in_fd >= 0) close(graph->in_fd);
    if (graph->out_fd >= 0) close(graph->out_fd);
    for (uint32_t i = 0; i < graph->weight_count; i++) {
        ANeuralNetworksMemory_free(graph->weights[i].memory);
        close(graph->weights[i].fd);
    }
    free(graph->weights);
    free(graph);
}

JNIEXPORT jstring JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeProbe(JNIEnv *env, jobject thiz) {
    (void)thiz;
    ANeuralNetworksDevice *dev = NULL;
    char name[64] = "none";
    int ok = prefer_device(&dev, name, sizeof(name));
    char buf[256];
    snprintf(buf, sizeof(buf), "{\"available\":%s,\"device\":\"%s\"}", ok ? "true" : "false", name);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jlong JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeLoad(
    JNIEnv *env, jobject thiz, jbyteArray graph_bytes, jint width, jint height) {
    (void)thiz;
    if (compilation_failed) {
        LOGE("Skipping DSP compilation after an earlier driver failure in this process");
        return 0;
    }
    jsize len = (*env)->GetArrayLength(env, graph_bytes);
    jbyte *bytes = (*env)->GetByteArrayElements(env, graph_bytes, NULL);
    NpuGraph *graph = calloc(1, sizeof(*graph));
    if (!graph || !bytes) {
        if (bytes) (*env)->ReleaseByteArrayElements(env, graph_bytes, bytes, JNI_ABORT);
        free(graph);
        return 0;
    }
    graph->in_fd = graph->out_fd = -1;
    ANeuralNetworksDevice *dev = NULL;
    if (!prefer_device(&dev, graph->device, sizeof(graph->device)) ||
        !build(graph, (const uint8_t *)bytes, (size_t)len, width, height, dev)) {
        (*env)->ReleaseByteArrayElements(env, graph_bytes, bytes, JNI_ABORT);
        destroy(graph);
        return 0;
    }
    (*env)->ReleaseByteArrayElements(env, graph_bytes, bytes, JNI_ABORT);
    return (jlong)graph;
}

JNIEXPORT jboolean JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeExecute(
    JNIEnv *env, jobject thiz, jlong handle, jobject input, jobject output, jlongArray timing) {
    (void)thiz;
    NpuGraph *graph = (NpuGraph *)handle;
    float *in = (*env)->GetDirectBufferAddress(env, input);
    float *out = (*env)->GetDirectBufferAddress(env, output);
    if (!graph || !in || !out) return JNI_FALSE;
    size_t pixels = (size_t)graph->width * graph->height;
    size_t in_bytes = pixels * 4;
    size_t out_bytes = (size_t)graph->out_w * graph->out_h * graph->channels;
    if ((*env)->GetDirectBufferCapacity(env, input) < (jlong)(in_bytes * sizeof(float)) ||
        (*env)->GetDirectBufferCapacity(env, output) < (jlong)(out_bytes * sizeof(float))) return JNI_FALSE;
    struct timespec start, compute_start, compute_end, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    uint8_t *qin = graph->in_ptr;
    for (size_t i = 0; i < in_bytes; i++) {
        int v = (int)lrintf(in[i] / ACT_SCALE) + ACT_ZP;
        qin[i] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
    }
    ANeuralNetworksExecution *exec = NULL;
    if (ANeuralNetworksExecution_create(graph->compilation, &exec) != ANEURALNETWORKS_NO_ERROR) return JNI_FALSE;
    if (!check(ANeuralNetworksExecution_setInputFromMemory(exec, 0, NULL, graph->in_mem, 0, in_bytes), "set input") ||
        !check(ANeuralNetworksExecution_setOutputFromMemory(exec, 0, NULL, graph->out_mem, 0, out_bytes), "set output")) {
        ANeuralNetworksExecution_free(exec);
        return JNI_FALSE;
    }
    bool benchmark = timing && (*env)->GetArrayLength(env, timing) >= 4;
    bool measure = benchmark && ANeuralNetworksExecution_setMeasureTiming(exec, true) == ANEURALNETWORKS_NO_ERROR;
    clock_gettime(CLOCK_MONOTONIC, &compute_start);
    int rc = ANeuralNetworksExecution_compute(exec);
    clock_gettime(CLOCK_MONOTONIC, &compute_end);
    uint64_t hardware = UINT64_MAX;
    uint64_t driver = UINT64_MAX;
    if (rc == ANEURALNETWORKS_NO_ERROR && measure) {
        ANeuralNetworksExecution_getDuration(exec, ANEURALNETWORKS_DURATION_ON_HARDWARE, &hardware);
        ANeuralNetworksExecution_getDuration(exec, ANEURALNETWORKS_DURATION_IN_DRIVER, &driver);
    }
    ANeuralNetworksExecution_free(exec);
    if (!check(rc, "execute graph")) return JNI_FALSE;
    uint8_t *qout = graph->out_ptr;
    for (size_t i = 0; i < out_bytes; i++) out[i] = ((int)qout[i] - graph->output_zero) * graph->output_scale;
    if (timing) {
        clock_gettime(CLOCK_MONOTONIC, &end);
        jlong times[] = {
            (end.tv_sec - start.tv_sec) * 1000000000LL + (end.tv_nsec - start.tv_nsec),
            (compute_end.tv_sec - compute_start.tv_sec) * 1000000000LL + (compute_end.tv_nsec - compute_start.tv_nsec),
            hardware == UINT64_MAX ? -1 : (jlong)hardware,
            driver == UINT64_MAX ? -1 : (jlong)driver,
        };
        (*env)->SetLongArrayRegion(env, timing, 0, benchmark ? 4 : 1, times);
    }
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeInfo(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NpuGraph *graph = (NpuGraph *)handle;
    jint vals[5] = {graph->scale, graph->channels, graph->composite, graph->out_w, graph->out_h};
    jintArray arr = (*env)->NewIntArray(env, 5);
    (*env)->SetIntArrayRegion(env, arr, 0, 5, vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeOperations(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    const NpuGraph *graph = (const NpuGraph *)handle;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, graph->added_count, string_class, NULL);
    if (!result) return NULL;
    for (uint32_t i = 0; i < graph->added_count; i++) {
        char name[64];
        snprintf(name, sizeof(name), "%s%s%s", op_name(graph->added_ops[i]),
                 graph->fused_relu[i] ? " + ReLU" : "",
                 graph->fused_unit_clamp[i] ? " + Clamp[0,1]" : "");
        jstring operation = (*env)->NewStringUTF(env, name);
        if (!operation) return NULL;
        (*env)->SetObjectArrayElement(env, result, i, operation);
        (*env)->DeleteLocalRef(env, operation);
    }
    return result;
}

JNIEXPORT void JNICALL Java_org_jellyfin_mobile_player_videoproxy_NpuRuntime_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    destroy((NpuGraph *)handle);
}
