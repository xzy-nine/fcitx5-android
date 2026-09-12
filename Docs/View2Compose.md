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

工具栏、候选栏与预编辑栏收敛到单一 `composeTopView` Composition；预编辑栏高度**贴合内容**
（空态 0 / 单行 / 双行，`ComposePreeditComponent.heightPx` 经 `onSizeChanged` 上报实际高度）。

> **成因（曾两次调整）**：预编辑栏曾并入 `composeTopView`（可变高度），撑高 `keyboardView`
> 使 `contentTopInsets` 随打字变化、应用页面反复伸缩（回归，故 994ea988 移回独立悬浮）；
> 本轮重新并入时关键在 `onComputeInsets` 补偿**预编辑栏实际高度**：keyboardView 顶部本身已含
> 预编辑高度，补偿后正好抵消 —— 预编辑栏高度可贴合内容变化而 **insets 恒定**，无需固定占位
> （固定占位会在预编辑文本与工具栏之间留空隙）。圆角由
> `customBackground.applyTopRoundedCornerClip(16dp, 预编辑实际高度)`（跳过预编辑行）实现，
> 高度经 `SideEffect` 跟随，keyboardView 不再整体裁剪（否则会裁掉顶部预编辑行）。

```text
FcitxInputMethodService
├── ComposeView (根 - 全窗口，MiuixTheme)
│   └── Box(fillMaxSize)
│       ├── key(themeState, recreateNonce) → AndroidView(InputView)
│       │   └── InputView
│       │       ├── customBackground (ImageView，顶部圆角裁剪、经 SideEffect 跟随预编辑栏实际高度)
│       │       ├── composeTopView (单一 ComposeView - MiuixTheme)
│       │       │   └── Column {
│       │       │       ├── composePreedit.PreeditContent()  ← 贴合内容高度（空态 0），onSizeChanged 上报 heightPx
│       │       │       │   └── ComposePreedit (上行: auxUp + preedit + 光标竖线 / 下行: auxDown)
│       │       │       └── composeKawaiiBar.ToolbarContent() (Idle/Candidate/Title 态)
│       │       │           ├── Idle 态: MenuButton + [Empty|Toolbar|Clipboard|NumberRow|InlineSuggestion] + HideKeyboardButton
│       │       │           │   ├── Toolbar: ToolbarButtonsRow (undo/redo/cursor/clipboard/split/more/tune)
│       │       │           │   ├── Clipboard: 文本行 (AndroidView 仅 InlineSuggestionsUi.root)
│       │       │           │   ├── NumberRow: NumberRowContent (ComposeNumberRow，全 Compose)
│       │       │           │   └── InlineSuggestion: AndroidView(inlineSuggestionsUi.root)
│       │       │           ├── Candidate 态: composeCandidate.CandidateBarContent() + ExpandButton
│       │       │           └── Title 态: BackButton + TitleText + ExtensionSlot (AndroidView 包装 onCreateBarExtension)
│       │       ├── windowManager.view (InputWindowManager - FrameLayout)
│       │       │   └── 当前活跃窗口 (KeyboardWindow / PickerWindow / ComposeExpandedCandidateWindow)
│       │       ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│       │       └── (弹窗层 / 候选操作菜单见 §4 / §6，已并入根组合)
│       ├── iv.popup.PopupOverlayContent()   ← 按键弹窗层（无触摸，见 §4）
│       └── iv.candidateActionMenu.OverlayContent()   ← 候选操作菜单（见 §6）
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
触发重组。左右边距由 `updateKeyboardSize()` 对 `composeTopView`（含预编辑栏与工具栏）
`setPadding(sidePadding, 0, sidePadding, 0)`。

**圆角**：
- **IME 体上缘**：`customBackground.applyTopRoundedCornerClip(dp(16), preeditHeightPx)`
  （`ViewOutlineExt`；`preeditHeightPx` 取 `ComposePreeditComponent.heightPx`，经 `SideEffect`
  跟随预编辑栏实际高度，圆角落在工具栏顶部、预编辑行保持透明露出应用内容）。
- **工具栏自身**：`ComposeToolbar` 的 `clip(RoundedCornerShape(16.dp))`。起分隔作用的是**下两角**
  （工具栏与键盘区的分界）—— 工具栏下缘不在屏幕边缘，必须自己裁；**IME 的下缘由屏幕自身的圆角代劳**，
  所以 `keyboardView` 不裁下缘。上两角与父级 outline 重合，一并写上是为了不依赖父级裁剪。

旧文件（断开接线，保留供对比）：

| 旧文件 | 状态 |
|---|---|
| PreeditComponent.kt / PreeditUi.kt | PreeditComponent 断开；PreeditUi 仍被 CandidatesView 使用 |
| KawaiiBarComponent.kt / IdleUi.kt / CandidateUi.kt / TitleUi.kt / ButtonsBarUi.kt / ClipboardSuggestionUi.kt / InlineSuggestionsUi.kt | 断开接线 |
| NumberRow.kt | 曾被 AndroidView 包装，后迁为 ComposeNumberRow |

---

## 4. 按键弹窗层 Compose 化

弹窗层由独立 `popup.root` ComposeView 宿主改为**根组合的覆盖层**
（`FcitxInputMethodService.createComposeInputView` 内、`AndroidView(InputView)` 之上），
只换渲染层、不动协调层。

```text
根组合 Box(fillMaxSize) 内（MiuixTheme + 强制 LTR）
└── iv.popup.PopupOverlayContent() (Box，无 pointer handler → 触摸穿透)
    └── PopupContent() (Compose)
        ├── PopupEntry × N (miuix Surface + Text，Modifier.offset 绝对定位)
        └── PopupContainer × N (miuix Surface)
            ├── PopupKeyboard (Row/Column 网格 + focusedIndex)
            └── PopupMenu (CircleShape 圆底 + miuix Icon)
