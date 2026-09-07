# AGENTS.md — custom 分支开发须知

> 本文件只存在于 `custom` 分支（上游 fcitx5-android 没有）。合并上游时务必保留本文件。
> 每次 custom 新增 feature 后：按下方「custom 分支特性」格式登记条目，并遵守「上游合并与防冲突约定」。

## 分支与远程模型

- `origin` = 个人 fork (`xzy-nine/fcitx5-android`)；`upstream` = 官方仓库 (`fcitx5-android/fcitx5-android`，默认分支是 **master** 不是 main)。
- 本地 `main` 只是上游快照，**不要在 main 上开发**；所有开发都在 `custom`。
- 同步上游：`git fetch upstream && git checkout main && git merge upstream/master && git checkout custom && git merge main`。冲突时优先保留 custom 侧的环境适配补丁（见下）。
- 不要向 upstream 发起 PR 时夹带 custom 私有改动（gitee 镜像、Windows 补丁、广播功能等均为 fork 私有）。

## custom 分支特性

新增功能请尽量放独立文件并在原文件只留 1–2 行调用锚点：

| 功能 | 主要位置 |
|---|---|
| 分离式键盘 (split keyboard) | `BaseKeyboard.kt`(重建逻辑) + `keyboard/SplitKeyboardLayoutMath.kt`(纯计算) |
| 工具栏高度自定义 | AppPrefs `toolbarHeight*`; `KawaiiBarComponent.HEIGHT` 是动态 getter 非 const |
| 横向候选滑动模式 | `HorizontalCandidateComponent.kt`（内联实现，勿轻拆） |
| 剪贴板广播/配对 | 独立包 `data/broadcast/*` + `BroadcastPairingService.kt` + `IBroadcastPairingService.aidl` |
| 设置搜索+高亮+分组 | `SettingsSearch*.kt`、`PreferenceHighlightHelper.kt`、`PreferenceGroupUi.kt`、`search_dialog_layout.xml` |
| 空格长按语音输入 | `SpaceLongPressBehavior.VoiceInput`; 图标显隐在 `KeyViewExt.kt` |
| 键盘顶部圆角裁剪 | `input/ViewOutlineExt.kt` |
| 数字键盘横屏分体历史符号 | `NumberKeyboard.kt`(分体分支 buildSplitLayout/buildRow4Split) + `RecentSymbolsView.kt`(历史符号面板)；左侧 45% 为「!?#切换钮 + 4 列可滚动网格」历史符号，中间 10% 空白分割，右侧 45% 保留「符号滑块+9宫格」且 row4 无 `!?#`（逗号加宽） |
| 数字键盘解耦 | 主键盘 `?123` 恒进 9 宫格 (`TextKeyboard.kt` 直接指向 `NumberKeyboard.Name`)，`!?#` 进符号页；`KeyboardWindow.kt` 已移除 `lastSymbolType` 记忆，`AppPrefs` 的 `last_symbol_layout` 为孤儿配置勿用 |
| Compose 导航+设置 UI(miuix/miuix-nav) | 导航壳 `ui/main/compose/AppRoute.kt` + `FcitxComposeApp.kt`; 首页 `HomeScreen.kt`; 设置渲染器 `ComposeManagedPrefsScreen.kt` / `ComposeRawConfigScreen.kt` / `RawConfigHostScreen.kt`; 旧 Fragment 页经 `LegacyScreen.kt`(自建 `LegacyGraph.kt`, start 用空 anchor)桥接 |
| WebDAV 云端备份同步(偏好 zip+词库 zip) | `sync/webdav/*`(Config/DictCollector+指纹/BackupZips/WebDavSyncEngine/SyncRestorer/AutoDictSync); UI `ui/main/compose/screens/WebDavSyncScreen.kt`(服务器登录走弹窗 `WebDavLoginDialog`,云端目录固定为服务器根/fcitx5xzy); 数据管理(浏览/导出/导入/WebDAV 入口)直接内联在 `ComposeManagedPrefsScreen.kt` 的 Advanced 分类列表底部; zip 兼容 `UserDataManager.import` 分区(metadata 硬校验/分区软容忍); 词库自动同步=事件+常驻驱动(无 WorkManager),锚点仅加在 FcitxApplication/IME service 与 PinyinDictManager/TableManager/QuickPhraseManager/ClipboardDictFeeder; 连接配置存 `filesDir` 私有文件(不入 shared_prefs,自指不随备份); Manifest 已加 INTERNET/ACCESS_NETWORK_STATE + `res/xml/network_security_config.xml` |
| 包名统一 + 历史 debug 备份兼容导入 | `build-logic/convention/.../AndroidAppConventionPlugin.kt`(移除 `applicationIdSuffix = ".debug"`) + `AndroidPluginAppConventionPlugin.kt`(debug 的 `MAIN_APPLICATION_ID`/`mainApplicationId` 占位符同步为无后缀，否则插件 IPC 权限/action 失配)；导入兼容层 `data/UserDataImportCompat.kt`(metadata 包名归一化比较 + `shared_prefs` 旧包名文件改名)，云端(`SyncRestorer`)与本地(`ComposeManagedPrefsScreen`/`AdvancedSettingsFragment`)导入均改走它，未改动上游 `UserDataManager.kt`；顺带统一应用名与图标：`app/build.gradle.kts` 里 `app_name`/`app_icon`/`app_icon_round` 的 resValue 上提到 `defaultConfig`（该三项没有默认资源，不能只删 debug 分支） |
| 环境适配（勿在上游 PR 中出现） | `.gitmodules`(全部 gitee.com/xzy-ime 镜像)、`gradle.properties` 的 `ndkVersion`、gradle-wrapper 华为云镜像、`FindFcitx5Utils.cmake` WIN32 MSYS2 gettext 路径、thai 插件 Iconv CACHE 补丁 |

