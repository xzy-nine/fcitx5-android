# AGENTS.md — custom 分支开发须知

> 本文件只存在于 `custom` 分支（上游 fcitx5-android 没有）。合并上游时务必保留本文件。
> 每次 custom 新增 feature 后：按下方「custom 分支特性」格式登记条目，并遵守「上游合并与防冲突约定」。
> 别往这里面登记bug与fix
> 注释只写代码功能，不写 bug原因/过程性质的东西/出处
## 分支与远程模型

- `origin` = 个人 fork (`xzy-nine/fcitx5-android`)；`upstream` = 官方仓库 (`fcitx5-android/fcitx5-android`，默认分支是 **master** 不是 main)。
- 本地 `main` 只是上游快照，**不要在 main 上开发**；所有开发都在 `custom`。
- 同步上游：`git fetch upstream && git checkout main && git merge upstream/master && git checkout custom && git merge main`。冲突时优先保留 custom 侧的环境适配补丁（见下）。
- 不要向 upstream 发起 PR 时夹带 custom 私有改动（gitee 镜像、Windows 补丁、广播功能等均为 fork 私有）。

## custom 分支特性

新增功能请尽量放独立文件并在原文件只留 1–2 行调用锚点 UI 文件除外，合并冲突时ui冲突也以custom侧为准：