```

接线链路：

1. **只换渲染层**：`PopupComponent` 继续任协调器与唯一入口（`listener` / `dismissAll()` 契约不变），
   内部由持有 View 引用改为维护 `MutableStateFlow<PopupLayerState>`；新增 `PopupOverlayContent()`
   供根组合订阅渲染，`root` ComposeView 宿主已移除。
2. **不接收触摸**：覆盖层 Box 无任何 pointer handler（等价原 `isClickable=false` / `isFocusable=false`），
   触摸穿透到下方 `AndroidView(InputView)`；手势仍由 `BaseKeyboard` / `PickerPageUi` 侧
   `CustomGestureView` 捕获后经 `PopupActionListener` 转发。
3. **锚点抽象化（关键）**：弹窗只消费「窗口绝对坐标 `Rect`」—— View 侧来自 `KeyView.bounds`
   （`getLocationInWindow`），Compose 侧来自 `boundsInWindow()`。因此 `BaseKeyboard` / `TextKeyboard` /
   `PickerWindow` / `PickerPageUi` / `ComposeNumberRow` 五个生产方零改动。
4. **同步返回语义保留**：`ChangeFocusAction.outResult` / `TriggerAction.outAction` 由调用方同步读取，
   故 `changeFocus` / `triggerFocused` 直接读写 `_state.value`，不经重组或协程。
5. **定位算式来源**：`rootBounds` 由 `PopupOverlayContent` 的 `onGloballyPositioned` 持续更新
   （`boundsInWindow()`），原 px 算式逐字保留，坐标相对覆盖层左上角，Compose 侧
   `Modifier.offset` + `LocalDensity` 换算。
6. **焦点热路径零分配**：行布局（`computeDisplayRows`）与 `keyOrders` 创建时算一次（`remember`）；
   `ChangeFocusAction`（手指 move 每帧）只替换 focusedIndex。
7. **强制 LTR**：`CompositionLocalProvider(LocalLayoutDirection provides LTR)`，等价原
   `root.layoutDirection = LTR`，避免 RTL 下 `Modifier.offset` 反转。

旧文件/接口：

| 项 | 状态 |
|---|---|
| `popup.root` | 已移除：FrameLayout → 独立 ComposeView → 根组合覆盖层 `PopupOverlayContent()` |
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
├── ComposeView (根 - 全窗口，MiuixTheme)
│   └── Box(fillMaxSize)
│       ├── key(themeState, recreateNonce) → AndroidView(InputView)
│       │   └── InputView
│       │       ├── customBackground
│       │       ├── composeTopView (单一 ComposeView - 预编辑 + 工具栏 + 候选)
│       │       ├── windowManager.view
│       │       │   └── 当前活跃窗口:
│       │       │       ├── KeyboardWindow / PickerWindow / ComposeExpandedCandidateWindow (ComposeWindow)
│       │       │       ├── StatusAreaWindow (ComposeWindow - 更多页)
│       │       │       ├── ClipboardWindow (ComposeWindow - 剪贴板主页)
│       │       │       ├── ClipboardEditWindow (ComposeWindow - 剪贴板编辑)
│       │       │       └── TextEditingWindow (ComposeWindow - 文本编辑)
│       │       ├── leftPaddingSpace, rightPaddingSpace, bottomPaddingSpace
│       ├── iv.popup.PopupOverlayContent() (按键弹窗层，无触摸)
│       └── iv.candidateActionMenu.OverlayContent() (候选操作菜单覆盖层，z 序最高)
├── CandidatesView (独立的浮动候选视图，迁移范围外)
```

