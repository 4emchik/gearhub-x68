#!/usr/bin/env python3
"""Generate the GearHub X68HE launcher icon set.

The icon is a keyboard-shaped rounded rectangle in off-white with a dark key
grid and the official Attack Shark "A" mark in brand red.

Run from the project root:

    python3 tools/make_icon.py

Requires Pillow (`pkg install python-pillow`).

The mark comes from tools/shark_mark.png (alpha cut-out of the official
wordmark). Attack Shark and its logo are trademarks of their owner; this
icon is fan-made for identification of the keyboard this app configures.
"""

import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "res")

BG = (10, 12, 16, 255)          # near-black deck background
BODY = (244, 246, 250, 255)     # keyboard body
KEY = (20, 24, 32, 255)         # keycaps
RED = (255, 50, 46, 255)        # Attack Shark red (from their logo SVG)
GLOW = (122, 18, 22)            # subtle red glow behind the deck

MARK = Image.open(os.path.join(HERE, "shark_mark.png")).convert("RGBA")


def tinted(color):
    """Recolor the mark while keeping its alpha channel."""
    out = Image.new("RGBA", MARK.size, color)
    out.putalpha(MARK.getchannel("A"))
    return out


def scaled(mark, height):
    w = max(1, round(mark.width * height / mark.height))
    return mark.resize((w, height), Image.LANCZOS)


def add_glow(size, color, strength, center=None):
    """Radial glow layer (RGBA) for the icon background."""
    g = Image.radial_gradient("L").resize((size, size), Image.BILINEAR)
    alpha = g.point(lambda v: max(0, int(strength * (1.0 - v / 255.0) ** 2)))
    layer = Image.new("RGBA", (size, size), color + (0,))
    layer.putalpha(alpha)
    if center is not None:
        shift = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        shift.alpha_composite(layer, (center[0] - size // 2, center[1] - size // 2))
        return shift
    return layer


def draw_keyboard(draw, x0, y0, x1, y1, radius, body=BODY, key=KEY):
    """Rounded keyboard body with two rows of keycaps."""
    draw.rounded_rectangle((x0, y0, x1, y1), radius=radius, fill=body)

    w, h = x1 - x0, y1 - y0
    pad = round(h * 0.10)
    gap = round(h * 0.06)
    key_r = max(1, round(h * 0.05))
    ix0, iy0, ix1, iy1 = x0 + pad, y0 + pad, x1 - pad, y1 - pad
    row_h = (iy1 - iy0 - gap) // 2

    count = 6
    key_w = (ix1 - ix0 - gap * (count - 1)) // count
    for i in range(count):
        kx = ix0 + i * (key_w + gap)
        draw.rounded_rectangle((kx, iy0, kx + key_w, iy0 + row_h), radius=key_r, fill=key)

    wide = round(key_w * 1.6)
    kx = ix0
    draw.rounded_rectangle((kx, iy0 + row_h + gap, kx + wide, iy1), radius=key_r, fill=key)
    space_x1 = ix1 - key_w - gap
    draw.rounded_rectangle(
        (kx + wide + gap, iy0 + row_h + gap, space_x1, iy1), radius=key_r, fill=key)
    draw.rounded_rectangle(
        (space_x1 + gap, iy0 + row_h + gap, ix1, iy1), radius=key_r, fill=key)


def compose(size, keyboard_w, keyboard_h, mark_h, corner_ratio, background):
    """Full icon: background layer + keyboard + mark."""
    img = background.copy()
    d = ImageDraw.Draw(img)

    cx, cy = size // 2, size // 2
    half_w, half_h = keyboard_w // 2, keyboard_h // 2
    radius = round(keyboard_h * corner_ratio)
    draw_keyboard(d, cx - half_w, cy - half_h, cx + half_w, cy + half_h, radius)

    mark = scaled(tinted(RED), mark_h)
    img.alpha_composite(mark, (cx - mark.width // 2, cy - mark.height // 2))
    return img


def rounded_bg(size, corner_ratio):
    """Legacy square icon background with a red glow."""
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=round(size * corner_ratio), fill=255)
    plate = Image.new("RGBA", (size, size), BG)
    plate.alpha_composite(add_glow(size, GLOW, 110, (size // 2, size // 2)))
    bg.paste(plate, (0, 0), mask)
    return bg


def circle_bg(size):
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    plate = Image.new("RGBA", (size, size), BG)
    plate.alpha_composite(add_glow(size, GLOW, 110, (size // 2, size // 2)))
    bg.paste(plate, (0, 0), mask)
    return bg


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def write_adaptive():
    """108 x 108 dp adaptive layers, 4 px per dp."""
    size = 432
    ensure(os.path.join(RES, "drawable-nodpi", "ic_launcher_background.png"))
    ensure(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))

    bg = Image.new("RGBA", (size, size), BG)
    bg.alpha_composite(add_glow(size, GLOW, 130, (size // 2, size // 2)))
    bg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_background.png"))

    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    # 66 x 33 dp, inside the 72 dp adaptive safe viewport.
    draw_keyboard(d, 84, 190, 348, 322, 22)
    mark = scaled(tinted(RED), 86)
    fg.alpha_composite(mark, (size // 2 - mark.width // 2, size // 2 - mark.height // 2))
    fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))

    adaptive = (
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES, "mipmap-anydpi-v26", name)
        ensure(path)
        with open(path, "w", encoding="utf-8") as f:
            f.write(adaptive)


def write_legacy():
    """Pre-API-26 PNG icons, plus the round variant."""
    master = 1024
    square = compose(master, 780, 390, 236, 0.32, rounded_bg(master, 0.22))
    round_icon = compose(master, 760, 380, 230, 0.32, circle_bg(master))

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for folder, px in densities.items():
        for name, src in (("ic_launcher.png", square), ("ic_launcher_round.png", round_icon)):
            path = os.path.join(RES, "mipmap-" + folder, name)
            ensure(path)
            src.resize((px, px), Image.LANCZOS).save(path)


def main():
    write_adaptive()
    write_legacy()
    print("icon set written to", RES)


if __name__ == "__main__":
    main()
