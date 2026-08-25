#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>

#include "frida-gumjs.h"

#define TAG "GumJsBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, ##__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, ##__VA_ARGS__)

static JavaVM *g_vm = nullptr;
static jobject g_callback = nullptr;   // Kotlin/Java 侧消息回调对象
static GumScriptBackend *g_backend = nullptr;
static GumScript *g_script = nullptr;
static bool g_gum_inited = false;
// F4：加载期错误消息标记。GumJS 的负载失败（顶层 JS 异常）不通过返回值上报
// （gum_script_load_sync 是 void API，无 GError/无状态查询），唯一外部信号是
// 消息处理器收到的 {"type":"error",...} 消息；本标记用于在 load+泵队列窗口内捕获它。
static std::atomic<bool> g_load_error_pending{false};

static const char MESSAGE_SIG[] = "(Ljava/lang/String;)V";

// 引擎异常消息前缀（Frida GumJS 固定紧凑 JSON 格式：{"type":"error","description":...）
static const char ERROR_MESSAGE_PREFIX[] = "{\"type\":\"error\"";

// ============================================================================
// RouteB B1：replace 调用同步通道（Kotlin → cpp → GumJS → cpp → Kotlin）
//
// 协议（与 cap 冻结 2026-08-25 一致，证据见 docs/RouteB-Facts.md §3/§4 与
// .tmp-ref/frida-gum-17.9.3/ .../runtime/message-dispatcher.js L24-73）：
//   C→JS : gum_script_post(["frida:rpc", <id>, "call", ["__lspHookReply", [<id>, <argsJson>]]])
//          （message-dispatcher 原生识别并调用 rpc.exports.__lspHookReply）
//   JS→C : dispatcher 自动 reply：["frida:rpc", <id>, "ok", <value>] / ["frida:rpc", <id>, "error", <msg>, ...]
//          本层命中 pending 即原生消费（不上行 Kotlin/宿主 UI）；未命中（fire-and-forget reply）静默丢弃。
//   嵌套 : async fn 内 await this.method(...) → shim send {"type":"send","payload":{"t":"lsp.rpc_inner","id":X}}
//          本层标记 pending[X].has_inner 并唤醒 → Kotlin 侧 chain.proceed() 后经
//          nativePostOriginalReply 回投 ["frida:rpc", <origId>, "call", ["__lspHookOriginalReply", [X, retJson]]]
//          （origId = "lsp-orig-N"，无 pending；其自动 reply 在此被静默消费）。
//
// 同步模型：pending[id] = {cond, result, done, has_inner}；全部状态与生命周期由 g_pending_lock 单锁保护；
// 等待线程（Java 拦截线程）在 g_pending_lock 上 g_cond_wait_until（每阶段 ≤500ms），
// on_message（gum-js-loop 线程）同样在 g_pending_lock 上状态写 + signal——单锁、无锁序问题、无死锁环。
// 生命周期：nativeLoadScript/nativeUnloadScript 清空全部 pending（等待者按空结果回退 proceed）。
// ============================================================================

#define LSP_CALL_TIMEOUT_MS 500
#define LSP_INNER_MARKER "{\"__inner\":true}"

typedef struct _LspPending {
    GCond cond;
    gchar *result;      // 终态 reply value（ok 的 value / error 的 {"over":false,"err":..}）或 NULL
    gboolean done;
    gboolean has_inner; // 收到 lsp.rpc_inner（一次一个；Kotlin 消费后复位）
    gchar *inner_args;  // B2：内层自定义参数（JSON 数组文本，来自 lsp.rpc_inner.args）；NULL=原参回退
} LspPending;

static GMutex g_pending_lock;
static GHashTable *g_pending = nullptr;    // id(str) -> LspPending*
static std::atomic<gint64> g_orig_seq{0};

static LspPending *pending_new(void) {
    LspPending *p = g_slice_new0(LspPending);
    g_cond_init(&p->cond);
    return p;
}

/** 释放 pending 条目（owner 线程终态使用；调用方须持有 g_pending_lock）。 */
static void pending_free(LspPending *p) {
    if (p == nullptr) return;
    g_cond_clear(&p->cond);
    g_free(p->result);
    g_free(p->inner_args);
    g_slice_free(LspPending, p);
}

