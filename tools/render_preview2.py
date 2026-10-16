"""Updated design previews for the usage page (stacked tokens, series legend, tap tooltip).

Reuses the drawing helpers from render_preview.py so both scripts share the palette and metrics.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw                      # noqa: E402
import render_preview as B                            # noqa: E402

C = B.C
C.setdefault("chart_2", C["chart2"])
C.setdefault("tok_hit", (0x70, 0x9A, 0xB0))
C.setdefault("tok_miss", (0x44, 0x7E, 0xB2))
C.setdefault("tok_out", (0x0A, 0x4E, 0xA6))
C.setdefault("stroke", (49, 49, 58))



def draw_stacked_bar(d, x0, top, x1, bottom, segments):
    """segments: list of (colour, fraction of the bar height) bottom-up, drawn inside one pill."""
    img = getattr(d, "_image", None)
    w, h = int(round(x1 - x0)), int(round(bottom - top))
    if img is None or w <= 1 or h <= 1:
        return
    tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    td = ImageDraw.Draw(tile)
    cursor = h
    for color, frac in segments:
        seg = frac * h
        td.rectangle([0, cursor - seg, w, cursor], fill=color)
        cursor -= seg
    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    radius = max(0, min(w // 2, h // 2))
    if radius > 0:
        md.rounded_rectangle([0, 0, w - 1, radius * 2], radius=radius, fill=255)
        md.rectangle([0, radius, w - 1, h - 1], fill=255)
    else:
        md.rectangle([0, 0, w - 1, h - 1], fill=255)
    tile.putalpha(mask)
    img.paste(tile, (int(round(x0)), int(round(top))), tile)


def axis_label(v, unit="money"):
    if unit == "money":
        return "%.2f" % v
    if v >= 1_000_000:
        return ("%.1fM" % (v / 1e6)).replace(".0M", "M")
    if v >= 1000:
        return ("%.1fK" % (v / 1e3)).replace(".0K", "K")
    return "%d" % round(v)


S = B.S
W, H = B.W, B.H
dp = B.dp
text = B.text
rrect = B.rrect
font = B.font
chip = B.chip
pill = B.pill
stat_card = B.stat_card
center_text = B.center_text

KEYS = [("cookie", C["chart"]), ("qiqipigeon", C["chart_2"])]
TOKENS = [("输入（命中缓存）", C["tok_hit"]), ("输入（未命中缓存）", C["tok_miss"]), ("输出", C["tok_out"])]


def stacked_chart(d, x, y, w, h, title, value, series, ticks, maxv, unit="money",
                  segment=None, note=None, legend=None, tooltip=None):
    rrect(d, [x, y, x + w, y + h], dp(22), C["card"])
    px, py = x + dp(12), y + dp(10)
    text(d, (px, py), title, font(12), C["text2"])
    box = d.textbbox((0, 0), title, font=font(12))
    text(d, (px + (box[2] - box[0]) + dp(6), py), value, font(12, True), C["text"])
    if segment:
        sw = dp(112)
        sx = x + w - dp(12) - sw
        rrect(d, [sx, py - dp(3), sx + sw, py + dp(23)], dp(13), C["control"])
        half = sw / 2
        rrect(d, [sx + dp(2), py - dp(1), sx + half, py + dp(21)], dp(11), C["seg_on"])
        center_text(d, sx + half / 2, py + dp(2), "模型", font(11, True), C["text"])
        center_text(d, sx + half + half / 2, py + dp(2), "API Key", font(11), C["text2"])
    elif note:
        nb = d.textbbox((0, 0), note, font=font(11))
        text(d, (x + w - dp(12) - (nb[2] - nb[0]), py + dp(1)), note, font(11), C["text3"])

    top = py + dp(22)
    if legend:
        lx = px
        for label, color in legend:
            text(d, (lx, top), "●", font(10), color)
            wbox = d.textbbox((0, 0), "●", font=font(10))
            text(d, (lx + (wbox[2] - wbox[0]) + dp(3), top), label, font(10), color)
            lbox = d.textbbox((0, 0), label, font=font(10))
            lx += (wbox[2] - wbox[0]) + dp(3) + (lbox[2] - lbox[0]) + dp(12)
        top += dp(16)

    pl, pr = px + dp(40), x + w - dp(12)
    pt, pb = top + dp(6), y + h - dp(20)
    f_lab = font(10)
    for frac in (0, 0.5, 1):
        gy = pb - (pb - pt) * frac
        d.line([pl, gy, pr, gy], fill=C["grid"], width=1)
        lab = axis_label(maxv * frac, unit)
        text(d, (pl - dp(4) - d.textbbox((0, 0), lab, font=f_lab)[2], gy - dp(6)), lab, f_lab, C["text3"])

    n = len(series[0]) if series else 0
    if n:
        slot = (pr - pl) / n
        bar_w = min(slot * 0.62, dp(20))
        for i in range(n):
            total = sum(s[i] for s in series)
            if total <= 0:
                continue
            cx = pl + slot * i + slot / 2
            segs = []
            for si, s in enumerate(series):
                if s[i] <= 0:
                    continue
                segs.append((legend[si][1] if legend else C["chart"], s[i] / total))
            bh = (pb - pt) * (total / maxv)
            draw_stacked_bar(d, cx - bar_w / 2, pb - bh, cx + bar_w / 2, pb, segs)
    for i, lab in enumerate(ticks):
        if not lab or not n:
            continue
        slot = (pr - pl) / n
        cx = pl + slot * i + slot / 2
        b = d.textbbox((0, 0), lab, font=f_lab)
        text(d, (cx - (b[2] - b[0]) / 2, y + h - dp(17)), lab, f_lab, C["text3"])

    if tooltip:
        draw_tooltip(d, x, y, w, h, pl, pr, pt, pb, tooltip)


def draw_tooltip(d, x, y, w, h, pl, pr, pt, pb, tip):
    pad = dp(14)
    rows = tip["rows"]
    title_f, row_f, val_f = font(12, True), font(11), font(12, True)
    tw = d.textbbox((0, 0), tip["title"], font=title_f)[2]
    vw = d.textbbox((0, 0), tip["total"], font=val_f)[2]
    lw = max(d.textbbox((0, 0), r[0], font=row_f)[2] + dp(16) for r in rows)
    rw = max(d.textbbox((0, 0), r[1], font=val_f)[2] for r in rows)
    card_w = min(w - dp(16), pad * 2 + max(tw + dp(18) + vw, lw + dp(18) + rw, dp(150)))
    row_h = dp(20)
    card_h = pad + dp(18) + dp(6) + len(rows) * row_h + pad - dp(6)
    left = max(dp(6), min(x + w - card_w - dp(6), pl + (pr - pl) / 2 - card_w / 2))
    top = pt + (pb - pt - card_h) / 2
    d.line([pl + (pr - pl) / 2, pt, pl + (pr - pl) / 2, pb], fill=C["grid"], width=1)
    rrect(d, [left, top, left + card_w, top + card_h], dp(14), C["card_alt"], C["stroke"])
    text(d, (left + pad, top + pad), tip["title"], title_f, C["text"])
    tb = d.textbbox((0, 0), tip["total"], font=val_f)
    text(d, (left + card_w - pad - (tb[2] - tb[0]), top + pad), tip["total"], val_f, C["text"])
    ry = top + pad + dp(10) + row_h / 2
    for label, value, color in rows:
        if color:
            rrect(d, [left + pad, ry - dp(5), left + pad + dp(10), ry + dp(5)], dp(2.5), color)
        text(d, (left + pad + dp(16), ry - dp(8)), label, row_f, C["text2"])
        vb = d.textbbox((0, 0), value, font=val_f)
        text(d, (left + card_w - pad - (vb[2] - vb[0]), ry - dp(9)), value, val_f, C["text"])
        ry += row_h


def stat_card3(d, x, y, w, h, label, value, unit=None):
    rrect(d, [x, y, x + w, y + h], dp(16), C["card_alt"])
    text(d, (x + dp(10), y + dp(8)), label, font(10), C["text3"])
    vf = font(15, True)
    vy = y + h - dp(15) - dp(8)
    text(d, (x + dp(10), vy), value, vf, C["text"])
    if unit:
        box = d.textbbox((0, 0), value, font=vf)
        text(d, (x + dp(10) + (box[2] - box[0]) + dp(4), vy + dp(5)), unit, font(9), C["text3"])


def usage_page(d, y0, tooltip_on=None):
    x = dp(12)
    y = y0 + dp(12)
    nx = chip(d, x, y, "时间维度 · 今天")
    chip(d, nx + dp(8), y, "模型 · 全部")
    pill(d, W - dp(12) - dp(62), y + dp(1), "导出", size=12, pad=16)
    y += dp(30) + dp(8)
    text(d, (x, y), "今日 ¥0.64 · 69 次请求 · 本机采样 9 次", font(11), C["text3"])
    text(d, (W - dp(12) - dp(66), y), "清除筛选条件", font(11), C["text3"])
    y += dp(22)

    cw = (W - dp(12) * 2 - dp(12)) / 3
    stat_card3(d, x, y, cw, dp(52), "消费金额", "¥0.64", "CNY")
    stat_card3(d, x + cw + dp(6), y, cw, dp(52), "API 请求次数", "69")
    stat_card3(d, x + 2 * (cw + dp(6)), y, cw, dp(52), "Tokens", "1,169,097")
    y += dp(52) + dp(8)

    avail = (H - dp(58) - dp(12)) - y - dp(12)
    chart_h = avail / 3
    ticks = [None] * 24
    for h, lab in ((0, "00:00"), (8, "08:00"), (15, "15:00"), (23, "23:00")):
        ticks[h] = lab

    cost_a = [0.0] * 24
    cost_b = [0.0] * 24
    for h, v in ((1, 0.02), (3, 0.005), (4, 0.005), (5, 0.004), (7, 0.16), (8, 0.08)):
        cost_a[h] = v
    for h, v in ((7, 0.22), (8, 0.11)):
        cost_b[h] = v
    req = [0.0] * 24
    for h, v in ((1, 4), (3, 2), (4, 2), (5, 2), (7, 38), (8, 21)):
        req[h] = v
    tok_hit = [0.0] * 24
    tok_miss = [0.0] * 24
    tok_out = [0.0] * 24
    for h, hit, miss, out in ((1, 1200, 300, 200), (3, 400, 120, 90), (4, 380, 100, 80),
                              (5, 350, 90, 70), (7, 532096, 122377, 9292), (8, 260000, 60000, 5000)):
        tok_hit[h], tok_miss[h], tok_out[h] = hit, miss, out
    max_tok = max(a + b + c for a, b, c in zip(tok_hit, tok_miss, tok_out)) * 1.2
    max_tok = 800000.0

    stacked_chart(d, x, y, W - dp(24), chart_h, "消费金额（CNY）", "¥0.64",
                  [cost_a, cost_b], ticks, 0.28, segment=True, legend=KEYS,
                  tooltip={"title": "08:00 ~ 09:00", "total": "¥0.19",
                           "rows": [("cookie", "¥0.16", C["chart"]),
                                    ("qiqipigeon", "¥0.03", C["chart_2"])]} if tooltip_on == "cost" else None)
    y += chart_h + dp(6)
    stacked_chart(d, x, y, W - dp(24), chart_h, "API 请求次数", "69", [req], ticks, 40.0,
                  unit="count", note="逐时（本机采样）",
                  tooltip={"title": "08:00 ~ 09:00", "total": "21",
                           "rows": [("请求次数", "21", 0)]} if tooltip_on == "req" else None)
    y += chart_h + dp(6)
    stacked_chart(d, x, y, W - dp(24), chart_h, "Tokens", "1,169,097",
                  [tok_hit, tok_miss, tok_out], ticks, max_tok, unit="count",
                  legend=TOKENS,
                  tooltip={"title": "08:00 ~ 09:00", "total": "663,765",
                           "rows": [("输入（命中缓存）", "532,096", C["tok_hit"]),
                                    ("输入（未命中缓存）", "122,377", C["tok_miss"]),
                                    ("输出", "9,292", C["tok_out"])]} if tooltip_on == "tokens" else None)


def render(path, tooltip_on=None):
    img = Image.new("RGB", (W, H), C["bg"])
    d = ImageDraw.Draw(img)
    d._image = img
    y = B.top_bar(d, dp(24))
    B.bottom_nav(d, H - dp(58))
    usage_page(d, y, tooltip_on)
    B.status_bar(d)
    img.save(path)
    print("saved", path, img.size)


if __name__ == "__main__":
    out = os.path.join(B.ROOT, "preview")
    os.makedirs(out, exist_ok=True)
    render(os.path.join(out, "preview-usage.png"))
    render(os.path.join(out, "preview-usage-tooltip.png"), tooltip_on="tokens")
