"""Render a static mockup of the proposed tool-call status UI (report artifact, not product code)."""
from PIL import Image, ImageDraw, ImageFont

W, H = 900, 820
BG = (245, 247, 251)
CARD = (255, 255, 255)
LINE = (229, 234, 243)
INK = (31, 41, 55)
TITLE = (36, 52, 73)
RAW = (154, 168, 187)
MUTED = (107, 122, 144)
THOUGHT = (75, 90, 110)
PANEL = (248, 249, 252)
CHIP_BG = (238, 242, 255)
CHIP_INK = (30, 64, 175)
OK = (21, 128, 61)
OK_BG = (236, 253, 243)
RUN = (29, 78, 216)
RUN_BG = (238, 244, 255)
QUEUE = (180, 83, 9)
QUEUE_BG = (255, 247, 232)

REG = "C:/Windows/Fonts/msyh.ttc"
BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def f(size, bold=False):
    return ImageFont.truetype(BOLD if bold else REG, size)


F13B, F13, F12, F12B, F11, F11B, F10 = f(13, True), f(13), f(12), f(12, True), f(11), f(11, True), f(10)


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def spinner(d, cx, cy, color, r=4):
    d.arc((cx - r, cy - r, cx + r, cy + r), start=-70, end=190, fill=color, width=2)


def pill(d, right, cy, text, fg, bg, kind):
    tw = d.textlength(text, font=F11B)
    lead = 14 if kind in ("done", "queued", "running", "notstarted", "failed") else 0
    w = int(tw + 18 + lead)
    x0 = right - w
    rr(d, (x0, cy - 11, right, cy + 11), 11, bg)
    cx = x0 + 9
    if kind == "done":
        d.line((cx, cy + 1, cx + 3, cy + 4), fill=fg, width=2)
        d.line((cx + 3, cy + 4, cx + 8, cy - 4), fill=fg, width=2)
    elif kind == "queued":
        d.ellipse((cx, cy - 3, cx + 6, cy + 3), fill=fg)
    elif kind == "notstarted":
        d.ellipse((cx, cy - 3, cx + 6, cy + 3), fill=fg)
    elif kind in ("running", "resource"):
        spinner(d, cx + 3, cy, fg)
    d.text((x0 + 9 + lead, cy), text, font=F11B, fill=fg, anchor="lm")


