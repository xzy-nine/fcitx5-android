
**视图层级结构：迁移开始**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景)
│           ├── kawaiiBar.view (工具栏 - KawaiiBarComponent)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / etc.)
│           ├── preedit.ui.root (预编辑区域)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root (弹出窗口)
├── CandidatesView (独立的浮动候选视图)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

---

**视图层级结构：横向候选栏 Compose 化接线后（上次迁移）**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景)
│           ├── kawaiiBar.view (ViewAnimator - 工具栏 KawaiiBarComponent)
│           │   └── 当前显示: candidateUi.root (ConstraintLayout - CandidateUi)
│           │       ├── expandButton (工具栏展开入口，唯一入口)
│           │       └── composeCandidate.view (ComposeView - ComposeCandidateComponent)
│           │           └── ComposeCandidateBar (Compose)
│           │               ├── CandidateRow (LazyRow)
│           │               │   └── CandidateItem × N (点击/长按)
│           │               └── HideKeyboardButton (候选行右侧，收起键盘)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── preedit.ui.root (预编辑区域)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root (弹出窗口)
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

接线链路（横向候选栏迁移）：

1. 候选事件：`InputBroadcaster.onCandidateUpdate` → `ComposeCandidateComponent.onCandidateUpdate` → `applyCandidates` → `MutableStateFlow<CandidateBarState>`
2. 渲染：`collectAsState` → `ComposeCandidateBar` 重组（LazyRow 候选列表）
3. 选择/长按：`CandidateItem` onClick → `fcitx.select(index)`；onLongClick → `inputView.showCandidateActionMenu`
4. 懒加载 + 滚动同步：`LazyListState.layoutInfo` snapshotFlow → `onScrollOffsetChanged(firstVisibleIndex)` → `_state.offset` + `refreshExpanded` → `_expandedCandidateOffset`；`onLoadMore` → `loadMoreIfNeeded` → `fcitx.getCandidates`
5. 展开联动：工具栏 `expandButton`（状态机驱动，唯一入口）→ `attachWindow(Grid|FlexboxExpandedCandidateWindow)`；展开窗口消费 `composeCandidate.total`（Idle=-1）与 `expandedCandidateOffset`
6. 滑动开关：`userScrollEnabled = horizontalCandidateSwipe`

旧文件位置（断开接线，均保留供后续审查）：

| 旧文件 | 路径 | 状态 |
|---|---|---|
| HorizontalCandidateComponent.kt | app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/ | 已断开接线，无外部引用 |
| HorizontalCandidateViewAdapter.kt | 同上 | 已断开接线，仅被 HorizontalCandidateComponent 引用 |
| CandidateItemUi.kt | app/src/main/java/org/fcitx/fcitx5/android/input/candidates/ | 仍被展开候选窗口使用（PagingCandidateViewAdapter），未断开 |
| CandidateViewHolder.kt | 同上 | 仍被展开候选窗口体系使用，未断开 |

---

**视图层级结构：工具栏 + 预编辑栏 Compose 化接线后（本次迁移）**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景)
│           ├── composeKawaiiBar.view (ComposeView - ComposeKawaiiBarComponent)
│           │   └── ComposeToolbar (Compose)
│           │       ├── Idle 态: MenuButton + [Empty|Toolbar|Clipboard|NumberRow|InlineSuggestion] + HideKeyboardButton
│           │       │   ├── Toolbar: ToolbarButtonsRow (undo/redo/cursor/clipboard/split/more/tune)
│           │       │   ├── Clipboard: ClipboardSuggestion
│           │       │   ├── NumberRow: AndroidView 包装 (BaseKeyboard)
│           │       │   └── InlineSuggestion: AndroidView 包装 (InlineContentView)
│           │       ├── Candidate 态: 候选栏内容 (ComposeCandidateBar) + ExpandButton
│           │       └── Title 态: BackButton + TitleText + ExtensionSlot
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── composePreedit.view (ComposeView - ComposePreeditComponent)
│           │   └── ComposePreedit (Compose)
│           │       ├── 上行: auxUp + preedit (含光标竖线)
│           │       └── 下行: auxDown
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root (弹出窗口)
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

接线链路（本次工具栏 + 预编辑栏迁移）：

**预编辑栏**：
1. 输入面板事件：`InputBroadcaster.onInputPanelUpdate` → `ComposePreeditComponent.onInputPanelUpdate` → 解析 auxUp/preedit/auxDown → `MutableStateFlow<PreeditState>`
2. 渲染：`collectAsState` → `ComposePreedit` 重组（上行文本 + 光标 + 下行文本）