| 功能 | 主要位置 |
|---|---|
| 分离式键盘 (split keyboard) | `BaseKeyboard.kt`(重建逻辑) + `keyboard/SplitKeyboardLayoutMath.kt`(纯计算) |
| 工具栏高度自定义 | AppPrefs `toolbarHeight*`; `KawaiiBarComponent.HEIGHT` 是动态 getter 非 const |
| 横向候选滑动模式 | `HorizontalCandidateComponent.kt`（内联实现，勿轻拆） |
| 剪贴板广播/配对 | 独立包 `data/broadcast/*` + `BroadcastPairingService.kt` + `IBroadcastPairingService.aidl` |
| 设置搜索+高亮+分组 | `SettingsSearch*.kt`(`SettingsSearchManager.search()`+`SearchResult` 纯查询) + `PreferenceHighlightHelper.kt` + `PreferenceGroupUi.kt`; Compose 弹窗 `compose/screens/SettingsSearchScreen.kt`(View 版已移除) |
| 空格长按语音输入 | `SpaceLongPressBehavior.VoiceInput`; 图标显隐在 `KeyViewExt.kt` |
| 键盘顶部圆角裁剪 + 顶部延伸带 | `input/ViewOutlineExt.kt`(圆角裁剪) + `input/ImeTopCorner.kt`(半径单一真源 `IME_TOP_CORNER_RADIUS_DP=16`); 顶部延伸带=`composeTopView` 在预编辑栏与工具栏之间插一格恒高 Box(=圆角半径、透出键盘底色/背景图), 键盘体上缘抬到 app 可视区之上; `InputView.topExtensionPx` 由 `FcitxInputMethodService.onComputeInsets` 加回 `contentTopInsets`(不计入 insets) |
| 数字键盘横屏分体历史符号 | Compose 路径：`ComposeNumberKeyboard.kt`(分体分支) + `ComposeRecentSymbols.kt`(历史符号面板) + 同文件 `ComposeSymbolSlider`(符号滑块); 行数据 `NumberKeyboardRows.kt` 单一实例 val; 左 45% 历史符号网格 + 右 45% 符号滑块/9宫格(中间 10% 分隔); View 版 `NumberKeyboard.kt`/`RecentSymbolsView.kt` 断线保留 |
| 数字键盘解耦 | 主键盘 `?123` 恒进 9 宫格(`TextKeyboard.kt` 指向 `NumberKeyboard.Name`)，`!?#` 进符号页; `KeyboardWindow.kt` 无 `lastSymbolType`; `last_symbol_layout` 孤儿配置勿用 |
| Compose 导航+设置 UI(miuix/miuix-nav) | 导航壳 `ui/main/compose/AppRoute.kt` + `FcitxComposeApp.kt`; 首页 `HomeScreen.kt`; 设置渲染器 `ComposeManagedPrefsScreen.kt` / `ComposeRawConfigScreen.kt` / `RawConfigHostScreen.kt`; 旧 Fragment 页经 `LegacyScreen.kt`(自建 `LegacyGraph.kt`)桥接; RawConfig 行形态判定抽到纯逻辑 `settings/RawConfigRowModel.kt`(`specOf`/`externalActionOf`/`RawConfigValue`, 按 descriptor 判类型) |
| IME 软键盘 Compose 化（外层嵌入 + 工具栏/候选/预编辑/状态区/弹窗 + 剪贴板/文本编辑 + miuix 颜色统一） | 外层 `input/LifecycleInputMethodService.kt`(owner 链挂 IME decorView) + `FcitxInputMethodService.createComposeInputView()`(ComposeView 根 + AndroidView 承载 `InputView`, `themeState`/`recreateNonce` 驱动 `key()`); 工具栏 `bar/ComposeKawaiiBarComponent.kt`; 候选 `candidates/horizontal/ComposeCandidateComponent.kt` + `candidates/ComposeCandidateActionMenu.kt`; 预编辑 `preedit/ComposePreeditComponent.kt` + `ComposePreedit.kt`; 状态浮窗 `status/StatusAreaWindow.kt` + `ComposeStatusArea.kt`; 弹窗 `popup/PopupComponent.kt` + `ComposePopupLayer.kt` + `PopupLayoutMath.kt`(纯计算); 剪贴板 `ClipboardWindow.kt` + `ComposeClipboard.kt` + `ClipboardEditWindow.kt`; 文本编辑 `TextEditingWindow.kt`; 浮窗宿主 `input/wm/ComposeWindowHost.kt`; 长按重复 `input/bar/ComposeFeedbackExt.kt`; 颜色统一 `MiuixTheme.colorScheme`(View 侧/主键盘键面走 fcitx `Theme` 桥 `keyboard/KeyboardVisuals.kt`) |
| WebDAV 云端备份同步(偏好 zip + 词库逐文件) | `sync/webdav/*`(Config/DictCollector/BackupZips/WebDavSyncEngine/SyncRestorer/AutoDictSync/DictReload); UI `ui/main/compose/screens/WebDavSyncScreen.kt`; 偏好 zip 走 `UserDataImportCompat`; 词库逐文件同步(上传 `uploadDictFiles`/下载 `downloadDictFiles`, 按 `data/` 相对路径映射); `DictReload` pinyin/quickphrase 热重载、table 重建进程; Manifest `INTERNET`/`ACCESS_NETWORK_STATE` + `res/xml/network_security_config.xml` |
| 偏好备份/恢复忽略广播配对 | Room `databases/broadcast_db`(`PairedAppEntity.keyAlias` 指向本机 Keystore 密钥); 备份经 `BroadcastBackupFilter` 剔除该库; 导入不动本机已有有效配对 |
| 候选三栏骨架统一（展开候选页 + Picker 共用一套调用） | `input/candidates/ComposeSplitCandidatesUi.kt`(左 15% 标签侧栏 + 中 `LazyVerticalGrid` + 右 15% 键盘; 同文件 `SplitTab` / `SplitCandidatesKeyboard.PageUp`·`PageDown` / `rememberCanPageUp`·`rememberCanPageDown`); 右栏删除键接线 `input/keyboard/ComposeKeyColumn.kt`; 两侧调用方 `candidates/expanded/ComposeExpandedCandidatesUi.kt` 与 `picker/ComposePickerSplitUi.kt` |
| 符号/表情/颜文字 Picker 左右分栏（展开候选页同款） | `input/picker/ComposePickerSplitUi.kt`(中栏 `LazyVerticalGrid` 铺 `PickerGridLayout` 槽位, 格子密度 符号32/Emoji44/颜文字76, 行高固定) + `PickerWindow.kt` + `PickerPageModel.flatCategories()` + `PickerGridLayout.kt`(纯计算: 候选槽 + 分类间换页空行槽); 中栏列数用 `PickerDensity.columnCount` 封顶 |
| 包名统一 + 历史 debug 备份兼容导入 | `build-logic/convention/.../AndroidAppConventionPlugin.kt`(移除 `.debug` 后缀) + `AndroidPluginAppConventionPlugin.kt`(占位符同步); 导入兼容层 `data/UserDataImportCompat.kt`; `app_name`/`app_icon`/`app_icon_round` 的 resValue 上提到 `defaultConfig` |
| @ 邮箱域名联想（QuickPhrase 词库 + App 层触发/自学习） | fcitx QuickPhrase 词库, 条目 `@域名 域名`; 内置词库 `usr/share/fcitx5/data/quickphrase.d/email.mb`(CMake 从 `app/src/main/cpp/email/email.mb` 安装) + 用户词库 `getExternalFilesDir(null)/data/data/quickphrase.d/email.mb`; App 层 `data/quickphrase/EmailDomainDict.kt`(自学习 MRU); 触发锚点 `FcitxInputMethodService.commitText` 末尾 `onEmailSuggestionTrigger`; JNI `native-lib.cpp` 加 `triggerQuickPhraseWithBuffer` |
| 内置语音输入（IME 面板 + 应用设置页; 离线 sherpa-onnx、在线火山/MiMo, 自有代码 LGPL-2.1-or-later） | 离线：`app/libs/sherpa-onnx-1.13.8.aar`(构建期 `sherpa-ort-align` 摘除自带 ONNX, 改由 `ai.onnxruntime`) + `data/voice/`(`VoiceAsrService.kt` `:asr` 进程 / `VoiceAsrClient` / `VoiceAudioCapture` / `VoiceSession` / `VoiceSpectrum` / `VoiceTextRules`); 在线 `data/voice/online/OnlineAsrProvider.kt`(+`VolcengineAsrProvider`/`MiMoAsrProvider`); 模型 `VoiceModelStore`/`VoiceModelCatalog`; UI `input/voice/`; 设置 `VoiceInputSettingsScreen` / `VoiceProviderConfigScreen`; 录音权限 `VoicePermissionActivity` |
| 模型市场公共组件（语音/数字墨水共用） | `data/market/`(`MarketModel.kt` 数据类型 + `MarketCategory` 接口 + `BaseMarketCategory` 公共实现(`runTrackedDownload` 下载骨架) + `MarketCategories` 注册表 + `ModelIndex` 远程索引 + `MarketModelGrouping` 语言变体分组 + `ModelDownloader` 文件下载器 + `MarketModelId`/`MarketPaths` id 校验与模型根目录); 分类实现 `data/voice/VoiceMarketCategory.kt` + `data/handwriting/DigitalInkMarketCategory.kt`; UI `ui/main/compose/screens/ModelMarketScreen.kt`(`ModelMarketHomeScreen.kt`) |
| 手写输入（第三种键盘布局; 识别引擎 = 系统内置 / 谷歌数字墨水, 自有代码 LGPL-2.1-or-later） | 识别后端 `data/handwriting/HandwritingRecognition.kt` + `HandwritingEngineKind.kt`; 纯逻辑 `HandwritingStrokeFx.kt` + `HandwritingTypes.kt`; UI `input/handwriting/HandwritingKeyboardLayout.kt`(镜像 L 布局) + `HandwritingInputComponent.kt`; 入口工具栏按钮 → `KeyboardWindow` 第三布局; 上屏原语 `FcitxInputMethodService.replaceBeforeCursor` / `deleteBeforeCursor`; 设置 `AppPrefs.Handwriting` + `HandwritingSettingsScreen` |
| 触控笔手写（Android 13+ 触控笔手写协议：应用内直接手写） | `input/handwriting/StylusHandwritingController.kt`(会话控制器 + 同文件 `StylusInkView` 原生墨迹 View, 非 Compose) + `StylusToolboxView.kt` 的 `StylusToolboxWindow`(addView 到 IME 窗口 decorView) + `ui/main/compose/screens/HandwritingGestureDemoScreen.kt`; 上屏 = 整段墨迹累积, 抬笔 `STYLUS_SETTLE_MS=500` 到点二选一 `tryConsumeAsGesture()` / `commitRecognition()`(整段一次出单个结果, 不逐笔/不切分/不出候选); 手势三态状态机 `advanceGestureState()`(路径∩编辑器可见文本行), 系统引擎优先否则谷歌手势分类器; 坐标走屏幕坐标; `FcitxInputMethodService` 锚点 `onUpdateEditorToolType` / `onPrepareStylusHandwriting` / `onStartStylusHandwriting` / `onStylusHandwritingMotionEvent` / `onFinishStylusHandwriting`; 前置 `res/xml/input_method.xml` `android:supportsStylusHandwriting="true"` |
| 同框重启输入连接不重置面板/书写状态 | `input/EditorKey.kt`(单一真源, internal): 按 pkg/fieldId/inputType/hintText/imeOptions 判「同框 resync」vs「换框」, 不含 initialSel; 消费方 `InputView.startInput` / `KeyboardWindow.onStartInput` / `VoiceInputComponent` / `StylusHandwritingController.onStartInput`; 日志 `InputView` 打 `startInput: restarting, sameEditor, key` |
| 手写识别引擎（统一入口 + 两档后端; 桥代码 LGPL-2.1-or-later） | 统一入口 `data/handwriting/HandwritingRecognition.kt` + `HandwritingEngineKind.kt`(`chainFrom()` 引擎链); 系统后端 `XiaomiHandwritingEngine.kt`(反射 `/system_ext/framework/xiaomi-pencilengine-pad.jar`, `recognizeText` 整段单结果 + `getGoogleGesture`); 谷歌后端 `GoogleDigitalInkEngine.kt`(ML Kit `digital-ink-recognition:19.0.0`, 模型运行时下载, `<tag>-x-gesture` 手势分类器经 `GoogleGestureLabels.kt` 映射); 内置模型 `MlKitBundledModel.kt`(`DigitalInkModelPlugin.kt` 构建期拉取, 物化到 `files/mlkit_digital_ink_recognition/.../datadownloadfile_<ts>/`); 纯逻辑 `HandwritingStrokeFx.kt` + `HandwritingTypes.kt`; 设置 `AppPrefs.handwriting.handwritingEngine` |
| 环境适配（勿在上游 PR 中出现） | `.gitmodules`(全部 gitee.com/xzy-ime 镜像)、`gradle.properties` 的 `ndkVersion`、gradle-wrapper 华为云镜像、`FindFcitx5Utils.cmake` WIN32 MSYS2 gettext 路径、thai 插件 Iconv CACHE 补丁 |

