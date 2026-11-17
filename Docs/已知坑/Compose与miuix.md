# Compose 与 miuix

Compose 宿主、miuix 弹层、owner 链与主题体系相关的问题与约束。

## ComposeView 是 final 类

不要把 Compose 宿主写成子类，用工厂函数包装。`setContent` 是它的成员函数，不需要 import。

## IME 内禁用 miuix 的 Window 弹层

`WindowDialog` 与 `WindowListPopup` 底层是系统 Dialog（Activity window token），在 IME 浮窗层级必崩 `BadTokenException`，需改用 `Overlay*` 系列同名组件。

`Overlay*` 本身不依赖系统 Dialog（内部只用 `AnimatedVisibility` + `Box` + `zIndex`），但它只是把内容注册进 `LocalRootPopupStates`，真正渲染的 `MiuixPopupHost` 由根 `Scaffold` 的 `popupHost` slot 提供。因此使用 `Overlay*` 的窗口必须在 Compose 根包一层 `Scaffold`（z 序最高，覆盖整窗），否则弹层无处渲染。

`Scaffold` 用于此目的时建议 `containerColor = Color.Transparent` 保留原底色、`contentWindowInsets = WindowInsets(0)`。IME insets 由 `InputView.onApplyWindowInsets` 处理，勿二次顶开。

## owner 链必须补齐

在 IME 视图树挂 `ComposeView` 必须补 owner 链。

需设置 `setViewTreeLifecycleOwner`、`setViewTreeSavedStateRegistryOwner`、`setViewTreeViewModelStoreOwner`、`setViewTreeNavigationEventDispatcherOwner`；注意 `SavedStateRegistryOwner : LifecycleOwner`。

该四条 owner 链已由 `input/LifecycleInputMethodService.kt` 统一实现并挂到 IME `decorView`，后续直接复用，勿重复挂载。

`viewModelStore` 是 `ViewModelStoreOwner` 的接口成员，必须写 `override val`，不能再额外声明同名字段。

## Overlay 弹层依赖 NavigationEventDispatcherOwner

`Overlay*` 弹层强依赖 `NavigationEventDispatcherOwner`，缺失是长按剪贴板条目必崩的根因。

miuix `MiuixPopupHost` 的 `PopupEntry`（`MiuixPopupUtils.kt`，`AnimatedVisibility` 之前）无条件调用 `NavigationBackHandler`，而 `NavigationEventHandler` 里 `checkNotNull(LocalNavigationEventDispatcherOwner.current)` 会在拿不到 owner 时抛 `IllegalStateException`。

该 local 靠 `ViewTreeHostDefaultKey` 从 `LocalView` 起沿视图树向上找 owner：Activity 由 `ComponentActivity` 与 `activity-compose` 提供，IME 场景框架不提供。异常发生在 composition 阶段、无法被业务代码捕获，会崩进程。

修复方式是让 `LifecycleInputMethodService` 实现 `NavigationEventDispatcherOwner` 并挂到 `decorView`，在 `onDestroy` 里 `dispose()`。语义上它是根 dispatcher、无输入源，handler 永不触发，返回键行为不变。

## 两套颜色体系并存

fcitx `Theme` 与 `MiuixTheme.colorScheme` 独立互不相干。

- Compose 迁移件（工具栏、候选、数字行、预编辑、状态区、弹窗）一律从 `MiuixTheme.colorScheme` 取色，取色函数如 `getVisuals()` 因此必须是 `@Composable`。
- 只有 View 侧（主键盘、展开候选、`CandidatesView`、`ToolButton`）仍读 fcitx `Theme`。切勿在新 Compose 代码里引用 fcitx 主题对象。
- 例外是工具栏为与键盘底色形成对比而读原始 `Theme.keyboardColor` 的明度（`ComposeKawaiiBarComponent.getVisuals()`），这是有意为之。

风险是键盘与候选栏分属两套色源，深色或自定义主题下可能出现色调不匹配。验收动作是在主题设置页切换浅色、深色、自定义主题（含背景图）后检查工具栏、候选栏、键盘三层过渡是否突兀。

## Compose 栈与 legacy 双栈

进入 legacy 页时 `LegacyScreen` attach 全新的 `NavHostFragment`（start 为空 anchor `LegacyAnchorFragment`），返回由 `LegacyNavRuntime.popLegacyBackStack()` 协调。

`SettingsRoute.kt` 上游文件保持零改动。
