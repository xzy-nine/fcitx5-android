
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

---

**复查修复（本次）**

迁移批次的复查结论与修复记录：

1. **删除链路恢复**：`ClipboardWindow` 在 Compose 化时丢失了旧实现的 Snackbar 撤销与 `realDelete()` 物理清理，
   导致 `deleted=1` 记录永久残留、撤销功能消失。现恢复为：
   - `onDelete` / `onConfirmDeleteAll` 软删除后记入 `_pendingDeleteIds`，并重置 4s 撤销窗口（`clearUndoJob`）；
   - `ComposeClipboard.UndoBar`（BottomCenter 悬浮条，`num_items_deleted` + `undo`）提供撤销入口；
   - 窗口结束 / 超时后调用 `ClipboardManager.realDelete()`，`deleteAll` 返回的 id 不再被丢弃。
2. **偏好持久化**：`ClipboardEditWindow.setInsertSpace()` 补 `clipboardEditInsertSpace.setValue`。
3. **选区态重组**：`TextEditingWindow.hasSelection` 由普通 `var` 改为 `MutableStateFlow`，`onSelectionUpdate` 写入,
   保证剪切/全选按钮随选区刷新。
4. **长按重复触发恢复**：新增 `Modifier.repeatableClick`（`input/bar/ComposeFeedbackExt.kt`），复刻
   `CustomGestureView(repeatEnabled = true)` 语义（长按延迟 → 50ms 间隔重复 → 松手单击、移出取消），
   接入 `ComposeTextEditing` 的方向键与退格，并按 `hapticOnRepeat` 提供重复期震动。
5. **候选菜单锚点**：`CandidateBarCallbacks.onCandidateLongClick` 增加长按点 `Offset`；`ComposeCandidateComponent`
   用 `LayoutCoordinates.positionInWindow()` + 长按点还原窗口绝对坐标，替换原先「整个候选栏矩形」的错误锚点。
6. **条目缓存**：`ComposeClipboard` 的 `cardCache` key 由 `(id, mask)` 改为 `(id, text, mask)`（编辑后自然失效），
   改为上限 256 的 LRU 并用锁保护跨线程读写。
7. **文案与收尾**：三处 Compose 文件的硬编码中文改 `stringResource`，新增 16 个字符串键（中英双语）；
   补文件末尾换行。

---

**剪贴板分页恢复（本次）**

复查中发现的数据层取舍：迁移时把剪贴板列表从 Paging 退化为「全量 Room Flow」，条目量大时列表会在
主线程一次构建全部卡片。现恢复分页，接入方式与删除语义已确认：**数据层复用 Room 的 `PagingSource`**，
**删除后立即刷新分页**。

1. **数据层**：`ClipboardManager` 新增 `val entriesPager by lazy`，内部构造 `Pager` +
   `cachedIn(this)`，
   `pagingSourceFactory = { clbDao.allEntries() }`（Room 生成的 `PagingSource`，页大小 16、预取一页）。
   该 Flow **必须单例复用**：早期用 `_pagingInvalidation.flatMapLatest { entriesPager() }` 重建的做法会泄漏——
   `cachedIn` 内部是 `shareIn(Lazily, replay=1)`，旧缓存只在「上游自身发出新 `PagingData`」时才
   `close()`，
   `flatMapLatest` 取消外层不会触发，于是每次刷新都在作用域里留下一个永不关闭的 `CachedPageEventFlow`
   （持有一条 Room 订阅）。
2. **显式刷新**：改为 `ClipboardManager.invalidatePagingSource()`，即在 `pagingSourceFactory` 中记录当前
   `PagingSource`（`@Volatile`），刷新时调其 `invalidate()`。`PageFetcher` 注册的失效回调会重新走工厂、
   复用同一 `cachedIn` 缓存，无泄漏；`invalidate()` 幂等，重复调用安全。
3. **列表渲染**：`ComposeClipboard` 的 `ClipboardListContent` / `ClipboardEntryList` 形参由
   `List<ClipboardEntry>` 改为 `LazyPagingItems<ClipboardEntry>`，用
   `items(count = itemCount, key = peek(index)?.id ?: IndexKey(index))`；条目为 null 时跳过渲染，
   key 退化为独立类型 `IndexKey`（与 `Int` id、与页脚 `String` key 均不相等，避免 key 冲突）。
   新增加载中页脚与失败重试页脚（`append` 失败与 `refresh` 失败各一处），传入 `onRetry`。
