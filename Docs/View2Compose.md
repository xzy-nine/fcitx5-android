# View → Compose 迁移记录

> 本文仅记录**迁移过程**：每个已落地节点给出最终视图层级、接线链路与旧文件去向。
> 迁移中临时引入又撤销的中间态（如合并容器回归、逐轮性能/崩溃修复）不在此记录，
> 仅在必要时以「约束 / 成因」备注保留结论。

---

## 1. 迁移起点

初始（全部为 View 实现）：

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

## 2. 横向候选栏 Compose 化

候选栏从工具栏 `candidateUi.root` 内的 `HorizontalCandidateComponent` 迁移到
`ComposeCandidateComponent`，嵌入工具栏区域。

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView
│           ├── customBackground
│           ├── kawaiiBar.view (ViewAnimator - 工具栏 KawaiiBarComponent)
│           │   └── 当前显示: candidateUi.root (ConstraintLayout - CandidateUi)
│           │       ├── expandButton (工具栏展开入口，唯一入口)
│           │       └── composeCandidate.view (ComposeView - ComposeCandidateComponent)
│           │           └── ComposeCandidateBar (LazyRow + CandidateItem × N + HideKeyboardButton)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── preedit.ui.root
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root
├── CandidatesView (迁移范围外，保持 View 实现)
```

接线链路：

1. 候选事件：`InputBroadcaster.onCandidateUpdate` → `ComposeCandidateComponent.onCandidateUpdate`
   → `applyCandidates` → `MutableStateFlow<CandidateBarState>`
2. 渲染：`collectAsState` → `ComposeCandidateBar` 重组（LazyRow 候选列表）
3. 选择/长按：`CandidateItem` onClick → `fcitx.select(index)`；
   onLongClick → `inputView.showCandidateActionMenu`
4. 懒加载 + 滚动同步：`LazyListState.layoutInfo` snapshotFlow →
   `onScrollOffsetChanged(firstVisibleIndex)` → `_state.offset` + `refreshExpanded` →
   `_expandedCandidateOffset`；`onLoadMore` → `loadMoreIfNeeded` → `fcitx.getCandidates`
5. 展开联动：工具栏 `expandButton`（状态机驱动，唯一入口）→
   `attachWindow(Grid|FlexboxExpandedCandidateWindow)`；展开窗口消费 `composeCandidate.total`（Idle=-1）
   与 `expandedCandidateOffset`
6. 滑动开关：`userScrollEnabled = horizontalCandidateSwipe`（横向滑动分页，默认关）

旧文件（断开接线，保留供审查）：

| 旧文件 | 路径 | 状态 |
|---|---|---|
| HorizontalCandidateComponent.kt | `input/candidates/horizontal/` | 已断开接线 |
| HorizontalCandidateViewAdapter.kt | 同上 | 仅被上者引用，已断开 |
| CandidateItemUi.kt | `input/candidates/` | 仍被展开候选窗口使用 |
| CandidateViewHolder.kt | 同上 | 仍被展开候选窗口体系使用 |

---

## 3. 工具栏 + 预编辑栏 Compose 化

工具栏与候选栏收敛到单一 `composeTopView` Composition；预编辑栏维持独立悬浮层。

> **成因（合并曾回归）**：曾把预编辑栏并入 `composeTopView`，但其可变高度撑高 `keyboardView`
> 使 `contentTopInsets` 随打字变化、应用页面反复伸缩；故预编辑栏移回键盘体之外独立悬浮（
> `composePreedit.view`），键盘体高度恒定、insets 稳定。工具栏圆角落点也由此改为裁 `keyboardView`。

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView
│           ├── customBackground (ImageView - 主题背景，铺满键盘体)
│           ├── composeTopView (单一 ComposeView - MiuixTheme，仅含工具栏)
│           │   └── composeKawaiiBar.ToolbarContent() (Composable 入口)
│           │       └── ComposeToolbar (Idle/Candidate/Title 态)
│           │           ├── Idle 态: MenuButton + [Empty|Toolbar|Clipboard|NumberRow|InlineSuggestion] + HideKeyboardButton
│           │           │   ├── Toolbar: ToolbarButtonsRow (undo/redo/cursor/clipboard/split/more/tune)
│           │           │   ├── Clipboard: 文本行 (AndroidView 仅 InlineSuggestionsUi.root)
│           │           │   ├── NumberRow: NumberRowContent (ComposeNumberRow，全 Compose)
│           │           │   └── InlineSuggestion: AndroidView(inlineSuggestionsUi.root)
│           │           ├── Candidate 态: composeCandidate.CandidateBarContent() + ExpandButton
│           │           └── Title 态: BackButton + TitleText + ExtensionSlot (AndroidView 包装 onCreateBarExtension)
│           ├── windowManager.view (InputWindowManager - FrameLayout)
│           │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow)
│           ├── composePreedit.view (ComposeView - ComposePreeditComponent，悬浮在键盘体上方)
│           │   └── ComposePreedit (上行: auxUp + preedit + 光标竖线 / 下行: auxDown)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│           └── popup.root
├── CandidatesView (迁移范围外，保持 View 实现)
```

