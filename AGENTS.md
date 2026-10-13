# AGENTS.md（E:\Gander）

> 本文件只写**规则与不可协商的约束**。不写任务、不写计划、不写操作教程、不写"应该做什么"。
> 所有内容默认对在本目录下工作的 agent 生效。

---

## 一、仓库性质（事实）

- 本目录是 `https://github.com/mokshablr/gander` 的**个人 fork**（MIT，Copyright © 2026 Arjun Maniyani）。
- 本地初始基点：tag **`fork-base`** = 上游 `main` @ `a8e2940`（v2.4，2026-10-13）。
- 远程：`upstream` = 上游 URL（**fetch-only**）；**没有 `origin`**，本地提交暂不上传任何地方。
- 分支：`main` = 上游镜像（**永不提交**）；工作分支 `local/*`。
- 上游仓库的**提交历史、作者、文件内容均不属于本仓库**，本地改动必须与上游可区分。

## 二、铁律（禁止 / 必须）

1. **禁止推送到 upstream**。`upstream` 的 push URL 已被禁用（`DISABLED_upstream_is_read_only__see_AGENTS.md`），并装有 `.git/hooks/pre-push` 拦截。禁止绕过（改 pushurl、直接 `git push <url>`、删钩子后推送）。
2. **禁止任何以 `mokshablr/gander` 为目标（`--repo` / `-R`，或 `GH_REPO` 等隐式指定）的 `gh` 写操作**：`issue` / `pr` / `comment` / `release` / `workflow`，以及 `gh api` 的任何 POST / PATCH / PUT / DELETE 请求。除非用户当轮明确要求。只读查询（`gh api` 的 GET、`gh repo view`、`gh pr list` 等）不受此限。
3. **禁止在 `main` 上提交**。一切改动提交在 `local/<主题>` 分支上。
4. **禁止 `git rebase` 上游历史**，禁止对上游同步使用 `git pull --rebase`。同步上游**必须** `git fetch upstream` + `git merge upstream/main`。
5. **禁止对已公开分支 force push**；对自己未公开的 `local/*` 分支确需强推时，只能 `git push --force-with-lease`。
6. **禁止在 `main` 上做 merge 以外的写入**；`fork-base` tag 禁止移动或删除。
7. **零权限 / 零网络是不可协商的**：禁止引入 `INTERNET` 权限、存储权限、统计/分析 SDK、任何网络请求或远程资源加载。所有渲染资源必须来自 APK 内的 `assets/`。
8. **禁止放宽 WebView 安全边界**：禁止开启 `allowFileAccess` / `allowContentAccess`、禁止放行 http(s) 外部请求、禁止削弱或改写 `ViewerPolicy.kt` 中的 CSP、禁止给 iframe 去除 `sandbox`。
9. **禁止使用 `addJavascriptInterface`**。页面与宿主之间只允许走既有的 `postMessage` 通道（`PortMessage.kt`）。
10. **禁止删除既有 workaround 及其说明注释**（`viewer/docx.js` 的 docx-preview 补丁、`viewer/xlsx.js` 的编码处理、`scripts/pptxjs.patch`）。删除或改写必须同时满足：有等价测试覆盖 + `docs/VENDORED.md` 相应记录更新。
11. **修改 `viewer/lib/` 下任何 vendored 文件，必须在同一次改动里同步**：`docs/VENDORED.md`（来源/版本/改动原因）+ `app/src/main/assets/licences.md`（许可全文，Apache-2.0 §4(a) 要求随二进制分发）。本地改动写成 `scripts/*.patch`，禁止直接编辑 minified 产物。
12. **新引入的第三方依赖/JS 库只允许宽松许可**（MIT / Apache-2.0 / BSD / ISC）。**禁止 GPL / AGPL / 双许可中选 copyleft** 的组件进入代码树或打进 APK；双许可必须显式选宽松那一侧并在 `licences.md` 记录。
13. **许可与署名不得移除**：`LICENSE`、`licences.md`、`docs/VENDORED.md` 中的版权与许可文本禁止删除、精简或改写。
14. **禁止把凭据入库**：keystore、密码、token、`~/.gradle/gradle.properties` 中的内容一律不进版本库（`keystore/` 已在 `.gitignore`）。
15. **行尾必须是 LF**。仓库已设 `core.autocrlf=false`、`core.eol=lf`；**禁止改回 `true`**、禁止提交 CRLF 化的大范围 diff。上游本来就是 CRLF 的 6 个文件（`gradlew.bat`、`viewer/lib/pptx/pptxjs.js`、`tests/fixtures/files/*.csv`、`*.stl`）保持原样。
16. **提交信息格式**：`type: 中文简述`，type ∈ `feat` / `fix` / `docs` / `chore`。若提交被用户明确要求回贡献上游，该提交单独成分支并使用英文。
17. **同步上游前必须先看差异**：`git fetch upstream` 后**必须**用 `git log --oneline HEAD..upstream/main` 与 `git diff --stat HEAD upstream/main` 确认范围，禁止盲 merge。
18. **冲突解决会被 rerere 记录**（`rerere.enabled=true`）。禁止关闭 rerere；解决冲突后必须核对 `git rerere diff` 记录的是正确解。
19. **命令安全**：禁止对盘根/家目录做不限深度的递归搜索；网络、构建、测试、递归统计类命令**必须显式设超时**；轮询必须有次数或时长上限，超限即判失败。
20. **GitHub 网络必须走代理**：直连 `github.com` 会连接重置，`git fetch/clone/push` 需 `HTTP_PROXY=http://127.0.0.1:7897`（`HTTPS_PROXY` 同）。

