#!/usr/bin/env bash
# LSPFRIFA Kotlin 已知坑静态扫描器（DSH 侧无编译器/无 JDK 的补偿质检）
# 规则库 = 本项目失败历史 + 实核清单；每轮 UI 交付前运行；0 输出=通过
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/kotlin"
files=$(find "$SRC" -name "*.kt")
issues=0

scan() { # $1=file $2=reason $3=regex
  if grep -nE "$3" "$1" >/dev/null 2>&1; then
    echo "❌ [$2] $1  $(grep -nE "$3" "$1" | head -2 | tr '\n' ' ')"
    issues=$((issues+1))
  fi
}

for f in $files; do
  # 1. padding 参数混搭（horizontal 只能与 vertical 搭配；bottom/top 只能与 start/end 搭配）
  scan "$f" "padding-horizontal-bottom混搭" "padding\(horizontal\s*=\s*[^)]*,(top|bottom)\s*="
  scan "$f" "padding-bottom-horizontal混搭" "padding\((top|bottom)\s*=\s*[^)]*,horizontal\s*="
  # 2. ImageVector 错误包（正确: androidx.compose.ui.graphics.vector.ImageVector）
  scan "$f" "ImageVector包少.vector" "(import androidx\.compose\.ui\.graphics\.(?!vector\.)ImageVector|androidx\.compose\.ui\.graphics\.ImageVector)"
  # 3. Miuix Colors 不存在的 token（正确: onSurfaceVariantSummary / outline / dividerLine / tertiaryContainer）
  scan "$f" "Miuix不存在的token-onSurfaceVariant" "colorScheme\.onSurfaceVariant([^S]|$)"
  scan "$f" "Miuix不存在的token-outlineVariant" "colorScheme\.outlineVariant"
  scan "$f" "Miuix不存在的token-tertiary\b" "colorScheme\.tertiary([^C]|$)"
  # 4. sora 0.23.6 不存在的方法名（正确: setDisplayLnPanel）
  scan "$f" "sora-fake-setLineNumberEnabled" "setLineNumberEnabled"
  # 5. @Composable 调用被包进非 @Composable lambda（runCatching { <composable> } 模式）
  scan "$f" "runCatching包裹Composable调用" "runCatching\s*\{[^}]{0,120}(dynamicLightColorScheme|rememberLayerBackdrop|textureBlur)"
  # 9. KDoc/块注释行内提前闭合（`*/` 出现在注释续行中间）
  #    后果：注释被提前终止，其后文本被当作 Kotlin 代码 → 编译器刷屏
  #    "Syntax error: Expecting member declaration"（本项目实证 38 条，2026-10-05）。
  #    模式：行首=空白+`*`（非 `*/`），行内出现 `*/` → 命中。
  #    不误报 ` */` 终止符（其后无字符）、`/** x */` 单行注释（不以 * 开头）、
  #    `/* @__PURE__ */`（不以 * 开头）。
  scan "$f" "KDoc提前闭合(*/后仍有文本)" '^[[:blank:]]*\*([^*]|\*[^/])*\*/[[:blank:]]*[^[:blank:]]'
  # 6. 非 import 语句插在 import 区（const 夹在 package 与 import 之间）
  if awk 'NR<=3 || /^import /' "$f" | grep -qE '^(private |public |internal |object |val |fun |class )' && \
     ! head -3 "$f" | grep -qE '^(private |public |internal |object |val |fun |class )'; then
    awk '/^import /{if(found==0 && NR>4){found=1}}' "$f"
    if head -20 "$f" | grep -qE '^(private const|public const)'; then
      first_import=$(grep -n '^import ' "$f" | head -1 | cut -d: -f1)
      first_const=$(grep -nE '^(private |public )?(const|val |fun |class |object )' "$f" | head -1 | cut -d: -f1)
      if [ -n "$first_import" ] && [ -z "$first_const" ] || [ -n "$first_const" ] && [ "$first_const" -lt "$first_import" ]; then
        :
      fi
    fi
  fi
done

# 6b. 简化检查：文件前 3 行含 const/val/fun（package 后 import 前）→告警
for f in $files; do
  if sed -n '3p' "$f" | grep -qE '^(private |public |internal )?(const|val |fun |class |object |enum |data )'; then
    echo "❌ [非import语句插入import区] $f (第3行非import)"
    issues=$((issues+1))
  fi
done