/** DevilKit 单头仅提供 _frida_* 宏而无 glib 原型：自实现 hash/equal（g_hash_table_new 参数）。 */
static guint lsp_str_hash(gconstpointer v) {
    const unsigned char *s = (const unsigned char *)v;
    guint h = 5381;
    while (*s != 0) h = ((h << 5) + h) + *s++;
    return h;
}
static gboolean lsp_str_equal(gconstpointer a, gconstpointer b) {
    return a == b || (a != nullptr && b != nullptr && strcmp((const char *)a, (const char *)b) == 0);
}

/** 构建 {"over":false,"err":"..."}（错误 reply 的结果；转义由 json-glib 保证） */
static gchar *json_build_error(const gchar *msg) {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "over");
    json_builder_add_boolean_value(b, FALSE);
    json_builder_set_member_name(b, "err");
    json_builder_add_string_value(b, msg != nullptr ? msg : "js error");
    json_builder_end_object(b);
    JsonNode *root = json_builder_get_root(b);
    JsonGenerator *g = json_generator_new();
    json_generator_set_root(g, root);
    gchar *out = json_generator_to_data(g, nullptr);
    json_node_unref(root);
    g_object_unref(g);
    g_object_unref(b);
    return out;
}

/** 构建 frida:rpc call 消息：["frida:rpc", rpc_id, "call", [export, [arg1, arg2]]] */
static gchar *build_rpc_call(const gchar *rpc_id, const gchar *export_name,
                             const gchar *a1, const gchar *a2) {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_array(b);
    json_builder_add_string_value(b, "frida:rpc");
    json_builder_add_string_value(b, rpc_id);
    json_builder_add_string_value(b, "call");
    // 5 元素平铺（message-dispatcher.js 契约）：["frida:rpc", id, "call", method, [args]]
    json_builder_add_string_value(b, export_name);
    json_builder_begin_array(b);
    json_builder_add_string_value(b, a1);
    if (a2 != nullptr) json_builder_add_string_value(b, a2);
    json_builder_end_array(b);   // args
    json_builder_end_array(b);   // 整体消息数组
    JsonNode *root = json_builder_get_root(b);
    JsonGenerator *g = json_generator_new();
    json_generator_set_root(g, root);
    gchar *out = json_generator_to_data(g, nullptr);
    json_node_unref(root);
    g_object_unref(g);
    g_object_unref(b);
    return out;
}

/** 从 json 节点取其字符串内容；非 string 节点返回 NULL */
static gchar *node_string(JsonNode *node) {
    if (node == nullptr || !JSON_NODE_HOLDS_VALUE(node)) return nullptr;
    if (json_node_get_value_type(node) == G_TYPE_STRING) {
        return g_strdup(json_node_get_string(node));
    }
    return nullptr;
}

/** 任意 json 节点回序列化为紧凑 JSON 文本；null 节点返回 NULL */
static gchar *node_to_json(JsonNode *node) {
    if (node == nullptr || json_node_is_null(node)) return nullptr;
    JsonGenerator *g = json_generator_new();
    JsonNode *dup = json_node_copy(node);
    json_generator_set_root(g, dup);
    gchar *out = json_generator_to_data(g, nullptr);
    json_node_unref(dup);
    g_object_unref(g);
    return out;
}

/** 解 GumJS 消息信封：JS send 的消息为 {"type":"send","payload":X}（frida GumJS 协议）。
 *  X 为数组（rpc reply / rpc call）或对象（lsp.rpc_inner 等）。裸数组（旧直连）亦兼容。 */
static JsonNode *unwrap_send_payload(JsonNode *root) {
    if (root == nullptr) return nullptr;
    if (JSON_NODE_HOLDS_ARRAY(root)) return root;             // 兼容裸数组
    if (JSON_NODE_HOLDS_OBJECT(root)) {
        JsonObject *obj = json_node_get_object(root);
        gchar *type = node_string(json_object_get_member(obj, "type"));
        if (type != nullptr && strcmp(type, "send") == 0) {
            g_free(type);
            JsonNode *pn = json_object_get_member(obj, "payload");
            if (pn != nullptr && (JSON_NODE_HOLDS_ARRAY(pn) || JSON_NODE_HOLDS_OBJECT(pn))) {
                return pn;
            }
            return nullptr;
        }
        g_free(type);
    }
    return nullptr;
}