4. **空态判定**：
   `isEmpty = loadState.refresh is NotLoading && loadState.append.endOfPaginationReached && itemCount < 1`。
   注意 `CombinedLoadStates.refresh.endOfPaginationReached` **恒为 false**，判断「已到底」必须用
   `append` 的那一位；
   该判定经 `LaunchedEffect` 推给 `ClipboardStateMachine`（`stateMachine` 为 `lateinit`，用
   `::stateMachine.isInitialized` 守卫）。
5. **生命周期配合**：`onAttached()` 移除对已删除的 `observeAllEntries()` 的订阅 job，改为调用一次
   `invalidatePaging()`（`cachedIn` 的 `replay=1` 会让重开的窗口拿到上次的旧 `PagingData`，须主动失效）；
   `onDetached()` 保留撤销窗口收尾的物理清理。

---

**miuix 组件对齐（本次，无视图层级变化）**

按「Compose 层统一 miuix」的收尾项，把迁移过程中手写 foundation 的地方换成 miuix 官方组件。
约定：miuix 的 `Window*` 系列是**系统 Dialog** 实现，IME 内不使用，**不计入**本次范围；
`Overlay*` 系列不依赖系统 Dialog（见下「第三轮」），可用。

| 文件 | 原实现 | 现实现 |
|---|---|---|
| `preedit/ComposePreedit.kt` | foundation `BasicText(style = TextStyle(...))` | miuix `Text(color, fontSize, softWrap = false, maxLines = 1, onTextLayout)` |
| `clipboard/ComposeClipboardEdit.kt` | `androidx.compose.foundation.text.BasicTextField` | miuix `TextField(cornerRadius = 12.dp)` |
| `bar/ComposeToolbar.kt` `MenuButton` / `HideKeyboardButton` / `ToolbarIconButton` | `Box + clip(CircleShape) + collectIsPressedAsState + clickable(indication = null)` | miuix `IconButton(cornerRadius = HEIGHT/2, minWidth/minHeight = HEIGHT)` |
| `candidates/ComposeCandidateActionMenu.kt` / `status/ComposeStatusArea.kt` | `Box.height(1.dp).background(dividerLine)` | miuix `HorizontalDivider()` |
| `candidates/horizontal/ComposeCandidateBar.kt` | `Box.width(1.dp).height(24.dp).background(...)` | miuix `VerticalDivider(color = dividerColor.copy(alpha = 0.3f))` |
| `clipboard/ComposeClipboard.kt` `PagingFooterLoading` | 单行「加载中」文字 | miuix `CircularProgressIndicator(progress = null, size = 24.dp)` |
| `clipboard/ComposeClipboard.kt` `ClipboardEntryCard` | `Surface(shape = RoundedCornerShape(12.dp)) + 手动 clip` | miuix `Card(cornerRadius = 12.dp, insideMargin = PaddingValues(14, 10, 14, 10))` |

要点：

1. **miuix `Text` 就是 `BasicText` 的包装**（内部 `style.merge(...)` 后转交 `BasicText`），
   因此 `onTextLayout` 拿到的 `TextLayoutResult` 语义与原先完全一致 —— 预编辑栏的光标定位算式
   （`getHorizontalPosition` / `getLineTop` / `getLineBottom`）**零改动**。
2. **`IconButton` 的按下高亮不手写**：`MiuixTheme` 通过 `LocalIndication provides MiuixIndication`
   注入主题色（`onBackground`，alpha 0.10），`IconButton` 内部 `clickable` 未传 `indication` 时自动取用，
   与原 `iconColor.copy(alpha = 0.1f)` 观感一致。`inputFeedback()`（触觉/音效）与
   `HideKeyboardButton` 的 `detectDragGestures` 顺序保持原样（modifier → inputFeedback → pointerInput）。
   圆角/最小尺寸显式传 `HEIGHT.dp`，因为 `IconButtonDefaults` 默认 40dp，工具栏高度可配置时会错位。
