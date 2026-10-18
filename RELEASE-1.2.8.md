# DSMonitor 1.2.8

## English

- **Platform data is now the only source for usage**: hourly bars, card totals, cache-hit rate and CSV export all come from the official hourly windows. If the platform can't be reached the charts stay empty with a hint to refresh — no more sampled estimates.
- **Cache hit rate** replaces the hourly average for Today / Yesterday: `hit ÷ (hit + miss)`, computed from the very same hourly data as the chart.
- **CSV export rewritten**: `hour,cost_cny,tokens,requests,hit,miss,out` for Today / Yesterday, `date,…` for ranges — filtered by the selected key/model, with hit/miss/out included so you can verify the rate yourself.
- **New "Today ¥x" line** under Cumulative spend, styled like the Total balance row.
- **Gift balance card is hidden** when the gift balance is 0.
- **The two ⓘ icons are tappable**: recharge balance opens the official FAQ, gift balance opens the grant-history page.
- **Secrets encrypted**: API key and platform token are stored with Android Keystore AES-GCM (non-exportable key); existing plain-text values migrate automatically.
- **Settings trimmed to two rows** (appearance, balance alert). Background sampling is always on, keeps 7 days, and no longer decides anything on screen.
- Minor: `自定义时间` entry fixed, stale key selections are dropped when switching ranges, the About line no longer shows the build number.

## 中文

- **用量数据只认官网**：逐时柱状图、卡片合计、缓存命中率、CSV 导出全部来自官方逐时窗口；平台取不到时图表留空并提示刷新，不再用本机采样估算。
- **缓存命中率**取代今天/昨天的"时均"：`命中 ÷（命中 + 未命中）`，与图表同源。
- **CSV 导出重写**：今天/昨天为 `hour,cost_cny,tokens,requests,hit,miss,out`，区间为 `date,…`；按所选 Key/模型统计，并给出 hit/miss/out 便于自行验算。
- **累计消费下方新增"今日 ¥x"一行**，样式与"总余额"完全一致。
- **赠送余额为 0 时整张卡片隐藏**。
- **首页两个 ⓘ 可点**：充值余额 → 官方文档，赠送余额 → 有效期查询页。
- **密钥加密**：API Key 与平台 User Token 使用 Android Keystore AES-GCM 加密存储（密钥不可导出），旧的明文值自动迁移。
- **偏好设置精简为两行**（外观模式、余额预警）；后台采样常开、保留 7 天，且不再参与任何界面数字。
- 其他：修复「自定义时间」入口、切换维度后自动剔除失效的 Key 选择、关于行不再显示构建号。
