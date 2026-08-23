# PR #2 修复计划

> 基于 CodeRabbit AI Review 的 19 条内联评论 + 12 条非内联评论。
> 创建时间：2026-08-23

## 状态总览

| 优先级 | 数量 | 状态 |
|:---:|:---:|:---:|
| 🔴 Critical | 2 | ✅ 已修 |
| 🟠 Major | 15 | ✅ 已修（2026-08-23） |
| 🟡 Minor | 12 | ⬜ 待修 |

---

## 🔴 Critical（合并前必修）

### 1. ClipboardEditWindow segments lateinit 崩溃
- **文件**: `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardEditWindow.kt:63`
- **问题**: `segments` 是 `lateinit`，在协程异步加载完成前用户点击"全选/反选/确定"会触发 `UninitializedPropertyAccessException`
- **修复**: `private lateinit var segments: List<String>` → `private var segments: List<String> = emptyList()`
- **状态**: ✅ 已修（2026-08-23）

### 2. 冷启动 Intent 丢失
- **文件**: `MainActivity.kt`, `ComposeMainShell.kt`
- **问题**: `ComposeMainShell` 在构造阶段读取 `activity.intent`，但 `intents` 用 `replay = 0` 的 `MutableSharedFlow`，冷启动 Intent 可能永远不会被路由
- **修复**: shell 延迟到 `onCreate` 初始化（`lateinit var`）；`MutableSharedFlow` 改为 `replay = 1` 保证订阅前发射的 Intent 仍会被回放；`init` 中的初始 Intent 在订阅建立后仍可被回放
- **状态**: ✅ 已修（2026-08-23）

---

## 🟠 Major（合并前应修）

### 3. ClipboardEditWindow "插入空格"开关未写回偏好
- **文件**: `ClipboardEditWindow.kt:149-150`, `AppPrefs.kt:476-478`
- **问题**: `clipboardEditInsertSpace` 只读初始值，`setOnCheckedChangeListener` 未注册
- **修复**: 在 `setupUi()` 中为 `clipboardEditInsertSpace` 注册 `setOnCheckedChangeListener`，将状态写回 `setValue()`
- **状态**: ✅ 已修（2026-08-23）

### 4. SimpleTextFieldDialog 确认后调用方状态不同步
- **文件**: `SimpleTextFieldDialog.kt:164-217`, `QuickPhraseListScreen.kt:161-180`
- **问题**: 确认时内部 `show` 置 false，但调用方 `showCreateDialog` 仍为 true，对话框无法再次打开
- **修复**: `SimpleTextFieldDialog` 确认后调用 `onDismiss()`；`QuickPhraseListScreen` 的 `onConfirm` 中设 `showCreateDialog = false`
- **状态**: ✅ 已修（2026-08-23）

### 5. BroadcastScreen 配对码在组合阶段生成
- **文件**: `BroadcastScreen.kt:68-73`
- **问题**: `remember` 块中调用 `generatePairingCode()`，即使未开启广播也会生成；且在主线程执行加密+磁盘写入
- **修复**: 移入 `LaunchedEffect(enabled)`，仅在 `enabled == true` 时在 `Dispatchers.IO` 上加载/生成
- **状态**: ✅ 已修（2026-08-23）

### 6. BroadcastScreen 配对码未标记敏感/未过滤剪贴板历史
- **文件**: `BroadcastScreen.kt:94-101`, `ClipboardManager.kt:171-198`
- **问题**: 配对码复制到剪贴板后会被 `ClipboardManager` 持久化到数据库
- **修复**: `copyPairingCode()` 中用 `EXTRA_IS_SENSITIVE` 标记 ClipData；`ClipboardManager.onPrimaryClipChanged()` 中在 `clbDao.insert` 前检查 `entry.sensitive`，跳过敏感条目
- **状态**: ✅ 已修（2026-08-23）

