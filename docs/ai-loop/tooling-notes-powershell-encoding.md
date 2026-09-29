# 工具链排错记录：为什么"agent 编辑文件"会出错（2026-09-29）

> 结论先说：**不是 DeepSeek Harness 的 edit 工具坏了**。本次会话所有编辑类事故都能归到
> 三类原因：① Windows PowerShell 的编码坑（真正的系统工具 bug，已复现）；② 我自己写错的
> 替换串/断言；③ 长命令被 harness 中止（看起来像失败，其实是超时）。

## 1. 已复现的系统工具 bug：PowerShell 5.1 + 代码页 936

```
PowerShell 版本 : 5.1.22621.4391     ← 5.1 的 Get-Content 默认按 ANSI(代码页) 解码
Active code page: 936 (GBK)
```

复现（字节数证据）：

| 步骤 | 结果 |
|---|---|
| 写入 UTF-8 中文文件 | 24 字节 |
| `Get-Content -Raw` 读回（不带 `-Encoding`） | `// 涓枃娉ㄩ噴娴嬭瘯 OK` ← 乱码，15 字符 |
| `Set-Content -Encoding UTF8` 写回 | **38 字节** ← 与原文不同 = 文件已损坏 |
| `Get-Content -Raw -Encoding UTF8` 读回 | 正常往返 ✓ |

**受害文件与恢复**：`core/designsystem/.../BiliParticleLoader.kt`、
`feature/video/.../player/PortraitSwipePager.kt` —— 都是我用
`Get-Content -Raw … | Set-Content` 做整文件文本替换时被静默弄坏（中文注释全变乱码、
结构错乱、编译器报一串 Syntax error）。两者都用 `git checkout -- <file>` 恢复后
改用 `edit` 工具重新施加改动，最终验证：UTF-8、无 BOM、无 U+FFFD ✓。

**规避规则（硬规则）**：
- 仓库源码**永远不要**用 `Get-Content` / `Set-Content` / `Out-File` 读写；
  一律用 harness 的 `edit` / `write`（它们写 UTF-8 正确）。
- 若脚本确实要碰文件，用 .NET API 并显式指定编码：
  ```powershell
  $t = [System.IO.File]::ReadAllText($p, [System.Text.UTF8Encoding]::new($false))
  [System.IO.File]::WriteAllText($p, $t, [System.Text.UTF8Encoding]::new($false))
  ```

## 2. 第二个坑：.NET 文件 API 不认 pwsh 的 `cd`

`[System.IO.File]::ReadAllBytes("相对路径")` 按**进程**当前目录解析，而不是 pwsh 会话里
`Set-Location` 后的目录 → 报 `Could not find part of the path`（今天又踩了一次，
`cd D:\111j\c\_ref\bbspace` 之后仍去 `D:\111j\c\...` 找文件）。
**规则**：脚本里一律用绝对路径。

## 3. 第三个坑：长命令被中止

两次 5~8 分钟的 `gradlew … assembleRelease` 都被 harness 以
`Error: tool call aborted` 掐掉（不是编译失败）。观感上像"又出错了"。
**规则**：长构建拆成两步（先 `:feature:video:testDebugUnitTest` 再 `:app:assembleRelease`），
或用后台任务跑、轮询结果。

## 4. 不是 bug 的部分：edit 工具本身

- `%USERPROFILE%\.dsh\logs` 只有 09-24 的启动日志（HMR/config reload 警告），
  近 24 小时无任何 DSH 日志写入 → 没有 edit 相关报错。
- 本会话几十次带中文 `old_string`/`new_string` 的编辑全部成功 ✓
  → edit 工具对 UTF-8 处理正确。
- 所有"编辑失败"的真实原因：
  1. `file has not been read` —— FS 策略要求先读再改（是我的流程问题）；
  2. `old_string was not found` —— 我自己写错（多行串跨了不相邻的行；
     大文件里 `}` `}` 这类短串不唯一）；
  3. 替换后编译失败 —— 我的 `new_string` 漏掉了原行（IconButton/Icon 那次）；
  4. 守卫测试红 —— 我的正则/计数断言与源码不符（`coverUrl` 命中
     `swipeCoverUrl`、`>=2` 实际是 1）。

## 5. 防再犯的检查清单

- [ ] 改完先 `git diff --stat`，再用 `[System.IO.File]::ReadAllBytes` 验 UTF-8/无 BOM/无 U+FFFD
- [ ] 大文件编辑：`old_string` 取短而唯一的锚点；`new_string` 与 `old_string` 行数对称
- [ ] 批量文本替换：只用 `edit`；必要时用 `replace_all`，不要用 PowerShell 重写文件
- [ ] 长构建：拆步或后台跑
