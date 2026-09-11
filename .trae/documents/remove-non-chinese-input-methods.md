# 彻底移除 fcitx5-android 非中文输入法插件

## Summary

自用分支 `custom` 上彻底删除 7 个非中文/方言输入法插件及其 git 子模块，使源码、构建产物、子模块拉取不再包含它们。保留：rime（中州韵）、clipboard-filter（剪贴板清理）、`lib/plugin-base`（通用基座）及主 APK 内置的拼音/双拼/五笔/仓颉（fcitx5-chinese-addons）。

## 删除范围（用户已确认）

**删除的插件（7 个）**：anthy（日文）、hangul（韩文）、unikey（越南文）、sayura（僧伽罗文）、thai（泰文）、jyutping（粤语）、chewing（注音）

**保留**：`plugin:rime`、`plugin:clipboard-filter`、`:lib:plugin-base`（rime/clipboard-filter 仍依赖它，不能删）

**prebuilt 子模块**：不动（无人引用后不影响构建，改动需提交到独立子模块仓库，收益小）

## 当前状态分析

- 插件架构：每个插件是独立 Android application（`applicationId = org.fcitx.fcitx5.android.plugin.<name>`），主 APK 通过 [DataManager.kt](file:///e:/GitHub-code/fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt) 运行时扫描安装的插件 APK 加载其 native addon 与数据，主 APK 本身不含这些插件。
- 构建集成：`settings.gradle.kts` 的 `include` 决定哪些插件被 `assembleReleasePlugins` 聚合任务构建。
- 子模块：8 个插件相关子模块（anthy 2 个、其余各 1 个），gitee 镜像，是"影响拉取"的根源。
- 主 APK 硬编码引用（需清理）：
  - [StatusIconMapping.kt](file:///e:/GitHub-code/fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/input/StatusIconMapping.kt)：`fcitx-jyutping`/`fcitx-chewing`/`ko`/`si`/`th`/`vi`/`ja` 7 处映射。
  - [StatusAreaEntry.kt](file:///e:/GitHub-code/fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaEntry.kt#L42-L45)：`// fcitx5-unikey` 注释块 3 行映射（图标为通用 `ic_baseline_*`，被多处复用，资源保留）。
  - drawable：`ic_status_jyutping/chewing/hangul/sayura/thai/vi.xml` 6 个（仅被 StatusIconMapping 引用，可安全删）。

## Proposed Changes

### 1. `settings.gradle.kts`（[当前 L30-L38](file:///e:/GitHub-code/fcitx5-android/settings.gradle.kts#L30-L38)）

删除 7 行 `include`：
`:plugin:anthy`、`:plugin:unikey`、`:plugin:rime`❌（保留！）、`:plugin:hangul`、`:plugin:chewing`、`:plugin:sayura`、`:plugin:jyutping`、`:plugin:thai`

实际删除：`:plugin:anthy`、`:plugin:unikey`、`:plugin:hangul`、`:plugin:chewing`、`:plugin:sayura`、`:plugin:jyutping`、`:plugin:thai`，**保留** `:plugin:rime` 与 `:plugin:clipboard-filter`。

### 2. `.gitmodules` + 子模块目录（Shell git 命令）

删除 8 个子模块条目（anthy 有 2 个）：

| 子模块路径 |
|---|
| plugin/anthy/src/main/cpp/anthy-cmake |
| plugin/anthy/src/main/cpp/fcitx5-anthy |
| plugin/unikey/src/main/cpp/fcitx5-unikey |
| plugin/hangul/src/main/cpp/fcitx5-hangul |
| plugin/sayura/src/main/cpp/fcitx5-sayura |
| plugin/thai/src/main/cpp/fcitx5-libthai |
| plugin/jyutping/src/main/cpp/libime-jyutping |
| plugin/chewing/src/main/cpp/fcitx5-chewing |

操作顺序：
1. `git rm -rf plugin/anthy plugin/hangul plugin/unikey plugin/sayura plugin/thai plugin/jyutping plugin/chewing`（git 会自动移除子模块 gitlink 并同步更新 `.gitmodules`；若 git 拒绝，先逐路径 `git submodule deinit -f -- <path>` 再 `git rm -rf`）
2. 若 `.gitmodules` 残留条目，用 Edit 删除对应 8 段并 `git add .gitmodules`
3. 该操作同时删除 7 个插件目录的全部源码（build.gradle.kts、cpp、res、Manifest 等），不再有 `assemble*Plugins` 引用。

### 3. `StatusIconMapping.kt`（[当前 L42-L55](file:///e:/GitHub-code/fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/input/StatusIconMapping.kt#L42-L55)）

删除 7 处映射：
- L42：`"fcitx-jyutping", "fcitx_jyutping_table" -> ...`
- L43：`"fcitx-chewing" -> ...`
- L51：`"ja" -> ...`（anthy 日文 fallback）
- L52：`"ko" -> ...`
- L53：`"si" -> ...`
- L54：`"th" -> ...`
- L55：`"vi" -> ...`

**保留**：mozc 映射块（L17-L22）与假名 label 块（L27-L32）及 `ic_status_hiragana/katakana/latin_*` 图标——mozc 非本项目插件、不占子模块，删除会牵连更多上游 diff，保留为死代码无副作用。

### 4. `StatusAreaEntry.kt`（[当前 L42-L45](file:///e:/GitHub-code/fcitx5-android/app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaEntry.kt#L42-L45)）

删除 4 行：`// fcitx5-unikey` 注释 + `document-edit`/`character-set`/`edit-find` 3 行映射。`ic_baseline_edit_24` 等通用图标被 10+ 处复用，资源保留。

### 5. drawable 资源（DeleteFile 工具）

删除 6 个文件：
`app/src/main/res/drawable/ic_status_jyutping.xml`、`ic_status_chewing.xml`、`ic_status_hangul.xml`、`ic_status_sayura.xml`、`ic_status_thai.xml`、`ic_status_vi.xml`

（已确认仅被 StatusIconMapping 引用，删除后无残留引用）

### 6. （可选）`AGENTS.md`

特性表末尾补一行登记："移除非中文输入法插件（anthy/hangul/unikey/sayura/thai/jyutping/chewing 及其子模块），保留 rime/clipboard-filter"——方便后续上游合并时知晓此结构性变更。

## Assumptions & Decisions

- **mozc 相关代码与假名图标保留**：本项目无 mozc 插件（非被删列表、无子模块），删除会扩大与上游 diff 范围，故保留。
- **上游 strings.xml / colors.xml 不动**：已确认 `res/values` 中无这些插件名，无需处理；drawable 删除会产生上游资源 diff，用户已确认接受（自用分支，AGENTS.md 规定 UI/资源冲突以 custom 侧为准）。
- **prebuilt 不动**：删除插件后其中 libhangul/libchewing/libthai/libime-jyutping/anthy-dict/chewing-dict 等成为孤儿，但无人引用不影响构建，改动需提交到独立子模块仓库，故跳过。
- **已装机旧数据**：用户设备上旧插件 APK 不卸载也不影响主 APK 运行（主 APK 扫描不到插件服务则跳过）；`DataHierarchy.diff` 会在下次同步时自动清理 dataDir 中残留的旧插件文件。本任务不涉及已装机设备。
- **Git 提交**：全部改动完成后单个提交，遵循仓库 commit message 规范（冒号后中文）。

## Verification

1. `./gradlew.bat :app:compileDebugKotlin` —— 验证 settings/代码/资源编译通过（drawable 删除后无引用残留）。
2. `git submodule status` —— 确认 8 个被删路径消失，剩余 12 个子模块（fcitx5 核心 5 个 + rime 5 个 + ClearURLsRules + webdav）。
3. `git status` / `git diff --stat` —— 确认改动清单：settings.gradle.kts、.gitmodules、7 个插件目录删除、StatusIconMapping.kt、StatusAreaEntry.kt、6 个 drawable。
4. `git diff main -- app/src/main/res/` —— 自查资源 diff 仅剩 6 个 drawable 删除（预期内）。
5. `git submodule update --init --recursive` 冒烟验证拉取不再触碰被删子模块（可选）。