/**
 * on_message 消费 frida:rpc reply（["frida:rpc", id, "ok"|"error", ...]）。
 * @return true = 已消费（不再上行 Kotlin）
 */
static gboolean handle_rpc_reply(const gchar *message) {
    if (message == nullptr || (message[0] != '[' && message[0] != '{')) return FALSE;
    JsonParser *parser = json_parser_new();
    if (!json_parser_load_from_data(parser, message, -1, nullptr)) {
        g_object_unref(parser);
        return FALSE;
    }
    gboolean consumed = FALSE;
    JsonNode *root = json_parser_get_root(parser);
    JsonNode *msg = unwrap_send_payload(root);   // 信封解开（send 型/裸数组）
    if (msg != nullptr && JSON_NODE_HOLDS_ARRAY(msg)) {
        JsonArray *arr = json_node_get_array(msg);
        guint len = json_array_get_length(arr);
        if (len >= 3) {
            JsonNode *n0 = json_array_get_element(arr, 0);
            JsonNode *n1 = json_array_get_element(arr, 1);
            JsonNode *n2 = json_array_get_element(arr, 2);
            gchar *head = node_string(n0);
            if (head != nullptr && strcmp(head, "frida:rpc") == 0) {
                gchar *id = node_string(n1);
                gchar *type = node_string(n2);
                if (id != nullptr && type != nullptr) {
                    g_mutex_lock(&g_pending_lock);
                    LspPending *p = (g_pending != nullptr)
                        ? (LspPending *)g_hash_table_lookup(g_pending, id) : nullptr;
                    if (p != nullptr) {
                        gchar *value = nullptr;
                        if (strcmp(type, "ok") == 0 && len >= 4) {
                            JsonNode *n3 = json_array_get_element(arr, 3);
                            gchar *as_str = node_string(n3);
                            if (as_str != nullptr) {
                                value = as_str;              // shim: JSON.stringify({over,r}) 字符串
                            } else {
                                value = node_to_json(n3);    // 兜底：任意节点序列化
                                if (value == nullptr) value = g_strdup("");
                            }
                        } else if (strcmp(type, "error") == 0 && len >= 4) {
                            JsonNode *n3 = json_array_get_element(arr, 3);
                            gchar *msg = node_string(n3);
                            value = json_build_error(msg);
                            g_free(msg);
                        }
                        if (value == nullptr) value = g_strdup("");
                        g_free(p->result);
                        p->result = value;
                        p->done = TRUE;
                        g_cond_broadcast(&p->cond);
                        // t10-C③：rpc reply 命中诊断（id/type/长度）
                        LOGI("[rpc-reply] HIT id=%s type=%s len=%zu", id, type, value ? strlen(value) : 0);
                    } else {
                        // t10-C①：启动探针——boot rpc 的 reply 无 pending（key 不存在 → shim 返回
                        // over:false 静默消费）；此日志=出站链路贯通证明
                        if (strcmp(id, "lsp-boot-check") == 0) {
                            LOGI("[boot] rpc reply consumed, id=%s type=%s", id, type);
                        }
                    }
                    // 未命中 pending（如 lsp-orig-N 的自动 reply）：仍消费，不上行 Kotlin
                    g_mutex_unlock(&g_pending_lock);
                    consumed = TRUE;
                }
                g_free(id);
                g_free(type);
            }
            g_free(head);
        }
    }
    g_object_unref(parser);
    return consumed;
}

