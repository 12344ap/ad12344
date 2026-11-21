"""生成 FloatKit 设计预览图（模拟真机效果，非真实截图）。

用法：python tools/make_preview.py
输出：docs/preview.png
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs")

W, H = 1080, 2160
DP = 3  # 1dp = 3px（360dp 逻辑宽度）

ACCENT = (59, 130, 246)
WHITE = (255, 255, 255)


def dp(v):
    return int(v * DP)


def font(size, bold=False):
    candidates = [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        r"C:\Windows\Fonts\simsun.ttc",
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, index=0)
            except Exception:
                continue
    raise SystemExit("找不到中文字体，请修改 font() 中的路径")


def vertical_gradient(size, top, bottom):
    img = Image.new("RGB", size)
    draw = ImageDraw.Draw(img)
    for y in range(size[1]):
        t = y / max(1, size[1] - 1)
        draw.line(
            [(0, y), (size[0], y)],
            fill=(
                int(top[0] + (bottom[0] - top[0]) * t),
                int(top[1] + (bottom[1] - top[1]) * t),
                int(top[2] + (bottom[2] - top[2]) * t),
            ),
        )
    return img


def soft_circle(img, center, radius, color, blur):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse(
        [center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius],
        fill=color,
    )
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    img.alpha_composite(layer)


def rounded_shadow(img, box, radius, blur, offset, color=(0, 0, 0, 140)):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(
        [box[0] + offset[0], box[1] + offset[1], box[2] + offset[0], box[3] + offset[1]],
        radius=radius,
        fill=color,
    )
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    img.alpha_composite(layer)


# ----------------------------------------------------------------------
# 图标（在 100x100 的局部坐标系里绘制，再缩放到目标尺寸）
# ----------------------------------------------------------------------
def draw_back(d, cx, cy, s):
    w = s
    d.line([(cx - w * 0.32, cy), (cx + w * 0.36, cy)], fill=WHITE, width=max(2, int(s * 0.11)))
    d.line([(cx - w * 0.32, cy), (cx - w * 0.02, cy - w * 0.3)], fill=WHITE, width=max(2, int(s * 0.11)))
    d.line([(cx - w * 0.32, cy), (cx - w * 0.02, cy + w * 0.3)], fill=WHITE, width=max(2, int(s * 0.11)))


def draw_home(d, cx, cy, s):
    w = s
    d.polygon(
        [(cx, cy - w * 0.36), (cx - w * 0.42, cy + w * 0.02), (cx + w * 0.42, cy + w * 0.02)],
        fill=WHITE,
    )
    d.rectangle(
        [cx - w * 0.28, cy + w * 0.02, cx + w * 0.28, cy + w * 0.34], fill=WHITE,
    )
    d.rectangle(
        [cx - w * 0.09, cy + w * 0.14, cx + w * 0.09, cy + w * 0.34],
        fill=ACCENT,
    )


def draw_recents(d, cx, cy, s):
    w = s
    tile = w * 0.30
    gap = w * 0.10
    for dx in (-(tile + gap) / 2.0, (tile + gap) / 2.0):
        for dy in (-(tile + gap) / 2.0, (tile + gap) / 2.0):
            d.rounded_rectangle(
                [cx + dx - tile / 2, cy + dy - tile / 2,
                 cx + dx + tile / 2, cy + dy + tile / 2],
                radius=int(w * 0.07),
                fill=WHITE,
            )


def draw_screenshot(d, cx, cy, s):
    w = s * 0.42
    t = max(2, int(s * 0.1))
    corners = [
        (cx - w, cy - w, 1, 1),
        (cx + w, cy - w, -1, 1),
        (cx - w, cy + w, 1, -1),
        (cx + w, cy + w, -1, -1),
    ]
    for x, y, sx, sy in corners:
        d.line([(x, y), (x + sx * w * 0.55, y)], fill=WHITE, width=t)
        d.line([(x, y), (x, y + sy * w * 0.55)], fill=WHITE, width=t)


def draw_record(d, cx, cy, s):
    r = s * 0.34
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(248, 113, 113))


def draw_lock(d, cx, cy, s):
    w = s * 0.34
    # 先画锁梁，再画锁体盖住下半部分
    d.arc(
        [cx - w * 0.62, cy - w * 1.62, cx + w * 0.62, cy - w * 0.38],
        start=180,
        end=360,
        fill=WHITE,
        width=max(2, int(s * 0.1)),
    )
    body_top = cy - w * 0.35
    d.rounded_rectangle(
        [cx - w, body_top, cx + w, cy + w * 1.05],
        radius=int(w * 0.32),
        fill=WHITE,
    )
    d.ellipse(
        [cx - w * 0.16, cy - w * 0.02, cx + w * 0.16, cy + w * 0.32],
        fill=ACCENT,
    )


def draw_settings(d, cx, cy, s):
    r = s * 0.2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=WHITE, width=max(2, int(s * 0.09)))
    for i in range(8):
        a = math.radians(i * 45)
        x1, y1 = cx + math.cos(a) * r * 1.25, cy + math.sin(a) * r * 1.25
        x2, y2 = cx + math.cos(a) * r * 1.7, cy + math.sin(a) * r * 1.7
        d.line([(x1, y1), (x2, y2)], fill=WHITE, width=max(2, int(s * 0.09)))


def draw_close(d, cx, cy, s):
    w = s * 0.22
    t = max(2, int(s * 0.1))
    d.line([(cx - w, cy - w), (cx + w, cy + w)], fill=WHITE, width=t)
    d.line([(cx - w, cy + w), (cx + w, cy - w)], fill=WHITE, width=t)


def draw_bubble_glyph(d, cx, cy, s):
    r = s * 0.3
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)
    d.polygon(
        [
            (cx - r * 0.6, cy + r * 0.7),
            (cx - r * 0.42, cy + r * 1.3),
            (cx + r * 0.02, cy + r * 0.95),
        ],
        fill=WHITE,
    )
    dot = s * 0.045
    for off in (-0.32, 0.0, 0.32):
        dx = cx + r * off
        d.ellipse([dx - dot, cy - dot, dx + dot, cy + dot], fill=ACCENT)


ICONS = {
    "返回": draw_back,
    "主页": draw_home,
    "多任务": draw_recents,
    "截屏": draw_screenshot,
    "录屏": draw_record,
    "锁屏": draw_lock,
}


def status_bar(img):
    d = ImageDraw.Draw(img)
    f = font(38, bold=True)
    d.text((dp(24), dp(16)), "9:41", font=f, fill=WHITE)
    # 信号
    x = W - dp(150)
    for i in range(4):
        h = dp(6 + i * 4)
        d.rounded_rectangle(
            [x + i * dp(9), dp(26) - h, x + i * dp(9) + dp(6), dp(26)],
            radius=dp(2),
            fill=WHITE,
        )
    # 电池
    bx = W - dp(64)
    d.rounded_rectangle([bx, dp(14), bx + dp(38), dp(30)], radius=dp(6), outline=WHITE, width=dp(2))
    d.rounded_rectangle([bx + dp(4), dp(18), bx + dp(30), dp(26)], radius=dp(3), fill=WHITE)
    d.rectangle([bx + dp(40), dp(19), bx + dp(43), dp(25)], fill=WHITE)
    # wifi
    wx, wy = W - dp(96), dp(22)
    d.arc([wx - dp(12), wy - dp(14), wx + dp(12), wy + dp(10)], start=200, end=340,
          fill=WHITE, width=dp(2))
    d.ellipse([wx - dp(2), wy + dp(3), wx + dp(2), wy + dp(7)], fill=WHITE)


def home_screen():
    img = vertical_gradient((W, H), (9, 14, 24), (19, 30, 48)).convert("RGBA")
    soft_circle(img, (int(W * 0.15), int(H * 0.18)), dp(190), (37, 99, 235, 70), 90)
    soft_circle(img, (int(W * 0.9), int(H * 0.8)), dp(210), (99, 102, 241, 55), 110)

    d = ImageDraw.Draw(img)
    clock = font(190, bold=True)
    d.text((W // 2, dp(200)), "9:41", font=clock, fill=(245, 248, 252), anchor="mm")
    sub = font(40)
    d.text((W // 2, dp(296)), "11月21日  星期五", font=sub, fill=(150, 168, 192), anchor="mm")

    # 底部 Dock（示意）
    dock_y = H - dp(120)
    d.rounded_rectangle(
        [dp(40), dock_y, W - dp(40), H - dp(40)],
        radius=dp(40),
        fill=(40, 50, 68),
    )
    colors = [(239, 68, 68), (34, 197, 94), (234, 179, 8), (168, 85, 247)]
    for i, color in enumerate(colors):
        cx = dp(96) + i * dp(88)
        d.rounded_rectangle(
            [cx - dp(26), dock_y + dp(20), cx + dp(26), dock_y + dp(72)],
            radius=dp(16),
            fill=color,
        )
    status_bar(img)
    return img


def floating_panel(img):
    bubble_size = dp(56)
    bubble_x = W - bubble_size - dp(8)
    bubble_y = int(H * 0.50)

    panel_w = dp(232)
    cell_w = panel_w // 3
    header_h = dp(34)
    cell_h = dp(74)
    panel_h = dp(6) + header_h + cell_h * 2 + dp(10)
    gap = dp(8)
    panel_x = bubble_x - panel_w - gap
    panel_y = bubble_y + bubble_size // 2 - panel_h // 2

    rounded_shadow(img, (panel_x, panel_y, panel_x + panel_w, panel_y + panel_h),
                   dp(20), blur=28, offset=(0, 10), color=(0, 0, 0, 170))
    rounded_shadow(img, (bubble_x, bubble_y, bubble_x + bubble_size, bubble_y + bubble_size),
                   bubble_size // 2, blur=22, offset=(0, 6), color=(0, 0, 0, 170))

    d = ImageDraw.Draw(img)

    # 面板背景
    d.rounded_rectangle(
        [panel_x, panel_y, panel_x + panel_w, panel_y + panel_h],
        radius=dp(20),
        fill=(24, 27, 34),
        outline=(66, 68, 74),
        width=dp(1),
    )

    # 顶部栏
    hy = panel_y + dp(6) + header_h // 2
    d.rounded_rectangle(
        [panel_x + dp(12), hy - dp(6), panel_x + dp(24), hy - dp(4)],
        radius=dp(1), fill=(112, 120, 132),
    )
    d.rounded_rectangle(
        [panel_x + dp(12), hy + dp(2), panel_x + dp(24), hy + dp(4)],
        radius=dp(1), fill=(112, 120, 132),
    )
    f_title = font(dp(11))
    d.text((panel_x + dp(30), hy), "悬浮菜单", font=f_title,
           fill=(154, 167, 184), anchor="lm")
    draw_settings(d, panel_x + panel_w - dp(40), hy, dp(18))
    draw_close(d, panel_x + panel_w - dp(16), hy, dp(18))

    # 六个功能按钮
    labels = list(ICONS.keys())
    body_top = panel_y + dp(6) + header_h
    for index, label in enumerate(labels):
        row, col = divmod(index, 3)
        cx = panel_x + cell_w * col + cell_w // 2
        cell_top = body_top + cell_h * row
        circle_cy = cell_top + dp(6) + dp(22)
        r = dp(22)
        d.ellipse([cx - r, circle_cy - r, cx + r, circle_cy + r],
                  fill=(54, 57, 63), outline=(90, 93, 98), width=dp(1))
        ICONS[label](d, cx, circle_cy, dp(22))
        f_label = font(dp(11))
        d.text((cx, circle_cy + dp(32)), label, font=f_label,
               fill=(185, 196, 211), anchor="mm")

    # 悬浮球
    d.ellipse([bubble_x, bubble_y, bubble_x + bubble_size, bubble_y + bubble_size],
              fill=(59, 130, 246), outline=(137, 180, 251), width=dp(2))
    draw_bubble_glyph(d, bubble_x + bubble_size // 2, bubble_y + bubble_size // 2, bubble_size)

    f_note = font(dp(11))
    d.text((dp(24), H - dp(18)), "设计预览（非真机截图）", font=f_note,
           fill=(120, 135, 158), anchor="lm")
    return img


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    img = home_screen()
    img = floating_panel(img)
    path = os.path.join(OUT_DIR, "preview.png")
    img.convert("RGB").save(path, quality=95)
    print("saved", path)


if __name__ == "__main__":
    sys.exit(main())
