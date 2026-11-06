#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>

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

// ============================================================================
// 引擎审查修复（2026-10-05）：g_script / g_callback 并发竞态
//
// 背景：常驻消息泵（e659ce9）把消息派发从“偶发”变为“持续”，使两条既有竞态
//   从低概率放大为常态，必须同批修复：
//   ① g_script：nativeCallJs / nativePostOriginalReply 无锁解引用，而
//      nativeLoadScript / nativeUnloadScript 会并发卸载并 g_object_unref
//      → use-after-free（目标进程崩溃）。
//   ② g_callback：on_message（泵线程持续执行）在 nativeSetCallback 的
//      DeleteGlobalRef 与 NewGlobalRef 之间读取 → 引用已释放的 jobject → UAF。
// 修复原则：锁只保护“指针交换”与“引用获取”两个瞬时动作；耗时操作一律锁外。
// ============================================================================
static GMutex g_script_lock;     // 保护 g_script（发布/摘除交换 vs 取引用）
static GMutex g_callback_lock;   // 保护 g_callback / g_callback_mid（交换 vs 取用）
static jmethodID g_callback_mid = nullptr;   // onScriptMessage 方法 id（注册时缓存）
static std::atomic<bool> g_locks_ready{false};   // nativeInitEngine 完成标记（锁就绪守卫）

// —— F2 修复：dispatch 与 unload 的生命周期互斥 ——
// 竞态：nativeDispatchHook 先 load 出 g_js_dispatcher，随后 nativeUnloadScript 把指针清空
// 并 gum_script_unload_sync 卸载 JS 引擎 → 前者再调用 fn_raw = 调已销毁运行时的函数指针（UAF）。
// Kotlin 侧的 dispatcherReady 是 check-then-act，关不住这个窗口。
// 方案：**in-flight 计数**（而不是把整条热路径塞进一把锁）——dispatch 仍然可以多线程并发，
// unload 只负责"等在场的那几次跑完再拆"。这样既消除 UAF，又不改变回调并发语义。
static GMutex g_dispatch_lock;
static GCond g_dispatch_cond;
static int g_dispatch_inflight = 0;      // 正在 JS 里的 dispatch 数（受 g_dispatch_lock 保护）
static bool g_dispatch_disabled = false; // unload 期间置位：新 dispatch 直接返回 NULL
// 本线程自己嵌套了几层 dispatch。quiesce 只等"别人"：若 unload 是从 JS 回调内部触发的
// （hook 里调 unloadScript 这类路径），等自己会永久死锁。
static thread_local int t_dispatch_depth = 0;

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

/** F2/F3：进入 JS 执行上下文（成功返回 true）。失败 = 正在卸载，调用方必须按"没进 JS"处理。 */
static bool dispatch_enter(void) {
    if (!g_locks_ready.load()) {   // 引擎未就绪：此阶段无并发，无需计数
        t_dispatch_depth++;
        return true;
    }
    bool ok = false;
    g_mutex_lock(&g_dispatch_lock);
    if (!g_dispatch_disabled) {
        g_dispatch_inflight++;
        ok = true;
    }
    g_mutex_unlock(&g_dispatch_lock);
    if (ok) t_dispatch_depth++;
    return ok;
}

static void dispatch_leave(void) {
    t_dispatch_depth--;
    if (!g_locks_ready.load()) return;
    g_mutex_lock(&g_dispatch_lock);
    if (--g_dispatch_inflight == 0) g_cond_broadcast(&g_dispatch_cond);
    g_mutex_unlock(&g_dispatch_lock);
}

/** 卸载前调用：拒绝新 dispatch，并等**其它线程**在堂的那几次退出。
 *  只等 "inflight > 本线程嵌套深度"：若 unload 是从 JS 回调内部触发的（hook 里调 unload），
 *  等自己会永久死锁。返回后即可安全销毁 JS 运行时。 */
static void dispatch_quiesce(void) {
    if (!g_locks_ready.load()) return;
    g_mutex_lock(&g_dispatch_lock);
    g_dispatch_disabled = true;
    while (g_dispatch_inflight > t_dispatch_depth) {
        g_cond_wait(&g_dispatch_cond, &g_dispatch_lock);
    }
    g_mutex_unlock(&g_dispatch_lock);
}

/** 装载完成后重新放行 dispatch。 */
static void dispatch_resume(void) {
    if (!g_locks_ready.load()) return;
    g_mutex_lock(&g_dispatch_lock);
    g_dispatch_disabled = false;
    g_cond_broadcast(&g_dispatch_cond);
    g_mutex_unlock(&g_dispatch_lock);
}

/** nativeLoadScript 用：无论从哪条 return 出去都重新放行 dispatch。
 *  装载路径有不少失败分支（create 返回 NULL / error / load 报错），逐个补 resume 太容易漏，
 *  漏一个就是"一次装载失败后同步通道永久禁用"。用一个作用域守卫兜住全部出口。 */
struct DispatchResumeGuard {
    ~DispatchResumeGuard() { dispatch_resume(); }
};

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

/** 锁内取 g_script 强引用（无脚本返回 nullptr）。调用方用毕须 g_object_unref。 */
static GumScript *lsp_acquire_script(void) {
    g_mutex_lock(&g_script_lock);
    GumScript *s = g_script;
    if (s != nullptr) g_object_ref(s);
    g_mutex_unlock(&g_script_lock);
    return s;
}

