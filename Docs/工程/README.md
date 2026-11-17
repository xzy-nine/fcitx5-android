# 工程

构建、测试、性能与模块划分。命令的权威来源是各页，根 [AGENTS.md](../AGENTS.md)) 只放常驻入口。

## 索引

| 页面 | 回答什么 |
|---|---|
| [构建与验证.md](构建与验证.md) | 工具链、构建命令、引擎来源、构建开关、文档门禁 |
| [测试策略.md](测试策略.md) | 测试分层、执行入口、按改动面的证据选择 |
| [性能诊断.md](性能诊断.md) | 逐键诊断、固定节拍探针、候选延迟研究 |
| [模块结构.md](模块结构.md) | Gradle 模块与 app 内部包职责 |

## 常用命令

```sh
pnpm install                            # 安装文档门禁依赖
pnpm run verify-all                     # 全部门禁
./gradlew.bat :app:compileDebugKotlin   # 快速验证 Kotlin 与资源
./gradlew.bat :app:testFxDebugUnitTest  # JVM 单元测试
./gradlew.bat :app:assembleDebug        # 完整构建
./gradlew.bat verifyDocs                # 文档门禁（Gradle 任务）
```