**工具栏**：
1. 候选/预编辑事件 → `KawaiiBarStateMachine` 状态转换 → `MutableStateFlow<KawaiiBarStateMachine.State>`
2. Idle 子状态：`evalIdleUiState()` → `MutableStateFlow<IdleSubState>`（Empty/Toolbar/Clipboard/NumberRow/InlineSuggestion）
3. Title 状态：`onWindowAttached` → `MutableStateFlow<TitleData?>`
4. 按钮回调：`ToolbarCallbacks` 委托给 `CommonKeyActionListener` / `InputWindowManager` / `FcitxInputMethodService`
5. 候选栏展开：复用 `ComposeCandidateComponent` + `ExpandButtonStateMachine`
6. Clipboard：`ClipboardManager.OnClipboardUpdateListener` + 超时逻辑
7. InlineSuggestions：系统 API 限制，AndroidView 包装

旧文件位置（断开接线，均保留供后续审查）：

| 旧文件 | 路径 | 状态 |
|---|---|---|
| PreeditComponent.kt | app/src/main/java/org/fcitx/fcitx5/android/input/preedit/ | 已断开接线，保留供对比 |
| PreeditUi.kt | 同上 | 仍被 CandidatesView 使用（浮动候选预编辑），未断开 |
| KawaiiBarComponent.kt | app/src/main/java/org/fcitx/fcitx5/android/input/bar/ | 已断开接线，保留供对比 |
| IdleUi.kt | app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/ | 已断开接线，保留供对比 |
| CandidateUi.kt | 同上 | 已断开接线，保留供对比 |
| TitleUi.kt | 同上 | 已断开接线，保留供对比 |
| ButtonsBarUi.kt | app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/ | 已断开接线，保留供对比 |
| ClipboardSuggestionUi.kt | 同上 | 已断开接线，保留供对比 |
| NumberRow.kt | 同上 | 仍被 NumberRow AndroidView 包装使用（待后续迁移） |
| InlineSuggestionsUi.kt | 同上 | 已断开接线，保留供对比 |

---

**本次追加完成的 5 项**：

1. **NumberRow AndroidView 包装**：`NumberRowHost` Composable 将 `NumberRow`(View) 包装在 `AndroidView` 中，设置 `keyActionListener`/`popupActionListener`/`onCollapseListener`
2. **InlineSuggestions AndroidView 包装**：`InlineSuggestionsHost` Composable 预留接口，待后续填充真实 `InlineContentView` inflate 逻辑
3. **候选栏内容集成**：`candidateContent` 直接使用 `AndroidView { composeCandidate.view }` 嵌入 `ComposeCandidateComponent` 的 `ComposeView`
4. **标题扩展槽**：`onWindowAttached` 中获取 `window.onCreateBarExtension()` 的 View，在 `TitleContent` 中用 `AndroidView` 包装
5. **hideKeyboardButton 滑动手势**：使用 `detectDragGestures` 实现：垂直向下拖拽隐藏键盘，水平拖拽左滑显示数字行（旋转图标反馈）

---

**视图层级结构：预编辑栏 + 工具栏合并为单一 Compose 容器（最新两个提交 `e795b214` + `d83285bb`）**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 顶部圆角裁剪到工具栏顶，预编辑栏右侧保持透明)
│           ├── composeTopView (单一 ComposeView - MiuixTheme + Column)
│           │   ├── composePreedit.PreeditContent() (预编辑栏，在上，透明)
│           │   │   └── ComposePreedit (PreeditLine: auxUp + preedit + 光标竖线 / 下行 auxDown)
│           │   └── composeKawaiiBar.ToolbarContent() (工具栏，在下，顶部裁圆角)
│           │       └── ComposeToolbar (Idle/Candidate/Title 态)
│           │           ├── Idle 态: MenuButton + [Empty|Toolbar|Clipboard|NumberRow|InlineSuggestion] + HideKeyboardButton
│           │           │   ├── Toolbar: ToolbarButtonsRow (undo/redo/cursor/clipboard/split/more/tune)
│           │           │   ├── Clipboard: InlineSuggestion?(系统) / 文本行 (AndroidView 仅 InlineSuggestionsUi.root)
│           │           │   ├── NumberRow: NumberRowHost (AndroidView 包装 BaseKeyboard)
│           │           │   └── InlineSuggestion: AndroidView(inlineSuggestionsUi.root)
│           │           ├── Candidate 态: composeCandidate.CandidateBarContent() (Composable，无嵌套 ComposeView) + ExpandButton
│           │           └── Title 态: BackButton + TitleText + ExtensionSlot (AndroidView 包装 onCreateBarExtension)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root (弹出窗口)
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

接线链路（合并为单一容器 ）：