接线链路：

**预编辑栏**：`InputBroadcaster.onInputPanelUpdate` → `ComposePreeditComponent.onInputPanelUpdate`
→ 解析 auxUp/preedit/auxDown → `MutableStateFlow<PreeditState>` → `ComposePreedit` 重组
（上行文本 + 光标 + 下行文本）。

**工具栏**：
1. 候选/预编辑事件 → `KawaiiBarStateMachine` 状态转换 → `MutableStateFlow<State>`
2. Idle 子状态：`evalIdleUiState()` → `MutableStateFlow<IdleSubState>`（Empty/Toolbar/Clipboard/NumberRow/InlineSuggestion）
3. Title 状态：`onWindowAttached` → `MutableStateFlow<TitleData?>`
4. 按钮回调：`ToolbarCallbacks` 委托给 `CommonKeyActionListener` / `InputWindowManager` / `FcitxInputMethodService`
5. 候选栏展开：复用 `ComposeCandidateComponent` + `ExpandButtonStateMachine`
6. Clipboard：`ClipboardManager.OnClipboardUpdateListener` + 超时逻辑
7. InlineSuggestions：系统 API 限制，AndroidView 包装

**组件形态**：`ComposeKawaiiBarComponent` / `ComposePreeditComponent` / `ComposeCandidateComponent`
由 `UniqueViewComponent`（持 `view: ComposeView`）改为 `UniqueComponent + Dependent + ManagedHandler`，
对外暴露 `@Composable` 入口 `ToolbarContent()` / `PreeditContent()` / `CandidateBarContent()`，
由父级 Composition 统一调用，消除「ComposeView 内嵌 ComposeView」的冗余 Composition。

**工具栏高度**：由 `ToolbarContent` 内部 `HEIGHT`（动态 getter）决定；偏好变化 →
`notifyToolbarHeightChanged()` → `_toolbarHeightVersion` 自增 → 父级 `key(toolbarHeightVersion)`
触发重组。左右边距由 `updateKeyboardSize()` 对 `composeTopView` 与 `composePreedit.view` 分别
`setPadding(sidePadding, 0, sidePadding, 0)`。

**顶部圆角**：`keyboardView.applyTopRoundedCornerClip(dp(16))`（`ViewOutlineExt`，圆角落在工具栏顶部，
预编辑栏在键盘体外不受影响）。

旧文件（断开接线，保留供对比）：

| 旧文件 | 状态 |
|---|---|
| PreeditComponent.kt / PreeditUi.kt | PreeditComponent 断开；PreeditUi 仍被 CandidatesView 使用 |
| KawaiiBarComponent.kt / IdleUi.kt / CandidateUi.kt / TitleUi.kt / ButtonsBarUi.kt / ClipboardSuggestionUi.kt / InlineSuggestionsUi.kt | 断开接线 |
| NumberRow.kt | 曾被 AndroidView 包装，后迁为 ComposeNumberRow |

---

## 4. 按键弹窗层 Compose 化

`popup.root` 由 FrameLayout 改为 ComposeView（z 序最顶），只换渲染层、不动协调层。

```
popup.root (ComposeView, matchParent, z 序最顶)
└── MiuixTheme
    └── CompositionLocalProvider(LocalLayoutDirection = Ltr)
        └── PopupContent() (Compose)
            ├── PopupEntry × N (miuix Surface + Text，Modifier.offset 绝对定位)
            └── PopupContainer × N (miuix Surface)
                ├── PopupKeyboard (Row/Column 网格 + focusedIndex)
                └── PopupMenu (CircleShape 圆底 + miuix Icon)
```

接线链路：