/** on_message 消费嵌套原调用请求：{"type":"send","payload":{"t":"lsp.rpc_inner","id":X,...}} */
static gboolean handle_rpc_inner(const gchar *message) {
    if (message == nullptr || message[0] != '{') return FALSE;
    JsonParser *parser = json_parser_new();
    if (!json_parser_load_from_data(parser, message, -1, nullptr)) {
        g_object_unref(parser);
        return FALSE;
    }
    gboolean consumed = FALSE;
    JsonNode *root = json_parser_get_root(parser);
    if (root != nullptr && JSON_NODE_HOLDS_OBJECT(root)) {
        JsonObject *outer = json_node_get_object(root);
        JsonNode *type_node = json_object_get_member(outer, "type");
        gchar *type_str = node_string(type_node);
        if (type_str != nullptr && strcmp(type_str, "send") == 0) {
            JsonNode *payload_node = json_object_get_member(outer, "payload");
            if (payload_node != nullptr && JSON_NODE_HOLDS_OBJECT(payload_node)) {
                JsonObject *payload = json_node_get_object(payload_node);
                gchar *t_str = node_string(json_object_get_member(payload, "t"));
                if (t_str != nullptr && strcmp(t_str, "lsp.rpc_inner") == 0) {
                    gchar *id = node_string(json_object_get_member(payload, "id"));
                    if (id != nullptr) {
                        // B2：透传自定义参数（payload.args，JSON 数组；缺省/非数组 → NULL=原参回退）
                        gchar *args = node_to_json(json_object_get_member(payload, "args"));
                        g_mutex_lock(&g_pending_lock);
                        LspPending *p = (g_pending != nullptr)
                            ? (LspPending *)g_hash_table_lookup(g_pending, id) : nullptr;
                        if (p != nullptr && !p->done) {
                            p->has_inner = TRUE;
                            g_free(p->inner_args);
                            p->inner_args = args;
                            args = nullptr;
                            g_cond_broadcast(&p->cond);
                        }
                        // t10-C②：嵌套原调用请求诊断（B2 追加 args 提示）
                        LOGI("[rpc-inner] id=%s hit=%d args=%s", id,
                            (p != nullptr && !p->done) ? 1 : 0,
                            (p != nullptr && p->inner_args != nullptr) ? "yes" : "no");
                        g_mutex_unlock(&g_pending_lock);
                        g_free(args);
                        g_free(id);
                        consumed = TRUE;   // 无论命中与否：rpc_inner 不上行 Kotlin
                    }
                }
                g_free(t_str);
            }
        }
        g_free(type_str);
    }
    g_object_unref(parser);
    return consumed;
}

/**
 * unload / 重载：置空全部 pending（唤醒等待线程；空结果 → 回退 chain.proceed()）。
 * 注意：此处只标记+广播，不删除/不释放——条目由等待线程（唯一 owner）在消费终态时
 * 从 map 移除并释放，避免等待线程在 cond 等待期间被 use-after-free。
 */
static void pending_clear_all(void) {
    g_mutex_lock(&g_pending_lock);
    if (g_pending != nullptr) {
        GHashTableIter it;
        gpointer k, v;
        g_hash_table_iter_init(&it, g_pending);
        while (g_hash_table_iter_next(&it, &k, &v)) {
            LspPending *p = (LspPending *)v;
            g_free(p->result);
            p->result = nullptr;
            g_free(p->inner_args);
            p->inner_args = nullptr;
            p->done = TRUE;
            g_cond_broadcast(&p->cond);
        }
    }
    g_mutex_unlock(&g_pending_lock);
}

// ============================================================================

