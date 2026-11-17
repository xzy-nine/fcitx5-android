# 候选与 Picker

候选栏、展开候选、Picker 与设置搜索的相关特性。

## 候选三栏骨架

展开候选页与 Picker 共用一套调用。

`input/candidates/ComposeSplitCandidatesUi.kt` 提供左 15% 标签侧栏 + 中 `LazyVerticalGrid` + 右 15% 键盘；同文件含 `SplitTab`、`SplitCandidatesKeyboard.PageUp` 与 `PageDown`、`rememberCanPageUp` 与 `rememberCanPageDown`。

右栏删除键接线在 `input/keyboard/ComposeKeyColumn.kt`。两侧调用方是 `candidates/expanded/ComposeExpandedCandidatesUi.kt` 与 `picker/ComposePickerSplitUi.kt`。

## Picker 分栏

符号、表情、颜文字 Picker 采用左右分栏，与展开候选页同款。

`input/picker/ComposePickerSplitUi.kt` 的中栏用 `LazyVerticalGrid` 铺 `PickerGridLayout` 槽位，格子密度为 符号 32、Emoji 44、颜文字 76，行高固定。配套 `PickerWindow.kt`、`PickerPageModel.flatCategories()`、`PickerGridLayout.kt`（纯计算：候选槽与分类间换页空行槽）。

中栏列数用 `PickerDensity.columnCount` 封顶。

## 设置搜索

`SettingsSearch*.kt` 提供 `SettingsSearchManager.search()` 与 `SearchResult` 的纯查询；`PreferenceGroupUi.kt` 负责分组渲染。

Compose 弹窗在 `compose/screens/SettingsSearchScreen.kt`，View 版已移除。