**候选操作菜单**：
1. `BaseInputView.showCandidateActionMenu` 增加基于 `Rect` 的重载（`open`，默认 no-op），供 Compose 使用。
2. `ComposeCandidateActionMenu` 的 `OverlayContent()` 由根组合渲染（`popup.root` 覆盖层之上、
   `AndroidView(InputView)` 之上），`root` ComposeView 宿主已移除。
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
2. `ComposeClipboardEdit.kt`（新增）：分词模式 `FlowRow` 词块 + 重组预览；芯片手势由
   `ComposeClipboardSegment.kt`（新增）：单击切换 / 长按进入拖选（此后才消费、接管手势）/ 拖动按
   「锚点..当前」整段连选（区间外原选中态保留）/ 拖到可视区上下边缘自动滚动并继续扩选（越过视口边界越远越快）/
   长按前超 `touchSlop` 移动则**放弃手势且不消费**，交还父级 `verticalScroll`，保证普通上下拖动可正常滚动；
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

---

## 8. 主键盘 Compose 化（KeyboardWindow，批次 C3/D）

`KeyboardWindow` 由 View（`onCreateView()` 兜底）改为 `ComposeWindow`，由 `InputWindowManager` 的统一 ComposeView 宿主承载（essential 窗口 Composition 缓存、不 dispose）。

```
windowManager.view
└── KeyboardWindow (ComposeWindow - essential)
    └── createComposeWindowView → KeyboardWindow.Content()
        └── BoxWithConstraints
            ├── TextKeyboard 态: ComposeTextKeyboard (ComposeKey × N + 空格 + 回车)
            └── NumberKeyboard 态: ComposeNumberKeyboard (符号滑块 + 最近符号 + 数字键)
```

接线链路：

1. 布局切换不再 `addView/removeView`，写 `currentLayout: MutableState<String>`（取值于 `KeyboardLayoutNames.Text` / `KeyboardLayoutNames.Number`）。
2. 按键本体 = `ComposeKey`（`pointerInput` 原生手势，长按 / 滑行 / 移出取消语义与 View 版 `CustomGestureView` 同源）。
3. 按键 / 弹层出口原样透传 `KeyActionListener` / `PopupActionListener`；按键 id 由 `ComposeKey` 依布局位置自造。
4. 尺寸上报走 `Modifier.onSizeChanged`（首次布局即上报一次）；窗口生命周期回调改为写状态层，不再 mutate View。
5. 布局数据：文本键盘由 `ComposeTextKeyboard` 经 `ComposeKeyboardRows`（定义于 `ComposeKeyboardLayout.kt`）消费 `TextKeyboard.Layout`（纯 `KeyDef` 数据）；数字键盘由 `ComposeNumberKeyboard` 直接读取 `NumberKeyboardRows`（`NumberKeyboard.Layout` 是空列表的死数据，勿改）。

旧文件（断开接线，保留供对比 / 回退）：

| 旧文件 | 状态 |
|---|---|
| `BaseKeyboard.kt` / `TextKeyboard.kt` / `NumberKeyboard.kt` | 断线；`TextKeyboard` 仍被设置页 `KeyboardPreviewUi` 实例化（主题预览），`Name` 常量已委托到 `KeyboardLayoutNames.kt` |
| `KeyView.kt` / `CustomGestureView.kt` | 断线；`CustomGestureView` 仍被 View 桥接层（`PickerTabsUi` / `ToolButton`）依赖，不能删 |
| `KeyViewExt.kt` / `RecentSymbolsView.kt` / `SymbolSliderKeyView.kt` | 断线 |

