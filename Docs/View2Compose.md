
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
