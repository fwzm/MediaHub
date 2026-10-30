# 补丁提案：android-ci.yml 的 concurrency group 加 SHA

> 配套 ADR：`ADR-AUTO-001-android-ci-concurrency.md`。本文只给可直接应用的补丁，**未应用**。

## 变更

```diff
--- a/.github/workflows/android-ci.yml
+++ b/.github/workflows/android-ci.yml
@@ -15,3 +15,10 @@
 concurrency:
-  group: ci-${{ github.ref }}
   cancel-in-progress: true
+
# 同一提交被重复触发时仍取消旧的；不同提交之间不互相取消 —— 否则同分支并行提交时，
+# 任何一方都拿不到自己的 exact-SHA 结论（见 ADR-AUTO-001）。这一条与 windows-ci 对齐：
+# windows-ci 没有 concurrency，所以每条提交都能拿到结论。
+group: ci-${{ github.ref }}-${{ github.sha }}
```

具体改动只有一行：

```yaml
concurrency:
  group: ci-${{ github.ref }}-${{ github.sha }}
  cancel-in-progress: true
```

## 为什么加 `${{ github.sha }}` 而不是关掉 `cancel-in-progress`

| 场景 | 现状（`ci-<ref>`） | 加 SHA 后 | 关掉 cancel |
| --- | --- | --- | --- |
| 同一提交重复触发（重跑、webhook 重复） | 取消旧的 | **取消旧的** | 全部排队 |
| 不同提交先后推送 | **取消旧的、丢证据** | 各自跑完、都有证据 | 全部排队 |
| 队列压力 | 最低 | 中（同时最多 2–3 条） | 最高 |

加 SHA 保留了「同一提交不重复跑」这个真正的省时目的，只是不再误伤不同提交。

## 应用后的验收方式

1. 连续推两个提交 A、B（间隔 < Android 运行时长）；
2. 断言：`A` 的 Android run 与 `B` 的 Android run **都** completed/success，且各自 `head_sha` 等于对应提交；
3. 断言：再对 `B` 手动 rerun 一次，旧的 `B` run 变成 cancelled（证明 `cancel-in-progress` 仍在生效）；
4. 记录两条 run id 进 `docs/automation/RUNLOG.md`。

第 3 条是关键的**负向对照**：只验证「不互相取消」会让「cancel-in-progress 失效」也通过 —— 那会让重复触发白白多跑 30 分钟。

## 不适用的情况

- 若分支保护或组织策略要求 `build` 这个 check 只能有一个在跑，则需要维护者确认；当前 `main` 的 required checks 只有 `build`（context 名），未限制并发数。