## 防冲突硬性约定

1. **新增字符串一律写入 `res/values*/custom_strings.xml`，颜色写 `custom_colors.xml`** —— 禁止加进上游的 `strings.xml` / `colors.xml`（Android 会自动合并多文件同名资源）。新语言翻译放对应 `values-xx/custom_strings.xml`。
2. 对上游高频改动文件（AppPrefs.kt、MainActivity.kt、KawaiiBarComponent.kt、InputView.kt、KeyView.kt、ManagedPreferenceUi.kt 等）只允许追加式小锚点，逻辑体放新文件（模式参考现有 `*Ext.kt` / `Navigator` / `Math.kt`）。
3. 新 AIDL 方法追加在接口末尾；Manifest 的 permission/service 追加块放在既有条目之后。
4. 新设置项必须定义在 `AppPrefs` 对应 inner class 内（框架约束），用 `init {}` 块集中注册并放在类尾部区域；分组渲染走 `PreferenceGroupUi.kt`，不要重写 `createUi` 主体。
5. 改完跑 `git diff main -- <上游文件>` 自查：预期只剩少量追加行；资源文件应零差异。

## 构建 / 验证

```bash
./gradlew.bat :app:compileDebugKotlin   # 快速验证 Kotlin+资源（~1 分钟）
./gradlew.bat :app:assembleDebug        # 完整构建（含 NDK native，需 MSYS2 gettext）
```

- `lib/*`、`plugin/*/src/main/cpp/*` 是 git submodule（native 源码）；构建前确保子模块已初始化，升级依赖 = 更新子模块哈希后单独提交（由于远程是gitee的镜像子模块，因此可能存在哈希不存在，此时提示用户去同步镜像仓库后重试）。

## 已知坑

- **debug 与 release 同包名**：已移除 debug 的 `.debug` 后缀，两个构建**不能共存安装**（签名不同，切换需先卸载，应用内数据会丢）—— 切构建前先在旧版里导出/WebDAV 备份，装新版后用 `UserDataImportCompat` 导入即可（历史 debug 备份可直接在无后缀包上恢复）。
- **WebDAV/网络调用必须在 IO 线程**：`github.xzynine.webdav` 底层是 OkHttp 同步 `execute()`，`WebDavSyncEngine` 的每个公开 suspend 方法都要 `withContext(Dispatchers.IO)`；漏了会在主线程抛 `NetworkOnMainThreadException`（`message` 为 null，UI 只会显示兜底文案「操作失败」）。
- **CRLF 幻影**：`core.autocrlf=true`，Gradle 构建会触碰大量文件导致 `git status` 出现上百个假 M（diff 内容为空）。不要提交它们：`git add --renormalize .` 或重新 `git status` 刷新索引即可消失。
- **miuix 依赖**：`miuix-nav` 仅 `0.9.4-rc01` 一版(与 miuix-ui 同版本);其产物为 **JVM target 21 字节码**，因此 `build-logic/convention/.../Versions.kt` 的 `java` 需保持 `VERSION_21`(这是唯一一条为 Compose 引入的上游 build-logic 改动)。
- **ComposeView 是 final 类**：不要把 Compose 宿主写成子类，用工厂函数包装;`setContent` 是它的**成员函数**，不需要 import。
- **IME 内禁用 compose Window\* 弹层**：miuix `WindowDialog`/`WindowListPopup` 底层是系统 Dialog(Activity window token)，在 IME 浮窗层级必崩 `BadTokenException`；键盘内嵌 Compose 只做控件级替换（按钮等），弹层仍走旧 View 逻辑。若在 IME 视图树挂 `ComposeView`，必须手动补 owner 链（setViewTreeLifecycleOwner / setViewTreeSavedStateRegistryOwner / setViewTreeViewModelStoreOwner；注意 `SavedStateRegistryOwner : LifecycleOwner`）。
- **Compose 栈与 legacy 双栈**：进入 Legacy 页时 `LegacyScreen` attach 全新 NavHostFragment(start=空 anchor `LegacyAnchorFragment`),返回由 `LegacyNavRuntime.popLegacyBackStack()` 协调;`SettingsRoute.kt` 上游文件保持零改动。
- `.trae/hooks.json` 是本地 Trae IDE 的命令拦截钩子配置，与项目无关，勿删勿改。
- 上游 README 描述的是官方功能集；custom 分支额外能力以上方特性表为准。
- 新增的页面除了IME外其他的均应该使用compose界面而不是view