def draw():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    d.text((44, 22), "绘画页 · 工具调用状态草案（静态示意，非真实页面）", font=f(12), fill=MUTED)

    # 主卡片
    rr(d, (40, 52, 860, 664), 16, CARD, LINE)

    # 用户消息
    d.ellipse((64, 84, 94, 114), fill=(219, 234, 254))
    d.text((79, 99), "我", font=F12B, fill=(29, 78, 216), anchor="mm")
    d.text((108, 82), "你", font=F11, fill=MUTED)
    rr(d, (108, 100, 836, 150), 12, (241, 245, 251))
    d.text((122, 112), "画一张雨夜霓虹街道的赛博朋克少女，参考图在附件里，画完直接发我。", font=F13, fill=INK)

    # AI 消息
    d.ellipse((64, 168, 94, 198), fill=(224, 231, 255))
    d.text((79, 183), "AI", font=F12B, fill=(55, 48, 163), anchor="mm")
    d.text((108, 166), "绘图助手", font=F11, fill=MUTED)

    # 思考链面板
    rr(d, (108, 184, 836, 478), 10, PANEL)
    d.rounded_rectangle((108, 184, 111, 478), radius=1, fill=(148, 163, 184))
    d.text((122, 196), "思考过程", font=F10, fill=(124, 138, 160))
    d.text((122, 216), "先确认角色设定和参考图，然后生成图片，最后把成图发出去。", font=F12, fill=THOUGHT)

    def tool_row(y, h, icon, title, raw, sub, state_text, state_fg, state_bg, kind, progress=None):
        rr(d, (120, y, 824, y + h), 10, CARD, LINE)
        # 图标块
        rr(d, (131, y + 12, 153, y + 34), 6, CHIP_BG)
        cx, cy = 142, y + 23
        if icon == "search":
            d.ellipse((cx - 6, cy - 6, cx + 2, cy + 2), outline=CHIP_INK, width=2)
            d.line((cx + 1, cy + 1, cx + 6, cy + 6), fill=CHIP_INK, width=2)
        elif icon == "image":
            d.rounded_rectangle((cx - 6, cy - 5, cx + 6, cy + 5), radius=2, outline=CHIP_INK, width=2)
            d.ellipse((cx - 4, cy - 3, cx - 1, cy), fill=CHIP_INK)
            d.line((cx - 5, cy + 4, cx - 1, cy, cx + 2, cy + 3, cx + 5, cy - 1), fill=CHIP_INK, width=2)
        else:
            d.line((cx - 6, cy + 6, cx + 5, cy - 5), fill=CHIP_INK, width=2)
            d.line((cx - 1, cy - 6, cx + 6, cy - 6, cx + 6, cy + 1), fill=CHIP_INK, width=2)
        # 标题 + 原始工具名
        d.text((165, y + 11), title, font=F13B, fill=TITLE)
        tw = d.textlength(title, font=F13B)
        d.text((165 + tw + 7, y + 13), raw, font=F10, fill=RAW)
        d.text((165, y + 30), sub, font=F11, fill=MUTED)
        if state_text:
            pill(d, 812, y + 24, state_text, state_fg, state_bg, kind)
        if progress is not None:
            rr(d, (165, y + 60, 770, y + 65), 2, (226, 232, 240))
            rr(d, (165, y + 60, 165 + int((770 - 165) * progress), y + 65), 2, (37, 99, 235))
            d.text((780, y + 62), str(int(progress * 100)) + "%", font=F11B, fill=RUN, anchor="lm")

    tool_row(244, 54, "search", "搜索角色设定", "search_character", "用时 1.8 秒",
             "", OK, OK_BG, "done")
    tool_row(306, 86, "image", "生成图片", "sdxl_gen_image", "已下发 ComfyUI · 第 8/20 步",
             "执行中", RUN, RUN_BG, "running", progress=0.38)
    tool_row(400, 54, "send", "发送图片", "send_image", "等待前一个工具执行完成",
             "", QUEUE, QUEUE_BG, "queued")

    # 正文
    d.text((108, 490), "正在生成，马上就好", font=F13, fill=INK)
    d.rectangle((228, 492, 235, 507), fill=(148, 163, 184))

    # 输入区
    d.line((64, 536, 836, 536), fill=LINE)
    rr(d, (64, 548, 836, 584), 9, (241, 246, 255), (219, 231, 255))
    spinner(d, 79, 566, RUN, r=4)
    x = 92
    for part, bold in (("1", True), (" 个执行中", False), ("  ·  ", False),
                       ("生成图片 38%", False)):
        d.text((x, 566), part, font=(F12B if bold else F12), fill=(67, 83, 107), anchor="lm")
        x += d.textlength(part, font=(F12B if bold else F12))
    d.text((826, 566), "00:14", font=F12, fill=MUTED, anchor="rm")

    rr(d, (64, 594, 836, 648), 12, CARD, LINE)
    d.text((78, 621), "描述画面，或继续补充要求…", font=F13, fill=(163, 176, 194), anchor="lm")
    rr(d, (744, 604, 824, 638), 9, (37, 99, 235))
    d.text((784, 621), "发送", font=F12B, fill=CARD, anchor="mm")

    # 图例
    rr(d, (40, 680, 860, 790), 12, CARD, LINE)
    d.text((56, 692), "状态语义（最终约定）", font=F12, fill=MUTED)
    items = [
        ("排队中", QUEUE, QUEUE_BG, "等待本地模型资源（P2，连接器 queued）"),
        ("执行中", RUN, RUN_BG, "有真实进度用真实值；没有则按 ETA 每 10% 推进到 90%"),
    ]
    y = 718
    for text, fg, bg, desc in items:
        pill(d, 168, y, text, fg, bg, {"排队中": "queued", "执行中": "running"}[text])
        d.text((186, y), desc, font=F11, fill=(67, 83, 107), anchor="lm")
        y += 30
    d.text((56, 770), "未开始/完成不显示；终态只用于清掉状态。", font=F10, fill=(139, 152, 171))

    img.save("I:/modding-workspace/AIWuxia/reports/tool-call-status-mockup.png", "PNG")
    print("saved")


if __name__ == "__main__":
    draw()