1. **单一 Composition 容器**：`InputView` 新增 `composeTopView`（一个 `ComposeView`），其 `setContent` 内用 `MiuixTheme(ThemeController(ColorSchemeMode.System))` 包裹 `Column`，依次直接调用 `composePreedit.PreeditContent()`（上）与 `composeKawaiiBar.ToolbarContent()`（下）。三个 Compose 组件不再各自持有独立 `ComposeView`，消除「ComposeView 内嵌 ComposeView」的冗余 Composition，主题与 Composition 统一。
2. **组件形态变更**：`ComposeKawaiiBarComponent`/`ComposePreeditComponent`/`ComposeCandidateComponent` 由 `UniqueViewComponent`（持有 `view: ComposeView`）改为 `UniqueComponent + Dependent + ManagedHandler`，对外只暴露 `@Composable` 入口 `ToolbarContent()` / `PreeditContent()` / `CandidateBarContent()`，由父级 Composition 统一调用。
3. **工具栏高度驱动**：高度不再由 View 的 `LayoutParams` 决定，改为 `ToolbarContent` 内部 `HEIGHT`（动态 getter）决定；偏好变化 → `composeKawaiiBar.notifyToolbarHeightChanged()` → `_toolbarHeightVersion` 自增 → 父级 `key(toolbarHeightVersion) { ToolbarContent() }` 触发重组。
4. **预编辑高度 + 背景裁剪**：`PreeditContent` 的 `Modifier.onSizeChanged { updatePreeditHeight(it.height) }` → `preeditHeightPx` → `applyCustomBackgroundClip()` 用 `customBackground.applyTopRoundedCornerClip(cornerRadius, preeditHeightPx)` 把键盘背景只绘制到工具栏顶部以下；`composeTopView` 自身 `setPadding` 处理左右边距。顶部圆角由「裁 keyboardView」改为「裁 customBackground + 工具栏自身（`Modifier.clip(RoundedCornerShape(topStart/topEnd=16dp))`）」，圆角始终落在工具栏顶部，预编辑栏右侧保持透明。
5. **候选栏内容接入**：`ComposeKawaiiBarComponent.ToolbarContent` 内 `candidateContent = { composeCandidate.CandidateBarContent() }` 直接作为 Composable 接入（替代原先 `AndroidView { composeCandidate.view }`），与工具栏共享同一 Composition；其余候选事件/选择/懒加载/展开联动接线不变（见上文「横向候选栏迁移」）。

旧文件/接口位置（本次接线变动）：

| 项 | 路径/符号 | 状态 |
|---|---|---|
| `composeKawaiiBar.view` | `ComposeKawaiiBarComponent` | 已移除，改为 `ToolbarContent()` Composable 入口 |
| `composePreedit.view` | `ComposePreeditComponent` | 已移除，改为 `PreeditContent()` Composable 入口 |
| `composeCandidate.view` | `ComposeCandidateComponent` | 已移除，改为 `CandidateBarContent()` Composable 入口（经 `candidateContent` 嵌入工具栏） |
| `UniqueViewComponent` 基类 | 三个 Component | 不再继承，改 `UniqueComponent + Dependent + ManagedHandler` |
| 键盘顶圆角裁剪 | `InputView.applyCustomBackgroundClip` / `ViewOutlineExt.applyTopRoundedCornerClip` | 由裁 keyboardView 改为裁 `customBackground`+工具栏自身 |
| `PreeditLine` / 光标受控滚动 | `ComposePreedit.kt` | `d83285bb` 新增，替代拼接字符的光标实现 |

---

**视图层级结构：合并容器回归修复后（当前状态）**

> 背景：`e795b214` 将预编辑栏收进 `composeTopView` 后引入回归 —— 预编辑栏可变高度撑高 `keyboardView`（`wrapContent` 高度 + 底部锚定），`onComputeInsets` 的 `contentTopInsets = keyboardView.top` 随打字变化，应用页面反复伸缩。修复：预编辑栏移回键盘体之外悬浮，`composeTopView` 只保留工具栏（高度恒定 `HEIGHT`）；键盘体高度恢复恒定，insets 稳定。

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景，铺满键盘体)
│           ├── composeTopView (单一 ComposeView - MiuixTheme，仅含工具栏)
│           │   └── composeKawaiiBar.ToolbarContent() (工具栏)
│           │       └── ComposeToolbar (Idle/Candidate/Title 态)
│           │           ├── Idle 态: MenuButton + [Empty|Toolbar|Clipboard|NumberRow|InlineSuggestion] + HideKeyboardButton
│           │           │   ├── Toolbar: ToolbarButtonsRow (undo/redo/cursor/clipboard/split/more/tune)
│           │           │   ├── Clipboard: 文本行 (AndroidView 仅 InlineSuggestionsUi.root)
│           │           │   ├── NumberRow: NumberRowContent (ComposeNumberRow，全 Compose)
│           │           │   └── InlineSuggestion: AndroidView(inlineSuggestionsUi.root)
│           │           ├── Candidate 态: composeCandidate.CandidateBarContent() (Composable，无嵌套 ComposeView) + ExpandButton
│           │           └── Title 态: BackButton + TitleText + ExtensionSlot (AndroidView 包装 onCreateBarExtension)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── composePreedit.view (ComposeView - ComposePreeditComponent，悬浮在 keyboardView 上方)
│           │   └── ComposePreedit (上行: auxUp + preedit + 光标竖线 / 下行: auxDown)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root (弹出窗口)
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