## 三、验收门禁（改了什么就必须过什么）

| 改动范围 | 必须通过 |
|---|---|
| `app/src/main/assets/viewer/**`（任何页面或 JS） | `pytest tests/viewer`（CI 的 `viewer` job） |
| 页面 CSP / 沙箱策略 | `pytest tests/viewer/test_policy.py` + `./gradlew testDebugUnitTest`（`ViewerPolicyTest` 逐字比对每页 `<meta>` CSP） |
| Kotlin 代码 | `./gradlew testDebugUnitTest` + `./gradlew lintDebug` |
| `AndroidManifest.xml` / 权限 | `./gradlew checkReleasePermissions`（release 合并清单） |
| vendored 库升级 | `pytest tests/viewer` + `docs/VENDORED.md` + `licences.md` 同步 |
| 参考截图（goldens） | **只允许在 Linux/CI 生成**：`scripts/test-viewer-visual.sh --update-goldens`（文本栅格化跨平台不一致，本机不得重生成） |

- `lintDebug` 对**新增**告警失败；存量 39 条在 `app/lint-baseline.xml`，**禁止**为了让构建通过而扩大 baseline。
- 真机测试（`connectedDebugAndroidTest`）由 CI 夜间跑，**不作为**本地门禁。

## 四、GitHub Actions 构建（规则与事实）

**事实**

- `.github/workflows/build.yml` — 触发 `push: branches: [main]` 与 `pull_request`，两个并行 job：
  - `viewer`：容器 `mcr.microsoft.com/playwright/python:v1.62.0-noble`，`pip install -r tests/viewer/requirements.txt` → `pytest tests/viewer`；失败时上传 `tests/viewer/goldens/*.png`。
  - `build`：`actions/checkout@v4` → `gradle/actions/wrapper-validation@v4` → `actions/setup-java@v4`（temurin **21**）→ `gradle/actions/setup-gradle@v4` → `./gradlew testDebugUnitTest` → `lintDebug` → `assembleDebug` → `assembleRelease` → `shasum -a 256`（哈希对应 APK 本体，GitHub 会再套一层 zip）→ 上传 artifact `gander-debug-apk`；失败时上传 `app/build/reports/`。
- `.github/workflows/device.yml` — `schedule: 0 3 * * *` + `push: tags: ['v*']`，模拟器真机测试（不参与 PR 门禁）。
- 无 keystore 时 CI 产出的 release APK 是**未签名**的；签名密钥取自 `keystore/gander.jks`（不入库）。
- 本机无 Android SDK ⇒ **CI 是本仓库唯一的 APK 产出途径**。
- 本仓库当前**没有 `origin`**，因此在建立自己的远程仓库之前，Actions 不会运行。

**规则**