### 7. InputMethodListScreen 添加输入法直接选第一个候选
- **文件**: `InputMethodListScreen.kt:226-239`
- **问题**: FAB `onClick` 取 `candidates.first()` 直接加入 enabled，用户无法选择
- **修复**: 改为弹出候选列表对话框，由用户选择后再添加
- **状态**: ✅ 已修（2026-08-23）

### 8. PinyinCustomPhraseScreen 编辑禁用短语会重新启用
- **文件**: `PinyinCustomPhraseScreen.kt:230-237`, `PinyinCustomPhrase.kt:15`
- **问题**: 编辑用 `order.toIntOrNull()`，`enabled` 由 `order > 0` 决定，编辑后 order 变正数
- **修复**: 重建时保留原符号：`val signed = if (entry.enabled) parsed else -parsed`
- **状态**: ✅ 已修（2026-08-23）

### 9. PinyinDictionaryScreen 删除词典无确认
- **文件**: `PinyinDictionaryScreen.kt:147-156`
- **问题**: 点击删除按钮立即 `entry.file.delete()`，不可撤销
- **修复**: 弹出 `SimpleConfirmDialog`，确认后再删除
- **状态**: ✅ 已修（2026-08-23）

### 10. QuickPhraseEditScreen save() 在主线程写文件
- **文件**: `QuickPhraseEditScreen.kt:82-84`
- **问题**: `quickPhrase?.saveData()` 在主线程执行，可能 ANR
- **修复**: 用 `rememberCoroutineScope` + `Dispatchers.IO` 异步保存
- **状态**: ✅ 已修（2026-08-23）

### 11. RawConfigHostScreen 连接与作用域未释放
- **文件**: `RawConfigHostScreen.kt:48-69`
- **问题**: `onDispose` 中只写回配置，未 `disconnect` 和 `scope.cancel()`
- **修复**: 保存协程结束后 `FcitxDaemon.disconnect(connectionName)` + `scope.cancel()`
- **状态**: ✅ 已修（2026-08-23）

### 12. TableInputMethodsScreen zip 导入未实现
- **文件**: `TableInputMethodsScreen.kt:71-87`
- **问题**: FAB 选择 zip 后只调用 `reload()`，从未调用 `TableManager.importFromZip()`
- **修复**: 回调中 `TableManager.importFromZip(context.contentResolver.openInputStream(uri)!!)`；已有 API 在 `TableManager.kt:39`；导入失败用 `context.importErrorDialog()`
- **状态**: ✅ 已修（2026-08-23，先前提交）

### 13. TableInputMethodsScreen 删除无确认/无 fcitx 重启
- **文件**: `TableInputMethodsScreen.kt:113-125`
- **问题**: (a) 替换按钮 `onClick` 为空 (b) 删除无确认 (c) 删除后未重启 fcitx
- **修复**: (a) 移除未实现的替换按钮 (b) 弹出 `SimpleConfirmDialog` (c) 删除后重启 fcitx
- **状态**: ✅ 已修（2026-08-23，先前提交）

### 14. ThemeScreen AndroidView 点击回调固化在首次 lambda
- **文件**: `ThemeScreen.kt:513-528`, `ThemeScreen.kt:493-500`
- **问题**: `factory` 中 `setOnClickListener` 捕获首次 `onClick`/`onLongClick`/`onEdit`，重组后不更新
- **修复**: `factory` 仅创建 view；将所有 listener 移入 `update` 块
- **状态**: ✅ 已修（2026-08-23）

### 15. ExpandableNumberPreference 逐字符提交打断输入
- **文件**: `ExpandableNumberPreference.kt:44-83`
- **问题**: `onValueChange` 每次按键都 `commit(t)`，clamp 后重置 text
- **修复**: `onValueChange` 仅保存原始文本；焦点丢失时（用 `Modifier.onFocusChanged` 检测）才 commit
- **状态**: ✅ 已修（2026-08-23，先前提交）

### 16. SetupActivity 违反追加式锚点策略
- **文件**: `SetupActivity.kt:14-37`
- **问题**: 删除 83 行新增 23 行，不符合 AGENTS.md 对上游高频文件的策略
- **修复**: 把 Compose 宿主逻辑移入新文件 `SetupComposeHost.kt`；`SetupActivity` 仅保留最小锚点
- **状态**: ✅ 已修（2026-08-23）