接线链路（回归修复后）：

1. **工具栏保持单一 Composition**：`composeTopView`（一个 `ComposeView`）的 `setContent` 内 `MiuixTheme(ThemeController(ColorSchemeMode.System))` 直接调用 `composeKawaiiBar.ToolbarContent()`（无 `Column` 包裹，工具栏高度恒定为 `HEIGHT`）；偏好变化 → `notifyToolbarHeightChanged()` → `_toolbarHeightVersion` 自增 → `key(toolbarHeightVersion) { ToolbarContent() }` 触发重组。
2. **候选栏仍在该 Composition 内**：`candidateContent = { composeCandidate.CandidateBarContent() }` 直接作为 Composable 接入，无嵌套 ComposeView。
3. **预编辑栏恢复独立悬浮层**：`ComposePreeditComponent` 重新持有 `view`（`ComposeView` + `MiuixTheme` 包裹 `PreeditContent()`），`InputView` 中 `above(keyboardView)` 锚定、`wrapContent` 高度；其高度不参与 `keyboardView` 测量，键盘体高度恒定、insets 稳定，预编辑栏右侧天然透明。
4. **顶部圆角恢复为裁 keyboardView**：删除按预编辑高度裁剪 `customBackground` 的逻辑（`preeditHeightPx`/`applyCustomBackgroundClip`），恢复 `keyboardView.applyTopRoundedCornerClip(dp(16))`（圆角落在工具栏顶部，预编辑栏在键盘体外不受影响）；`ViewOutlineExt.applyTopRoundedCornerClip` 的 `topInsetPx` 参数保留（默认 0，当前未使用）。
5. **左右边距**：`updateKeyboardSize()` 对 `composeTopView` 与 `composePreedit.view` 分别 `setPadding(sidePadding, 0, sidePadding, 0)`。

**后续主键盘迁移计划（不变）**：主键盘（`windowManager.view` 下的 `KeyboardWindow`/`BaseKeyboard` 及 Picker/展开候选窗口）同样落在 `composeTopView` 的单一 Composition 内 —— `composeTopView` 扩展到整个键盘体（matchParent 高度），Compose 内 `Column { ToolbarContent(); KeyboardContent() }`；窗口 attach/detach 改为状态驱动切换内容；按键弹窗保持 Compose 内叠加（不另开窗口，遵循 IME 弹层限制）。键盘体高度恒定，不影响 insets。预编辑栏维持独立悬浮层。

旧文件/接口位置（本次回归修复变动）：

| 项 | 路径/符号 | 状态 |
|---|---|---|
| `composePreedit.view` | `ComposePreeditComponent` | **恢复**：合并提交曾移除，回归修复后重新提供独立 `view`（悬浮 ComposeView），`PreeditContent()` 保留供其宿主调用 |
| `composeKawaiiBar.view` | `ComposeKawaiiBarComponent` | 保持移除，入口为 `ToolbarContent()`（在 `composeTopView` 单一 Composition 内） |
| `composeCandidate.view` | `ComposeCandidateComponent` | 保持移除，入口为 `CandidateBarContent()`（经 `candidateContent` 嵌入工具栏） |
| `preeditHeightPx` / `InputView.applyCustomBackgroundClip` | `InputView.kt` | 已删除（合并提交引入，回归修复移除） |
| 键盘顶圆角裁剪 | `InputView.kt` / `ViewOutlineExt.kt` | 恢复裁 `keyboardView`（`dp(16)`）；`applyTopRoundedCornerClip` 的 `topInsetPx` 默认 0 未使用 |

---