> `KeyboardWindow.kt` 顶部注释引用的 `Docs/KeyboardComposePlan.md` 不存在（计划文档未落地），本节为其内容的正式记录。

---

## 9. Picker 窗口 Compose 化（批次 D-1~D-3）

`PickerWindow`（符号 / Emoji / 颜文字）改为 `ComposeWindow`。

```
windowManager.view
└── PickerWindow (ComposeWindow - essential)
    └── createComposeWindowView → PickerWindow.Content()
        ├── HorizontalPager (rememberPagerState) × ComposePickerPage × N
        │   └── ComposeKey 网格 (density.columnCount × rowCount，末行 CHAIN_PACKED + 退格 15%)
        ├── PickerKeyboardRows (底部内嵌键盘，纯 KeyDef 数据)
        └── AndroidView islands:
            ├── PickerPaginationUi (分页指示条，AndroidView 桥)
            └── (经 onCreateBarExtension) PickerTabsUi (标签页 → 工具栏 Title 态 AndroidView)
```

接线链路：

1. `ViewPager2` → `HorizontalPager`；弹层小键盘显示时 `userScrollEnabled = false`。
2. 页数据 = `PickerPageModel`（从 `PickerPagesAdapter` 抽出，与 View 版共用同一份分页逻辑）。
3. 密度规格 `PickerPageUi.Density` 抽离到 `PickerDensity.kt`；`PickerPageUi` 保留 `typealias Density = PickerDensity` 兼容死引用。
4. 标签页 / 分页指示条仍是 View 版（`PickerTabsUi` / `PickerPaginationUi`），经 AndroidView 桥接（前者系统 `onCreateBarExtension` 契约、后者 View 渐变指示器复用）。

旧文件（断开接线）：

| 旧文件 | 状态 |
|---|---|
| `PickerPageUi.kt` | 断线；`Density` 已抽离，本类保留 `typealias` |
| `PickerPagesAdapter.kt` / `PickerLayout.kt` | 断线（逻辑已抽到 `PickerPageModel` / `PickerKeyboardRows`） |

---

## 10. 展开候选窗口 Compose 化

`ComposeExpandedCandidateWindow` 取代 View 版 `BaseExpandedCandidateWindow` / `GridExpandedCandidateWindow` / `FlexboxExpandedCandidateWindow` 与 `ExpandedCandidateLayout`。

```
windowManager.view
└── ComposeExpandedCandidateWindow (ComposeWindow - 非 essential，每次 attach 新建、detach dispose)
    └── createComposeWindowView → Content()
        └── ComposeExpandedCandidatesUi
            ├── 标签栏 (CandidateTabActions 数据)
            ├── LazyVerticalGrid (列数由 computeGridSpanCount 按前段候选实测宽度反推)
            └── 内嵌键盘 (ComposeExpandedCandidateKeyboard)
```

接线链路：

1. **形态只保留表格（Grid）一种**：原「流式（Flexbox）」形态与 `expandedCandidateStyle` 偏好已移除（`AppPrefs` 定义、`group_candidate_style` 分组引用、import 均已删；`ExpandedCandidateStyle.kt` 枚举保留为断线文件）。
2. 列数不再用户固定：`computeGridSpanCount`（`ExpandedCandidateGridSpan.kt`，与 View 侧 `SpanHelper` 同口径）按列表前段候选实测宽度（em）与 `expandedCandidateGridSpanCount` 上限反推；该偏好降级为列数上限，同时被横向候选栏填宽逻辑复用，故保留。
3. 数据 = `CandidatesPagingSource` 分页 + `ComposeCandidateComponent` 的 `total` / `expandedCandidateOffset`（不直接订阅 `CandidateListEvent`）。

旧文件（断开接线）：

| 旧文件 | 状态 |
|---|---|
| `expanded/window/BaseExpandedCandidateWindow.kt` / `GridExpandedCandidateWindow.kt` / `FlexboxExpandedCandidateWindow.kt` | 断线 |
| `expanded/ExpandedCandidateLayout.kt` / `PagingCandidateViewAdapter.kt` / `GridPagingCandidateViewAdapter.kt` / `SpanHelper.kt` / `CandidateTabActionsAdapter.kt` / `decoration/*` | 断线 |
| `expanded/ExpandedCandidateStyle.kt` | 断线（偏好已删，枚举孤立保留） |

