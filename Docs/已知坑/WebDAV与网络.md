# WebDAV 与网络

网络调用、权限与 HTTP 客户端相关的问题与约束。

## 调用必须在 IO 线程

WebDAV 与网络调用必须在 IO 线程。

`github.xzynine.webdav` 底层是 OkHttp 同步 `execute()`，`WebDavSyncEngine` 的每个公开 suspend 方法都要 `withContext(Dispatchers.IO)`。漏了会在主线程抛 `NetworkOnMainThreadException`，其 `message` 为 null，UI 只显示兜底文案「操作失败」。

## 本地网络权限的前置条件

`ACCESS_LOCAL_NETWORK` 只在 `targetSdk = 37` 下生效。

它是拆分权限：目标 SDK 小于 37 时凭 `INTERNET` 隐式获得局域网访问权，此时声明该权限无意义，`LocalNetworkPermission.isGranted()` 也会直接返回 true。因此 `Versions.targetSdk` 与子模块清单声明必须成对存在，单独改任一侧都测不出效果。

未授权时的表现是静默超时（TCP）而非明确报错，排查时先确认权限。

## 权限请求入口的位置

该权限的请求入口只能在 Activity 或 Compose。

- `WebDavSyncScreen` 各网络入口走 `rememberLocalNetworkPermissionGate().ensure(url)`。
- 后台链路（`AutoDictSync` 的自动上传与下载）弹不出权限框，因此在显式判断 `LocalNetworkGuard.isDenied()` 后静默跳过，不要在那里调用 `ensure()`。
- 引擎侧 `LocalNetworkGuard.require()` 抛出的 `LocalNetworkPermissionRequiredException` 在 `listRemotePrefs` 与 `listRemoteDictFiles` 里必须重新抛出：这两处的 `runCatching` 把任何异常都当成「云端没有备份」，吞掉权限错误会让界面显示「已是最新」而不是提示授权。

## OkHttp 版本

必须是 5.x（`libs.okhttp = 5.4.0`），不能退回 4.12.0。

语音在线 provider（火山、MiMo）与 WebDAV 都用 okhttp 5.x。OkHttp 5 默认启用 `fastFallback`，4.x 没有这个开关（`OkHttpClient.Builder` 无 `fastFallback`），只能逐个解析地址各等满 `connectTimeout`。

遇到同域名某个 IP 或 IPv6 不可达时，表现为 `SocketTimeoutException`，而同时用 `curl` 访问同一 URL 秒开，极易被误判成设备网络问题。