1. **只换渲染层**：`PopupComponent` 继续任协调器与唯一入口（`listener` / `dismissAll()` 契约不变），
   内部由持有 View 引用改为维护 `MutableStateFlow<PopupLayerState>`；`root` 改 ComposeView，
   `setContent` 内 `MiuixTheme` 调用 `PopupContent(state, visuals)`。
2. **不接收触摸**：`root` 保持 `isClickable=false` / `isFocusable=false`；手势仍由 `BaseKeyboard` /
   `PickerPageUi` 侧 `CustomGestureView` 捕获后经 `PopupActionListener` 转发。
3. **锚点抽象化（关键）**：弹窗只消费「窗口绝对坐标 `Rect`」—— View 侧来自 `KeyView.bounds`
   （`getLocationInWindow`），Compose 侧来自 `boundsInWindow()`。因此 `BaseKeyboard` / `TextKeyboard` /
   `PickerWindow` / `PickerPageUi` / `ComposeNumberRow` 五个生产方零改动。
4. **同步返回语义保留**：`ChangeFocusAction.outResult` / `TriggerAction.outAction` 由调用方同步读取，
   故 `changeFocus` / `triggerFocused` 直接读写 `_state.value`，不经重组或协程。
5. **定位算式来源**：`rootBounds` 继续用 `addOnLayoutChangeListener` + `getLocationInWindow()`，原 px
   算式逐字保留，坐标相对 `popup.root` 左上角，Compose 侧 `Modifier.offset` + `LocalDensity` 换算。
6. **焦点热路径零分配**：行布局（`computeDisplayRows`）与 `keyOrders` 创建时算一次（`remember`）；
   `ChangeFocusAction`（手指 move 每帧）只替换 focusedIndex。
7. **强制 LTR**：`CompositionLocalProvider(LocalLayoutDirection provides LTR)`，等价原
   `root.layoutDirection = LTR`，避免 RTL 下 `Modifier.offset` 反转。

旧文件/接口：

| 项 | 状态 |
|---|---|
| `popup.root` | FrameLayout → ComposeView（宿主不变，仍由 InputView 挂载） |
| PopupLayoutMath.kt | 新增：纯计算模块（无 View 依赖），注入 `PopupLayerState` 渲染 |
| PopupEntryUi.kt / PopupKeyboardUi.kt / PopupMenuUi.kt / PopupContainerUi.kt | 断开接线；PopupContainerUi 保持零改动（计算已复制到 PopupLayoutMath，避免上游合并冲突） |
| PopupAction.kt / PopupActionListener.kt / PopupPreset.kt / EmojiModifier.kt | 不动，对外契约 |

组件选型：miuix 优先（`MiuixTheme` / `Surface` / `Text` / `Icon`），布局与定位用 foundation
（`Box` / `Row` / `Column` / `Modifier.offset`）。IME 内不使用任何 miuix `Window*` 弹层。

**依赖统一（同批）**：Compose 由 JetBrains CMP 伞包改为 Google 官方
`androidx.compose.runtime` / `foundation` / `ui`，统一版本 `1.12.0`
（`gradle/libs.versions.toml` 单一 `compose` 版本源），不引入 compose BOM 避免版本冲突。

---

## 5. wm 共存机制 + 更多页（状态区）Compose 化

引入 `ComposeWindow`（Compose 化窗口标记接口），`InputWindowManager` 以「同一宿主、两类实现」共存挂载，
同一批落地首个 Compose 窗口 `StatusAreaWindow`（更多页）。

```
windowManager.view (InputWindowManager - FrameLayout)
└── 当前活跃窗口:
    ├── KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow (View 窗口，onCreateView 兜底)
    └── StatusAreaWindow (ComposeWindow - 更多页)
        └── composeWindowView (ComposeView - MiuixTheme，经 createComposeWindowView 创建)
            └── StatusAreaWindow.Content()
                └── StatusAreaGrid (LazyVerticalGrid ×4，StateFlow 驱动)
                    ├── StatusAreaCell × N (miuix IconButton 48dp 圆角图标/首字符 + 12sp 标签)
                    └── StatusAreaMenu (fcitx 子菜单，Compose 弹层锚定格子)
```

接线链路：