> 第 6 节原记「展开候选页按用户要求跳过」——已过时，本节为正式落地记录。

---

## 11. 寄生数据 / 常量抽离与偏好清理

为让 Compose 层不再 import 死 View 类，把寄生在死 View 类中的纯数据 / 常量抽到独立文件，死类保留别名 / 委托兼容内部引用：

| 抽离项 | 新文件 | 原寄生位置 | Compose 消费方 |
|---|---|---|---|
| 数字行布局 | `bar/ui/idle/NumberRowLayout.kt`（`val NumberRowLayout`） | `NumberRow.companion.Layout` | `ComposeNumberRow` |
| Picker 密度规格 | `picker/PickerDensity.kt`（`enum PickerDensity`） | `PickerPageUi` 嵌套 `enum Density` | `ComposePickerPage` / `ComposeNumberKeyboard` / `PickerWindow` / `PickerWindowPreset` / `PickerPageModel`（`PickerPageUi` 保留 `typealias Density = PickerDensity`） |
| 剪贴板文本摘要 | `clipboard/ClipboardTextUtils.kt`（`fun excerptClipboardText`） | `ClipboardAdapter.companion.excerptText` | `ComposeClipboard`（`ClipboardAdapter.excerptText` 改为委托） |
| 键盘布局名常量 | `keyboard/KeyboardLayoutNames.kt`（`object KeyboardLayoutNames`） | `TextKeyboard.companion.Name` / `NumberKeyboard.companion.Name` | `KeyboardWindow` / `NumberKeyboardRows` / `PickerKeyboardRows` / `PickerWindowPreset`（死类 `Name` 改为 `= KeyboardLayoutNames.X`） |

同批清理：

- `InputView` 中 `private val preedit = PreeditComponent()` 死实例化（属性、import、`scope += preedit` 注释三处；`PreeditComponent` 早已断开接线但属性残留，每次 `InputView` 创建都白构造一个 component 对象）。
- `AppPrefs.expandedCandidateStyle` 偏好（定义、`group_candidate_style` 分组引用、import 三处）。

---

## 12. 单一 Composition 边界（为何不收敛为单一宿主）

迁移收敛后，`InputView` 仅持 **1 个** `ComposeView` 宿主（`composeTopView`）+ wm 内每个
`ComposeWindow` 一个宿主；**弹窗层与候选操作菜单已并入根组合**（`createComposeInputView` 的
`Box(fillMaxSize)`，作为 `AndroidView(InputView)` 之上的覆盖层）。未能进一步收敛的硬约束：

| 宿主 | 位置 | 成因 |
|---|---|---|
| `composeTopView` | `keyboardView` 内顶部 | 键盘顶部条单一 Composition（预编辑 + 工具栏 + 候选，已收敛：原 `KawaiiBarComponent` / `CandidateUi` / `IdleUi` / `TitleUi` / `ComposeCandidateComponent` / `ComposePreeditComponent` 合并到此）。 |
| 根组合覆盖层（`PopupOverlayContent` / `OverlayContent`） | `createComposeInputView` 根 Box 内，`AndroidView(InputView)` 之上 | 弹窗层必须覆盖整个 IME 窗口且不接收触摸（覆盖层 Box 无 pointer handler，触摸穿透到下方 `AndroidView(InputView)`）；候选操作菜单须在弹窗层之上。物理上无法并入 `composeTopView`（后者在 keyboardView 内、仅键盘顶条高度）。 |
| wm `createComposeWindowView` | `windowManager.view` 内 | 每个 `ComposeWindow` 独立 Composition，配合 essential 缓存 / 非 essential `disposeComposition()` 生命周期；与 `TransitionManager` Slide/Fade 进出场动画耦合。 |

**根 `ComposeView`（`FcitxInputMethodService.createComposeInputView`）**：`ComposeView → Box → [AndroidView(InputView) + 两个覆盖层]`。根 ComposeView 职责为 `key(themeState, recreateNonce)` 触发 `InputView` 重建（等价旧 `replaceInputView`），并作为弹窗层 / 候选操作菜单的单一 Composition 宿主；若改为 `InputView` 直接作 `contentView` + 手动重建，可省根 ComposeView，但失去「主题 / 重建以 Compose 状态驱动」的统一入口，暂保留。`inputView` 在 Service 侧为 `mutableStateOf`，覆盖层据此读取当前 `InputView` 的组件并在重建时重组。