3. **`Card` 未启用交互态**：长按菜单需要**按下点偏移**做锚定，而 `Card` 的 `onLongPress` 不提供坐标，
   故仍保留 `pointerInput { detectTapGestures }` 作为唯一手势入口；`showIndication` 单独传无效
   （它依赖 `onClick != null` 才挂 `combinedClickable`）。`Card` 的 content 即 `ColumnScope`，
   原先手写的 `Column(padding(...))` 由 `insideMargin` 取代。
4. **性能取舍**：`IconButton` / `Card` 的圆角走 `squircleSurface`，尺寸 ≤ 2048px 时会用
   `CompositingStrategy.Offscreen` 的 graphics layer 做 shader 蒙版（工具栏 9 个按钮 = 9 个离屏层）。
   状态区早已使用 `IconButton`，此处保持一致；运行时 shader 不可用或 `LocalSquircleEnabled = false`
   时自动回退为 `clip(RoundedCornerShape) + background`，零成本。
5. **未做**：`ComposeClipboard.UndoBar` 仍为自绘悬浮条（miuix `Snackbar` 需先确认能否脱离
   `Scaffold` 在 IME 浮窗内挂载）；`ComposeStatusArea` 的 `DropdownImpl` 名字带 `Impl`，
   疑似内部件，待确认 API 稳定性。

**补充：第二轮扫描（首轮按「导入」筛漏的部分）**

首轮只看了 `import`，漏掉「组件已是 miuix、但没用它的交互重载」这一类。第二轮按模式全量扫描后补修：

| 文件 | 原实现 | 现实现 |
|---|---|---|
| `candidates/horizontal/ComposeCandidateBar.kt` `ExpandButton` | `Box.size(32.dp).clip(RoundedCornerShape(16.dp)).inputFeedback().combinedClickable(indication = null)` + 手动图标变暗 | miuix `IconButton(cornerRadius = 16.dp, minWidth/minHeight = 32.dp)` |
| `clipboard/ComposeClipboard.kt` 实体芯片 | `Surface(modifier.clip(...).inputFeedback().clickable{...})` | `Surface(onClick = {...}, modifier = Modifier.inputFeedback())` |
| `clipboard/ComposeClipboard.kt` `EnableListeningUi` 启用按钮 | `Surface(color = primary) + clickable + 手写内边距` | miuix `Button(colors = ButtonDefaults.buttonColorsPrimary())` |
| `clipboard/ComposeClipboard.kt` `UndoBar` / `CenteredOverlay` / `AnchoredMenu` | `Surface` 的 `modifier` 上多一次 `.clip(RoundedCornerShape(...))` | 删除（miuix `Surface` 内部已有 `.clip(shape).background(color)`，重复裁剪纯冗余） |

**保留手写、不换 miuix 的位置及理由**（第二轮结论，避免后续重复排查）：

- `ComposeNumberRow.NumberKey`、`ComposePopupLayer` 键格/菜单格：键盘按键，miuix 无按键组件；
  且属多点触控热路径，`Box + combinedClickable + 手写按下高亮` 是刻意选择。
- `ComposeCandidateBar.CandidateItem`、`ComposeClipboardEdit` 分词芯片：都需要
  **`onClick` + `onLongClick` + 自定义选中态 border** 的组合。miuix `Surface` 有 `border` 但只有
  `onClick`（无 `onLongPress`），`Card` 有 `onClick`/`onLongPress` 但无 `border` 参数 —— 两者都不完整，
  强行替换会丢功能。
- 各 overlay 的蒙层/定位（`CenteredOverlay` / `AnchoredMenu` / `CandidateActionMenuOverlay` /
  `StatusAreaMenu`）：按「`Overlay*` / `Window*` 是弹层不参与」的约定不引入 miuix 弹层；
  其**内容容器**已统一为 miuix `Surface`。
- `Box`/`Row`/`Column`/`LazyRow`/`LazyColumn`/`FlowRow`/`Modifier.offset`：miuix 无对应基础件，正确。
- 各处窗口/工具栏/预编辑栏底色、光标条、弹窗蒙层：只是纯色填充，不是「卡片/容器」语义。