1. **wm 共存挂载**：`InputWindowManager.attachWindow` 用 `window is ComposeWindow` 分流——
   Compose 窗口经 `createComposeWindowView(context) { window.Content() }` 创建统一 ComposeView
   （`MiuixTheme(ThemeController(System))` 包裹，强制 LTR）；其余窗口维持 `window.onCreateView()` 兜底。
   `TransitionManager` Slide/Fade 100ms 进出场动画与 `EssentialWindow` 视图缓存逻辑不变。
2. **Composition 生命周期**：detach 时对非 essential 的 Compose 窗口先 `onDetached()` →
   `removeView` → `disposeComposition()`；essential 窗口视图缓存复用、不 dispose。
3. **更多页数据驱动**：`StatusAreaWindow` 实现 `ComposeWindow`，新增
   `MutableStateFlow<List<StatusAreaEntry>>`；`onStatusAreaUpdate` 写入（staticEntries + fcitx 条目），
   `Content()` 内 `collectAsState` 渲染；`onAttached` 仍 `fcitx.statusArea()` 拉数据。
4. **fcitx 子菜单 Compose 弹层**：格子点击用 `onGloballyPositioned` 记录 `boundsInRoot()` 作锚点，
   根 `BoxWithConstraints` 内 `Modifier.offset` 绝对定位展开 `StatusAreaMenu`（不触发系统 Dialog /
   `PopupMenu`，IME 浮窗内安全）；菜单项 `isSeparator` 渲染分隔线，点击回调 `activateAction`。
5. **点击逻辑复用**：Android 项（主题/输入法/重载/键盘）与无子菜单 fcitx 项走
   `StatusAreaWindow.onItemClick`（`AppUtil` 跳转 / `reloadConfig`+`SubtypeManager.syncWith` / 直接 `activateAction`）。
6. **工具栏扩展槽不变**：`onCreateBarExtension()` 仍返回 View（editorinfo + settings `ToolButton`），
   由工具栏 Title 态 `AndroidView` 承载。

旧文件/接口：

| 项 | 路径 | 状态 |
|---|---|---|
| ComposeWindow.kt | `input/wm/` | 新增：`@Composable fun Content()` 标记接口 |
| ComposeWindowHost.kt | 同上 | 新增：`createComposeWindowView(context, content)` 统一宿主 |
| InputWindowManager.kt | 同上 | 仅追加 ComposeWindow 分支与 `disposeComposition()`；InputWindow.kt 零改动 |
| ComposeStatusArea.kt | `input/status/` | 新增：StatusAreaGrid / StatusAreaCell / StatusAreaMenu |
| StatusAreaWindow.kt | 同上 | 实现 ComposeWindow；移除 RecyclerView / PopupMenu / adapter |
| StatusAreaAdapter.kt / StatusAreaEntryUi.kt | 同上 | 断开接线，保留供对比 |

**Compose 层主题统一到 miuix（同批落地之一）**：

- `ComposeKawaiiBarComponent.getVisuals()`（`@Composable`）：`barColor = background`、
  `iconColor/textColor = onSurface`（删除未用的 `pressHighlightColor`/`dividerColor`）；移除 `theme`/`keyBorder` 依赖。
- `ComposeCandidateComponent.getVisuals()`（`@Composable`）：`textColor = onSurface`、
  `commentColor = onSurfaceVariantSummary`、`pressHighlightColor = onSurface.copy(alpha=0.1f)`、`dividerColor = dividerLine`。
- `NumberRowContent`/`NumberKey`：删除 `theme: Theme` 参数，文字 `onSurface`、按下高亮 `onSurface.copy(alpha=0.1f)`。
- `ComposePreeditComponent.getVisuals()`（`@Composable`）：`textColor = onSurface`、`highlightColor = primary`、
  `backgroundColor = background`；移除 `theme`/`keyBorder` 依赖与未用死代码。
- View 侧（主键盘 `KeyboardWindow`/`BaseKeyboard`、展开候选窗口、`CandidatesView`、`ToolButton` 等）
  仍用 fcitx `Theme`，后续迁移。

**后续迁移计划**：主键盘（`windowManager.view` 下 `KeyboardWindow`/`BaseKeyboard` 及 Picker 窗口）可复用
wm 共存机制逐个 Compose 化，或并入 `composeTopView` 单一 Composition；`KeyView.bounds` 对弹窗层的耦合
已于弹窗层迁移解除，两者不必同批迁移。

---

## 6. 候选操作菜单 + 剪贴板主页/编辑 + 文本编辑 Compose 化