// GumJS on_message -> JNI 回调宿主层 (console.log / send() / frida:rpc reply)
static void on_message(const gchar *message, GBytes *data, gpointer user_data) {
    (void) data; (void) user_data;
    if (g_vm == nullptr || g_callback == nullptr) return;

    // F4：捕获加载期引擎异常消息（原逻辑保持不变）
    if (message != nullptr &&
        strncmp(message, ERROR_MESSAGE_PREFIX, sizeof(ERROR_MESSAGE_PREFIX) - 1) == 0) {
        g_load_error_pending.store(true);
    }

    // RouteB：frida:rpc reply 与 lsp.rpc_inner 在 native 层原生消费（不进 Kotlin / 不上行宿主 UI）
    if (message != nullptr) {
        // t10-C②：入站消息头诊断（仅 frida:rpc 数组消息——send/console.log 走 Kotlin UI 日志不重复打）
        if (strstr(message, "frida:rpc") != nullptr) {
            LOGI("[onmsg] rpc-head=%.48s", message);
        }
        if (handle_rpc_reply(message)) return;
        if (handle_rpc_inner(message)) return;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_callback);
    jmethodID mid = env->GetMethodID(cls, "onScriptMessage", MESSAGE_SIG);
    if (mid != nullptr) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallVoidMethod(g_callback, mid, jmsg);
        env->DeleteLocalRef(jmsg);
    }
    env->DeleteLocalRef(cls);

    if (attached) g_vm->DetachCurrentThread();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeInitEngine(JNIEnv *env, jclass) {
    env->GetJavaVM(&g_vm);
    g_mutex_init(&g_pending_lock);

    if (!g_gum_inited) {
        gum_init_embedded();
        g_gum_inited = true;
        LOGI("gum_init_embedded done");
    }

    if (g_backend == nullptr) {
        g_backend = gum_script_backend_obtain_qjs();   // QuickJS 后端, ES2020+
        if (g_backend == nullptr) {
            LOGE("obtain qjs backend failed");
            return JNI_FALSE;
        }
        // 注：GumJS 17.x 自带默认调度器，无需手动创建
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeSetCallback(JNIEnv *env, jclass, jobject cb) {
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    if (cb != nullptr) g_callback = env->NewGlobalRef(cb);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeLoadScript(
        JNIEnv *env, jclass, jstring jscript, jstring jname) {

    pending_clear_all();   // RouteB：重载前清空在途 replace 请求（等待者回退 proceed）

    if (g_backend == nullptr) { LOGE("engine not inited"); return JNI_FALSE; }
    if (g_script != nullptr) { LOGI("unload previous script first"); /* fallthrough: 先卸旧 */
        gum_script_unload_sync(g_script, nullptr);
        g_object_unref(g_script);
        g_script = nullptr;
    }

    const char *src = env->GetStringUTFChars(jscript, nullptr);
    const char *name = env->GetStringUTFChars(jname, nullptr);
    GError *error = nullptr;

    g_script = gum_script_backend_create_sync(
            g_backend, name, src, nullptr, nullptr, &error);

    if (error != nullptr) {
        LOGE("create script error: %s", error->message);
        g_error_free(error);
        env->ReleaseStringUTFChars(jscript, src);
        env->ReleaseStringUTFChars(jname, name);
        return JNI_FALSE;
    }
    if (g_script == nullptr) {
        // 防御：create 返回 NULL 且未设 error（理论上不应发生），避免后续解引用崩溃
        LOGE("create script returned null (no error set)");
        env->ReleaseStringUTFChars(jscript, src);
        env->ReleaseStringUTFChars(jname, name);
        return JNI_FALSE;
    }

    gum_script_set_message_handler(g_script, on_message, nullptr, nullptr);

    // F4：gum_script_load_sync 为 void API（无 GError/无返回值），加载期异常只能通过
    // 消息处理器观测。先清零标记再加载，随后泵队列（异常消息在加载时即入队），
    // 若在加载窗口内收到 {"type":"error",...} 则判定加载失败：卸旧+释放+JNI_FALSE。
    g_load_error_pending.store(false);
    gum_script_load_sync(g_script, nullptr);   // 零重启热加载

    // 泵一次主上下文，确保 on_message / send() 在加载后能及时派发
    GMainContext *ctx = g_main_context_default();
    while (g_main_context_pending(ctx)) {
        g_main_context_iteration(ctx, FALSE);
    }

    if (g_load_error_pending.load()) {
        LOGE("script [%s] load failed (js error reported)", name);
        gum_script_unload_sync(g_script, nullptr);
        g_object_unref(g_script);
        g_script = nullptr;
        env->ReleaseStringUTFChars(jscript, src);
        env->ReleaseStringUTFChars(jname, name);
        return JNI_FALSE;
    }

    LOGI("script [%s] loaded", name);

    // t10-A：显式请求调度器 start（幂等——js_thread 非空即 no-op；首次 push 已惰性启动，
    // 此处为防御（fork/Worker 等路径复位）+ 日志佐证）。
    gum_script_scheduler_start(gum_script_backend_get_scheduler());
    LOGI("scheduler start requested (idempotent)");

    // t10-C①：启动探针——post boot rpc（key="boot" 不存在于用户 fn 注册表 → shim 走
    // "未注册 key → 返回 over:false"静默路径，不触发任何用户 fn；其自动 reply 无 pending，
    // 由 handle_rpc_reply 打 "[boot] rpc reply consumed"）。该 reply 经 default context 出站，
    // 会在首次 nativeCallJs 的 t10-B 泵送周期被派发 → 一次性证明"出站→入站"链路贯通。
    {
        gchar *boot = build_rpc_call("lsp-boot-check", "__lspHookReply", "lsp-boot-check",
            "{\"key\":\"boot\",\"args\":[],\"this\":null}");
        gum_script_post(g_script, boot, nullptr);
        g_free(boot);
        // 非阻塞抽干一次（load 泵的延续；若 reply 已回，LOGI 立即出现）
        GMainContext *dctx = g_main_context_default();
        while (g_main_context_pending(dctx)) {
            g_main_context_iteration(dctx, FALSE);
        }
        LOGI("[boot] boot rpc posted (id=lsp-boot-check)");
    }

    env->ReleaseStringUTFChars(jscript, src);
    env->ReleaseStringUTFChars(jname, name);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeUnloadScript(JNIEnv *, jclass) {
    pending_clear_all();   // RouteB: 清空在途请求
    if (g_script != nullptr) {
        gum_script_unload_sync(g_script, nullptr);
        g_object_unref(g_script);
        g_script = nullptr;
        LOGI("script unloaded");
    }
}

/**
 * RouteB nativeCallJs(id, payload)：登记 pending → post frida:rpc call → 同步等待（≤500ms/阶段）。
 * - payload 非空：发起新请求；payload 为空：续等（内层原调用服务后的“等最终 reply”阶段）。
 * - 返回（字符串，Kotlin 解析）：
 *     ""                          → 超时/无结果（调用方回退 chain.proceed()）
 *     {"__inner":true}            → 收到 async fn 的 this.method 原调用请求（Kotlin 服务后再次调用本函数续等）
 *     其它                        → reply value（shim 的 {"over":bool,"r":...}；或 error 的 {"over":false,"err":..}）
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeCallJs(JNIEnv *env, jclass, jstring jid, jstring jpayload) {
    if (g_script == nullptr) return env->NewStringUTF("");

    const char *id = env->GetStringUTFChars(jid, nullptr);
    const char *payload = (jpayload != nullptr) ? env->GetStringUTFChars(jpayload, nullptr) : nullptr;

    gboolean do_post = (payload != nullptr && payload[0] != '\0');

    // t10-C②：nativeCallJs 入口诊断（id + 是否首次发请求 + payload 长度）
    LOGI("[calljs] id=%s post=%d payload_len=%zu", id, do_post ? 1 : 0,
        payload != nullptr ? strlen(payload) : 0);

    g_mutex_lock(&g_pending_lock);
    if (g_pending == nullptr) {
        g_pending = g_hash_table_new(lsp_str_hash, lsp_str_equal);
    }
    LspPending *p = (LspPending *)g_hash_table_lookup(g_pending, id);
    if (p == nullptr) {
        p = pending_new();
        g_hash_table_insert(g_pending, g_strdup(id), p);
    }
    g_mutex_unlock(&g_pending_lock);

    if (do_post) {
        gchar *msg = build_rpc_call(id, "__lspHookReply", id, payload);
        gum_script_post(g_script, msg, nullptr);
        g_free(msg);
    }

    gchar *out = nullptr;
    g_mutex_lock(&g_pending_lock);
    // 首等/续等共用同一 pending 条目（同 id 复用 = 嵌套栈复用，见 Facts §5）
    p = (LspPending *)g_hash_table_lookup(g_pending, id);
    if (p == nullptr) {
        out = g_strdup("");   // unload 竞态：条目已被清空
    } else {
        // t10-B：出站泵修复——JS→C（send/console.log/dispatcher 自动 reply）经 gum_quick_script_emit
        // 附着在【全局默认 main context】（script.main-context=创建线程 task 的 thread-default context，
        // Java 线程无 thread-default → 回退 default context；见 t10 诊断）。若无人泵送，reply 永久排队
        // → 500ms REPLACE_TIMEOUT。此处"释放锁→泵 default context（非阻塞）→10ms 切片短等"循环保证
        // 出站消息被派发（on_message 由本线程同步执行→持锁写 pending→唤醒）；总预算仍 ≤500ms 兜底。
        gint64 total_deadline = g_get_monotonic_time() + (gint64)LSP_CALL_TIMEOUT_MS * 1000;
        GMainContext *dctx = g_main_context_default();
        while (!p->done && !p->has_inner) {
            g_mutex_unlock(&g_pending_lock);
            // P1-1（t13 建议）：内层泵循环受总预算守卫（JS 生产速率异常时防无限泵）
            while (g_main_context_pending(dctx) && g_get_monotonic_time() < total_deadline) {
                g_main_context_iteration(dctx, FALSE);
            }
            g_mutex_lock(&g_pending_lock);
            if (p->done || p->has_inner || g_get_monotonic_time() >= total_deadline) break;
            g_cond_wait_until(&p->cond, &g_pending_lock,
                MIN(total_deadline, g_get_monotonic_time() + 10 * 1000));
        }
        if (p->has_inner) {
            p->has_inner = FALSE;   // 消费一次内层请求（Kotlin 凭返回标记服务原方法）
            // B2：args 透传（JSON 数组文本，由 node_to_json 产出——安全内嵌）；NULL → 无 args 字段
            if (p->inner_args != nullptr) {
                out = g_strdup_printf("{\"__inner\":true,\"args\":%s}", p->inner_args);
            } else {
                out = g_strdup(LSP_INNER_MARKER);
            }
            // 条目保留：Kotlin 服务完内层后会再次调用本函数续等
        } else if (p->done) {
            out = g_strdup(p->result != nullptr ? p->result : "");
            // 终态：owner 线程移除并释放（未命中的迟到 reply 被 on_message 静默消费）
            if (p == g_hash_table_lookup(g_pending, id)) {
                g_hash_table_remove(g_pending, id);
                pending_free(p);
            }
        } else {
            // 超时且无内层：按空结果返回，owner 线程移除并释放
            out = g_strdup("");
            if (p == g_hash_table_lookup(g_pending, id)) {
                g_hash_table_remove(g_pending, id);
                pending_free(p);
            }
        }
    }
    g_mutex_unlock(&g_pending_lock);

    jstring jout = env->NewStringUTF(out != nullptr ? out : "");
    g_free(out);

    if (payload != nullptr) env->ReleaseStringUTFChars(jpayload, payload);
    env->ReleaseStringUTFChars(jid, id);
    return jout;
}

/**
 * RouteB nativePostOriginalReply(id, retJson)：把原方法（chain.proceed）结果回投 JS
 * （fire-and-forget rpc call → rpc.exports.__lspHookOriginalReply；其自动 reply 被 on_message 静默消费）。
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativePostOriginalReply(
        JNIEnv *env, jclass, jstring jid, jstring jretJson) {
    if (g_script == nullptr) return;
    const char *id = env->GetStringUTFChars(jid, nullptr);
    const char *retJson = (jretJson != nullptr) ? env->GetStringUTFChars(jretJson, nullptr) : "null";
    gint64 seq = g_orig_seq.fetch_add(1) + 1;
    gchar orig_id[32];
    g_snprintf(orig_id, sizeof(orig_id), "lsp-orig-%lld", (long long) seq);
    gchar *msg = build_rpc_call(orig_id, "__lspHookOriginalReply", id, retJson);
    gum_script_post(g_script, msg, nullptr);
    g_free(msg);
    if (jretJson != nullptr) env->ReleaseStringUTFChars(jretJson, retJson);
    env->ReleaseStringUTFChars(jid, id);
}