**状态区子菜单容器也统一为 `Surface`**：`ComposeStatusArea.StatusAreaMenu` 原先手写
`Modifier.shadow(8.dp, RoundedCornerShape(12.dp)).clip(...).background(surfaceContainer)`，
与同族的 `ComposeCandidateActionMenu` 不一致，现改为
`Surface(shape = RoundedCornerShape(12.dp), color = surfaceContainer, shadowElevation = 8.dp)`，
删除 `draw.shadow` / `draw.clip` 导入。

**`UndoBar` → miuix `Snackbar`：可行性结论 = 不采用**

查 miuix 0.9.4-rc01 源码确认：

- **技术上可行**：`SnackbarHost` 只是一个 `Box(contentAlignment = BottomCenter) + LazyColumn`，
  **不依赖 `Scaffold`**，可以直接放在 IME 浮窗的 Compose 树里。`SnackbarDuration.Short` 恰为 4000ms，
  与 4s 撤销窗口一致。
- **语义上不匹配**：`SnackbarHostState.showSnackbar(...)` 是 **suspend + 一次性** API，
  `SnackbarHostState` **没有**公开的「从外部 dismiss」入口（只有 `SnackbarData.dismiss()`，
  而 `data` 只在自定义 `content` lambda 里拿得到）。而撤销条是**状态驱动**的：
  `pendingDeleteIds` 每新增一条删除都要**重置 4s 窗口并刷新计数**。
  若按计数作 `LaunchedEffect` key 重启协程，旧协程会在 `result.await()` 处被取消，
  而 `entries` 里那条 `SnackbarEntry(visible = true)` 无人置灰 → **永久残留**（列表里卡住一条看不见的
  Snackbar，且 `LazyColumn` 仍持有它）。按布尔 key 则计数不刷新，属功能回归。
- **结论**：保留自绘 `UndoBar`（它已是 miuix 主题：`Surface(color = surface, shadowElevation = 8.dp)`
  + `TextButton`），不引入 `Snackbar`。

---

**第三轮：剪贴板长按菜单改用 miuix `Overlay*` 系列 + 条目按压态**

### 1. 推翻旧约定：`Overlay*` 不依赖系统 Dialog，可在 IME 浮窗内使用

源码证据（miuix 0.9.4-rc01）：

| 结论 | 依据 |
|---|---|
| `MiuixPopupHost` 不依赖系统 Dialog | `MiuixPopupUtils.kt` 的 `MiuixPopupHost()` / `PopupEntry` / `DialogEntry` 只组合 `AnimatedVisibility + Box + zIndex` |
| `Overlay*` 不依赖系统 Dialog | `OverlayListPopup.kt` → `ListPopupLayout` + `PopupLayout`（同上） |
| `Window*` 依赖系统 Dialog | `WindowDialog.kt` / `WindowListPopup.kt` 内部 `import androidx.compose.ui.window.Dialog` |
| `Scaffold` 提供弹层宿主 | `Scaffold` → `CompositionLocalProvider(LocalPopupStates/LocalRootPopupStates)` → `ScaffoldLayout` 的 `popupHost` slot，**最后 `place(0, 0)` = z 序最高**，覆盖整个窗口 |

因此 `AGENTS.md` 里「IME 内不使用任何 miuix `Window*` 弹层」的规则收窄为：**只禁 `Window*`，`Overlay*` 可用**。

### 2. 剪贴板窗口根部包一层 `Scaffold`

`ClipboardWindow.Content()` 的根：

```kotlin
Scaffold(
    containerColor = Color.Transparent,
    contentWindowInsets = WindowInsets(0),
) { ClipboardContent() }
```

- **必须**：`OverlayListPopup` 只是把内容注册进 `LocalRootPopupStates`，真正渲染它的
  `MiuixPopupHost` 由 `Scaffold` 的 `popupHost` slot 提供；没有 Scaffold 弹层无处渲染。
- `containerColor = Color.Transparent`：剪贴板页原本没有底色，透出的是 `InputView` 的主题底图
  （`customBackground`），给 `surface` 会平白盖一层。
- `contentWindowInsets = WindowInsets(0)`：IME 窗口的 insets 已由 `InputView.onApplyWindowInsets`
  交给 `bottomPaddingSpace` 处理；若用 `Scaffold` 默认的 `systemBars ∪ displayCutout`，
  内容会被导航栏 inset 二次顶开。