按「候选操作菜单 → 剪贴板 → 文本编辑」从易到难推进；**展开候选页按用户要求跳过**、后续再处理。

```
FcitxInputMethodService
├── ComposeView (Compose 根)
│   └── AndroidView
│       └── InputView
│           ├── customBackground
│           ├── composeTopView (单一 ComposeView - 仅含工具栏)
│           ├── windowManager.view
│           │   └── 当前活跃窗口:
│           │       ├── KeyboardWindow / PickerWindow / Grid|FlexboxExpandedCandidateWindow (View 窗口)
│           │       ├── StatusAreaWindow (ComposeWindow - 更多页)
│           │       ├── ClipboardWindow (ComposeWindow - 剪贴板主页)
│           │       ├── ClipboardEditWindow (ComposeWindow - 剪贴板编辑)
│           │       └── TextEditingWindow (ComposeWindow - 文本编辑)
│           ├── composePreedit.view (ComposeView - 悬浮预编辑)
│           ├── popup.root (ComposeView - 按键弹窗层)
│           ├── candidateActionMenu.root (ComposeView - 候选操作菜单覆盖层，z 序最高)
│           │   └── ComposeCandidateActionMenu overlay (Rect 锚定 + dismiss 蒙层)
│           ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
├── CandidatesView (独立的浮动候选视图，迁移范围外)
```

**候选操作菜单**：
1. `BaseInputView.showCandidateActionMenu` 增加基于 `Rect` 的重载（`open`，默认 no-op），供 Compose 使用。
2. `InputView` 新增 `candidateActionMenu`（`ComposeCandidateActionMenu`），root 作为 `popup.root` 同级
   覆盖层加入 `InputView`（matchParent）。
3. `showCandidateActionMenu(idx, text, anchor: Rect)` → `MutableStateFlow<CandidateActionMenuState?>` 驱动
   `CandidateActionMenuOverlay`（Rect 锚定 + 透明 dismiss 蒙层），动作回调触发 fcitx 动作后 dismiss。
4. `ComposeCandidateComponent` 长按候选用 `LayoutCoordinates.positionInWindow()` + 长按点 `Offset`
   还原窗口绝对坐标 `Rect` 喂新菜单；`CandidatesView`（浮动候选，范围外）仍保留原系统 `PopupMenu`。

**剪贴板主页**：
1. `ClipboardWindow` 实现 `ComposeWindow`，`onCreateView()` 改 `createComposeWindowView { Content() }`。
2. 数据分页：`ClipboardManager.entriesPager`（`val ... by lazy` **单例**）内部 `Pager` + `cachedIn(this)`
   复用 Room `PagingSource`（`clbDao.allEntries()`，页大小 16、预取一页）。刷新改走
   `ClipboardManager.invalidatePagingSource()`（代理 `PagingSource.invalidate()`，删后立即刷新分页）。
   > **防泄漏约束**：**禁止**用 `flatMapLatest` 重建 `Pager` —— `cachedIn` 内部 `shareIn(Lazily, replay=1)`，
   > 旧缓存只在「上游自身发出新 `PagingData`」时才 `close()`，`flatMapLatest` 取消不触发，会在作用域内
   > 留下永不关闭的 `CachedPageEventFlow`（持有一条 Room 订阅）。
3. `ComposeClipboard.kt`（新增）：`ClipboardListContent` / `ClipboardEntryCard` / 删除全部确认层 / `UndoBar`
   （底部撤销条）。列表侧 `collectAsLazyPagingItems()` + `items(count, key = peek(index)?.id ?: IndexKey(index))`；
   空态 `isEmpty = loadState.refresh is NotLoading && loadState.append.endOfPaginationReached && itemCount < 1`
   （refresh 那位恒为 false）。
4. 删除走软删除（记 `_pendingDeleteIds`）+ 4s 撤销窗口（`UndoBar`），超时或窗口销毁调
   `ClipboardManager.realDelete()` 物理清理。

**剪贴板编辑**：
1. `ClipboardEditWindow` 实现 `ComposeWindow`；分词用现成 `ClipboardTextAnalyzer.segment()`。
2. `ComposeClipboardEdit.kt`（新增）：分词模式 `FlowRow` 词块（点击选中/长按单选）+ 重组预览；
   文本模式 miuix `TextField`；底部 全选/反选/模式切换 + 复制/取消/确定。复制/上屏借窗口
   `copyOnly` / `commitToInput` / `exitToPrevWindow`。设置项 `clipboardEditInsertSpace` 落
   `AppPrefs`。

