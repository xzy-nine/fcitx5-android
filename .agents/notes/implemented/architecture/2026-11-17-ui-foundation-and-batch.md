# Agent Note: 界面迁移的基础设施与收尾批次

Status: implemented

## Problem

界面迁移不是一次性重写，而是按窗口分批次进行。批次之间需要共享宿主、owner 链、颜色桥与布局命名等基础设施，收尾阶段还要处理 miuix 组件对齐与寄生数据抽离。

## Decision

基础设施先于窗口迁移落地，各批次复用。

**宿主与 owner 链**：`FcitxInputMethodService.createComposeInputView()` 返回 `FrameLayout`，`InputView` 直接作子 View，覆盖层 `ComposeView` 压其上。`input/LifecycleInputMethodService.kt` 统一把四条 owner 链挂到 IME `decorView`。

**组合期同步**：`themeState`、`recreateNonce`、`stylusUiActive` 由 `syncHostedInputView()` 在组合期重建，窗口不自行监听偏好。

**颜色桥**：Compose 迁移件读 `MiuixTheme.colorScheme`；View 侧与主键盘键面经 `keyboard/KeyboardVisuals.kt` 读 fcitx `Theme`。

**布局命名**：键位名收敛到 `KeyboardLayoutNames`，`TextKeyboard.Name` 与 `NumberKeyboard.Name` 委托给它。

**冷启动**：`input/ColdStartTrace.kt` 分阶段计时，`input/ReflectionPrewarm.kt` 做 kotlin-reflect 描述符预热。

**单一边界**：`ComposeEditorInfoContent` 等窗口共用同一宿主，不引入第二个 Compose 根。

**寄生数据抽离**：迁移中把散落在组件内的常量与数据抽成独立文件，包括 `bar/ui/idle/NumberRowLayout.kt`（数字行 `KeyDef` 布局数据）、`picker/PickerDensity.kt`（密度枚举）、`clipboard/ClipboardTextUtils.kt`（`excerptClipboardText` 顶层函数）、`keyboard/KeyboardLayoutNames.kt`（布局名常量对象）。

## Alternatives considered

**每个窗口各自建立 Compose 根**。会重复实现 owner 链与 insets，且多个根之间无法共享状态。

**等所有窗口改完再统一抽公共层**。迁移期间各窗口会各自复制宿主与颜色逻辑，后期合并成本高于先行落地。

## Consequences

- 新增 IME 窗口默认复用现有宿主与 owner 链，不再新建 Compose 根。
- 颜色取值按 Compose 与 View 分流，跨侧引用需经 `KeyboardVisuals`。
- 抽离出的数据文件是单一真源，View 与 Compose 两侧都从它们读。