### 3. 条目长按反馈改用 miuix 自带按压态

`ClipboardEntryCard` 的 `Card` 换成**交互重载**并显式传 `pressFeedbackType = PressFeedbackType.Sink`
（miuix 按下时把整卡缩到 0.94，`SinkFeedback`），不再手写压暗。

关键细节：**不传 `onClick` / `onLongPress`**。原因是 miuix `Card` 的 `clickableModifier` 是挂在
**内层 `Column`** 上的 `combinedClickable`，而 `combinedClickable` 会在 `down` 时 `consume()`，
会把外层我们自己的 `pointerInput { detectTapGestures }` 挡掉（`awaitFirstDown(requireUnconsumed = true)`）。
不传点击回调时 `isClickable = false`，`combinedClickable` 不挂载，而
`Modifier.pressable(...)`（承载 `SinkFeedback`）走 `awaitFirstDown(requireUnconsumed = false)`
且**从不 consume**，两者可以共存 —— 于是「miuix 按压态」与「长按点坐标」同时拿到。

（附带结论：`showIndication` 只在 `onClick != null` 时才有意义，本场景不可用。）

### 4. 长按菜单：`AnchoredMenu` → `OverlayListPopup`

删除自绘的 `AnchoredMenu`（`fillMaxSize + windowDimming + clickable + Surface + offset` 定位）与
`MenuOptionRow` 的菜单用途，改为：

```kotlin
OverlayListPopup(
    show = show,
    popupPositionProvider = positionProvider,   // 自定义：长按点锚定
    alignment = PopupPositionProvider.Align.TopStart,
    onDismissRequest = onDismissRequest,
    onDismissFinished = onDismissFinished,
) {
    ListPopupColumn {
        ActionMenuItem(iconResId, text, optionSize, index) { ... }   // 内部 = DropdownImpl
        ...
    }
}
```

免费得到的 miuix 能力：窗口外点击关闭、返回手势关闭、`windowDimming` 压暗层、
`ListPopupContent` 自带 `surfaceContainer` 背景 + 16dp 圆角 + clip-reveal 入场动画、
行按压高亮（`DropdownImpl` 内部 `selectable` + `LocalIndication`）、点击震动（`Confirm`）。

两处必须自己处理的坐标/时序问题：

1. **坐标系换算**：`anchorOffset` 是 Compose 根坐标，而 `PopupPositionProvider.calculatePosition`
   要求**窗口坐标**。弹层的父级是铺满整个窗口的容器，因此 `anchorBounds.left/top`
   就是「根原点在窗口中的位置」，一次加法即完成换算（不必去摸 View 层级）。
   定位语义保持旧行为：长按点为菜单左上角，外扩 8dp（`getMargins()`），越界 `coerceIn` 收回窗口内。

   为什么 `windowBounds` 可以直接拿来夹取：`LocalWindowInfo.containerSize` 的实现是
   `WindowMetricsCalculator.computeCurrentWindowMetrics(...)`（`AndroidWindowInfo.android.kt`
   的 `calculateWindowSize`，其 `tryUnwrapContext` **显式支持 `InputMethodService`**），
   拿到的是 **IME 窗口**尺寸而非 ComposeView 尺寸，所以 `windowBounds` 与 `positionInWindow()`
   系同一套窗口坐标，不需要再换算。
2. **退出动画**：`show` 必须与「菜单是否挂载」分开持有 —— 关闭时先把 `show = false` 让弹层播完
   退场动画，`onDismissFinished` 里才把 `menuState` 置空卸载内容；否则弹层会被直接抽走，没有动画。

`UndoBar` / `CenteredOverlay`（删除全部确认层）本轮未动，仍为自绘；`CenteredOverlay` 是
`OverlayDialog` 的天然候选，留待确认。

## 第四轮：`OverlayListPopup` 触发 `NavigationEventDispatcherOwner` 缺失崩溃与修复

上一轮把长按菜单换成 `OverlayListPopup` 后，**长按任意剪贴板条目必崩**（`FATAL EXCEPTION`，
IME 进程被杀后重启）。本轮定位并修复。

### 1. 崩溃表现与触发条件