**文本编辑**：
1. `TextEditingWindow` 实现 `ComposeWindow` + `InputBroadcastReceiver`；方向键/全选/剪切/复制/粘贴/退格
   动作逻辑保留（`sendCombinationKeyEvents` / `performContextMenuAction` / `sendDownUpKeyEvents`），
   `hasSelection` / `_userSelection`（`MutableStateFlow`）驱动选区态；`onCreateBarExtension()` 仍返回含
   剪贴板 ToolButton 的 View。
2. `ComposeTextEditing.kt`（新增）：复刻原 constraint 网格——左侧 2 列 3 行方向键 + 起始/结尾，
   右侧 30% 动作列（全选/剪切↔、复制/粘贴/退格），方向键/动作键均 miuix `Surface`。方向键/退格接入
   `Modifier.repeatableClick`（`input/bar/ComposeFeedbackExt.kt`，复刻 `CustomGestureView(repeatEnabled=true)`
   的长按延迟 + 50ms 间隔重复），并按 `hapticOnRepeat` 提供重复期震动。

旧文件（断开接线，保留供对比）：

| 旧文件 | 路径 | 状态 |
|---|---|---|
| ClipboardUi.kt / ClipboardAdapter.kt / ClipboardEntryUi.kt / ClipboardInstructionUi.kt / SpacesItemDecoration.kt | `input/clipboard/` | 断开接线（ClipboardStateMachine / ClipboardTextAnalyzer 仍被 Compose 复用） |
| TextEditingUi.kt / TextEditingButton.kt | `input/editing/` | 断开接线 |
| ClipboardEditWindow.xml / ClipboardEditWindowBinding | `res/layout/` | 断开接线 |
| CandidateActionMenuUi / 旧候选长按菜单 | `input/candidates/` | 被 Compose 覆盖层取代 |

---

## 7. miuix 组件对齐（收尾）

按「Compose 层统一 miuix」收尾，把迁移中手写 foundation 的地方换成 miuix 官方组件。

| 文件 | 原实现 | 现实现 |
|---|---|---|
| `preedit/ComposePreedit.kt` | foundation `BasicText` | miuix `Text`（`softWrap=false, maxLines=1, onTextLayout`） |
| `clipboard/ComposeClipboardEdit.kt` | `BasicTextField` | miuix `TextField(cornerRadius = 12.dp)` |
| `bar/ComposeToolbar.kt` MenuButton / HideKeyboardButton / ToolbarIconButton | `Box + clip(CircleShape) + collectIsPressed + clickable` | miuix `IconButton(cornerRadius = HEIGHT/2, minWidth/minHeight = HEIGHT)` |
| `candidates/ComposeCandidateActionMenu.kt` / `status/ComposeStatusArea.kt` | `Box.height(1.dp).background(dividerLine)` | miuix `HorizontalDivider()` |
| `candidates/horizontal/ComposeCandidateBar.kt` | `Box.width(1.dp).height(24.dp)` | miuix `VerticalDivider(color = dividerColor.copy(alpha = 0.3f))` |
| `clipboard/ComposeClipboard.kt` PagingFooterLoading | 单行「加载中」文字 | miuix `CircularProgressIndicator(size = 24.dp)` |
| `clipboard/ComposeClipboard.kt` ClipboardEntryCard | `Surface + 手动 clip` | miuix `Card(cornerRadius = 12.dp, insideMargin = PaddingValues(14,10,14,10))`（不传 onClick/onLongPress，用 `pointerInput { detectTapGestures }` 拿长按点坐标） |

要点：

1. miuix `Text` 是 `BasicText` 的包装，`onTextLayout` 的 `TextLayoutResult` 语义与原先完全一致——预编辑栏
   光标定位算式（`getHorizontalPosition` / `getLineTop` / `getLineBottom`）零改动。
2. `IconButton` 按下高亮不手写：`MiuixTheme` 经 `LocalIndication provides MiuixIndication` 注入
   （`onBackground`，alpha 0.10），与手写 `iconColor.copy(alpha=0.1f)` 观感一致。圆角/最小尺寸显式传
   `HEIGHT.dp`（`IconButtonDefaults` 默认 40dp，工具栏高度可配置时会错位）。
