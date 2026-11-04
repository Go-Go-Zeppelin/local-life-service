# AI 代码审查

## 配置来源

工作流参考 Aruing 的 `.github/workflows/pr-agent.yml`，使用 `the-pr-agent/pr-agent@v0.39.0` 和 DeepSeek 原生 provider。

默认模型为 `deepseek/deepseek-v4-pro`，备用模型为 `deepseek/deepseek-v4-flash`，输出语言为中文。模型与语言集中配置在 `.github/workflows/pr-agent.yml`，Java 项目的审查重点配置在 `.pr_agent.toml`。

## 配置 API key

在当前 GitHub 仓库打开 Settings → Secrets and variables → Actions → New repository secret。

| Name | Secret |
| --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek 平台生成的 API key |

`GITHUB_TOKEN` 由 GitHub Actions 自动提供。工作流声明了读取代码、写 PR 和写 Issue 的权限，用于获取差异和发布审查评论。

缺少 `DEEPSEEK_API_KEY` 时，工作流在配置检查步骤明确失败。密钥仅保存在 Actions Secrets，工作流通过环境变量读取。模型调用使用该密钥所属 DeepSeek 账户的额度。

## 自动与手动触发

同仓库的非草稿 PR 在新建、推送新提交、重新打开或转为待审核时触发自动审查。机器人事件和 fork PR 的自动事件跳过。

配置合入默认分支 `main` 后，仓库所有者、组织成员或协作者还可以在 PR 对话中创建或编辑以下命令评论。

```text
/review
```

普通讨论评论、普通 Issue 评论和机器人评论跳过。自动事件只执行审查，结果通过 PR-Agent 的持久化审查评论更新，PR 描述继续由提交者维护。

每个 PR 有独立的并发组，同一 PR 的后续请求取消正在运行的旧请求，单次任务最多运行 15 分钟。

## 启用步骤

1. 在 GitHub 配置 `DEEPSEEK_API_KEY`。
2. 将 `.github/workflows/pr-agent.yml`、`.pr_agent.toml` 及相关文档提交并合入 `main`。
3. 创建非草稿 PR，或向已有 PR 推送新提交。
4. 在 Actions → PR Agent 中检查执行情况，并在 PR 页面查看中文审查结果。

该工作流独立于 `ci.yml` 的 Maven 构建和测试，AI 审查意见作为评审辅助。仓库分支规则决定哪些检查必须通过才能合并。

## 本地验证

工作流可以使用 actionlint 检查，`.pr_agent.toml` 可以使用 TOML 解析器检查。验证配置文件不需要 API key，实际模型调用及 PR 评论验证需要先配置 Secret 并在 GitHub 触发工作流。

审查指令文件默认从仓库默认分支读取，因此 `.pr_agent.toml` 合入 `main` 后生效。自动事件及新提交的命令配置已按固定版本的事件处理实现核对。[PR-Agent 事件处理](https://github.com/the-pr-agent/pr-agent/blob/v0.39.0/pr_agent/servers/github_action_runner.py)、[仓库配置读取](https://github.com/the-pr-agent/pr-agent/blob/v0.39.0/pr_agent/git_providers/github_provider.py)

备用模型沿用 Aruing 中的 `deepseek-v4-flash` 名称，DeepSeek 官方目前将其作为兼容名称接受。[DeepSeek 模型说明](https://api-docs.deepseek.com/quick_start/pricing)