# 7. 括号平衡（仅提示）
# 已修正（2026-09-26）：先剔除**字符字面量** '{' / '}' —— 本项目已实证该误报
# （ScriptImport.kt 的 LBRACE/RBRACE 常量曾致 30/29 假阳性，浪费人工排查）。
for f in $files; do
  src=$(sed -e "s/'{'//g" -e "s/'}'//g" "$f")
  ob=$(printf '%s' "$src" | grep -o '{' | wc -l); cb=$(printf '%s' "$src" | grep -o '}' | wc -l)
  po=$(printf '%s' "$src" | grep -o '(' | wc -l); pc=$(printf '%s' "$src" | grep -o ')' | wc -l)
  if [ "$ob" != "$cb" ]; then echo "⚠️ 花括号不平衡: $f {{$ob/$cb}}"; fi
  # 圆括号检查默认关闭：本仓库 30 个 kt 中已有 2 处确定性误报
  # （JavaBridgeBundle.kt 正则串 `[^)]`；SettingsScreenV093.kt 注释里全角`（`配半角`)`），
  # 而历史真实 bug 全部由"花括号净值 + 未导入符号"捕获 → 圆括号噪声大于收益。
  # 需要时：LSPFRIFA_CHECK_PARENS=1 bash tools/check-kt.sh
  if [ "${LSPFRIFA_CHECK_PARENS:-0}" = "1" ] && [ "$po" != "$pc" ]; then
    echo "⚠️ 圆括号不平衡: $f (($po/$pc))"
  fi
done

if [ "$issues" -eq 0 ]; then echo "✅ 已知坑扫描通过（0 规则命中，规则库=padding混搭/ImageVector包/token误用/sora假API/runCatching包Composable/import区污染）"; else
  echo "❌ 共 $issues 处命中 —— 交付前必须清零"; exit 1
fi

# ============ 9. 死常量自查（v3 新增，2026-10-07） ============
# 背景：本项目已累积 4 个"声明后从未使用"的常量，其中 2 个是**伪装成同步点的死代码**
#   （注释写"与宿主 XX 一致"，实际零使用 → 将来改常量时会命中它，让人误以为已同步）：
#     · ScriptStore.REMOTE_WRITE_BUDGET  （400KB 拦截后永不可达）
#     · LSPFRIFAModule.MAX_SCRIPT_BYTES  （注释声称"与宿主一致，仅用于日志告警"，实为零使用）
#     · LSPFRIFAModule.INNER_KEY_MODULES · ScriptImport.Origin.MANUAL
# 判据（**必须区分可见性**，否则产生假阴性——本项目实证 CIRCUIT_THRESHOLD/CLIP 被误报）：
#   · private const val → 仅同文件可见：同文件内、排除声明行/注释行后出现 0 次 = 死
#   · public  const val → 可跨文件：全项目内、排除声明行/注释行后出现 0 次 = 死
# 误报方向是"漏报"而非"误杀"（安全方向）；真需保留的常量请加 `// keep:` 注释。
if command -v python3 >/dev/null 2>&1; then
  dead=$(python3 - "$SRC" <<'PY'
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
files = list(root.rglob('*.kt'))
texts = {f: f.read_text(encoding='utf-8').splitlines() for f in files}
dead = []
for f, lines in texts.items():
    for i, line in enumerate(lines, 1):
        m = re.search(r'^[ \t]*(private\s+)?const\s+val\s+(\w+)', line)
        if not m:
            continue
        is_private, name = bool(m.group(1)), m.group(2)
        if '// keep:' in line:
            continue

        def is_use(l, same_file_and_line=False):
            if same_file_and_line:
                return False
            s = l.strip()
            return (name in l and 'const val' not in l
                    and not s.startswith('*') and not s.startswith('//')
                    and not s.startswith('/*') and '://' not in l)

        if is_private:
            cnt = sum(1 for j, l in enumerate(lines, 1) if j != i and is_use(l))
        else:
            cnt = 0
            for g, glines in texts.items():
                for j, l in enumerate(glines, 1):
                    if is_use(l, same_file_and_line=(g == f and j == i)):
                        cnt += 1
        if cnt == 0:
            vis = 'private' if is_private else 'public'
            dead.append(f"{f}:{i}: {name} ({vis})")
for d in dead:
    print(d)
PY
)
  if [ -z "$dead" ]; then
    echo "✅ 死常量扫描通过（0 命中，规则=private同文件/public全项目 双判据）"
  else
    echo "❌ 死常量 $(( $(printf '%s' "$dead" | wc -l) )) 处 —— 删除，或加 '// keep:' 注明保留理由"
    printf '%s\n' "$dead" | sed 's/^/     /'
    exit 1
  fi
else
  echo "⚠️ python3 不可用——死常量扫描跳过"
fi