**视图层级结构：按键弹窗层 Compose 化接线后（本次迁移）**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景，铺满键盘体)
│           ├── composeTopView (单一 ComposeView - MiuixTheme，仅含工具栏)
│           │   └── composeKawaiiBar.ToolbarContent()
│           │       └── ComposeToolbar (Idle/Candidate/Title 态)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── composePreedit.view (ComposeView - 悬浮在 keyboardView 上方)
│           ├── popup.root ← [本次改动] FrameLayout → ComposeView (matchParent × matchParent，z 序最顶)
│           │   └── MiuixTheme
│           │       └── CompositionLocalProvider(LocalLayoutDirection = Ltr)
│           │           └── PopupContent() (Compose)
│           │               ├── PopupEntry × N (miuix Surface + miuix Text，Modifier.offset 绝对定位)
│           │               └── PopupContainer × N (miuix Surface)
│           │                   ├── PopupKeyboard (Row/Column 网格 + focusedIndex)
│           │                   └── PopupMenu (CircleShape 圆底 + miuix Icon)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
│   ├── preeditUi.root (预编辑)
│   └── candidatesUi.root (分页候选列表 - PagedCandidatesUi)
```

接线链路（本次按键弹窗层迁移）：

1. **只换渲染层，不换协调层**：`PopupComponent` 继续担任协调器与唯一入口（`listener` / `dismissAll()` 契约不变），内部由「持有 View 引用」改为维护 `MutableStateFlow<PopupLayerState>`；`root` 由 `FrameLayout` 改为 `ComposeView`，`setContent` 内 `MiuixTheme(ThemeController(ColorSchemeMode.System))` 调用 `PopupContent(state, visuals)`。
2. **弹窗层不接收触摸**：`root` 保持 `isClickable = false` / `isFocusable = false`，Compose 根 `Box` 不加任何点击修饰符；手势仍由 `BaseKeyboard` / `PickerPageUi` 侧的 `CustomGestureView` 捕获后经 `PopupActionListener` 转发。
3. **锚点抽象化（关键）**：弹窗只消费「窗口绝对坐标 `Rect`」—— View 侧来自 `KeyView.bounds`（`getLocationInWindow`），Compose 侧来自 `boundsInWindow()`（`ComposeNumberRow` 已验证通用）。因此 `BaseKeyboard` / `TextKeyboard` / `PickerWindow` / `PickerPageUi` / `ComposeNumberRow` 五个生产方**零改动**。
4. **同步返回语义保留**：`ChangeFocusAction.outResult` 与 `TriggerAction.outAction` 由调用方同步读取，故 `changeFocus` / `triggerFocused` 直接读写 `_state.value`，不经过重组或协程。
5. **定位算式的来源**：`rootBounds` 继续用 `addOnLayoutChangeListener` + `getLocationInWindow()`（`ComposeView` 本身是 View），原 px 算式逐字保留，避开「Compose 首次布局未完成时拿不到原点」的时序问题；坐标一律是「相对 `popup.root` 左上角的 px」，Compose 侧用 `Modifier.offset { IntOffset(x, y) }` + `LocalDensity` 换算。
6. **焦点热路径零分配**：行布局（`computeDisplayRows`）与 `keyOrders` 在容器创建时算一次，`remember` 缓存；`ChangeFocusAction`（手指 move 每帧）只替换 container 实例的 `focusedIndex` 单字段。
7. **强制 LTR**：`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`，等价原 View 的 `root.layoutDirection = LTR`，否则 RTL 下 `Modifier.offset` 方向反转。

旧文件/接口位置（本次接线变动）：

| 项 | 路径/符号 | 状态 |
|---|---|---|
| `popup.root` | `PopupComponent` | 由 `FrameLayout` 改为 `ComposeView`（宿主不变，仍由 `InputView` 第 349 行 `add(popup.root, lParams(matchParent, matchParent))` 挂载） |
| `PopupLayerState.kt` | 新增 | `PopupLayerState` / `PopupEntryState` / `sealed PopupContainerState{Keyboard, Menu}` / `PopupVisuals` |
| `PopupLayoutMath.kt` | 新增 | 纯计算（无 View 依赖）：`calcInitialFocusedColumn` / `createColumnOrder` / `limitIndex` / `keyboardGrid` / `computeKeyOrders` / `computeDisplayRows` / `computeEntryPosition` / `computeKeyboardOffset` / `computeMenuOffset` / `keyboardFocusIndex` / `menuFocusIndex` |
| `ComposePopupLayer.kt` | 新增 | 渲染层：`PopupContent()` 入口；`PopupEntryItem` / `PopupKeyboardItem` / `PopupKeyCell` / `ScaledLabel` / `PopupMenuItem` / `PopupMenuCell` |
| `PopupEntryUi.kt` | 同上目录 | 已断开接线，保留供对比 |
| `PopupKeyboardUi.kt` | 同上 | 已断开接线，保留供对比 |
| `PopupMenuUi.kt` | 同上 | 已断开接线，保留供对比 |
| `PopupContainerUi.kt` | 同上 | 已断开接线，**保持零改动**（其纯计算已复制到 `PopupLayoutMath.kt`，避免上游合并冲突） |
| `PopupAction.kt` / `PopupActionListener.kt` / `PopupPreset.kt` / `EmojiModifier.kt` | 同上 | 不动，对外契约 |

组件选型：miuix 优先（主题 `MiuixTheme`、背景 `Surface`、文字 `Text`、图标 `Icon`），布局与定位用 foundation（`Box` / `Row` / `Column` / `Modifier.offset`，miuix 无对应基础件）。IME 内不使用任何 miuix `Window*` 弹层。

**依赖统一（同批完成）**：Compose 由 JetBrains CMP 伞包（`org.jetbrains.compose.foundation:foundation-android`）改为 Google 官方 `androidx.compose.runtime` / `foundation` / `ui`，统一版本 `1.12.0`（`gradle/libs.versions.toml` 单一 `compose` 版本源），与 Notify-Relay / WebDAVPass 路线一致；不引入 compose BOM，避免与已固定版本冲突。

**后续迁移计划（不变）**：主键盘（`windowManager.view` 下的 `KeyboardWindow`/`BaseKeyboard` 及 Picker 窗口）并入单一 Composition。本次已解除 `KeyView.bounds` 对弹窗层的耦合——键盘 Compose 化后只需把 Compose 坐标（`onGloballyPositioned`）换算成同一个 `Rect` 喂给 `PopupComponent.listener` 即可，两者不必同批迁移。

---

**视图层级结构：wm 共存机制 + 「更多页」Compose 化接线后（本次迁移）**

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景，铺满键盘体)
│           ├── composeTopView (单一 ComposeView - MiuixTheme，仅含工具栏)
│           │   └── composeKawaiiBar.ToolbarContent()
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口:
│           │       ├── KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow (View 窗口，onCreateView 兜底)
│           │       └── StatusAreaWindow (ComposeWindow - 更多页) [本次改动]
│           │           └── composeWindowView (ComposeView - MiuixTheme，经 createComposeWindowView 创建)
│           │               └── StatusAreaWindow.Content()
│           │                   └── StatusAreaGrid (LazyVerticalGrid ×4，StateFlow 驱动)
│           │                       ├── StatusAreaCell × N (miuix IconButton 48dp 圆角图标/首字符 + 12sp 标签)
│           │                       └── StatusAreaMenu (fcitx 子菜单，Compose 弹层锚定格子)
│           ├── composePreedit.view (ComposeView - 悬浮在 keyboardView 上方)
│           ├── popup.root (ComposeView - 按键弹窗层，宿主统一走 createComposeWindowView)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
```