**主题包装冗余**：`composeTopView` 与根组合各自包一层 `MiuixTheme`（2 次）+ wm 每窗口一次。这是独立 Composition 的必然代价；除非把 wm 与键盘体整体迁入单一组合，否则无法进一步省去。

---

## 13. EditorInfo 窗口 Compose 化（补窗口迁移漏网者）

此前 `EditorInfoWindow` 是窗口迁移的漏网者（仍 View），本轮补上。

```
windowManager.view
└── EditorInfoWindow (ComposeWindow - 非 essential，每次 attach 新建、detach dispose)
    └── createComposeWindowView → EditorInfoWindow.Content()
        └── ComposeEditorInfoContent (纵向滚动 键→值 两列表格，MiuixTheme 取色)
```

接线链路：

1. `EditorInfoWindow` 实现 `ComposeWindow`；`onCreateView()` 改 `createComposeWindowView { Content() }`
   （同样被 `InputWindowManager.createWindowView` 的 Compose 分流承载）。
2. 数据驱动：`propertyMap` 改 `MutableStateFlow<Map<String,String>>`；`onAttached()` 写入
   `EditorInfoParser.parse(service.currentInputEditorInfo)`，`Content()` 内 `collectAsState` 渲染。
3. `buildMarkdownString()` 改读 `_propertyMap.value`；标题 `title` 与复制按钮 `onCreateBarExtension()`
   （copy `ToolButton`，仍 View，与 `ClipboardWindow` / `TextEditingWindow` 一致）不变。
4. `ComposeEditorInfo.kt`（新增）：渲染层，`Column + verticalScroll`，两列（加粗键名 + 可换行取值），
   列间/行间用 miuix `Text` / `HorizontalDivider`，颜色取 `MiuixTheme.colorScheme`
   （`background` / `onSurface` / `dividerLine`）。

旧文件（断开接线，保留供审查）：

| 旧文件 | 状态 |
|---|---|
| `EditorInfoUi.kt` | 断线（原 TableLayout 表格，被 `ComposeEditorInfoContent` 取代） |
| `EditorInfoWindow.kt`（原 onCreateView 内 `ui` 惰性实例） | 已删除（window 仍保留，仅去掉 View 渲染） |

---

## 附录 A：断线文件清单

> 「断线」= 已不再被活跃代码引用，仅被同类断线文件互引或保留别名 / 委托。上游文件按 fork 约定保留不动、不删除；custom 私有文件可择机清理。

### A.1 候选栏 / 展开候选

| 文件 | 状态 |
|---|---|
| `candidates/CandidateItemUi.kt` / `CandidateViewHolder.kt` | 断线（原被展开候选窗口用，Compose 化后仅死文件互引） |
| `candidates/horizontal/HorizontalCandidateComponent.kt` / `HorizontalCandidateViewAdapter.kt` | 断线（被 `ComposeCandidateComponent` 取代） |
| `candidates/expanded/ExpandedCandidateStyle.kt` | 断线（偏好已删，枚举孤立保留） |
| `candidates/expanded/ExpandedCandidateLayout.kt` / `PagingCandidateViewAdapter.kt` / `GridPagingCandidateViewAdapter.kt` / `SpanHelper.kt` / `CandidateTabActionsAdapter.kt` | 断线 |
| `candidates/expanded/window/{Base,Grid,Flexbox}ExpandedCandidateWindow.kt` | 断线 |
| `candidates/expanded/decoration/*` | 断线 |

### A.2 工具栏 / 预编辑

| 文件 | 状态 |
|---|---|
| `bar/KawaiiBarComponent.kt` | 断线（被 `ComposeKawaiiBarComponent` 取代；内部仍 import 死展开窗口） |
| `bar/ui/IdleUi.kt` / `CandidateUi.kt` / `TitleUi.kt` / `idle/ButtonsBarUi.kt` / `idle/ClipboardSuggestionUi.kt` | 断线 |
| `bar/ui/idle/NumberRow.kt` | 断线（`Layout` 已抽离到 `NumberRowLayout.kt`，本类仅保留别名） |
| `preedit/PreeditComponent.kt` | 断线（被 `ComposePreeditComponent` 取代；`InputView` 死实例化已清理） |
| `preedit/PreeditUi.kt` | **仍活跃**（被范围外的 `CandidatesView` 使用，非断线） |