# ============ 8. 未导入符号检测（"使用了但未 import"——编译器级未解析前兆） ============
# 背景：clickable/ImageVector/Column 等已多次因缺 import 编译失败，人工 grep 不可靠；
# 本规则 = 符号库对照法（v2）：库 = 全项目 import 名全集（8a 自动生成）∪ 预置 compose/kotlinx
# 高频符号；文件 token 命中库内符号而无对应 import/声明 → 报"疑似漏 import"。
# 检测器输出 ❌ 计为 issue（exit 1）；⚠️（通配 import 跳过/by 委托特征）仅提示。
if command -v awk >/dev/null 2>&1; then
  TMPD=$(mktemp -d 2>/dev/null || echo "/tmp/lspfrifa-check-$$")
  mkdir -p "$TMPD/decls"
  : > "$TMPD/symbols.txt"
  # 8a-0. AIDL 生成类（IScriptExecutor/ILogReceiver 等）与 .kt 同包引用无需 import——补入声明集
  if [ -d "$ROOT/app/src/main/aidl" ]; then
    for a in $(find "$ROOT/app/src/main/aidl" -name "*.aidl" 2>/dev/null); do
      rel=$(echo "$a" | sed "s#^$ROOT/app/src/main/aidl/##")
      apkg=$(dirname "$rel" | tr '/' '.')
      aname=$(basename "$a" ".aidl")
      apf=$(printf '%s' "$apkg" | tr '.' '_')
      [ -n "$apf" ] && echo "$aname" >> "$TMPD/decls/$apf"
    done
  fi
  # 8a. 收集"同包声明"集 + "全项目符号库"（import 名全集：末段名/as 别名；通配跳过）
  for f in $files; do
    pkg=$(awk 'NR<=40 && /^package /{print $2; exit}' "$f")
    pf=$(printf '%s' "$pkg" | tr '.' '_')
    if [ -z "$pf" ]; then continue; fi
    awk '{
      if ($0 ~ /^[ \t]*(data |sealed |abstract |open |final |private |public |internal |value |expect |actual |inline |inner )*(class|object|interface|enum class|typealias|annotation class)[ \t]+[A-Za-z_][A-Za-z0-9_]*/) {
        m=$0
        sub(/^[ \t]*(data |sealed |abstract |open |final |private |public |internal |value |expect |actual |inline |inner )*(class|object|interface|enum class|typealias|annotation class)[ \t]+/, "", m)
        if (match(m, /[A-Za-z_][A-Za-z0-9_]*/)) print substr(m, RSTART, RLENGTH)
      } else if ($0 ~ /^[ \t]*(private |public |internal )*(fun|val|var)[ \t]+[A-Z][A-Za-z0-9_]*/) {
        m=$0
        sub(/^[ \t]*(private |public |internal )*(fun|val|var)[ \t]+/, "", m)
        if (match(m, /[A-Z][A-Za-z0-9_]*/)) print substr(m, RSTART, RLENGTH)
      }
    }' "$f" >> "$TMPD/decls/$pf"
    # 符号库：import 末段名 / as 别名（* 通配行跳过）
    awk '
      /^import / {
        imp=$0; sub(/^import /, "", imp)
        if (imp ~ /\*$/) next
        nm=imp
        if (match(nm, /[ \t]+as[ \t]+[A-Za-z_][A-Za-z0-9_]*$/)) { sub(/.*[ \t]+as[ \t]+/, "", nm) }
        else { sub(/.*\./, "", nm) }
        sub(/[^A-Za-z0-9_].*$/, "", nm)
        if (nm != "") print nm
      }' "$f" >> "$TMPD/symbols.txt"
  done
  sort -u "$TMPD/symbols.txt" -o "$TMPD/symbols.txt"
  # 8b. 逐文件检测（samepkg=同包声明；symfile=符号库）
  sec8_hits=0
  for f in $files; do
    pkg=$(awk 'NR<=40 && /^package /{print $2; exit}' "$f")
    pf=$(printf '%s' "$pkg" | tr '.' '_')
    if [ -z "$pf" ]; then pf="nopkg"; fi
    hits=$(awk -v samepkg="$TMPD/decls/$pf" -v symfile="$TMPD/symbols.txt" -f "$ROOT/tools/check-imports.awk" "$f" 2>/dev/null)
    if [ -n "$hits" ]; then
      echo "$hits"
      while IFS= read -r hl; do
        case "$hl" in ❌*) sec8_hits=$((sec8_hits+1)) ;;
        esac
      done <<EOF
$hits
EOF
    fi
  done
  rm -rf "$TMPD"
  if [ "$sec8_hits" -eq 0 ]; then
    echo "✅ 未导入符号检测通过（0 命中，规则=全项目符号库∪预置 compose/kotlinx 高频符号）"
  else
    echo "❌ 未导入符号检测共 $sec8_hits 处 —— 编译前必须清零或人工确认（误报则补 tools/check-imports.awk 白名单）"
    exit 1
  fi
else
  echo "⚠️ awk 不可用——未导入符号检测跳过（建议安装 awk 后复跑）"
fi