#!/usr/bin/env python3
"""Generate the GearHub launcher icon set.

The icon is the official Attack Shark mark in black on a plain white
background, as requested for the combined keyboard + mouse app.

Run from the project root:

    python3 tools/make_icon.py

Requires Pillow (`pkg install python-pillow`).

The mark comes from tools/shark_mark.png (alpha cut-out of the official
wordmark). Attack Shark and its logo are trademarks of their owner; this
icon is fan-made for identification of the devices this app configures.
"""

import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "res")

WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)
TRANSPARENT = (0, 0, 0, 0)

MARK = Image.open(os.path.join(HERE, "shark_mark.png")).convert("RGBA")


def black_mark(height):
    """Scale the alpha cut-out and fill it with solid black."""
    width = max(1, round(MARK.width * height / MARK.height))
    alpha = MARK.getchannel("A").resize((width, height), Image.LANCZOS)
    out = Image.new("RGBA", (width, height), BLACK)
    out.putalpha(alpha)
    return out


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def compose_square(size, mark_height, corner_ratio):
    """White rounded square with the black mark centered."""
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=round(size * corner_ratio), fill=255)
    plate = Image.new("RGBA", (size, size), WHITE)
    img.paste(plate, (0, 0), mask)

    mark = black_mark(mark_height)
    img.alpha_composite(mark, ((size - mark.width) // 2, (size - mark.height) // 2))
    return img


def compose_circle(size, mark_height):
    """White circle with the black mark centered."""
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    plate = Image.new("RGBA", (size, size), WHITE)
    img.paste(plate, (0, 0), mask)

    mark = black_mark(mark_height)
    img.alpha_composite(mark, ((size - mark.width) // 2, (size - mark.height) // 2))
    return img


def write_adaptive():
    """108 x 108 dp adaptive layers, 4 px per dp (432 px)."""
    size = 432
    background_path = os.path.join(RES, "drawable-nodpi", "ic_launcher_background.png")
    foreground_path = os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png")
    ensure(background_path)
    ensure(foreground_path)

    Image.new("RGBA", (size, size), WHITE).save(background_path)

    # Keep the mark comfortably inside the 66 dp adaptive-icon safe circle.
    foreground = Image.new("RGBA", (size, size), TRANSPARENT)
    mark = black_mark(200)
    foreground.alpha_composite(
        mark, ((size - mark.width) // 2, (size - mark.height) // 2))
    foreground.save(foreground_path)

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
    square = compose_square(master, 560, 0.22)
    round_icon = compose_circle(master, 520)

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for folder, px in densities.items():
        for name, src in (("ic_launcher.png", square),
                          ("ic_launcher_round.png", round_icon)):
            path = os.path.join(RES, "mipmap-" + folder, name)
            ensure(path)
            src.resize((px, px), Image.LANCZOS).save(path)


def main():
    write_adaptive()
    write_legacy()
    print("black-on-white icon set written to", RES)


if __name__ == "__main__":
    main()