/** UTF-8 -> jstring 安全转换（先转 UTF-16 再 NewString）。
 *  规避 NewStringUTF 对非 modified-UTF8（emoji 四字节序列/非法字节）的未定义行为；
 *  非法输入先经 g_utf8_make_valid 清洗，保证不丢消息、不崩溃。 */
static jstring lsp_new_jstring(JNIEnv *env, const char *utf8) {
    if (utf8 == nullptr) return nullptr;
    glong written = 0;
    gunichar2 *u16 = g_utf8_to_utf16(utf8, -1, nullptr, &written, nullptr);
    if (u16 == nullptr) {
        gchar *fixed = g_utf8_make_valid(utf8, -1);
        if (fixed == nullptr) return nullptr;
        u16 = g_utf8_to_utf16(fixed, -1, nullptr, &written, nullptr);
        g_free(fixed);
        if (u16 == nullptr) return nullptr;
    }
    jstring s = env->NewString(reinterpret_cast<const jchar *>(u16), (jsize) written);
    g_free(u16);
    return s;
}

/** Java String -> 标准 UTF-8（g_malloc；调用方 g_free）。
 *  输入侧 UTF 修复（引擎审查，2026-10-05）：JNI GetStringUTFChars 产出 modified UTF-8——
 *  非 BMP 字符（emoji 等）按 CESU-8 编码（每代理 3 字节）→ 对 JSON 解析器与
 *  gum_script_backend（均要求标准 UTF-8）属非法字节序列。
 *  本函数改走 GetStringChars（UTF-16 原文）→ g_utf16_to_utf8（标准编码）。
 *  非法 UTF-16（未配对代理）退化为 modified UTF-8（保数据不丢）。 */
static gchar *lsp_get_utf8(JNIEnv *env, jstring js, const char *fallback) {
    if (js == nullptr) return (fallback != nullptr) ? g_strdup(fallback) : nullptr;
    const jchar *chars = env->GetStringChars(js, nullptr);
    gchar *out = nullptr;
    if (chars != nullptr) {
        jsize len = env->GetStringLength(js);
        out = g_utf16_to_utf8(reinterpret_cast<const gunichar2 *>(chars), (glong) len,
                              nullptr, nullptr, nullptr);
        env->ReleaseStringChars(js, chars);
    }
    if (out == nullptr) {
        const char *m = env->GetStringUTFChars(js, nullptr);
        out = (m != nullptr) ? g_strdup(m) : ((fallback != nullptr) ? g_strdup(fallback) : nullptr);
        if (m != nullptr) env->ReleaseStringUTFChars(js, m);
    }
    return out;
}

// ============================================================================
// 第一阶段双通道（2026-11 接线；契约 = tools/javabridge/BRIDGE-CONTRACT.md §1）
//
// 本文件现在有两条方向相反的跨语言通道，线程/锁纪律完全不同，务必区分：
//
// ① 上行 JS 线程 → Kotlin 同步反馈：lsp_sync_call（本段实现）
//    上游 implementation = fn / = null 必须同步拿到路由结果并同步抛错，不允许
//    fire-and-forget。链路：JS 调 libgumjs_bridge.so 的 C 导出 → 本层用缓存的
//    jclass/jmethodID → GumJsBridge.lspSync(String): String → 结果 JSON 回 JS。
//    线程：调用方是 GumJS 的 JS 线程（NativeFunction 同步执行），可能尚未 attach JVM →
//    GetEnv 失败即 AttachCurrentThread 且**不 detach**（JS 线程长驻，与上游 vm.perform
//    对 jsThread 的处理一致；重复调用时 GetEnv 直接成功，不会重复 attach）。
//    锁纪律：全程**不持有** g_pending_lock / g_script_lock / g_callback_lock 进 Kotlin
//    （Kotlin 侧可能反过来回到 nativeCallJs，持锁进 Kotlin 会造成锁序/自等）。
//
// ② 下行 同线程同步 app 线程 → JS：nativeDispatchHook（本段实现）
//    上游 implement() 的 hook 回调机制是 NativeCallback：ART 在 app（拦截）线程调用，
//    JS 就在该线程同步执行。故 Kotlin → nativeDispatchHook → JS dispatcher 全程同一线程：
//    无 post、无 pending、无 500ms 泵等待、无锁。JS 返回的 char* 属 JS
//    （Memory.allocUtf8String），本层只读、**不 free**。
//
// 与 RouteB 的关系：上面 RouteB 的 nativeCallJs 是**反向**通道（Kotlin→JS 异步 rpc +
// 500ms 泵等待），保留为 dispatcher 未注册/失败时的兜底；两条通道不要混用。
// 重入保护：JS 执行上下文内（JS 线程的 lsp_sync_call、或 app 线程的 nativeDispatchHook）
// 再进 nativeCallJs 必然自己等自己（JS 引擎单线程 + JS 锁），→ 立即返回
// {"__reentrant":true}，Kotlin 收到该标记直接 proceed 兜底，不吃 500ms 超时。
//
// 句柄约定：全局/局部引用句柄 = (void*)(intptr_t)jobject 原值，Kotlin 一律用 Long 传递、
// 不做算术；lsp_new_global_ref / lsp_delete_global_ref 供 JS 钉住/释放对象（如 Class），
// nativeNewGlobalRef / nativeNewLocalRef / nativeDeleteGlobalRef / nativePtrOf 供 Kotlin 侧
// 在回调协议里搬运 <E> 编码里的对象引用。C ABI 导出用默认可见性（显式 attribute 防御
// 未来误加 -fvisibility=hidden；JNIEXPORT 本身已带 default 可见性）。
// ============================================================================