接线链路（本次 wm 共存 + 更多页迁移）：

1. **wm 共存挂载**：`InputWindowManager.attachWindow` 用 `window is ComposeWindow` 分流——Compose 窗口经
   `createComposeWindowView(context) { window.Content() }` 创建统一 `ComposeView`（
   `MiuixTheme(ThemeController(System))` 包裹，强制 LTR）；其余窗口维持 `window.onCreateView()` 兜底。
   `TransitionManager` Slide/Fade 100ms 进出场动画对两类 view 一致，`EssentialWindow` 视图缓存逻辑不变。
2. **Composition 生命周期**：detach 时对非 essential 的 Compose 窗口先 `onDetached()` →
   `removeView` → `oldView.disposeComposition()`，避免 Composition 泄漏；essential Compose 窗口视图缓存复用、不
   dispose。
3. **更多页数据驱动**：`StatusAreaWindow` 实现 `ComposeWindow`，新增
   `MutableStateFlow<List<StatusAreaEntry>>`；`onStatusAreaUpdate` 写入（staticEntries + fcitx 条目），
   `Content()` 内 `collectAsState` 渲染；`onAttached` 仍 `fcitx.statusArea()` 拉数据。
4. **fcitx 子菜单 Compose 弹层**：格子点击时用 `onGloballyPositioned` 记录 `boundsInRoot()` 作锚点，在根
   `BoxWithConstraints` 内以 `Modifier.offset` 绝对定位展开 `StatusAreaMenu`（不触发系统 Dialog /
   `PopupMenu`，IME 浮窗内安全）；菜单项 `isSeparator` 渲染分隔线，点击项回调 `activateAction(action)`。
5. **点击逻辑复用**：Android 项（主题/输入法/重载/键盘）与无子菜单的 fcitx 项走
   `StatusAreaWindow.onItemClick`（`AppUtil` 跳转 / `reloadConfig`+`SubtypeManager.syncWith` / 直接
   `activateAction`）。
6. **工具栏扩展槽不变**：`onCreateBarExtension()` 仍返回 View（editorinfo + settings `ToolButton`
   ），由工具栏 Title 态 `AndroidView` 承载。

旧文件/接口位置（本次接线变动）：