## 防冲突硬性约定

1. **新增字符串一律写入 `res/values*/custom_strings.xml`，颜色写 `custom_colors.xml`** —— 禁止加进上游的 `strings.xml` / `colors.xml`（Android 会自动合并多文件同名资源）。新语言翻译放对应 `values-xx/custom_strings.xml`。
2. 对上游高频改动文件只允许追加式小锚点，逻辑体放新文件（模式参考现有 `*Ext.kt` / `Navigator` / `Math.kt`）。**例外**： UI 文件此条限制不适用。
3. 新 AIDL 方法追加在接口末尾；Manifest 的 permission/service 追加块放在既有条目之后。
4. 新设置项必须定义在 `AppPrefs` 对应 inner class 内（框架约束），用 `init {}` 块集中注册并放在类尾部区域；分组渲染走 `PreferenceGroupUi.kt`，不要重写 `createUi` 主体。
5. 改完跑 `git diff main -- <上游文件>` 自查：预期只剩少量追加行；资源文件应零差异。
6. **注释禁止出现反编译出处与功能变更史** —— 不得在注释里写厂商品牌名（米系/搜狗/讯飞/Gboard 等）、反编译 `.java` 类名、仓外本地路径、或「之前出过 X bug / 已移除 Y」式的历史叙述；注释只写代码功能与可执行约束/不变量。

