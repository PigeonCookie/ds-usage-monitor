"""Render design previews of the three pages.

These PNGs are drawn from the same dimensions/colours as the layout XML (360dp wide, 3 px/dp) so the
layout can be reviewed without a device — they are illustrations, not screenshots of a running app.
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "preview")
S = 3                      # px per dp
W, H = 360 * S, 780 * S    # logical screen

C = {
    "bg": (16, 16, 20),
    "nav": (22, 22, 26),
    "card": (31, 31, 36),
    "card_alt": (38, 38, 44),
    "control": (42, 42, 49),
    "text": (236, 237, 239),
    "text2": (165, 168, 175),
    "text3": (113, 116, 123),
    "accent": (77, 107, 254),
    "chart": (112, 154, 176),
    "chart2": (78, 134, 184),
    "chart3": (63, 126, 193),
    "grid": (44, 44, 51),
    "pill_bg": (233, 234, 236),
    "pill_fg": (20, 22, 26),
    "badge_bg": (22, 50, 31),
    "badge_fg": (74, 222, 128),
    "divider": (40, 40, 46),
    "seg_on": (12, 12, 16),
}

FONT_DIR = r"C:\Windows\Fonts"
REG = os.path.join(FONT_DIR, "msyh.ttc")
BOLD = os.path.join(FONT_DIR, "msyhbd.ttc")
if not os.path.exists(BOLD):
    BOLD = REG


def font(size_dp, bold=False):
    path = BOLD if bold else REG
    return ImageFont.truetype(path, int(size_dp * S))


def dp(v):
    return int(v * S)


def center_text(d, cx, y, text, f, fill):
    box = d.textbbox((0, 0), text, font=f)
    d.text((cx - (box[2] - box[0]) / 2, y), text, font=f, fill=fill)


def rrect(d, box, radius, fill, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(d, xy, s, f, fill):
    d.text(xy, s, font=f, fill=fill)


def status_bar(d, y0=0):
    text(d, (dp(16), y0 + dp(4)), "9:59", font(12, True), C["text"])
    right = "5G   92%"
    box = d.textbbox((0, 0), right, font=font(11, True))
    text(d, (W - dp(16) - (box[2] - box[0]), y0 + dp(5)), right, font(11, True), C["text"])


def top_bar(d, y0, reload=True):
    h = dp(54)
    if reload:
        # reload glyph (circular arrow approximated) — pages 1/2 only
        cx, cy, r = dp(26), y0 + h // 2, dp(9)
        d.arc([cx - r, cy - r, cx + r, cy + r], start=40, end=330, fill=C["text2"], width=dp(1.6))
        d.polygon([(cx + r - dp(1), cy - r - dp(3)), (cx + r + dp(4), cy - r + dp(2)),
                   (cx + r - dp(2), cy - r + dp(4))], fill=C["text2"])
    logo = Image.open(os.path.join(RES, "drawable-nodpi", "logo_transparent.webp")).convert("RGBA")
    logo = logo.resize((dp(22), dp(22)), Image.LANCZOS)
    f_brand = font(17, True)
    box = d.textbbox((0, 0), "deepseek", font=f_brand)
    badge_f = font(9)
    bbox = d.textbbox((0, 0), "用量监测", font=badge_f)
    total = dp(22) + dp(6) + (box[2] - box[0]) + dp(6) + (bbox[2] - bbox[0]) + dp(14)
    x = (W - total) / 2
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.paste(logo, (int(x), int(y0 + (h - dp(22)) / 2)), logo)
    text(d, (x + dp(22) + dp(6), y0 + dp(15)), "deepseek", f_brand, C["text"])
    bx = x + dp(22) + dp(6) + (box[2] - box[0]) + dp(6)
    rrect(d, [bx, y0 + dp(18), bx + (bbox[2] - bbox[0]) + dp(14), y0 + dp(38)], dp(8), C["control"])
    text(d, (bx + dp(7), y0 + dp(20)), "用量监测", badge_f, C["text2"])
    d.line([0, y0 + h, W, y0 + h], fill=C["divider"], width=1)
    return y0 + h + 1


def bottom_nav(d, y0):
    h = dp(58)
    d.rectangle([0, y0, W, y0 + h], fill=C["nav"])
    d.line([0, y0, W, y0], fill=C["divider"], width=1)
    labels = ["余额", "用量", "我的"]
    for i, label in enumerate(labels):
        cx = W * (2 * i + 1) / 6
        col = C["accent"] if i == 0 else C["text3"]
        icy = y0 + dp(14)
        if i == 0:  # wallet
            rrect(d, [cx - dp(11), icy - dp(7), cx + dp(11), icy + dp(7)], dp(3), col)
            d.ellipse([cx + dp(3), icy - dp(2), cx + dp(7), icy + dp(2)], fill=C["nav"])
        elif i == 1:  # bars
            for k, hh in enumerate((7, 13, 10)):
                x = cx - dp(9) + k * dp(7)
                rrect(d, [x, icy + dp(7) - dp(hh), x + dp(4.5), icy + dp(7)], dp(1.5), col)
        else:  # person
            d.ellipse([cx - dp(4), icy - dp(8), cx + dp(4), icy], fill=col)
            d.pieslice([cx - dp(9), icy - dp(1), cx + dp(9), icy + dp(15)], 180, 360, fill=col)
        f = font(11, i == 0)
        center_text(d, cx, y0 + dp(38), label, f, col)
    return y0 + h


def chip(d, x, y, label, bold=False):
    f = font(12, bold)
    box = d.textbbox((0, 0), label, font=f)
    w = (box[2] - box[0]) + dp(24)
    h = dp(30)
    rrect(d, [x, y, x + w, y + h], h / 2, C["control"])
    text(d, (x + dp(12), y + dp(6)), label, f, C["text2"])
    # caret
    cx = x + w - dp(12)
    d.polygon([(cx - dp(4), y + dp(13)), (cx + dp(4), y + dp(13)), (cx, y + dp(17))], fill=C["text3"])
    return x + w


def pill(d, x, y, label, bg=None, fg=None, size=13, pad=20):
    bg = bg or C["pill_bg"]
    fg = fg or C["pill_fg"]
    f = font(size, True)
    box = d.textbbox((0, 0), label, font=f)
    w = (box[2] - box[0]) + dp(pad) * 2
    h = dp(size + 13)
    rrect(d, [x, y, x + w, y + h], h / 2, bg)
    text(d, (x + dp(pad), y + dp(6)), label, f, fg)
    return w, h


def money(d, x, y, amount, size=34, color=None):
    f = font(size, True)
    text(d, (x, y), amount, f, color or C["text"])
    box = d.textbbox((0, 0), amount, font=f)
    return x + (box[2] - box[0])


def card(d, y, height, radius=22):
    rrect(d, [dp(16), y, W - dp(16), y + height], dp(radius), C["card"])
    return y + height


def page_dashboard(d, y0):
    x = dp(16)
    y = y0 + dp(14)
    text(d, (x, y), "用量信息", font(26, True), C["text"])
    y += dp(38)
    f = font(12)
    text(d, (x, y), "所有日期均按 GMT+8 时间显示，数据可能有 5 分钟", f, C["text3"])
    text(d, (x, y + dp(17)), "延迟。", f, C["text3"])
    y += dp(44)

    # balance card
    top = y
    rrect(d, [x, top, W - x, top + dp(158)], dp(22), C["card"])
    cy = top + dp(18)
    text(d, (x + dp(18), cy), "充值余额", font(14), C["text2"])
    lx = x + dp(18) + dp(60)
    d.ellipse([lx, cy + dp(3), lx + dp(11), cy + dp(14)], outline=C["text3"], width=1)
    text(d, (lx + dp(3.6), cy + dp(1)), "?", font(8, True), C["text3"])
    bx = lx + dp(18)
    bf = font(10)
    bw = d.textbbox((0, 0), "余额预警已开启", font=bf)
    rrect(d, [bx, cy - dp(2), bx + (bw[2] - bw[0]) + dp(16), cy + dp(18)], dp(8), C["badge_bg"])
    text(d, (bx + dp(8), cy), "余额预警已开启", bf, C["badge_fg"])
    text(d, (bx + (bw[2] - bw[0]) + dp(22), cy), "去设置", font(10), C["text3"])
    my = top + dp(58)
    mx = money(d, x + dp(18), my, "¥44.68", 34)
    text(d, (mx + dp(6), my + dp(22)), "CNY", font(15), C["text3"])
    pill(d, W - x - dp(18) - dp(104), my + dp(6), "去充值", size=13)
    text(d, (x + dp(18), top + dp(122)), "余额可用：是", font(11), C["text3"])
    y = top + dp(158) + dp(12)

    # cumulative
    rrect(d, [x, y, W - x, y + dp(120)], dp(22), C["card"])
    text(d, (x + dp(18), y + dp(18)), "累计消费金额", font(14), C["text2"])
    mx = money(d, x + dp(18), y + dp(48), "¥385.88", 32)
    text(d, (mx + dp(6), y + dp(68)), "CNY", font(15), C["text3"])
    text(d, (x + dp(18), y + dp(93)), "来自平台用量接口，跨月累计", font(11), C["text3"])
    y += dp(120) + dp(12)

    # granted
    rrect(d, [x, y, W - x, y + dp(120)], dp(22), C["card"])
    text(d, (x + dp(18), y + dp(18)), "赠送余额", font(14), C["text2"])
    mx = money(d, x + dp(18), y + dp(48), "¥61.28", 32)
    text(d, (mx + dp(6), y + dp(68)), "CNY", font(15), C["text3"])
    y += dp(120) + dp(12)

    # source
    rrect(d, [x, y, W - x, y + dp(96)], dp(22), C["card"])
    text(d, (x + dp(18), y + dp(16)), "数据来源：官方余额接口 + 平台用量接口", font(12), C["text2"])
    text(d, (x + dp(18), y + dp(40)), "最近刷新：2月4日 09:59:12", font(12), C["text3"])
    text(d, (x + dp(18), y + dp(64)), "本机采样：128 条（用于「用量」页逐时曲线）", font(12), C["text3"])
    return y + dp(96)


def stat_card(d, x, y, w, h, label, value, unit=None):
    rrect(d, [x, y, x + w, y + h], dp(16), C["card_alt"])
    text(d, (x + dp(12), y + dp(10)), label, font(11), C["text3"])
    vf = font(19, True)
    vy = y + h - dp(19) - dp(10)
    text(d, (x + dp(12), vy), value, vf, C["text"])
    if unit:
        box = d.textbbox((0, 0), value, font=vf)
        text(d, (x + dp(12) + (box[2] - box[0]) + dp(4), vy + dp(8)), unit, font(10), C["text3"])


def axis_label(v, unit):
    if unit == "money":
        return "%.2f" % v
    if v >= 1_000_000:
        return ("%.1fM" % (v / 1e6)).replace(".0M", "M")
    if v >= 1000:
        return ("%.1fK" % (v / 1e3)).replace(".0K", "K")
    return "%d" % round(v)


def chart(d, x, y, w, h, title, value, series, ticks, maxv, unit="money", note=None, segment=False):
    rrect(d, [x, y, x + w, y + h], dp(22), C["card"])
    px = x + dp(12)
    py = y + dp(10)
    text(d, (px, py), title, font(12), C["text2"])
    box = d.textbbox((0, 0), title, font=font(12))
    text(d, (px + (box[2] - box[0]) + dp(6), py), value, font(12, True), C["text"])
    if segment:
        sw = dp(96)
        sx = x + w - dp(12) - sw
        rrect(d, [sx, py - dp(3), sx + sw, py + dp(23)], dp(13), C["control"])
        half = sw / 2
        rrect(d, [sx + dp(2), py - dp(1), sx + half, py + dp(21)], dp(11), C["seg_on"])
        center_text(d, sx + half / 2, py + dp(2), "模型", font(11, True), C["text"])
        center_text(d, sx + half + half / 2, py + dp(2), "合计", font(11), C["text2"])
    elif note:
        nb = d.textbbox((0, 0), note, font=font(11))
        text(d, (x + w - dp(12) - (nb[2] - nb[0]), py + dp(1)), note, font(11), C["text3"])

    # plot area
    pl, pr = px + dp(30), x + w - dp(12)
    pt, pb = py + dp(26), y + h - dp(20)
    f_lab = font(10)
    for i, frac in enumerate((0, 0.5, 1)):
        gy = pb - (pb - pt) * frac
        d.line([pl, gy, pr, gy], fill=C["grid"], width=1)
        lab = axis_label(maxv * frac, unit)
        text(d, (pl - dp(4) - d.textbbox((0, 0), lab, font=f_lab)[2], gy - dp(6)), lab, f_lab, C["text3"])
    n = len(series[0]) if series else 0
    if n:
        slot = (pr - pl) / n
        for i in range(n):
            vals = [s[i] for s in series]
            total = sum(vals)
            if total <= 0:
                continue
            bw = min(slot * 0.62 / max(1, len([v for v in vals if v > 0])), dp(13))
            gap = dp(1.5) if len(vals) > 1 else 0
            groupw = bw * len(vals) + gap * (len(vals) - 1)
            gx = pl + slot * i + (slot - groupw) / 2
            for si, v in enumerate(vals):
                if v <= 0:
                    continue
                bh = (pb - pt) * (v / maxv)
                col = [C["chart"], C["chart2"], C["chart3"]][si % 3]
                bx = gx + si * (bw + gap)
                rrect(d, [bx, pb - bh, bx + bw, pb], min(bw / 2, dp(6)), col)
    for i, lab in enumerate(ticks):
        if not lab:
            continue
        slot = (pr - pl) / n
        cx = pl + slot * i + slot / 2
        b = d.textbbox((0, 0), lab, font=f_lab)
        text(d, (cx - (b[2] - b[0]) / 2, y + h - dp(17)), lab, f_lab, C["text3"])


def page_usage(d, y0):
    x = dp(12)
    y = y0 + dp(12)
    nx = chip(d, x, y, "时间维度 · 今天")
    nx = chip(d, nx + dp(8), y, "API Key · 全部")
    pill(d, W - dp(12) - dp(62), y + dp(1), "导出", size=12, pad=16)
    y += dp(30) + dp(8)
    text(d, (x, y), "今日 ¥0.42 · 46 次请求 · 本机采样 8 次", font(11), C["text3"])
    text(d, (W - dp(12) - dp(66), y), "清除筛选条件", font(11), C["text3"])
    y += dp(22)

    cw = (W - dp(12) * 2 - dp(8)) / 2
    stat_card(d, x, y, cw, dp(58), "消费金额（CNY）", "¥0.42", "CNY")
    stat_card(d, x + cw + dp(8), y, cw, dp(58), "API 请求次数", "46")
    y += dp(58) + dp(8)
    stat_card(d, x, y, W - dp(12) * 2, dp(58), "Tokens", "1,025,644")
    y += dp(58) + dp(6)

    avail = (H - dp(58) - dp(12)) - y - dp(12)
    chart_h = avail / 3

    hours = [0] * 24
    for h, v in ((1, 0.02), (3, 0.004), (4, 0.004), (5, 0.004), (7, 0.19), (8, 0.21)):
        hours[h] = v
    ticks = {0: "00:00", 6: "06:00", 12: "12:00", 18: "18:00", 23: "23:00"}
    tick_labels = [ticks.get(i) for i in range(24)]
    reqs = [0] * 24
    reqs[7], reqs[8] = 20, 26
    toks = [0] * 24
    toks[7], toks[8] = 640000, 385644

    chart(d, x, y, W - dp(24), chart_h, "消费金额（CNY）", "¥0.42", [hours], tick_labels,
          0.28, segment=True)
    y += chart_h + dp(6)
    chart(d, x, y, W - dp(24), chart_h, "API 请求次数", "46", [reqs], tick_labels, 40.0,
          unit="count", note="逐时（本机采样）")
    y += chart_h + dp(6)
    chart(d, x, y, W - dp(24), chart_h, "Tokens", "1,025,644", [toks], tick_labels, 800000.0,
          unit="count", note="deepseek-chat")


def page_profile(d, y0):
    x = dp(16)
    y = y0 + dp(14)
    w = W - dp(32)
    rrect(d, [x, y, x + w, y + dp(100)], dp(22), C["card"])
    logo = Image.open(os.path.join(RES, "drawable-nodpi", "logo_mark.webp")).convert("RGBA")
    logo = logo.resize((dp(64), dp(64)), Image.LANCZOS)
    mask = Image.new("L", logo.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, logo.size[0] - 1, logo.size[1] - 1], fill=255)
    d._image.paste(logo, (x + dp(18), y + dp(18)), mask)
    text(d, (x + dp(18) + dp(64) + dp(14), y + dp(22)), "曲奇", font(17, True), C["text"])
    text(d, (x + dp(18) + dp(64) + dp(14), y + dp(50)), "更换头像", font(12), C["accent"])
    pw, ph = pill(d, x + w - dp(18) - dp(58), y + dp(34), "保存", size=12, pad=16)
    y += dp(100) + dp(12)

    rrect(d, [x, y, x + w, y + dp(268)], dp(22), C["card"])
    text(d, (x + dp(18), y + dp(16)), "API 配置", font(15, True), C["text"])
    text(d, (x + dp(18), y + dp(48)), "DeepSeek API Key（查询余额）", font(12), C["text2"])
    rrect(d, [x + dp(18), y + dp(68), x + w - dp(18), y + dp(112)], dp(14), C["card_alt"], C["divider"])
    text(d, (x + dp(32), y + dp(84)), "sk-••••••••••••••••••", font(14), C["text3"])
    d.ellipse([x + w - dp(50), y + dp(83), x + w - dp(34), y + dp(99)], outline=C["text3"], width=1)
    text(d, (x + dp(18), y + dp(126)), "平台 User Token（查询用量明细）", font(12), C["text2"])
    rrect(d, [x + dp(18), y + dp(146), x + w - dp(18), y + dp(190)], dp(14), C["card_alt"], C["divider"])
    text(d, (x + dp(32), y + dp(162)), "粘贴 platform.deepseek.com 的 Bearer …", font(13), C["text3"])
    text(d, (x + dp(18), y + dp(198)), "获取方式：浏览器登录平台 → 用量页 → F12 → Network", font(11), C["text3"])
    text(d, (x + dp(18), y + dp(214)), "→ 复制 usage/cost 请求里的 Bearer Token。", font(11), C["text3"])
    pill(d, x + dp(18), y + dp(232), "保存", size=13, pad=20)
    pill(d, x + dp(18) + dp(78), y + dp(232), "测试连接", size=13, pad=18, bg=C["control"], fg=C["text2"])
    y += dp(268) + dp(12)

    rrect(d, [x, y, x + w, y + dp(190)], dp(22), C["card"])
    text(d, (x + dp(18), y + dp(16)), "偏好设置", font(15, True), C["text"])
    text(d, (x + dp(18), y + dp(50)), "外观模式", font(13), C["text2"])
    sw = dp(168)
    sx = x + w - dp(18) - sw
    rrect(d, [sx, y + dp(44), sx + sw, y + dp(74)], dp(15), C["control"])
    seg_w = sw / 3
    rrect(d, [sx + seg_w * 1 + dp(2), y + dp(46), sx + seg_w * 2 - dp(2), y + dp(72)], dp(13), C["seg_on"])
    for i, lab in enumerate(("日间", "夜间", "跟随系统")):
        center_text(d, sx + seg_w * i + seg_w / 2, y + dp(52), lab, font(11, i == 1),
                    C["text"] if i == 1 else C["text2"])
    d.line([x + dp(18), y + dp(88), x + w - dp(18), y + dp(88)], fill=C["divider"])
    text(d, (x + dp(18), y + dp(102)), "余额预警", font(13), C["text2"])
    text(d, (x + dp(18), y + dp(122)), "预警阈值（CNY）", font(11), C["text3"])
    rrect(d, [x + w - dp(18) - dp(86) - dp(60), y + dp(104), x + w - dp(18) - dp(60), y + dp(140)], dp(14),
          C["card_alt"], C["divider"])
    center_text(d, x + w - dp(18) - dp(73), y + dp(112), "10", font(13), C["text"])
    rrect(d, [x + w - dp(18) - dp(44), y + dp(112), x + w - dp(18), y + dp(134)], dp(11), C["accent"])
    d.ellipse([x + w - dp(18) - dp(22), y + dp(113), x + w - dp(18) - dp(2), y + dp(133)], fill=(255, 255, 255))
    d.line([x + dp(18), y + dp(154), x + w - dp(18), y + dp(154)], fill=C["divider"])
    text(d, (x + dp(18), y + dp(166)), "后台每小时采样", font(13), C["text2"])


def render(page, path):
    img = Image.new("RGB", (W, H), C["bg"])
    d = ImageDraw.Draw(img)
    d._image = img
    status = dp(24)
    y = top_bar(d, status, reload=page != "profile")
    bottom_nav(d, H - dp(58))
    if page == "dashboard":
        page_dashboard(d, y)
    elif page == "usage":
        page_usage(d, y)
    else:
        page_profile(d, y)
    status_bar(d)
    img.save(path)
    print("saved", path, img.size)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    render("dashboard", os.path.join(OUT, "preview-dashboard.png"))
    render("usage", os.path.join(OUT, "preview-usage.png"))
    render("profile", os.path.join(OUT, "preview-profile.png"))
