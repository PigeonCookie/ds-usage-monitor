# DSMonitor 1.2.8

## English

- Usage now comes **only from official platform data** — hourly bars, totals, cache-hit rate and CSV share one source; no sampled fallback.
- **CSV export rewritten**: hourly rows with `hit/miss/out` for Today / Yesterday, daily rows for ranges.
- **Cache-hit rate** replaces the hourly average: `hit ÷ (hit + miss)`.
- **Today's spend** line under cumulative spend · gift card hidden when 0 · both ⓘ icons open the official pages.
- **API key & token encrypted** with Android Keystore AES-GCM; settings trimmed to two rows.

## 中文

- 用量**只认官网数据**：图表、合计、命中率、CSV 同源，不再用采样兜底。
- **CSV 导出重写**：今天/昨天按小时并含 `hit/miss/out`，区间按天。
- **缓存命中率**取代时均（命中 ÷（命中 + 未命中））。
- 累计消费下新增**今日花费**一行 · 赠送余额为 0 隐藏整卡 · 两个 ⓘ 可点开官方页面。
- **密钥改用 Keystore AES-GCM 加密**；偏好设置精简为两行。