3. `Card` 未启用交互态：长按菜单需按下点偏移锚定，`Card` 的 `onLongPress` 不提供坐标，故
   `pointerInput { detectTapGestures }` 仍为唯一手势入口（`showIndication` 单独传无效）。
4. `IconButton` / `Card` 圆角走 `squircleSurface`（尺寸 ≤2048px 时用离屏 graphics layer 做 shader 蒙版，
   工具栏 9 按钮 = 9 离屏层）；`LocalSquircleEnabled = false` 或 shader 不可用时自动回退 clip+background。
5. 未做：`ComposeClipboard.UndoBar` 仍为自绘悬浮条；`ComposeStatusArea` 的 `DropdownImpl` 疑为内部件。

**保留手写、不换 miuix 的位置及理由**：

- `ComposeNumberRow.NumberKey`、`ComposePopupLayer` 键格/菜单格：键盘按键，miuix 无按键组件；
  属多点触控热路径，`Box + combinedClickable + 手写按下高亮` 是刻意选择。
- `ComposeCandidateBar.CandidateItem`、`ComposeClipboardEdit` 分词芯片：需 `onClick` + `onLongClick` +
  自定义选中态 border 组合。miuix `Surface` 有 border 但只有 onClick；`Card` 有 onClick/onLongPress 但
  无 border —— 都不完整，强行替换丢功能。
- 各 overlay 的蒙层/定位（`CenteredOverlay` / `AnchoredMenu` / `CandidateActionMenuOverlay` /
  `StatusAreaMenu`）：不引入 miuix 弹层（见下），其内容容器统一为 miuix `Surface`。
- `Box`/`Row`/`Column`/`LazyRow`/`LazyColumn`/`FlowRow`/`Modifier.offset`：miuix 无对应基础件。
- 各处纯底色/光标条/弹窗蒙层：纯色填充，非「卡片/容器」语义。

**Overlay 弹层约定（剪贴板长按菜单落地）**：

- **`Overlay*` 可用、`Window*` 禁用**：`OverlayListPopup` / `OverlayDialog` 不依赖系统 Dialog
  （其 `PopupEntry` / `ListPopupLayout` 只组合 `AnimatedVisibility + Box + zIndex`）；`Window*` 系列底层是
  系统 Dialog（Activity window token），IME 浮窗层级必崩 `BadTokenException`。
- **根包一层 `Scaffold`**：`Overlay*` 只是把内容注册进 `LocalRootPopupStates`，真正渲染的 `MiuixPopupHost`
  由根 `Scaffold` 的 `popupHost` slot 提供（z 序最高、覆盖整窗），无 Scaffold 弹层无处渲染。
  `ClipboardWindow.Content()` 根部因此包 `Scaffold(containerColor = Color.Transparent,
  contentWindowInsets = WindowInsets(0))`（IME insets 已由 `InputView.onApplyWindowInsets` 交给
  `bottomPaddingSpace`，勿二次顶开）。
- **`NavigationEventDispatcherOwner` 前置**：miuix `MiuixPopupHost` 的 `PopupEntry` 无条件调用
  `NavigationBackHandler`，其内部 `checkNotNull(LocalNavigationEventDispatcherOwner.current)` 在取不到 owner
  时抛 `IllegalStateException`（发生在 composition 阶段，无法被业务捕获 → FATAL EXCEPTION 崩进程）。
  IME 场景框架不提供该 owner，故由 `input/LifecycleInputMethodService.kt` 实现
  `NavigationEventDispatcherOwner` 并 `decorView.setViewTreeNavigationEventDispatcherOwner(this)` 挂到
  IME decorView（`onDestroy` 里 `dispose()`）。它是根 dispatcher、无输入源（IME 窗口不可聚焦、收不到返回
  手势），handler 永不触发，语义安全。这是框架级修复，任何用 `Overlay*` 的窗口一并受益。
- **条目的按压缩放**：`pressFeedbackType = PressFeedbackType.Sink`（整卡缩到 0.94）替代手写压暗。
  关键：**不传 `onClick`/`onLongPress`**——miuix `Card` 的 `combinedClickable` 挂在内层 `Column`，会在
  down 时 `consume()` 挡住外层自己的 `pointerInput { detectTapGestures }`；不传时 `pressable`（承载
  `SinkFeedback`）走 `requireUnconsumed = false` 且从不 consume，两者共存，按压态与长按点坐标同时拿到。