- 表现：`ComposeInternal E Error was captured in composition.` →
  `java.lang.IllegalStateException: No NavigationEventDispatcher was provided via
  LocalNavigationEventDispatcherOwner` → `AndroidRuntime E FATAL EXCEPTION: main` →
  `Process is going to kill itself!` / `SIG: 9`，随后 `PROCESS STARTED`（Zygote 重启 IME）。
  从 `ACTION_DOWN` 到异常约 0.4s。
- 触发条件：进入剪贴板窗口后**长按任意条目**（`menuVisible` 由 false→true，
  `OverlayListPopup` 首次把内容注册进 `LocalPopupStates`，`MiuixPopupHost` 中**第一次**
  生成 `PopupEntry`）。不开菜单则完全不触发。

### 2. 根因

miuix `MiuixPopupUtils.kt` 的 `PopupEntry` 在 `AnimatedVisibility` 之前**无条件**调用：

```kotlin
val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
NavigationBackHandler(
    state = navigationEventState,
    isBackEnabled = popupState.enableBackHandler && (visibleState.currentState || visibleState.targetState),
    onBackCompleted = { popupState.showState.value = false },
)
```

`NavigationBackHandler` → `NavigationEventHandler` 里：

```kotlin
val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current) {
    "No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner"
}.navigationEventDispatcher
```

`LocalNavigationEventDispatcherOwner` 是 `compositionLocalWithHostDefaultOf`，取值链路：

`LocalHostDefaultProvider.currentValue.getHostDefault(key)` → compose-ui 的
`ViewTreeHostDefaultProvider(owner.view)`（在 `ProvideCompositionLocals` 里提供，挂在
`AndroidComposeView` 上）→ 从 `LocalView` 起沿 `View.getParentOrViewTreeDisjointParent()`
逐级 `getTag(R.id.view_tree_navigation_event_dispatcher_owner)`。

- **Activity 场景**：`ComponentActivity` 实现 `NavigationEventDispatcherOwner`，
  `activity-compose` 的 `setContent` 把它挂到 decorView → 找得到。
- **IME 场景**：框架不提供。`LifecycleInputMethodService` 原本只挂了 Lifecycle /
  SavedStateRegistry / ViewModelStore 三个 owner，第四个缺失 → `getHostDefault` 返回 null →
  `checkNotNull` 抛异常。

异常发生在 composition 阶段，无法被业务代码捕获，于是升级为进程级崩溃。

### 3. 修复（一处生效）

只改 `input/LifecycleInputMethodService.kt`：

```kotlin
open class LifecycleInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    NavigationEventDispatcherOwner {          // ← 新增

    private val _navigationEventDispatcher by lazy { NavigationEventDispatcher() }
    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = _navigationEventDispatcher

    override fun onCreate() {
        ...
        decorView.setViewTreeNavigationEventDispatcherOwner(this)   // ← 新增
        ...
    }

    override fun onDestroy() {
        ...
        _navigationEventDispatcher.dispose()                        // ← 新增
    }
}
```

要点：

- 这是**框架级**修复，`ClipboardWindow` / `ComposeCandidateActionMenu` / `ComposeStatusArea`
  等将来任何用 `Overlay*` 的窗口一并受益，不需要每个窗口自己挂。
- 语义安全：`NavigationEventDispatcher()` 是**根 dispatcher**，IME 场景下没有任何
  `NavigationEventInput` 注册（IME 窗口不可聚焦、收不到返回手势），所以 miuix 注册进来的
  handler 只是**永不触发**，不会误吞/误触返回键。
- `dispose()` 时机安全：`onDestroy()` 时所有 `ComposeView` 的 composition 均已 dispose，
  handler 由各自的 `DisposableEffect` 摘除，而 `removeHandler` 是 `internal` 且**不**做
  `checkInvariants`，因此不会出现「disposed 后再 addHandler」的崩溃。

### 4. 验证

- `./gradlew :app:compileDebugKotlin --console=plain` → `BUILD SUCCESSFUL`。
- 设备侧：重装后打开剪贴板 → 长按任意条目 → 菜单正常弹出，`logcat` 无
  `No NavigationEventDispatcher was provided` / `FATAL EXCEPTION`。