## 构建 / 验证

```bash
./gradlew.bat :app:compileDebugKotlin   # 快速验证 Kotlin+资源（~1 分钟）
./gradlew.bat :app:assembleDebug        # 完整构建（含 NDK native，需 MSYS2 gettext）
```

- `lib/*`、`plugin/*/src/main/cpp/*` 是 git submodule（native 源码）；构建前确保子模块已初始化，升级依赖 = 更新子模块哈希后单独提交（由于远程是gitee的镜像子模块，因此可能存在哈希不存在，此时提示用户去同步镜像仓库后重试）。

## 已知坑

- **debug 与 release 同包名**：已移除 debug 的 `.debug` 后缀，两个构建**不能共存安装**（签名不同，切换需先卸载，应用内数据会丢）—— 切构建前先在旧版里导出/WebDAV 备份，装新版后用 `UserDataImportCompat` 导入即可（历史 debug 备份可直接在无后缀包上恢复）。
- **重启 fcitx 必须用 `FcitxDaemon.restartFcitx()`**：`stopFcitx()`/`startFcitx()` 都不持 `FcitxDaemon` 的锁，分步调用中间任意 `connect()` 都会抢先 `start()`，导致引擎在词库被覆盖期间仍在运行（输入法按键有反应但无法上屏，只能重启应用）。词库/表格导入统一走 `restartFcitx()`（锁内原子 stop+start）；只有“整体用户数据导入”才 `stopFcitx()` 且随后必须重启进程。
- **WebDAV 恢复词库后必须重启进程**：即便用 `restartFcitx()`，引擎侧已完全重启（`ReadyEvent` 正常），IME 与引擎之间的连接状态仍不会复位——按键有触摸反馈但 JNI 侧收不到任何输入事件。因此手动「下载并恢复词库」成功后走 `AppUtil.showRestartNotification + exit`（与偏好恢复一致）。`AutoDictSync` 自动恢复同样重建进程，但只在键盘隐藏时：`setKeyboardVisible` 由 `onStartInputView/onFinishInputView` 维护，退出前复查两次（恢复后 + 延迟 1s），任一次可见就放弃本次重启；且**只 `AppUtil.exit()` 结束进程**，不发通知、不启动界面，避免把应用拉到前台。
- **WebDAV/网络调用必须在 IO 线程**：`github.xzynine.webdav` 底层是 OkHttp 同步 `execute()`，`WebDavSyncEngine` 的每个公开 suspend 方法都要 `withContext(Dispatchers.IO)`；漏了会在主线程抛 `NetworkOnMainThreadException`（`message` 为 null，UI 只会显示兜底文案「操作失败」）。
- **CRLF 幻影**：`core.autocrlf=true`，Gradle 构建会触碰大量文件导致 `git status` 出现上百个假 M（diff 内容为空）。不要提交它们：`git add --renormalize .` 或重新 `git status` 刷新索引即可消失。
- **miuix 依赖**：`miuix-nav` 仅 `0.9.4-rc01` 一版(与 miuix-ui 同版本);其产物为 **JVM target 21 字节码**，因此 `build-logic/convention/.../Versions.kt` 的 `java` 需保持 `VERSION_21`(这是唯一一条为 Compose 引入的上游 build-logic 改动)。
- **ComposeView 是 final 类**：不要把 Compose 宿主写成子类，用工厂函数包装;`setContent` 是它的**成员函数**，不需要 import。
- **IME 内禁用 miuix的 Window\* 弹层**：miuix `WindowDialog`/`WindowListPopup` 底层是系统 Dialog(Activity window token)，在 IME 浮窗层级必崩 `BadTokenException`如需使用请使用Overlay* 系列的同名组件。**`Overlay*` 本身不依赖系统 Dialog（内部只用 `AnimatedVisibility`+`Box`+`zIndex`），但它只是把内容注册进 `LocalRootPopupStates`，真正渲染的 `MiuixPopupHost` 由根 `Scaffold` 的 `popupHost` slot 提供 —— 因此用 `Overlay*` 的窗口必须在 Compose 根包一层 `Scaffold`（z 序最高，覆盖整窗），否则弹层无处渲染**。`Scaffold` 用于此目的时建议 `containerColor = Color.Transparent`（保留原底色）+ `contentWindowInsets = WindowInsets(0)`（IME insets 由 `InputView.onApplyWindowInsets` 处理，勿二次顶开）。若在 IME 视图树挂 `ComposeView`，必须手动补 owner 链（setViewTreeLifecycleOwner / setViewTreeSavedStateRegistryOwner / setViewTreeViewModelStoreOwner / **setViewTreeNavigationEventDispatcherOwner**；注意 `SavedStateRegistryOwner : LifecycleOwner`）。**该 owner 链（4 个）已由 `input/LifecycleInputMethodService.kt` 统一实现并挂到 IME `decorView`，后续直接复用，勿重复挂载**（注意 `viewModelStore` 是 `ViewModelStoreOwner` 的接口成员，必须写 `override val`，不能再额外声明同名字段）。
- **`Overlay*` 弹层强依赖 `NavigationEventDispatcherOwner`（长按剪贴板条目必崩的根因）**：miuix `MiuixPopupHost` 的 `PopupEntry`（`MiuixPopupUtils.kt`，`AnimatedVisibility` 之前）**无条件**调用 `NavigationBackHandler`，而 `NavigationEventHandler` 里 `checkNotNull(LocalNavigationEventDispatcherOwner.current)` 会在拿不到 owner 时抛 `IllegalStateException: No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner`。该 local 是 `compositionLocalWithHostDefaultOf`，靠 `ViewTreeHostDefaultKey`（tag = `R.id.view_tree_navigation_event_dispatcher_owner`）从 `LocalView` 起沿视图树向上找 owner —— Activity 由 `ComponentActivity` + `activity-compose` 提供，**IME 场景框架不提供**。异常发生在 composition 阶段、无法被业务代码捕获 → `FATAL EXCEPTION` 崩进程（IME 被 Zygote 重启）。修复：`LifecycleInputMethodService` 实现 `NavigationEventDispatcherOwner` 并挂到 `decorView`（`onDestroy` 里 `dispose()`）。语义上它是**根 dispatcher、无输入源**（IME 窗口不可聚焦、收不到返回手势），故 handler 永不触发，返回键行为不变。
- **Compose 栈与 legacy 双栈**：进入 Legacy 页时 `LegacyScreen` attach 全新 NavHostFragment(start=空 anchor `LegacyAnchorFragment`),返回由 `LegacyNavRuntime.popLegacyBackStack()` 协调;`SettingsRoute.kt` 上游文件保持零改动。
- **两套颜色体系并存**：fcitx `Theme` 与 `MiuixTheme.colorScheme` 独立互不相干。Compose
  迁移件（工具栏/候选/数字行/预编辑/状态区/弹窗）一律从 `MiuixTheme.colorScheme` 取色（取色函数如
  `getVisuals()` 因此必须是 `@Composable`）；只有 View 侧（主键盘/展开候选/`CandidatesView`/
  `ToolButton`）仍读 fcitx `Theme`。切勿在新 Compose 代码里引用 fcitx 主题对象。
