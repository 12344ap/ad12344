"""生成 FloatKit 启动图标（各密度 PNG）。

用法：python tools/make_icons.py
需要 Pillow。
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

MASTER = 1024
TOP = (37, 99, 235)      # #2563EB
BOTTOM = (59, 130, 246)  # #3B82F6


def gradient_bg(size):
    img = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        color = (
            int(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
            int(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
            int(TOP[2] + (BOTTOM[2] - TOP[2]) * t),
            255,
        )
        draw.line([(0, y), (size, y)], fill=color)
    return img


def draw_glyph(img):
    size = img.size[0]
    draw = ImageDraw.Draw(img)
    cx, cy = size * 0.5, size * 0.47
    r = size * 0.265
    # 白色对话气泡
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
    # 气泡小尾巴
    draw.polygon(
        [
            (cx - r * 0.62, cy + r * 0.72),
            (cx - r * 0.42, cy + r * 1.24),
            (cx + r * 0.02, cy + r * 0.90),
        ],
        fill=(255, 255, 255, 255),
    )
    # 三个蓝色圆点
    dot = size * 0.034
    for offset in (-0.33, 0.0, 0.33):
        dx = cx + r * offset
        draw.ellipse([dx - dot, cy - dot, dx + dot, cy + dot], fill=TOP + (255,))


def rounded_mask(size, radius_ratio=0.225):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    r = int(size * radius_ratio)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return mask


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def build_master():
    img = gradient_bg(MASTER)
    draw_glyph(img)
    return img


def main():
    master = build_master()
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in sizes.items():
        target_dir = os.path.join(RES, folder)
        os.makedirs(target_dir, exist_ok=True)

        scaled = master.resize((px, px), Image.LANCZOS)

        square = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        square.paste(scaled, (0, 0), rounded_mask(px))
        square.save(os.path.join(target_dir, "ic_launcher.png"))

        rounded = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rounded.paste(scaled, (0, 0), circle_mask(px))
        rounded.save(os.path.join(target_dir, "ic_launcher_round.png"))

        print("generated", folder, px)
    print("done")


if __name__ == "__main__":
    sys.exit(main())
