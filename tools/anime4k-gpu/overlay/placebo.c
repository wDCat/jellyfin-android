#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <libplacebo/opengl.h>
#include <libplacebo/renderer.h>
#include <libplacebo/shaders/custom.h>

struct renderer {
    pl_log log;
    pl_opengl gl;
    pl_renderer render;
    const struct pl_hook **hooks;
    int count;
    struct pl_render_params params;
};

static void gpu_log(void *ctx, enum pl_log_level level, const char *msg)
{
    (void)ctx;
    __android_log_write(level <= PL_LOG_ERR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "OverlayGPU", msg);
}

static void dispose(struct renderer *r)
{
    if (!r) return;
    for (int i = 0; i < r->count; i++) pl_mpv_user_shader_destroy(&r->hooks[i]);
    free(r->hooks);
    pl_renderer_destroy(&r->render);
    pl_opengl_destroy(&r->gl);
    pl_log_destroy(&r->log);
    free(r);
}

#define NATIVE(name) Java_org_jellyfin_mobile_player_videoproxy_PlaceboNative_##name
JNIEXPORT jlong JNICALL NATIVE(create)(JNIEnv *env, jobject self, jobjectArray sources)
{
    struct renderer *r = calloc(1, sizeof(*r));
    if (!r) return 0;
    r->log = pl_log_create(PL_API_VER, pl_log_params(.log_cb = gpu_log, .log_level = PL_LOG_WARN));
    r->gl = pl_opengl_create(r->log, pl_opengl_params(.get_proc_addr = eglGetProcAddress,
        .egl_display = eglGetCurrentDisplay(), .egl_context = eglGetCurrentContext()));
    if (!r->gl || !(r->render = pl_renderer_create(r->log, r->gl->gpu))) goto fail;
    __android_log_print(ANDROID_LOG_WARN, "OverlayGPU", "GL %s compute=%d max_threads=%d max_group=%d,%d",
        glGetString(GL_VERSION), r->gl->gpu->glsl.compute, r->gl->gpu->glsl.max_group_threads,
        r->gl->gpu->glsl.max_group_size[0], r->gl->gpu->glsl.max_group_size[1]);
    const int count = (*env)->GetArrayLength(env, sources);
    r->hooks = calloc(count, sizeof(*r->hooks));
    if (!r->hooks && count) goto fail;
    for (int i = 0; i < count; i++) {
        jstring str = (*env)->GetObjectArrayElement(env, sources, i);
        if (!str) goto fail;
        const char *text = (*env)->GetStringUTFChars(env, str, NULL);
        if (!text) goto fail;
        r->hooks[i] = pl_mpv_user_shader_parse(r->gl->gpu, text, (*env)->GetStringUTFLength(env, str));
        (*env)->ReleaseStringUTFChars(env, str, text);
        (*env)->DeleteLocalRef(env, str);
        if (!r->hooks[i]) goto fail;
        r->count++;
    }
    r->params = pl_render_fast_params;
    r->params.hooks = r->hooks;
    r->params.num_hooks = count;
    return (intptr_t)r;
fail:
    dispose(r);
    return 0;
}

static struct pl_frame frame(pl_tex tex)
{
    return (struct pl_frame){
        .num_planes = 1, .planes = {{ .texture = tex, .flipped = true, .components = 4,
            .component_mapping = {PL_CHANNEL_R, PL_CHANNEL_G, PL_CHANNEL_B, PL_CHANNEL_A} }},
        .repr = {.sys = PL_COLOR_SYSTEM_RGB, .levels = PL_COLOR_LEVELS_FULL, .alpha = PL_ALPHA_INDEPENDENT},
        .color = pl_color_space_srgb,
    };
}

JNIEXPORT jboolean JNICALL NATIVE(draw)(JNIEnv *env, jobject self, jlong handle,
    jint texture, jint width, jint height, jint fbo, jint out_w, jint out_h)
{
    struct renderer *r = (void *)(intptr_t)handle;
    pl_gpu gpu = r->gl->gpu;
    // libplacebo queries GLES read formats while wrapping the input texture.
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    pl_tex input = pl_opengl_wrap(gpu, pl_opengl_wrap_params(.texture = texture, .width = width,
        .height = height, .target = GL_TEXTURE_2D, .iformat = GL_RGBA8));
    pl_tex output = pl_opengl_wrap(gpu, pl_opengl_wrap_params(.framebuffer = fbo, .width = out_w, .height = out_h));
    struct pl_frame src = frame(input), dst = frame(output);
    bool ok = input && output && pl_render_image(r->render, &src, &dst, &r->params);
    struct pl_render_errors errors = pl_renderer_get_errors(r->render);
    pl_tex_destroy(gpu, &output);
    pl_tex_destroy(gpu, &input);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, out_w, out_h);
    return ok && !errors.errors && !errors.num_disabled_hooks;
}

JNIEXPORT void JNICALL NATIVE(release)(JNIEnv *env, jobject self, jlong handle)
{
    dispose((void *)(intptr_t)handle);
}