| 项                                               | 路径/符号           | 状态                                                                                                                                                               |
|-------------------------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ComposeWindow.kt`                              | `input/wm/`     | 新增：标记接口 `@Composable fun Content()`，Compose 化窗口实现                                                                                                                |
| `ComposeWindowHost.kt`                          | 同上              | 新增：`createComposeWindowView(context, content)` 统一宿主（ComposeView + MiuixTheme + LTR）                                                                              |
| `InputWindowManager.kt`                         | 同上              | 仅追加：attachWindow 增加 `ComposeWindow` 分支；detach 增加 `disposeComposition()`；`InputWindow.kt` 零改动                                                                     |
| `ComposeStatusArea.kt`                          | `input/status/` | 新增：`StatusAreaGrid`（4 列 `LazyVerticalGrid`）/ `StatusAreaCell`（miuix `IconButton` 48dp 圆角+12sp 标签）/ `StatusAreaMenu`（fcitx 子菜单 Compose 弹层，miuix `DropdownImpl` 行） |
| `StatusAreaWindow.kt`                           | 同上              | 实现 `ComposeWindow`；`onCreateView()` 改为 `createComposeWindowView` 兜底；移除 RecyclerView/`PopupMenu`/adapter，`onDetached` 不再 dismiss                                  |
| `StatusAreaAdapter.kt` / `StatusAreaEntryUi.kt` | 同上              | 已断开接线，保留供对比（不再被任何代码引用）                                                                                                                                           |

组件选型：**Compose 层全部走 miuix 主题**（`MiuixTheme.colorScheme`，不再读 fcitx View 主题），组件复用
miuix（`IconButton` 可调 `cornerRadius`、`DropdownImpl` 行、`Icon` / `Text`），布局与定位用 foundation（
`BoxWithConstraints` / `LazyVerticalGrid` / `GridCells.Fixed(4)` / `Modifier.offset`）；fcitx 子菜单弹层为纯
Compose 覆盖层，严禁 Android `PopupMenu` / miuix `Window*` 弹层。这是「废弃 View 颜色主题体系、转为
miuix 主题」的落地之一。

**Compose 层主题统一（本次补充）**：工具栏 / 候选栏 / 数字行与状态区一并切到 miuix 主题——

- `ComposeKawaiiBarComponent.getVisuals()`（`@Composable`）：`barColor = background`、
  `iconColor/textColor = onSurface`（`ToolbarVisuals` 删除未使用的 `pressHighlightColor`/
  `dividerColor`）；移除 `theme`/`keyBorder` 依赖。
- `ComposeCandidateComponent.getVisuals()`（`@Composable`）：`textColor = onSurface`、
  `commentColor = onSurfaceVariantSummary`、`pressHighlightColor = onSurface.copy(alpha=0.1f)`、
  `dividerColor = dividerLine`。
- `NumberRowContent`/`NumberKey`：删除 `theme: Theme` 参数，按键文字 `onSurface`、按下高亮
  `onSurface.copy(alpha=0.1f)`。
- `ComposePreeditComponent.getVisuals()`（`@Composable`）：`textColor = onSurface`、
  `highlightColor = primary`、`backgroundColor = background`；移除 `theme`/`keyBorder` 依赖与未使用的
  `activeBkg` 死代码。
- View 侧（主键盘 `KeyboardWindow`/`BaseKeyboard`、展开候选窗口、`CandidatesView`、`ToolButton` 等）仍用
  fcitx `Theme`，待后续迁移。

**后续迁移计划（不变）**：主键盘（`windowManager.view` 下的 `KeyboardWindow`/`BaseKeyboard` 及 Picker
窗口）可复用 wm 共存机制逐个 Compose 化，或并入 `composeTopView` 单一 Composition；`KeyView.bounds`
对弹窗层的耦合已解除，两者不必同批迁移。

---

**视图层级结构：候选操作菜单 + 剪贴板主页/编辑 + 文本编辑 Compose 化接线后（本次迁移）**

> 计划按「候选操作菜单 → 剪切板 → 文本编辑」从易到难推进；**展开候选（候选网格）页按用户要求跳过**，
> 后续再处理。候选操作菜单改为 IME 安全的 Compose 覆盖层，替代系统 `PopupMenu`。

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView (extends BaseInputView extends ConstraintLayout)
│           ├── customBackground (ImageView - 主题背景)
│           ├── composeTopView (单一 ComposeView - 仅含工具栏)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口:
│           │       ├── KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow (View 窗口)
│           │       ├── StatusAreaWindow (ComposeWindow - 更多页，上批)
│           │       ├── ClipboardWindow (ComposeWindow - 剪贴板主页) [本次]
│           │       ├── ClipboardEditWindow (ComposeWindow - 剪贴板编辑) [本次]
│           │       └── TextEditingWindow (ComposeWindow - 文本编辑) [本次]
│           ├── composePreedit.view (ComposeView - 悬浮预编辑)
│           ├── popup.root (ComposeView - 按键弹窗层)
│           ├── candidateActionMenu.root (ComposeView - 候选操作菜单覆盖层) [本次新增，z 序最高]
│           │   └── ComposeCandidateActionMenu overlay (Rect 锚定 + dismiss 蒙层)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
├── CandidatesView (独立的浮动候选视图，迁移范围外，保持 View 实现)
```

