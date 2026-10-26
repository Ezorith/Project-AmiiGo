"""
Mockups for the README: screenshots and short clips from a connected phone, plus framed images.

  python tools/mockups.py shot NAME            screenshot -> docs/mockups/raw/NAME.png
  python tools/mockups.py clip NAME SECONDS    screen recording -> docs/mockups/raw/NAME.mp4
  python tools/mockups.py frame                every raw PNG -> docs/mockups/NAME.png (rounded, on a dark card)
  python tools/mockups.py strip A B C OUT      raw PNGs side by side -> docs/mockups/OUT.png

Set ADB to adb's path if it isn't on PATH, and DEVICE to pick a phone when several are connected.
Before capturing, use demo content: these grab whatever is on screen, notifications included.
Needs Python 3 with Pillow.
"""
import os, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "mockups")
RAW = os.path.join(OUT, "raw")
ADB = os.environ.get("ADB", "adb")
DEVICE = os.environ.get("DEVICE")


def adb(*args, **kw):
    cmd = [ADB] + (["-s", DEVICE] if DEVICE else []) + list(args)
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(cmd, check=True, env=env, **kw)


def shot(name):
    os.makedirs(RAW, exist_ok=True)
    adb("shell", "screencap", "-p", "/sdcard/mockup.png")
    adb("pull", "/sdcard/mockup.png", os.path.join(RAW, name + ".png"), stdout=subprocess.DEVNULL)
    print("saved", name + ".png")


def clip(name, seconds):
    os.makedirs(RAW, exist_ok=True)
    adb("shell", "screenrecord", "--time-limit", str(seconds), "--bit-rate", "12000000", "/sdcard/mockup.mp4")
    adb("pull", "/sdcard/mockup.mp4", os.path.join(RAW, name + ".mp4"), stdout=subprocess.DEVNULL)
    print("saved", name + ".mp4")


def framed(path, width=540):
    """The screen with rounded corners and a soft shadow, on transparent."""
    img = Image.open(path).convert("RGB")
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    r = width // 11
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), r, fill=255)
    pad = 40
    out = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("L", out.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((pad, pad + 10, pad + img.width, pad + img.height + 10), r, fill=140)
    out.putalpha(shadow.filter(ImageFilter.GaussianBlur(18)))
    border = Image.new("RGBA", (img.width + 6, img.height + 6), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle((0, 0, border.width - 1, border.height - 1), r + 3, fill=(40, 40, 40, 255))
    out.alpha_composite(border, (pad - 3, pad - 3))
    out.paste(img, (pad, pad), mask)
    return out


def frame_all():
    os.makedirs(OUT, exist_ok=True)
    for f in sorted(os.listdir(RAW)):
        if f.endswith(".png"):
            framed(os.path.join(RAW, f)).save(os.path.join(OUT, f))
            print("framed", f)


def strip(names, out):
    shots = [framed(os.path.join(RAW, n + ".png"), 400) for n in names]
    w = sum(s.width for s in shots); h = max(s.height for s in shots)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = 0
    for s in shots:
        canvas.alpha_composite(s, (x, 0)); x += s.width
    canvas.save(os.path.join(OUT, out + ".png"))
    print("saved", out + ".png")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["shot"]: shot(a[1])
    elif a[:1] == ["clip"]: clip(a[1], int(a[2]) if len(a) > 2 else 8)
    elif a[:1] == ["frame"]: frame_all()
    elif a[:1] == ["strip"]: strip(a[1:-1], a[-1])
    else: print(__doc__)
