"""
Rebuild the N9 theme bitmaps at 3x for high-density screens.

The firmware only ships the blanco theme at 1x (480 px wide), and the phone draws it ~2.25x larger,
so the 1x art looked soft. There are no vector sources in the firmware, so each bitmap is upscaled
here with care instead of being stretched at runtime:

  * colour is resampled premultiplied (no dark fringes) with Lanczos, then lightly sharpened;
  * outlines are re-sharpened in the alpha channel relative to the local maximum alpha, so edges come
    out crisp while intentionally see-through areas (unlit signal bars, the pressed overlay) keep
    their opacity;
  * soft art (gradients, glows) is only resampled.

    python tools/hd_assets.py            # app/art/n9-1x/*.png -> app/src/main/res/drawable-nodpi/
"""
import os, sys
from PIL import Image, ImageFilter

F = 3  # output scale; N9.HD in the app must match
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "art", "n9-1x")
DST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

SOFT = ("n9_gradient", "n9_video_overlay", "n9_event_pressed", "n9_home_event_bg")
# Flat glyphs: the crispest edges.
GLYPH = ("n9_status_", "n9_media_", "n9_close_thumbnail", "n9_position_indicator", "n9_event_icon_mask",
         "n9_home_button")


def smoothstep(lo, hi, x):
    if x <= lo: return 0.0
    if x >= hi: return 1.0
    t = (x - lo) / (hi - lo)
    return t * t * (3 - 2 * t)


def upscale(im, kind):
    im = im.convert("RGBA")
    w, h = im.size
    W, H = w * F, h * F
    a = im.split()[3]
    # Premultiply.
    px = list(im.get_flattened_data())
    pre = [(p[0] * p[3] // 255, p[1] * p[3] // 255, p[2] * p[3] // 255) for p in px]
    chans = []
    for i in range(3):
        c = Image.new("L", im.size); c.putdata([p[i] for p in pre]); chans.append(c)
    chans.append(a)
    up = [c.resize((W, H), Image.LANCZOS) for c in chans]
    if kind != "soft":
        # Colour only: sharpening alpha too would leave a light fringe round each icon's outline.
        up = [c.filter(ImageFilter.UnsharpMask(radius=1.4, percent=38, threshold=1)) for c in up[:3]] + [up[3]]
    A = list(up[3].get_flattened_data())
    if kind != "soft":
        # Edge re-sharpening relative to the local max alpha (keeps plateaus' opacity).
        m = a.filter(ImageFilter.MaxFilter(3)).resize((W, H), Image.BILINEAR)
        M = list(m.get_flattened_data())
        lo, hi = (0.32, 0.68) if kind == "glyph" else (0.2, 0.8)
        newA = []
        for av, mv in zip(A, M):
            if mv < 8: newA.append(0 if av < 8 else av); continue
            t = min(1.0, av / mv)
            newA.append(int(round(mv * smoothstep(lo, hi, t))))
    else:
        newA = A
    R, G, B = (list(c.get_flattened_data()) for c in up[:3])
    # Along outlines Lanczos rings; there the colour comes from a smooth (bilinear) resample instead.
    soft = [list(c.resize((W, H), Image.BILINEAR).get_flattened_data()) for c in chans]
    Mx = list(a.filter(ImageFilter.MaxFilter(3)).resize((W, H), Image.BILINEAR).get_flattened_data())
    Mn = list(a.filter(ImageFilter.MinFilter(3)).resize((W, H), Image.BILINEAR).get_flattened_data())
    out = []
    for i in range(W * H):
        if newA[i] <= 0: out.append((0, 0, 0, 0)); continue
        edge = Mx[i] - Mn[i] > 24
        if edge or A[i] <= 8:
            a0 = max(1, soft[3][i]); r, g, b = soft[0][i], soft[1][i], soft[2][i]
        else:
            a0 = A[i]; r, g, b = R[i], G[i], B[i]
        k = 255.0 / a0
        out.append((min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)), max(0, min(255, newA[i]))))
    res = Image.new("RGBA", (W, H)); res.putdata(out)
    return res


def main():
    names = sorted(f for f in os.listdir(SRC) if f.endswith(".png"))
    for i, f in enumerate(names):
        base = f[:-4]
        kind = "soft" if base.startswith(SOFT) else "glyph" if base.startswith(GLYPH) else "art"
        res = upscale(Image.open(os.path.join(SRC, f)), kind)
        res.save(os.path.join(DST, f), optimize=True)
        sys.stdout.write("\r%d/%d %s          " % (i + 1, len(names), f)); sys.stdout.flush()
    print()


if __name__ == "__main__":
    main()