**候选操作菜单（本次）**：

1. `BaseInputView.showCandidateActionMenu` 增加基于 `Rect` 的重载（`open`，默认 no-op），供 Compose 调用方使用。
2. `InputView` 内新增 `candidateActionMenu`（`ComposeCandidateActionMenu`，`UniqueComponent + Dependent + ManagedHandler`），
   其 `root`（ComposeView，经 `createComposeWindowView`）作为 `popup.root` 同级覆盖层加入 `InputView`（matchParent）。
3. `showCandidateActionMenu(idx, text, anchor: Rect)` → `candidateActionMenu.show(...)`：`MutableStateFlow<CandidateActionMenuState?>`
   驱动 `CandidateActionMenuOverlay` 渲染（Rect 锚定 + 透明 dismiss 蒙层），动作回调触发 fcitx 动作后 dismiss。
4. `ComposeCandidateComponent` 长按候选改用 `getLocationInWindow()` 换算 `Rect` 喂新菜单；`CandidatesView`（浮动候选，
   范围外）仍保留原系统 `PopupMenu`。

**剪贴板主页（本次）**：

1. `ClipboardWindow` 实现 `ComposeWindow`，`onCreateView()` 改 `createComposeWindowView { Content() }`。
2. 数据：`MutableStateFlow<List<ClipboardEntry>>` 订阅新增的 `ClipboardManager.observeAllEntries()`（Room Flow）；
   `ClipboardDao` 追加 `observeAllEntries(): Flow<List<ClipboardEntry>>`（`allEntries()` 仍为 `PagingSource`，与旧
   `ClipboardAdapter` 共存）。UI 态复用 `ClipboardStateMachine`（EnableListening/AddMore/Normal）用
   `MutableStateFlow<State>` 驱动。
3. `ComposeClipboard.kt`（新增）：`ClipboardListContent`（按 state 切 EnableListening/AddMore/列表）、
   `ClipboardEntryCard`（`combinedClickable` 点击上屏/长按菜单，实体气泡点击上屏片段，钉标记叠加）、删除全部确认层。
   图标一律 `painterResource`（material `Icons` 依赖未引入，改用既有 drawable）。

**剪贴板编辑（本次）**：

1. `ClipboardEditWindow` 实现 `ComposeWindow`，`onCreateView()` 改 `createComposeWindowView { Content() }`；
   分词用现成 `ClipboardTextAnalyzer.segment()`。
2. `ComposeClipboardEdit.kt`（新增）：`ClipboardEditContent`——分词模式 `FlowRow` 词块（点击选中/长按单选，
   简化原拖选语义）+ 重组预览；文本模式 `BasicTextField`；底部 全选/反选/模式切换 + 复制/取消/确定。复制/上屏仍
   借窗口 `copyOnly` / `commitToInput` / `exitToPrevWindow`。

**文本编辑（本次）**：

1. `TextEditingWindow` 实现 `ComposeWindow` + `InputBroadcastReceiver`；方向键/全选/剪切/复制/粘贴/退格动作逻辑保留（
   `sendCombinationKeyEvents` / `performContextMenuAction` / `sendDownUpKeyEvents`），`hasSelection` 与
   `_userSelection`（`MutableStateFlow`）驱动选区态；`onCreateBarExtension()` 仍返回含剪贴板 `ToolButton` 的 View。
2. `ComposeTextEditing.kt`（新增）：`TextEditingContent` 复刻原 constraint 网格——左侧 2 列 3 行方向键 + 起始/结尾，
   右侧 30% 动作列（全选/剪切↔、复制/粘贴/退格），方向键/动作键均 miuix `Surface`。

旧文件位置（断开接线，均保留供对比）：

| 旧文件 | 路径 | 状态 |
|---|---|---|
| `ClipboardUi.kt` / `ClipboardAdapter.kt` / `ClipboardEntryUi.kt` / `ClipboardInstructionUi.kt` / `ClipboardStateMachine.kt` / `ClipboardTextAnalyzer.kt` / `SpacesItemDecoration.kt` | `input/clipboard/` | 除 `ClipboardStateMachine`/`ClipboardTextAnalyzer`（仍被 Compose 复用）外其余断开接线、保留供对比 |
| `TextEditingUi.kt` / `TextEditingButton.kt` | `input/editing/` | 已断开接线，保留供对比 |
| `ClipboardEditWindow.xml` / `ClipboardEditWindowBinding` | `res/layout/` | 已断开接线，保留供对比（binding 文件不再引用） |
| `ClipboardEditActivity`（历史入口） | — | 早前已移除，不在此批 |