// lspSync 目标：GumJsBridge 是 Kotlin object + @JvmStatic → static java.lang.String lspSync(java.lang.String)
#define LSP_SYNC_SIG "(Ljava/lang/String;)Ljava/lang/String;"
// nativeCallJs 同线程重入快速返回标记（Kotlin 收到即走 chain.proceed 兜底）
#define LSP_REENTRANT_MARKER "{\"__reentrant\":true}"

// —— 上行缓存：GumJsBridge 类全局引用 / lspSync 静态方法 id ——
// 建立方式：nativeInitEngine 用传入的 jclass 参数 NewGlobalRef（**禁止 FindClass**：
// 模块类不在 app classloader 里，attach 线程的 FindClass 找不到）。
static std::atomic<jclass> g_bridge_class{nullptr};
static std::atomic<jmethodID> g_sync_mid{nullptr};

// —— 下行：JS 侧 NativeCallback 函数指针（nullptr = 未注册；脚本加载/卸载时置空） ——
static std::atomic<void *> g_js_dispatcher{nullptr};

// —— JS 执行上下文识别（nativeCallJs 重入自等保护） ——
// 注意：on_message 在消息泵线程（默认 GMainContext）上执行，**不是** JS 线程，不能在那里记录，
// 否则会把泵线程误标成 JS 线程。JS 线程身份由第一次 lsp_sync_call 反推（该 C 导出只可能被
// JS 的 NativeFunction 在 JS 线程同步调用），再配合线程局部“正在同步执行 JS”的深度：
//   - JS 线程进 lsp_sync_call（Kotlin lspSync 内可能回流 nativeCallJs）；
//   - app 线程进 nativeDispatchHook（其 JS 回调内可能回流 nativeCallJs）。
static std::atomic<GThread *> g_js_thread_id{nullptr};
static thread_local int t_lsp_js_depth = 0;

/** 标记“本线程当前处于 JS 执行上下文”，作用域结束自动复原（提前 return 亦生效）。 */
struct LspJsGuard {
    LspJsGuard() { t_lsp_js_depth++; }
    ~LspJsGuard() { t_lsp_js_depth--; }
};

/** 取当前线程 JNIEnv：未 attach 则 AttachCurrentThread 且**不 detach**（JS 线程长驻，
 *  与上游 vm.perform 对 jsThread 的处理一致）；已 attach 时 GetEnv 直接成功、不重复 attach。
 *  JVM 未初始化（nativeInitEngine 之前）返回 nullptr。 */