### 17. clipboard_edit_window.xml 底部操作栏窄屏适配
- **文件**: `clipboard_edit_window.xml:83-134`
- **问题**: 6 个 `wrap_content` 按钮水平排列，窄屏被裁剪
- **修复**: 将按钮分为两行（左3右3），或用 `FlexboxLayout` 支持换行
- **状态**: ✅ 已修（2026-08-23）

---

## 🟡 Minor（建议合并前修复）

### 18. QuickPhraseListScreen 删除自定义短语无确认
- **文件**: `QuickPhraseListScreen.kt:134-137`
- **问题**: `onDelete` 直接调用 `entry.file.delete()`
- **修复**: 弹出 `SimpleConfirmDialog`，确认后再删除

### 19. ThemeScreen 旋转后键盘预览不替换
- **文件**: `ThemeScreen.kt:210-237`
- **问题**: `AndroidView` 的 `factory` 只执行一次，旋转后仍挂载首次创建的 preview
- **修复**: 用 `key(orientation) { AndroidView(...) }` 包裹，强制方向变化时重建

### 20. LegacyNavRuntime popLegacyBackStack 条件错误
- **文件**: `LegacyNavRuntime.kt:47-54`
- **问题**: `legacyStackSize() > 2` 应为 `> 3`
- **修复**: 改为 `legacyStackSize() > 3`

### 21. PunctuationScreen 加载失败永久 loading
- **文件**: `PunctuationScreen.kt:78-91`
- **问题**: `fcitx.runOnReady` 没有错误处理，异常终止后 loading 永远为 true
- **修复**: 捕获异常，设置错误状态，显示提示信息

### 22. 多个页面 IconButton 缺少无障碍标签
- **文件**: HomeScreen.kt, InputMethodListScreen.kt, PinyinDictionaryScreen.kt, PinyinCustomPhraseScreen.kt, PunctuationScreen.kt, QuickPhraseEditScreen.kt
- **问题**: 所有 `IconButton` 内 `Icon` 都传入 `contentDescription = null`
- **修复**: 为操作性图标补充 `contentDescription`；建议抽出公共图标按钮组件

### 23. LegacyScreen 背景色不随主题更新
- **文件**: `LegacyScreen.kt:34-40`
- **问题**: `container` 只在首次组合时创建并设置背景色
- **修复**: 加 `SideEffect { container.setBackgroundColor(bgArgb) }`

### 24. ClipboardEditWindow 模式切换按钮文案相同
- **文件**: `ClipboardEditWindow.kt:340-357`
- **问题**: `if/else` 两个分支都执行 `setText(R.string.clipboard_edit_text_mode)`
- **修复**: 分词模式用不同字符串资源

### 25. ClipboardAdapter popupMenu 未赋值
- **文件**: `ClipboardAdapter.kt:109-133`
- **问题**: 新代码创建局部变量 `popup`，字段 `popupMenu` 不再被赋值，`onDetached()` 无效
- **修复**: `popup.show()` 前赋值 `popupMenu = popup`

### 26. ClipboardTextAnalyzer Code 实体判定过宽
- **文件**: `ClipboardTextAnalyzer.kt:100-103`
- **问题**: 只检查"非中文"和"长度 2~64"，标点片段 `……` `---` 也被识别
- **修复**: 加 `w.all { it.isLetterOrDigit() } && w.any { it.isDigit() || it.isLetter() }`

### 27. FcitxInputMethodService ensureFed 可能过早执行
- **文件**: `FcitxInputMethodService.kt:212-218`
- **问题**: `runImmediately` 不等待 READY，CustomPhraseManager.load() 可能过早调用
- **修复**: 移入 `runOnReady` 回调