- **同框重启输入连接不再把面板踢回主键盘**：`onStartInputView(restarting=true)` 既可能是「焦点换框」，也可能是「同一个框被应用 resync 重启输入连接」（实测 `io.legato.kazusa` 在文本/选区被改动后会重启）。`InputView.startInput` 现按 `EditorKey`（pkg/fieldId/inputType/hintText/imeOptions，**不含 initialSel**——应用常带陈旧选区，含进去会把同框重启永远判成换框）判定是否同框：同框保留当前面板（Picker/剪贴板/展开候选），跨框才按「焦点变化时重置键盘」偏好回主键盘；`restarting=false`（新会话）行为不变。日志：`InputView` 打 `startInput: restarting=…, sameEditor=…, key=…`，`FcitxInputMethodService.onStartInput` 打 pkg/fieldId/inputType。
- **内置语音输入的 `:asr` 进程不能碰主进程子系统**：`VoiceAsrService` 跑在 `android:process=":asr"`，该进程只做 dlopen + 读模型文件。`FcitxApplication.onCreate` 里有 `Application.getProcessName().endsWith(":asr")` 的**早退守卫**，删了它会在 `:asr` 里初始化 `ClipboardManager`(Room) 与 `AutoDictSync`(网络同步)，出现多进程同开 Room / 凭空后台同步。同理 `:asr` 没有 `AppPrefs`，所以 AIDL `startAsr` 传的是**4 个模型文件的绝对路径**（由 app 侧 `VoiceModelStore.resolve()` 解析），不要改回传 modelDir 后在 `:asr` 里读偏好。
- **`libonnxruntime.so` 由 `ai.onnxruntime` 提供（sherpa AAR 的副本被构建期摘除）**：离线语音用官方 sherpa-onnx AAR（`app/libs/sherpa-onnx-1.13.8.aar`，Apache-2.0），`sherpa-ort-align` 插件（`build-logic/convention/.../SherpaOrtAlignPlugin.kt`）在构建期产出**打过补丁的副本**：摘掉它自带的 `libonnxruntime.so`，并把 `libsherpa-onnx-{jni,c-api}.so` 的 ELF 版本需求从 `VERS_1.28.2` 改写到 `VERS_1.28.0`，使其链接到 `com.microsoft.onnxruntime:onnxruntime-android`（`libs.onnxruntime.android`）提供的 runtime。app 侧用 `implementation(files(<stripped aar>))` 引入该副本，不需要 CMake `IMPORTED` 目标。**注意 `.gitignore` 的 `*.aar` 会忽略 `app/libs/*.aar`**，仓库已用 `!app/libs/*.aar` 放行；升级 AAR 时同步改文件名、插件常量、Gradle 依赖与 `NOTICE.md`（47.8 MB 二进制入库，接近 GitHub 单文件 50 MB 警告线）。
- **语音只保留一份 `libonnxruntime.so`（打包方式踩过坑，改动后必须回归语音）**：包内那份 runtime 来自 `ai.onnxruntime`（手写 ONNX 引擎已移除；该依赖的 Java API 已无使用者，但 runtime 必须留）。相关规则：
  - ❌ **不要用 `packaging { jniLibs { excludes += "**/libonnxruntime.so" } }`**：`excludes` 不区分来源，会把唯一那份也排掉，实测导致 APK 里 **0 份** runtime（语音启动即 `UnsatisfiedLinkError`）。
  - ✅ 用 `packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }` 兜底：即便将来某个依赖又带回同名 .so，也只会保留第一份。
  - **版本必须与插件对齐**：`libs.versions.toml` 的 `onnxruntimeAndroid` 版本要对应 `SherpaOrtAlignPlugin` 里的 `toTag`（`VERS_1.28.0`）——插件改写的正是被链接的版本需求，两者不一致会在真机上 `UnsatisfiedLinkError`。
  - 验证方式：`unzip -l app-debug.apk | grep libonnxruntime` 必须看到**恰好 1 份** `libonnxruntime.so` + 1 份 `libonnxruntime4j_jni.so`。