### A.3 弹窗层

| 文件 | 状态 |
|---|---|
| `popup/PopupEntryUi.kt` / `PopupKeyboardUi.kt` / `PopupMenuUi.kt` / `PopupContainerUi.kt` | 断线（被 `ComposePopupLayer` 取代；计算已复制到 `PopupLayoutMath.kt`，避免改上游文件） |

### A.4 状态区 / 剪贴板 / 文本编辑

| 文件 | 状态 |
|---|---|
| `status/StatusAreaAdapter.kt` / `StatusAreaEntryUi.kt` | 断线 |
| `clipboard/ClipboardUi.kt` / `ClipboardEntryUi.kt` / `ClipboardInstructionUi.kt` / `SpacesItemDecoration.kt` | 断线 |
| `clipboard/ClipboardAdapter.kt` | 断线（`excerptText` 已委托到 `ClipboardTextUtils.kt`） |
| `editing/TextEditingUi.kt` / `TextEditingButton.kt` | 断线 |

### A.5 键盘 / Picker

| 文件 | 状态 |
|---|---|
| `keyboard/BaseKeyboard.kt` | 断线（仍被 `TextKeyboard` / `NumberRow` 继承；`TextKeyboard` 被设置页 `KeyboardPreviewUi` 实例化故全链保留） |
| `keyboard/TextKeyboard.kt` | **半活跃**（`KeyboardPreviewUi` 实例化；`Name` 已委托 `KeyboardLayoutNames.Text`，`Layout` 被 `ComposeTextKeyboard` 经数据文件复用） |
| `keyboard/NumberKeyboard.kt` | 断线（`Name` 已委托 `KeyboardLayoutNames.Number`） |
| `keyboard/KeyView.kt` | 断线（仍被 `CustomGestureView` 体系与 View 桥接层 `PickerPageUi` 等引用） |
| `keyboard/CustomGestureView.kt` | **半活跃**（被 View 桥接层 `PickerTabsUi` / `ToolButton` 依赖，不能删） |
| `keyboard/KeyViewExt.kt` / `RecentSymbolsView.kt` / `SymbolSliderKeyView.kt` | 断线 |
| `picker/PickerPageUi.kt` | 断线（`Density` 已抽离到 `PickerDensity.kt`，本类保留 `typealias`） |
| `picker/PickerPagesAdapter.kt` / `PickerLayout.kt` | 断线 |

### A.6 范围外（仍活跃 View，非断线，仅登记）

| 文件 | 说明 |
|---|---|
| `input/CandidatesView.kt` | 浮动候选（物理键盘模式），仍被 `FcitxInputMethodService` 挂载；用 `PreeditUi` / `PagedCandidatesUi` / `LabeledCandidateItemUi` |
| `input/keyboard/KeyboardTuneOverlay.kt` | custom 调校浮层，仍 View，挂 `keyboardView` 内 |
| `ui/main/settings/theme/KeyboardPreviewUi.kt` | 设置页主题预览，实例化 `TextKeyboard`（View 键盘） |
| `input/bar/ui/ToolButton.kt` | 仍活跃（多个窗口 `onCreateBarExtension` 返回的 View 按钮） |
| `input/picker/PickerTabsUi.kt` / `PickerPaginationUi.kt` | 仍活跃（`PickerWindow` 的 AndroidView island） |
| `input/bar/ui/idle/InlineSuggestionsUi.kt` | 仍活跃（系统 API 限制，`ComposeKawaiiBarComponent` AndroidView 包装） |

### A.7 本次新增的独立数据文件

| 文件 | 内容 |
|---|---|
| `bar/ui/idle/NumberRowLayout.kt` | 数字行 `KeyDef` 布局数据 |
| `picker/PickerDensity.kt` | Picker 密度枚举 |
| `clipboard/ClipboardTextUtils.kt` | `excerptClipboardText` 顶层函数 |
| `keyboard/KeyboardLayoutNames.kt` | 布局名常量对象 |

### A.8 EditorInfo

| 文件 | 状态 |
|---|---|
| `editorinfo/EditorInfoUi.kt` | 断线（被 `ComposeEditorInfoContent` 取代） |