static JNIEnv *lsp_env_get(void) {
    if (g_vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    return env;
}

/** 首次在 JS 线程调用时记录线程身份（幂等；仅在 JS 线程侧路径调用）。 */
static void lsp_note_js_thread(void) {
    if (g_js_thread_id.load(std::memory_order_relaxed) == nullptr) {
        g_js_thread_id.store(g_thread_self(), std::memory_order_release);
    }
}

/** 当前线程是否处于 JS 执行上下文（同线程再调 nativeCallJs 必自等超时）。 */
static bool lsp_in_js_context(void) {
    if (t_lsp_js_depth > 0) return true;
    GThread *jt = g_js_thread_id.load(std::memory_order_acquire);
    return jt != nullptr && jt == g_thread_self();
}

/** nativeInitEngine 调用：用传入的 jclass NewGlobalRef 缓存 GumJsBridge 类引用。
 *  @return false = 缓存失败（env/clazz 无效或 NewGlobalRef OOM），调用方降级继续。 */
static bool lsp_cache_bridge_class(JNIEnv *env, jclass clazz) {
    if (env == nullptr || clazz == nullptr) {
        LOGE("[sync] cache bridge class failed (env=%p clazz=%p)", (void *) env, (void *) clazz);
        return false;
    }
    if (g_bridge_class.load(std::memory_order_acquire) == nullptr) {
        jclass cached = (jclass) env->NewGlobalRef(clazz);
        if (cached == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGE("[sync] NewGlobalRef(GumJsBridge) failed");
            return false;
        }
        g_bridge_class.store(cached, std::memory_order_release);
        LOGI("[sync] GumJsBridge class cached (global ref)");
    }
    return true;
}

/** 惰性补齐 lspSync 的 jmethodID（缓存命中即 no-op）。失败时清掉 NoSuchMethodError，
 *  返回 nullptr；lsp_sync_call 每次会重试（Kotlin 侧签名修正后无需重启进程）。 */
static jmethodID lsp_ensure_sync_mid(JNIEnv *env) {
    jmethodID mid = g_sync_mid.load(std::memory_order_acquire);
    if (mid != nullptr) return mid;
    jclass cls = g_bridge_class.load(std::memory_order_acquire);
    if (cls == nullptr) return nullptr;
    mid = env->GetStaticMethodID(cls, "lspSync", LSP_SYNC_SIG);
    if (mid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();   // NoSuchMethodError 不得悬挂
        LOGE("[sync] GetStaticMethodID lspSync%s not found", LSP_SYNC_SIG);
        return nullptr;
    }
    g_sync_mid.store(mid, std::memory_order_release);
    LOGI("[sync] lspSync method id cached");
    return mid;
}

/** 失败结果：写合法 JSON 到 thread_local 缓冲并返回其 c_str（契约：永不返回非法 JSON）。 */
static const char *lsp_sync_fail(std::string &out, const char *msg) {
    out.assign("{\"ok\":false,\"err\":\"");
    out.append(msg != nullptr ? msg : "lspSync failed");
    out.append("\"}");
    return out.c_str();
}

// C ABI 导出（默认可见性；显式 visibility 防御未来误加 -fvisibility=hidden）
#define LSP_EXPORT extern "C" __attribute__((visibility("default")))

/**
 * 同步回调 Kotlin：GumJsBridge.lspSync(String): String。
 * 返回 thread_local UTF-8 NUL 结尾缓冲（下一次同线程调用前有效）。
 * 任何 JNI/异常/空返回都不得崩：失败返回 {"ok":false,"err":"..."}（合法 JSON）。
 * 锁纪律：本函数不取任何本文件的锁（Kotlin 侧可能回到 nativeCallJs）。
 */
LSP_EXPORT const char *lsp_sync_call(const char *json) {
    thread_local std::string t_result;   // 契约：同线程下次调用前有效
    LspJsGuard guard;                    // 本线程正在 JS 执行上下文（nativeCallJs 重入保护）
    lsp_note_js_thread();                // 本线程 = JS 线程（NativeFunction 同步执行）

    JNIEnv *env = lsp_env_get();
    if (env == nullptr) return lsp_sync_fail(t_result, "no JavaVM or attach failed");

    // 类引用在 nativeInitEngine 缓存；方法 id 未命中则惰性重试（同样只用缓存 jclass）
    if (g_bridge_class.load(std::memory_order_acquire) == nullptr ||
        lsp_ensure_sync_mid(env) == nullptr) {
        return lsp_sync_fail(t_result, "GumJsBridge.lspSync not cached");
    }

    const char *arg = (json != nullptr) ? json : "";
    // 输入侧 UTF 修复：与既有代码一致，避免 NewStringUTF 对 4 字节 UTF-8（emoji）的未定义行为
    jstring jarg = lsp_new_jstring(env, arg);
    if (jarg == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return lsp_sync_fail(t_result, "lspSync arg conversion failed");
    }

    jobject jres = env->CallStaticObjectMethod(g_bridge_class.load(std::memory_order_acquire),
                                               g_sync_mid.load(std::memory_order_acquire), jarg);
    env->DeleteLocalRef(jarg);

    if (env->ExceptionCheck()) {
        // Kotlin 侧抛异常（如 install 失败的自定义异常）：清掉并回合法失败 JSON，JS 侧据 ok:false 抛
        env->ExceptionClear();
        if (jres != nullptr) env->DeleteLocalRef(jres);
        return lsp_sync_fail(t_result, "lspSync exception");
    }
    if (jres == nullptr) return lsp_sync_fail(t_result, "lspSync null/exception");

    gchar *utf8 = lsp_get_utf8(env, (jstring) jres, nullptr);
    env->DeleteLocalRef(jres);
    if (utf8 == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();   // GetStringChars OOM 等：不悬挂异常
        return lsp_sync_fail(t_result, "lspSync result conversion failed");
    }
    t_result.assign(utf8);   // 标准 UTF-8 → thread_local 缓冲
    g_free(utf8);
    if (t_result.empty()) return lsp_sync_fail(t_result, "lspSync empty result");
    return t_result.c_str();
}

/** 把 JS 线程上的 jobject 局部引用钉成全局引用（失败 NULL）。句柄即 jobject 原值。
 *  注：不在此记录 JS 线程身份——本函数也可能在 nativeDispatchHook 的 app 线程 JS 回调里
 *  被调用，记录会把 app 线程误标成 JS 线程（重入标记必须靠 t_lsp_js_depth 覆盖该场景）。 */
LSP_EXPORT void *lsp_new_global_ref(void *localRef) {
    if (localRef == nullptr) return nullptr;
    JNIEnv *env = lsp_env_get();
    if (env == nullptr) return nullptr;
    jobject g = env->NewGlobalRef(reinterpret_cast<jobject>(localRef));
    if (g == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return reinterpret_cast<void *>(g);
}

/** 删除 lsp_new_global_ref 返回的全局引用（无效/未初始化时静默）。 */
LSP_EXPORT void lsp_delete_global_ref(void *globalRef) {
    if (globalRef == nullptr) return;
    JNIEnv *env = lsp_env_get();
    if (env == nullptr) return;
    env->DeleteGlobalRef(reinterpret_cast<jobject>(globalRef));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ----------------------------------------------------------------------------
// 下行：同线程同步 JS 回调（app/拦截线程 → JS NativeCallback）
// ----------------------------------------------------------------------------

/** JS 侧 dispatcher 原型：char* fn(const char* payloadJson)。
 *  返回值为 JS 分配的 UTF-8 缓冲（Memory.allocUtf8String），生命周期由 JS 管，本层不 free。 */
typedef char *(*LspJsDispatchFn)(const char *payload);

/** 注册/清除 JS dispatcher 函数指针（fn == 0 → 清除）。 */
extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeSetDispatcher(JNIEnv *, jclass, jlong fn) {
    g_js_dispatcher.store(reinterpret_cast<void *>((intptr_t) fn), std::memory_order_release);
    LOGI("[dispatch] js dispatcher %s", (fn != 0) ? "registered" : "cleared");
}

/**
 * 同线程同步调用 JS：payload 为 UTF-8 JSON，返回结果 JSON（jstring）。
 * 失败/未注册返回 NULL（Kotlin 走 nativeCallJs 异步兜底）。
 * 锁纪律：不取 pending/script/callback 锁（JS 内可能反过来调 JNI 触发其它路径）。
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeDispatchHook(
        JNIEnv *env, jclass, jstring jpayload) {
    if (env == nullptr) return nullptr;
    // F2：先"进场"再取指针并调用。nativeUnloadScript 会 dispatch_quiesce() 等我们退出后
    // 才销毁 JS 运行时；否则这里可能拿着已卸载运行时的函数指针调用（UAF）。
    if (!dispatch_enter()) return nullptr;   // 正在卸载 → 按"没进 JS"处理，Kotlin 走 async 兜底
    struct DispatchLeave { ~DispatchLeave() { dispatch_leave(); } } leave_guard;
    void *fn_raw = g_js_dispatcher.load(std::memory_order_acquire);
    if (fn_raw == nullptr) return nullptr;   // 未注册（脚本未加载/已卸载）

    gchar *json = lsp_get_utf8(env, jpayload, nullptr);   // payload 可能含非 BMP 字符
    if (json == nullptr) return nullptr;
    if (env->ExceptionCheck()) {   // 防御：进 JS 前清掉悬挂异常，避免污染 JS 回调内的 JNI
        env->ExceptionClear();
        g_free(json);
        return nullptr;
    }

    // 进入 JS 执行上下文：本线程内若回流 nativeCallJs，必须快速返回而不是自等 500ms
    LspJsGuard guard;
    char *ret = ((LspJsDispatchFn) (intptr_t) fn_raw)(json);
    g_free(json);

    if (ret == nullptr) return nullptr;   // JS 侧异常/空结果 → 交给 Kotlin async fallback
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    // ret 属 JS（Memory.allocUtf8String）：只读转换，不 free、不 DeleteLocalRef 之外的释放
    jstring out = lsp_new_jstring(env, ret);
    if (out == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    return out;
}

/** jobject → (jlong)(intptr_t)jobject（null → 0）。供 Kotlin 把 app 线程局部引用地址交给
 *  同线程 JS 用 Java.cast(ptr, ...) 临时引用；调用方必须保证在该局部引用有效期内使用。 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativePtrOf(JNIEnv *, jclass, jobject obj) {
    return (jlong) reinterpret_cast<intptr_t>(obj);
}

// ----------------------------------------------------------------------------
// 引用助手 JNI（Kotlin 侧 private external；回调协议 <E> 编码搬运对象引用）
// ----------------------------------------------------------------------------

/** 局部引用 → 全局引用，返回句柄；obj == NULL → 0。 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeNewGlobalRef(JNIEnv *env, jclass, jobject obj) {
    if (env == nullptr || obj == nullptr) return 0;
    jobject g = env->NewGlobalRef(obj);
    if (g == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    return (jlong) reinterpret_cast<intptr_t>(g);
}

/** 全局引用句柄 → 局部引用；handle == 0 → NULL。 */
extern "C"
JNIEXPORT jobject JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeNewLocalRef(JNIEnv *env, jclass, jlong handle) {
    if (env == nullptr || handle == 0) return nullptr;
    return env->NewLocalRef(reinterpret_cast<jobject>((intptr_t) handle));
}

/** 删除全局引用句柄；handle == 0 → 静默。 */
extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeDeleteGlobalRef(JNIEnv *env, jclass, jlong handle) {
    if (env == nullptr || handle == 0) return;
    env->DeleteGlobalRef(reinterpret_cast<jobject>((intptr_t) handle));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// GumJS on_message -> JNI 回调宿主层 (console.log / send() / frida:rpc reply)
static void on_message(const gchar *message, GBytes *data, gpointer user_data) {
    (void) data; (void) user_data;

    // ① F4：加载期错误捕获——先于一切检查（callback 未注册时同样必须捕获；
    //    修复前该路径被 g_callback==nullptr 提前 return 跳过 → “加载即报错”被漏判为成功）。
    if (message != nullptr &&
        strncmp(message, ERROR_MESSAGE_PREFIX, sizeof(ERROR_MESSAGE_PREFIX) - 1) == 0) {
        g_load_error_pending.store(true);
    }

    if (g_vm == nullptr) return;

    // ② native 层消费（rpc reply / rpc_inner）——不依赖 callback，必须先于 callback 检查：
    //    修复前 callback 未注册时 reply 被丢弃 → 调用方 500ms 超时（丢回复）。
    if (message != nullptr) {
        // t10-C②：入站消息头诊断（仅 frida:rpc 数组消息——send/console.log 走 Kotlin UI 日志不重复打）
        if (strstr(message, "frida:rpc") != nullptr) {
            LOGI("[onmsg] rpc-head=%.48s", message);
        }
        if (handle_rpc_reply(message)) return;
        if (handle_rpc_inner(message)) return;
    }
    if (message == nullptr) return;   // 防御：无内容可上行（原代码未查空，NewStringUTF(NULL) 属未定义）

    // ③ 上行 Kotlin（需 callback；锁内取局部引用，防与 nativeSetCallback 交换竞争 → UAF）
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jobject cb = nullptr;
    jmethodID mid = nullptr;
    g_mutex_lock(&g_callback_lock);
    if (g_callback != nullptr) {
        cb = env->NewLocalRef(g_callback);   // 局部引用保活：此后全局引用即使被换掉，本引用仍有效
        mid = g_callback_mid;
    }
    g_mutex_unlock(&g_callback_lock);

    if (cb != nullptr && mid != nullptr) {
        jstring jmsg = lsp_new_jstring(env, message);
        if (jmsg != nullptr) {
            env->CallVoidMethod(cb, mid, jmsg);
            env->DeleteLocalRef(jmsg);
            // Kotlin 回调若抛异常：在 JNI 层清理，避免悬挂异常影响后续 JNI 调用/线程分离
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
    }
    if (cb != nullptr) env->DeleteLocalRef(cb);

    if (attached) g_vm->DetachCurrentThread();
}

// ============================================================================
// 常驻消息泵（根因修复，2026-10-05）：脚本输出积压 → 需热更新才涌出
//
// 症状（真机实测）：脚本 main() 日志后长时间静默；手动点“运行”（热更新）后，
//   积压日志一次性涌出——其中 `engine poll left 4760` 与 `left 4720` 的时间戳
//   仅相隔 1ms，而按脚本配置（50ms×40tick）二者必须相隔 2s（排队后冲出的铁证）。
//
// 根因（源码实证）：
//   - 脚本出站消息（send/console.log）经 gum_quick_script_emit 以 idle source 挂到
//     【脚本创建线程的 thread-default context】；Java 线程无 thread-default →
//     回退到【进程默认 GMainContext】（frida-gum gumquickscript.c + glib gmain.c
//     g_main_context_ref_thread_default 回退逻辑）。
//   - 目标进程没有任何线程持续泵送该 context：仅有的派发点是
//     ① nativeLoadScript 尾部的一次性排空（故加载瞬间的 main() 日志能出）；
//     ② nativeCallJs 等待 reply 时的泵（仅 hook 命中时发生）。
//   - 而脚本定时器/逻辑照常运行（定时器挂 gum-js-loop 的 js_context，由调度器主循环
//     泵送，与本问题无关）→ 功能其实执行了，但输出全部积压。
//
// 修复：启动一条常驻线程（lspfrifa-pump）对默认 context 运行主循环——等价于
//   frida-server “主线程跑主循环”的职责。进程内幂等，仅启动一次，随进程结束终止。
// ============================================================================
static std::atomic<bool> g_pump_started{false};

static gpointer lspfrifa_pump_thread_func(gpointer data) {
    (void) data;
    // nullptr → 全局默认 context（与 emit 挂载点一致；该 context 进程生命周期内
    // 永不销毁，无需 ref/unref）。主循环常驻，消息到达即派发。
    GMainLoop *loop = g_main_loop_new(nullptr, FALSE);
    if (loop == nullptr) {
        LOGE("[pump] g_main_loop_new failed");
        return nullptr;
    }
    LOGI("[pump] message pump started (default context)");
    g_main_loop_run(loop);   // 永不退出；随进程结束终止
    return nullptr;
}

/** 幂等启动消息泵线程（首次调用后为 no-op）。 */
static void lspfrifa_ensure_pump(void) {
    if (g_pump_started.exchange(true)) return;
    GThread *th = g_thread_try_new("lspfrifa-pump", lspfrifa_pump_thread_func, nullptr, nullptr);
    if (th == nullptr) {
        g_pump_started.store(false);
        LOGE("[pump] failed to create pump thread");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeInitEngine(JNIEnv *env, jclass clazz) {
    env->GetJavaVM(&g_vm);
    // 第一阶段上行通道：用**传入的 jclass 参数** NewGlobalRef 缓存 GumJsBridge 类引用，
    // 并顺带预取静态方法 lspSync 的 jmethodID。禁止 FindClass：模块类不在 app classloader
    // 里，attach 线程的 FindClass 找不到（见 BRIDGE-CONTRACT.md §1.2）。
    lsp_cache_bridge_class(env, clazz);
    lsp_ensure_sync_mid(env);
    g_mutex_init(&g_pending_lock);
    g_mutex_init(&g_script_lock);     // 引擎审查修复：g_script 生命周期锁
    g_mutex_init(&g_callback_lock);   // 引擎审查修复：g_callback 交换锁
    g_mutex_init(&g_dispatch_lock);   // F2/F3：dispatch 与 unload 的生命周期互斥
    g_cond_init(&g_dispatch_cond);
    g_locks_ready.store(true);

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
    // 常驻消息泵（幂等；根因修复：脚本输出积压 —— 详见本函数上方注释块）
    lspfrifa_ensure_pump();
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeSetCallback(JNIEnv *env, jclass, jobject cb) {
    // 竞态修复：与 on_message（泵线程持续执行）的交换必须原子化。
    // g_locks_ready 守卫：nativeInitEngine 之前若被调用（防御路径），退化为无锁交换（该阶段无并发）。
    const bool locked = g_locks_ready.load();
    if (locked) g_mutex_lock(&g_callback_lock);
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    g_callback_mid = nullptr;
    if (cb != nullptr) {
        jobject g = env->NewGlobalRef(cb);
        // 缓存 jmethodID：避免每条消息 GetObjectClass + GetMethodID（高频日志路径开销）
        jmethodID mid = nullptr;
        jclass cls = env->GetObjectClass(cb);
        if (cls != nullptr) {
            mid = env->GetMethodID(cls, "onScriptMessage", MESSAGE_SIG);
            env->DeleteLocalRef(cls);
        }
        if (mid == nullptr) {
            // 拿不到 mid 就不能留下一个"有引用但永远调不通"的 g_callback：on_message 里
            // `cb != nullptr && mid != nullptr` 会永久静默丢弃所有消息，同时白占一个全局引用。
            // 这里把悬挂异常清掉（GetMethodID 失败会挂 NoSuchMethodError），不留到后续 JNI。
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (g != nullptr) env->DeleteGlobalRef(g);
            LOGE("[callback] onScriptMessage not found; callback NOT installed");
        } else {
            g_callback = g;
            g_callback_mid = mid;
        }
    }
    if (locked) g_mutex_unlock(&g_callback_lock);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeLoadScript(
        JNIEnv *env, jclass, jstring jscript, jstring jname) {

    // 第一阶段下行通道：脚本引用即将失效 → JS 侧 dispatcher 指针必须作废
    // （旧脚本的 main() 若在加载期重新上报，会在 gum_script_load_sync 内覆盖本值）
    g_js_dispatcher.store(nullptr, std::memory_order_release);

    pending_clear_all();   // RouteB：重载前清空在途 replace 请求（等待者回退 proceed）

    if (g_backend == nullptr) { LOGE("engine not inited"); return JNI_FALSE; }

    // 竞态修复：锁内摘除旧脚本 → 锁外卸载（卸载耗时，不在锁内做）。
    // 修复前：g_script 无锁读写，callJs 线程可能在 unload+unref 之后仍解引用 → UAF。
    // F2：拆旧运行时前同样要等在途 dispatch 退出（它们还持有旧运行时的函数指针）。
    dispatch_quiesce();
    DispatchResumeGuard resume_guard;   // 作用域结束（含所有 return 分支）自动放行
    g_mutex_lock(&g_script_lock);
    GumScript *old_script = g_script;
    g_script = nullptr;
    g_mutex_unlock(&g_script_lock);
    if (old_script != nullptr) {
        LOGI("unload previous script first");
        gum_script_unload_sync(old_script, nullptr);
        g_object_unref(old_script);
    }

    // 输入侧 UTF 修复：脚本正文可能含非 BMP 字符（emoji 注释/字符串）
    gchar *src = lsp_get_utf8(env, jscript, nullptr);
    gchar *name = lsp_get_utf8(env, jname, "main");
    if (src == nullptr) src = g_strdup("");
    if (name == nullptr) name = g_strdup("main");
    GError *error = nullptr;

    GumScript *script = gum_script_backend_create_sync(
            g_backend, name, src, nullptr, nullptr, &error);

    if (error != nullptr) {
        LOGE("create script error: %s", error->message);
        g_error_free(error);
        g_free(src);
        g_free(name);
        return JNI_FALSE;
    }
    if (script == nullptr) {
        // 防御：create 返回 NULL 且未设 error（理论上不应发生），避免后续解引用崩溃
        LOGE("create script returned null (no error set)");
        g_free(src);
        g_free(name);
        return JNI_FALSE;
    }

    gum_script_set_message_handler(script, on_message, nullptr, nullptr);

    // F4：gum_script_load_sync 为 void API（无 GError/无返回值），加载期异常只能通过
    // 消息处理器观测。先清零标记再加载，随后泵队列（异常消息在加载时即入队），
    // 若在加载窗口内收到 {"type":"error",...} 则判定加载失败：卸旧+释放+JNI_FALSE。
    g_load_error_pending.store(false);
    gum_script_load_sync(script, nullptr);   // 零重启热加载

    // 泵一次主上下文，确保 on_message / send() 在加载后能及时派发
    GMainContext *ctx = g_main_context_default();
    while (g_main_context_pending(ctx)) {
        g_main_context_iteration(ctx, FALSE);
    }
    // F4 宽限（与常驻泵并发的防漏判）：错误消息可能已被泵线程领取但尚未派发完成
    // （pending 短暂为 false 而 flag 未置位）——最多宽限 ~10ms 并持续排空，避免漏判
    // “加载即报错”。代价：正常加载最多多 ~10ms（一次性，可忽略）。
    for (int i = 0; i < 10 && !g_load_error_pending.load(); i++) {
        g_usleep(1000);
        while (g_main_context_pending(ctx)) {
            g_main_context_iteration(ctx, FALSE);
        }
    }

    if (g_load_error_pending.load()) {
        LOGE("script [%s] load failed (js error reported)", name);
        gum_script_unload_sync(script, nullptr);
        g_object_unref(script);
        g_free(src);
        g_free(name);
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
        gum_script_post(script, boot, nullptr);
        g_free(boot);
        // 非阻塞抽干一次（load 泵的延续；若 reply 已回，LOGI 立即出现）
        GMainContext *dctx = g_main_context_default();
        while (g_main_context_pending(dctx)) {
            g_main_context_iteration(dctx, FALSE);
        }
        LOGI("[boot] boot rpc posted (id=lsp-boot-check)");
    }

    // 发布新脚本（锁内交换）：此后 callJs/postOriginalReply 才能获取其强引用。
    // 放在最后一步：保证发布时脚本已完全就绪（加载错误检查、boot 探针、drain 均已完成）。
    g_mutex_lock(&g_script_lock);
    g_script = script;
    g_mutex_unlock(&g_script_lock);

    g_free(src);
    g_free(name);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeUnloadScript(JNIEnv *, jclass) {
    // F2：先拒绝新 dispatch 并等在场的跑完，再销毁运行时（否则在途 dispatch 会调已释放的
    // 函数指针 = UAF）。顺序不能反：quiesce 之后指针才允许失效。
    dispatch_quiesce();
    // 第一阶段下行通道：脚本卸载后 JS 侧函数指针必失效
    g_js_dispatcher.store(nullptr, std::memory_order_release);
    pending_clear_all();   // RouteB: 清空在途请求
    // 竞态修复：同 nativeLoadScript——锁内摘除、锁外卸载。
    g_mutex_lock(&g_script_lock);
    GumScript *old_script = g_script;
    g_script = nullptr;
    g_mutex_unlock(&g_script_lock);
    if (old_script != nullptr) {
        gum_script_unload_sync(old_script, nullptr);
        g_object_unref(old_script);
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
    // 同线程重入快速返回：当前线程已处于 JS 执行上下文（JS 线程的 lsp_sync_call 内，或
    // app 线程的 nativeDispatchHook 同步执行 JS 时）再 post+等 500ms = 自己等自己必超时
    // （JS 引擎单线程 + JS 锁，reply 不可能在本线程阻塞期间被处理）。
    // 直接回 {"__reentrant":true}，Kotlin 收到该标记走 chain.proceed 兜底。
    if (lsp_in_js_context()) return env->NewStringUTF(LSP_REENTRANT_MARKER);

    // 竞态修复：锁内取强引用（防与 reload 的 unload+unref 竞争 → UAF）；用毕 unref。
    GumScript *script = lsp_acquire_script();
    if (script == nullptr) return env->NewStringUTF("");
    // F3：lsp_acquire_script 只保住了 GObject，**没**保住"脚本仍处于 loaded 状态"。
    // 并发的 nativeUnloadScript 可以在我们 gum_script_post 之前把 JS core 卸掉，
    // post 就落到已卸载的运行时上。用与 dispatch 同一套 in-flight 守卫：
    // unload 的 dispatch_quiesce() 会等这里退出，这里的 dispatch_enter() 在卸载中直接失败。
    if (!dispatch_enter()) {
        g_object_unref(script);
        return env->NewStringUTF("");
    }
    struct CallJsLeave {
        ~CallJsLeave() { dispatch_leave(); }
    } calljs_leave;

    // 输入侧 UTF 修复：payload（含用户参数）可能含非 BMP 字符
    gchar *id = lsp_get_utf8(env, jid, "");
    gchar *payload = (jpayload != nullptr) ? lsp_get_utf8(env, jpayload, nullptr) : nullptr;

    gboolean do_post = (payload != nullptr && payload[0] != '\0');

    // t10-C②：nativeCallJs 入口诊断（id + 是否首次发请求 + payload 长度）
    LOGI("[calljs] id=%s post=%d payload_len=%zu", id, do_post ? 1 : 0,
        payload != nullptr ? strlen(payload) : 0);

    g_mutex_lock(&g_pending_lock);
    if (g_pending == nullptr) {
        // key_destroy = g_free：g_hash_table_insert 用的是 g_strdup(id)，没有 key_destroy
        // 时每次 remove/销毁都会泄漏那个 strdup 出来的 id 字符串（每次 replace 回调一个）。
        g_pending = g_hash_table_new_full(lsp_str_hash, lsp_str_equal, g_free, nullptr);
    }
    LspPending *p = (LspPending *)g_hash_table_lookup(g_pending, id);
    if (p == nullptr) {
        p = pending_new();
        g_hash_table_insert(g_pending, g_strdup(id), p);
    }
    g_mutex_unlock(&g_pending_lock);

    if (do_post) {
        gchar *msg = build_rpc_call(id, "__lspHookReply", id, payload);
        gum_script_post(script, msg, nullptr);
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

    // UTF-8 安全转换（reply 可能含任意用户字符串）
    jstring jout = lsp_new_jstring(env, out != nullptr ? out : "");
    if (jout == nullptr) jout = env->NewStringUTF("");
    g_free(out);

    g_free(payload);
    g_free(id);
    g_object_unref(script);
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
    // 竞态修复：同 callJs——锁内取强引用，用毕 unref。
    GumScript *script = lsp_acquire_script();
    if (script == nullptr) return;
    // F3：同 nativeCallJs——强引用不代表"仍在 loaded"，并发 unload 会让 post 落到已卸载的运行时。
    if (!dispatch_enter()) {
        g_object_unref(script);
        return;
    }
    struct PostLeave {
        ~PostLeave() { dispatch_leave(); }
    } post_leave;
    // 输入侧 UTF 修复：返回值 JSON 可能含非 BMP 字符
    gchar *id = lsp_get_utf8(env, jid, "");
    gchar *retJson = (jretJson != nullptr) ? lsp_get_utf8(env, jretJson, "null") : g_strdup("null");
    gint64 seq = g_orig_seq.fetch_add(1) + 1;
    gchar orig_id[32];
    g_snprintf(orig_id, sizeof(orig_id), "lsp-orig-%lld", (long long) seq);
    gchar *msg = build_rpc_call(orig_id, "__lspHookOriginalReply", id, retJson);
    gum_script_post(script, msg, nullptr);
    g_free(msg);
    g_free(retJson);
    g_free(id);
    g_object_unref(script);
}
