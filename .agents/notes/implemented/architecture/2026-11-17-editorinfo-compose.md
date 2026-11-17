# Agent Note: EditorInfo 窗口 Compose 化

Status: implemented

## Problem

`EditorInfoUi` 是迁移后期才发现的漏网窗口，仍以 View 渲染，与其余已迁移窗口的宿主方式不一致。

## Decision

内容迁到 `ComposeEditorInfoContent`，以 `ComposeView` 挂在 `editorinfo` 窗口；窗口的挂载与尺寸上报沿用其余 Compose 窗口的方式。

断线保留：`editorinfo/EditorInfoUi.kt`。

## Alternatives considered

**保留 View 实现并在后续统一处理**。该窗口与其它窗口共用挂载时序，单独保留会让宿主逻辑分叉。

**把内容并入设置页**。编辑器信息是 IME 内的即时信息，不属于设置。

## Consequences

- 该窗口与其余 Compose 窗口共享 owner 链与 insets 处理。
- 新增编辑相关信息只改 `ComposeEditorInfoContent`。
