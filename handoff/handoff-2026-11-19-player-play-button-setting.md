# Handoff — 2026-11-19 — 播放按钮显示设置（#77）

## 任务状态

- Round 9/256；task_id=player-play-button-setting-20261119；branch=feature/player-play-button-setting-20261119。
- 代码提交：1b4088cca6216ec73f54b64b4da6563a642bcd7d（11 files，+135/−23）。
- Draft PR：https://github.com/fwzm/MediaHub/pull/152（OPEN / Draft / MERGEABLE，base=main，head=1b4088cca6216ec73f54b64b4da6563a642bcd7d）。
- 实现：UserPreferences 末尾新增 showPlayerPlayButton（默认 true）；UserPreferencesStore DataStore key+读写；SettingsScreen 开关+中英文文案；PlayerPlayPauseButton 抽取并按 visible 条件渲染；RestoreSnapshotStore codec v3。
- 验证：聚焦 GREEN 8m24s/242 tasks/exit 0；全量 GREEN 14m33s/546 tasks/exit 0；91 suites / 949 tests / 12 skipped / 0 failures / 0 errors；NC 2/2 PASS_ONE_RED；APK size=130579827 sha256=cace95505529bca6a0f6c0a3b8d7fe0fe046f4367ad5319550a88827e09468ef。
- 真机：NOT_RUN（设备 123e243f 未触碰；Room 6↔5 阻塞，禁清库）。

## 目标

- 关闭审计行 #77「新增-关闭播放按钮显示设置」：增加用户偏好与设置开关，关闭后播放器覆盖层不显示播放/暂停按钮；不禁用手势、媒体键或改变播放状态。

## 已试方案与失败原因

- 首次全量门禁失败：feature/settings RestoreSnapshotStore.kt:203/204 类型错位（Boolean/SubtitleStyle/PlayerGestures），原因是新字段插入 UserPreferences 构造器中间破坏位置参数调用；修复为字段移到 data class 末尾，并把备份 codec 升到 v3（v1/v2 解码回退 true），随后全量通过。
- PlayerPlayPauseButtonTest 首次运行因测试 import 了不存在的 assertDoesNotExist 编译失败，属测试代码错误，不计产品 RED；删除 import 后聚焦 GREEN 通过，并由 NC button-hidden 证明 hidden 断言恰好 1 红。
- git push 首次失败：credential.helper=manager 不可用；改用 git -c credential.helper="!gh auth git-credential" push 成功。

## 进度与下一步

- 下一步：用 40 位 SHA 1b4088cca6216ec73f54b64b4da6563a642bcd7d 查询 PR #152 exact-head Android CI，结果未出前保持 CI_PENDING。
- 下一轮候选：审计行 #148 聚合空状态文案（Search/Library/Detail 语义不一致，需先定产品边界）或审计后续优先级。
- 证据目录：D:/deepseek_test/mh-play-button-evidence-20261119（snapshot、nc、focused-settings-fix-20261119.log、green-final-20261119.log、pr-body.md）。