- **在线语音平台是内置 Kotlin provider，没有插件框架**：新增平台 = 写一个 `OnlineAsrProvider` 实现（声明 id/名称/`streamsAudio`/`streamsText`/`isConfigured`/`settings()`/`newSession()`）+ 在 `OnlineAsrRegistry.providers` 注册一项，设置页与配置表单会自动渲染其字段；不要再引入 `assets/plugins`/Lua 之类的运行期脚本层（已随 Xime 代码一并移除）。
- **输入法服务无法申请运行时权限**：`FcitxInputMethodService` 是 Service，录音权限必须经透明中转的 `VoicePermissionActivity`（结果写 `VoicePermissionState` 这个同进程 StateFlow，IME 面板据此刷新），不要在 IME 里直接 `requestPermissions`。
- **OkHttp 必须是 5.x（`libs.okhttp = 5.4.0`），不能退回 4.12.0**：语音在线 provider（火山/MiMo）与 WebDAV 都用 okhttp 5.x。OkHttp 5 默认启用 `fastFallback`（多地址 Happy-Eyeballs 式快速回退），4.x 没有这个开关（`OkHttpClient.Builder` 无 `fastFallback`），只能**逐个解析地址各等满 `connectTimeout`** —— 遇到同域名的某个 IP/IPv6 不可达时，表现为 `SocketTimeoutException: failed to connect to <host>/<ip> (port 443) ... after 30000ms`，而同时设备上用 `curl` 访问同一 URL 却秒开（curl 本身就做快速回退），极容易被误判成"设备网络问题"。`webdav` 模块与语音在线 provider 在 5.4.0 下均已验证可编译。
- `.trae/hooks.json` 是本地 Trae IDE 的命令拦截钩子配置，与项目无关，勿删勿改。
- 上游 README 描述的是官方功能集；custom 分支额外能力以上方特性表为准。
- 新增的页面除了IME外其他的均应该使用compose界面而不是view