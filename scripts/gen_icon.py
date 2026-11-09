#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""YSPlayer 图标生成器 - 生成所有密度的 mipmap Launcher 图标 (现代风格)"""

import os
from PIL import Image, ImageDraw

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "res")

SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# === 品牌色板 ===
NAVY_DEEP   = (10,  22,  48)    # #0A1630
BLUE_MID    = (30,  64,  175)   # #1E40AF
BLUE_BRIGHT = (37,  99,  235)   # #2563EB
CYAN_ACCENT = (6,   182, 212)   # #06B6D4
WHITE_SOFT  = (245, 247, 250)   # #F5F7FA
WHITE       = (255, 255, 255)


def lerp_color(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def rounded_rect_mask(size, r):
    """Create a rounded rectangle alpha mask"""
    mask = Image.new('L', (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return mask


def draw_gradient_bg(img, size_px, radius):
    """Draw radial gradient background on the image"""
    cx = cy = size_px / 2
    max_dist = size_px * 0.75

    for y in range(size_px):
        for x in range(size_px):
            dx = (x - cx) / max_dist
            dy = (y - cy) / max_dist
            t = min((dx * dx + dy * dy) ** 0.5, 1.0)

            r = int(NAVY_DEEP[0] + (BLUE_MID[0] - NAVY_DEEP[0]) * (1 - t))
            g = int(NAVY_DEEP[1] + (BLUE_MID[1] - NAVY_DEEP[1]) * (1 - t))
            b = int(NAVY_DEEP[2] + (BLUE_BRIGHT[2] - NAVY_DEEP[2]) * (1 - t))

            img.putpixel((x, y), (r, g, b, 255))

    mask = rounded_rect_mask(size_px, radius)
    img.putalpha(mask)


def draw_logo_mark(draw, cx, cy, logo_size):
    """Draw the modern YSPlayer logo mark: geometric Y + play triangle"""
    s = logo_size

    # === Play Triangle (left side) ===
    tri_w = s * 0.32
    tri_h = s * 0.42
    tri_left = cx - s * 0.20 - tri_w
    tri_top = cy - tri_h / 2

    # Rounded play triangle
    p1 = (tri_left, tri_top)
    p2 = (tri_left, tri_top + tri_h)
    p3 = (tri_left + tri_w, cy)

    # Glow behind play triangle
    glow_r = tri_w * 0.9
    for i in range(4, 0, -1):
        alpha = int(25 / i)
        gr = int(glow_r * (0.6 + i * 0.1))
        draw.ellipse([
            p3[0] - gr, cy - gr,
            p3[0] + gr, cy + gr,
        ], fill=(37, 99, 235, alpha))

    draw.polygon([p1, p2, p3], fill=(37, 99, 235, 220))

    # Inner highlight on triangle
    inset = tri_w * 0.10
    ip1 = (p1[0] + inset, p1[1] + inset * 0.7)
    ip2 = (p2[0] + inset, p2[1] - inset * 0.7)
    ip3 = (p3[0] - inset * 1.1, p3[1])
    draw.polygon([ip1, ip2, ip3], fill=CYAN_ACCENT + (180,))

    # === Geometric Y mark (right side) ===
    y_cx = cx + s * 0.12
    y_w = s * 0.09
    y_h = s * 0.52
    angle = 0.35

    # Upper-left arm of Y
    arm_len = y_h * 0.55
    arm_offset_x = arm_len * angle * 0.5
    arm_offset_y = arm_len * (1 - angle * 0.3)

    # Upper-left arm polygon
    ul_top = (y_cx - y_w / 2 - arm_offset_x, cy - y_h * 0.30)
    ul_bot = (y_cx + y_w / 2, cy)
    ul_mid = (y_cx - y_w / 2, cy - y_h * 0.08)
    draw.polygon([
        (y_cx - y_w / 2, cy - y_h * 0.25),
        (y_cx - y_w - arm_offset_x * 0.7, cy - y_h * 0.52),
        (y_cx - y_w * 0.3, cy - y_h * 0.35),
    ], fill=WHITE_SOFT + (245,))
    draw.polygon([
        (y_cx - y_w * 0.3, cy - y_h * 0.35),
        (y_cx - y_w - arm_offset_x * 0.7, cy - y_h * 0.52),
        (y_cx - y_w * 1.1 - arm_offset_x * 0.2, cy - y_h * 0.48),
    ], fill=WHITE_SOFT + (245,))

    # Upper-right arm of Y
    draw.polygon([
        (y_cx + y_w / 2, cy - y_h * 0.25),
        (y_cx + y_w + arm_offset_x * 0.7, cy - y_h * 0.52),
        (y_cx + y_w * 0.3, cy - y_h * 0.35),
    ], fill=WHITE_SOFT + (245,))
    draw.polygon([
        (y_cx + y_w * 0.3, cy - y_h * 0.35),
        (y_cx + y_w + arm_offset_x * 0.7, cy - y_h * 0.52),
        (y_cx + y_w * 1.1 + arm_offset_x * 0.2, cy - y_h * 0.48),
    ], fill=WHITE_SOFT + (245,))

    # Vertical stem of Y
    stem_w = y_w * 0.9
    stem_top = cy - y_h * 0.05
    stem_bot = cy + y_h * 0.45
    draw.rounded_rectangle(
        [y_cx - stem_w / 2, stem_top, y_cx + stem_w / 2, stem_bot],
        radius=int(stem_w / 2), fill=WHITE_SOFT + (245,)
    )

    # Small accent dot at the center junction
    dot_r = y_w * 0.55
    draw.ellipse([
        y_cx - dot_r, cy - dot_r,
        y_cx + dot_r, cy + dot_r,
    ], fill=CYAN_ACCENT + (220,))


def generate_icon(size_px):
    """Generate a single launcher icon at the given size (modern style)"""
    img = Image.new('RGBA', (size_px, size_px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Background: rounded square with gradient
    radius = int(size_px * 0.18)
    draw_gradient_bg(img, size_px, radius)

    # Logo mark
    pad = size_px * 0.20
    logo_size = size_px - pad * 2
    cx = size_px / 2
    cy = size_px / 2
    draw_logo_mark(draw, cx, cy, logo_size)

    return img


def main():
    print("YSPlayer 图标生成器 (现代风格)")
    print("=" * 50)

    for folder, px in sorted(SIZES.items(), key=lambda x: x[1]):
        path = os.path.join(RES_DIR, folder)
        os.makedirs(path, exist_ok=True)
        filepath = os.path.join(path, "ic_launcher.png")
        img = generate_icon(px)
        img.save(filepath, "PNG")
        print(f"  {folder:20s} {px:3d}x{px:<3d}  ->  {filepath}")

    print("=" * 50)
    print("所有图标已生成!")


if __name__ == "__main__":
    main()