# tools/check-imports.awk — LSPFRIFA「使用了但未 import」启发式检测器（v2：符号库对照法）
# 用法: awk -v samepkg=<同包声明文件名(可空)> -v symfile=<全项目符号库文件> -f tools/check-imports.awk <目标.kt>
#
# 原理（单遍，自动、可进化）：
# 1. 状态机剥离字符串(""/"""/char)与注释(//,/* */) → "纯代码流"（保留行号）；
# 2. 收集本文件 import 名（含 as 别名；通配 import → 整文件跳过并提示）；
# 3. 收集本文件声明（class/object/interface/typealias/annotation class 及 PascalCase fun/val/var）；
# 4. 收集全部标识符 token（含小写——扩展函数如 clickable/padding/dp 必须被覆盖）；
# 5. 对照【已知符号库】= symfile（全项目 import 名全集，由 check-kt.sh 8a 自动生成）
#    ∪ 预置 PRE（compose/kotlinx 高频符号，防"首次引入"漏报；只放【必须 import】的符号，
#    不放 kotlin.*/java.lang.* 隐式——隐式符号未 import 合法，不得入库）；
#    判定：token 在库中 且 不在（本文件 import ∪ 声明 ∪ 同包声明）→ 报"疑似使用了但未 import"。
# 6. by 委托特征（getValue/setValue 语法糖不出现在源码）→ 提示级复核。
#
# 已知局限：①同名成员撞库（如某属性恰与 import 名同名）会误报——报告写明"疑似"，
#   人工确认后可从库移除或补声明；②预置库不可能穷尽——新符号首次引入时若此前无人 import，
#   不在自动库内（漏报一次，编译兜底后该符号入库，后续可防）。
# 不适用 kapt/生成类；符号均按大小写敏感匹配。
BEGIN {
  # 预置：必须 import 的非隐式高频符号（compose runtime/foundation/animation/unit + kotlinx.coroutines）
  _pre = "clickable background padding size width height fillMaxWidth fillMaxHeight fillMaxSize offset clip navigationBarsPadding statusBarsPadding safeDrawingPadding imePadding windowInsetsPadding remember mutableStateOf mutableIntStateOf mutableLongStateOf mutableFloatStateOf rememberCoroutineScope rememberUpdatedState derivedStateOf collectAsStateLaunchedEffect snapshotFlow rememberSaveable produceState mutableStateListOf mutableStateMapOf getValue setValue animateColorAsState animateDpAsState animateFloatAsState animateIntAsState animateValueAsState tween spring fadeIn fadeOut slideInVertically slideOutVertically scaleIn scaleOut expandVertically shrinkVertically expandHorizontally shrinkHorizontally launch withContext delay async awaitAll supervisorScope coroutineScope joinAll dp sp"
  n = split(_pre, _p, " ")
  for (i = 1; i <= n; i++) syms[_p[i]] = 1
  if (symfile != "") {
    while ((getline ln < symfile) > 0) { if (ln != "") syms[ln] = 1 }
    close(symfile)
  }
  if (samepkg != "") {
    while ((getline ln < samepkg) > 0) { if (ln != "") decls[ln] = 1 }
    close(samepkg)
  }
  cs = 0; cb = 0; s2 = 0; s3 = 0; wild = 0
  tn = 0; hasBy = 0
}
{
  line = $0
  code = ""
  i = 1; L = length(line)
  while (i <= L) {
    c = substr(line, i, 1)
    c2 = (i < L) ? substr(line, i + 1, 1) : ""
    c3 = (i + 1 < L) ? substr(line, i + 2, 1) : ""
    if (s3) {
      if (c == "\"" && c2 == "\"" && c3 == "\"") { s3 = 0; code = code " "; i += 3 }
      else { code = code " "; i++ }
    } else if (s2) {
      if (c == "\\") { code = code "  "; i += 2 }
      else if (c == "\"") { s2 = 0; code = code " "; i++ }
      else { code = code " "; i++ }
    } else if (cb) {
      if (c == "*" && c2 == "/") { cb = 0; code = code "  "; i += 2 }
      else { code = code " "; i++ }
    } else if (cs) {
      code = code " "; i++
    } else {
      if (c == "/" && c2 == "/") { cs = 1; code = code " "; i += 2 }
      else if (c == "/" && c2 == "*") { cb = 1; code = code " "; i += 2 }
      else if (c == "\"" && c2 == "\"" && c3 == "\"") { s3 = 1; code = code " "; i += 3 }
      else if (c == "\"") { s2 = 1; code = code " "; i++ }
      else { code = code c; i++ }
    }
  }
  if (cs) cs = 0
  if (!wild && code ~ /^import /) {
    imp = code
    sub(/^import /, "", imp)
    if (imp ~ /\*$/) { wild = 1 }
    else {
      nm = imp
      if (match(nm, /[ \t]+as[ \t]+[A-Za-z_][A-Za-z0-9_]*$/)) { sub(/.*[ \t]+as[ \t]+/, "", nm) }
      else { sub(/.*\./, "", nm) }
      sub(/[^A-Za-z0-9_].*$/, "", nm)
      if (nm != "") imports[nm] = 1
    }
  }
  if (!wild) {
    o = code
    if (o ~ /^[ \t]*(data |sealed |abstract |open |final |private |public |internal |value |expect |actual |inline |inner )*(class|object|interface|enum class|typealias|annotation class)[ \t]+[A-Za-z_][A-Za-z0-9_]*/) {
      m = o
      sub(/^[ \t]*(data |sealed |abstract |open |final |private |public |internal |value |expect |actual |inline |inner )*(class|object|interface|enum class|typealias|annotation class)[ \t]+/, "", m)
      if (match(m, /[A-Za-z_][A-Za-z0-9_]*/)) decls[substr(m, RSTART, RLENGTH)] = 1
    }
    if (o ~ /^[ \t]*(private |public |internal )*(fun|val|var)[ \t]+[A-Z][A-Za-z0-9_]*/) {
      m = o
      sub(/^[ \t]*(private |public |internal )*(fun|val|var)[ \t]+/, "", m)
      if (match(m, /[A-Z][A-Za-z0-9_]*/)) decls[substr(m, RSTART, RLENGTH)] = 1
    }
    # by 委托特征（getValue/setValue 语法糖在源码中不出现；by lazy 为 kotlin 隐式，豁免）
    if (code ~ /(^|[^A-Za-z0-9_])by[ \t]+lazy[ \t]*\{/) { }
    else if (code ~ /(^|[^A-Za-z0-9_])by[ \t]+/) hasBy = 1
    # enum 声明行（含枚举值如 { Info, Warn }）——行内 token 全部豁免（非 import 对象）
    en = 0
    if (code ~ /^[ \t]*(private |public |internal |open |sealed |value )*enum class[ \t]+/) en = 1
    # A 轨：大写 token（类型/对象/伴生），排除 . / $ 前缀（枚举值/静态属性/FQN 中段不收集）
    rest = code
    while (match(rest, /(^|[^A-Za-z0-9_$.])[A-Z][A-Za-z0-9_]*/)) {
      st = RSTART
      if (substr(rest, st, 1) !~ /[A-Z]/) st++
      tok = substr(rest, st, RLENGTH - (st - RSTART))
      if (!en && !(tok in seen)) { seen[tok] = 1; tline[tok] = FNR; order[++tn] = tok }
      rest = substr(rest, RSTART + RLENGTH)
    }
    # B 轨：小写 token，仅"函数调用形态" name(（含 Modifier 链扩展 .name(；参数名/属性不收集）
    # 成员调用如 .isTargetEnabled( 不在符号库 → 自然静默；扩展函数如 .padding( 在库 → 可捕获
    rest = code
    while (match(rest, /(^|[^A-Za-z0-9_$])[a-z_][A-Za-z0-9_]*/)) {
      st = RSTART
      if (substr(rest, st, 1) !~ /[a-z_]/) st++
      tok = substr(rest, st, RLENGTH - (st - RSTART))
      pos = st + length(tok)
      while (substr(rest, pos, 1) ~ /[ \t]/) pos++
      if (substr(rest, pos, 1) == "(") {
        if (!en && !(tok in seen)) { seen[tok] = 1; tline[tok] = FNR; order[++tn] = tok }
      }
      rest = substr(rest, RSTART + RLENGTH)
    }
  }
}
END {
  if (wild) { printf "⚠️ [未导入symbol] %s: 含通配 import——本文件跳过该检测\n", FILENAME } else {

  for (k = 1; k <= tn; k++) {
    t = order[k]
    if (t in imports || t in decls) continue
    if (!(t in syms)) continue
    printf "❌ [未导入symbol] %s:%d: %s（全项目已知符号，本文件未 import；疑似漏 import——编译器级未解析前兆）\n", FILENAME, tline[t], t
  }
  if (hasBy && !(("getValue" in imports) && ("setValue" in imports))) {
    printf "⚠️ [by委托] %s: 使用 by 委托但缺 getValue/setValue import（若为 interface 委托可忽略；Compose state 委托则编译错）\n", FILENAME
  }
}
}