### 28. 资源文件与 main 存在差异
- **文件**: clipboard_edit_window.xml, values-zh-rCN/custom_strings.xml, values-zh-rCN/strings.xml, values-zh-rTW/custom_strings.xml, values/custom_strings.xml, values/strings.xml
- **问题**: 与 main 存在差异，违反资源文件零差异规范
- **修复**: 恢复或确认哪些是新增的（应放 custom_strings.xml）

---

## 📋 Outside Diff Range

### 29. BaseKeyboard splitKeyboard 突出量可能为负
- **文件**: `BaseKeyboard.kt:217-258`
- **问题**: `splitKeyboardBlankRatio` 为 0 时生成负的 `matchConstraintPercentWidth`
- **修复**: halfKeyInGroup 限制为 `gapInGroup / 2f`，从实际共享中间键的 percentWidth 计算半键宽度，移入辅助文件

---

## 修复顺序

| 批次 | 内容 | 预估耗时 |
|:---:|:---|:---:|
| **P0** | #1 segments lateinit、#2 冷启动 Intent | ~15 min |
| **P1** | #3-#6 安全/功能正确性 | ~40 min |
| **P2** | #7-#17 功能错误 | ~60 min |
| **P3** | #18-#29 Minor + Outside Diff | ~45 min |

## 调查结论

### #12 TableInputMethodsScreen zip 导入 — 已完整实现

`zipLauncher` 回调中已正确实现完整导入流程：

当前实现（`TableInputMethodsScreen.kt:112-137`）：
1. 校验文件名后缀（`.zip`）
2. 创建 notification channel（在 `remember` 块中，lines 82-92）+ 发送 indeterminate 进度通知（`NotificationCompat`，`PRIORITY_HIGH`，不可清除）
3. `withContext(Dispatchers.IO)` 打开 InputStream → `TableManager.importFromZip(inputStream).getOrThrow()`
4. 成功 → `reload()`；失败 → `importErrorDialog()`
5. 无论结果 → `nm.cancel()` 取消通知
6. 导入后立即 `FcitxDaemon.restartFcitx()`

**结论**：完整功能已实现，符合旧版流程。

### #13 TableInputMethodsScreen 删除/替换码表 — 已完整实现

**替换按钮**：已完整实现（lines 139-206）。流程：点击按钮 → 弹 `SimpleConfirmDialog` → 选择文件 → `TableManager.replaceTableDict(im, dictName, dictStream)` → 更新 `im.table` → 重启 fcitx → `reload()`。

**删除按钮**：已完整实现（lines 171-187）。流程：点击按钮 → 弹 `SimpleConfirmDialog` 确认 → `target.delete()` → `FcitxDaemon.restartFcitx()` → `reload()`。

**结论**：删除和替换功能均已正确实现，包括确认对话框、错误处理和 fcitx 重启。

### #15 ExpandableNumberPreference 焦点 API

Miuix `TextField` **没有暴露专用焦点回调 API**，但 `modifier` 直接传给底层 `BasicTextField`，标准 Compose 焦点 API 可用。

**实际方案**：`Modifier.onFocusChanged`

```kotlin
TextField(
    value = text,
    onValueChange = { t -> text = t },  // 仅保存，不 commit
    modifier = Modifier
        .weight(1f)
        .onFocusChanged { focusState ->
            if (!focusState.isFocused) commit(text)
        },
)
```

### #28 资源文件差异

共 16 个文件有差异，净增 302 行。

**合规问题**：
1. `clipboard_edit_select_all`、`clipboard_edit_invert`、`clipboard_edit_text_mode`、`clipboard_edit_no_segment` 4 个字符串**直接追加到了上游 `strings.xml`**，应移入 `custom_strings.xml`
2. `custom_colors.xml`、`colors.xml` 合规
3. 繁体中文 `values-zh-rTW/custom_strings.xml` 缺少 `clipboard_edit_insert_space` 翻译

**新文件（均为 custom 合法新增）**：`clipboard_edit_window.xml`、`search_dialog_layout.xml`、2 个 drawable 图标、各语言 `custom_strings.xml`、`custom_colors.xml`