1. 禁止删除或弱化 `wrapper-validation`，禁止引入未经校验的 Gradle wrapper jar。
2. 禁止调整 `build` job 的步骤顺序使检查晚于产物生成：`testDebugUnitTest`、`lintDebug` 必须先于 `assembleDebug` / `assembleRelease`。
3. 禁止为了让 CI 通过而扩大 `app/lint-baseline.xml`，禁止加 `-x` / `continue-on-error` / `|| true` 跳过任何门禁步骤。
4. 禁止把签名口令、keystore、token 写进 workflow、仓库或 artifact：只能走 GitHub Secrets / 环境变量，keystore 仅允许在 runner 临时目录解码，且上传 artifact 前必须删除。
5. 禁止在 workflow 中新增任何会外发用户文档或把仓库外内容上传/上报的步骤；CI 不得触碰任何用户文件。
6. 禁止改动 `build.yml` 的 `concurrency` 语义（同一 ref 的旧运行必须被取消）。
7. 面向 fork 的额外工作流必须是**新文件**（如 `.github/workflows/fork-build.yml`）；禁止修改上游 `build.yml` / `device.yml` 的既有语义。新工作流同样受第 2、3、4、5 条约束。
8. Actions 必须使用 JDK 21（与依赖中的 Robolectric toolchain 要求一致）；禁止单独升级 JDK / Gradle / AGP 而不整体验证。
9. 参考截图（goldens）只允许在 Linux runner 生成或更新；禁止在 Windows/macOS 本地 `--update-goldens` 后提交。
10. viewer 测试容器镜像 tag 中的 playwright 版本必须与 `tests/viewer/requirements.txt` 里的 `playwright` 版本一致；改一个必须同改另一个。

## 五、环境事实（不是待办）

- 工具链：Gradle 8.13（wrapper）、AGP 8.13.2、Kotlin 2.1.0、`compileSdk`/`targetSdk` 36、`minSdk` 26、`versionName` 2.4、JDK **21**。
- 本机 JDK：`C:\Program Files\Java\jdk-21.0.10`（可用）；默认 `java` 是 25，**不适用于**本工程（Robolectric 4.16.1 需 JDK 21），跑 Gradle 前需把 `JAVA_HOME` 指向 21。
- 本机**未安装 Android SDK**（`ANDROID_HOME` 为空）⇒ 本地无法 `assembleDebug/assembleRelease`；APK 由 GitHub Actions（`.github/workflows/build.yml`）产出。
- 本机可用的本地验证：Python 3.11 + `pytest tests/viewer`（需 `pip install -r tests/viewer/requirements.txt` 与 `playwright install chromium`）。
- 应用**不含任何 native 代码**（无 NDK、无 `jniLibs`）；引入 native 依赖是与第 7 条同级的重大变更。
- 每个 viewer 页面自带固定 CSP；页面数量的变化会影响 `ViewerPolicyTest` 与 `tests/viewer/test_policy.py`。
- Git 钩子与 `core.*` 本地配置**不在版本控制内**，重新 clone 后需按第七节恢复。

## 六、目录职责（只描述边界，不描述做法）

- `app/src/main/java/com/arjun/gander/ViewerActivity.kt` — 文档宿主与 WebView 边界（外来文档全部在此边界内被处理）。**与之相关的安全规则见第二章第 8/9 条。**
- `app/src/main/java/com/arjun/gander/ViewerPolicy.kt` — CSP/策略的唯一权威副本。
- `app/src/main/java/com/arjun/gander/PortMessage.kt` — 宿主与页面之间唯一允许的通道。
- `app/src/main/java/com/arjun/gander/DocRange.kt` — 文档字节按 Range 提供给页面。
- `app/src/main/java/com/arjun/gander/FileKind.kt` — 格式判定。
- `app/src/main/assets/viewer/**` — 页面与渲染器；**`lib/` 为 vendored 上游产物，受第二章第 11 条约束**。
- `docs/VENDORED.md` — vendored 依赖的唯一 provenance 权威表。
- `app/src/main/assets/licences.md` — 随 APK 分发的许可全文，受第二章第 13 条约束。
- `tests/viewer/**` — 浏览器内测试；`goldens/` 只允许 CI 更新。
- `CONTRIBUTING.md` 与 `SECURITY.md` 是上游规矩，**与本文件冲突时以本文件为准**（本文件更严）。

## 七、重新 clone 后必须恢复的本地状态（不可丢）

```sh
git remote rename origin upstream
git remote set-url --push upstream DISABLED_upstream_is_read_only__see_AGENTS.md
git config core.autocrlf false && git config core.eol lf
git config rerere.enabled true && git config rerere.autoUpdate true
git config merge.conflictStyle zdiff3 && git config fetch.prune true
git config core.quotepath false && git config push.default simple
# 重新安装 .git/hooks/pre-push（内容见仓库外备份或按第二章第 1 条重建）
git tag -a fork-base -m "fork base @ upstream/main" upstream/main
git switch -c local/dev
```

- 恢复后 `git ls-files --eol | grep w/crlf` 只应剩上游本来 CRLF 的 6 个文件。
- `fork-base` tag、`upstream` pushurl、钩子三者的缺失都视为**配置损坏**，需立即恢复。
