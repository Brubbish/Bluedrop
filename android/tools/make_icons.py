"""Generate Bluedrop launcher artwork (blue drop) with Pillow.

Run from the repo root:  python tools/make_icons.py

Outputs:
  - app/src/main/res/mipmap-*/ic_launcher*.webp   (legacy + adaptive foreground)
  - app/src/main/res/AppIcon.png                  (rounded square, README)
  - app/src/main/ic_launcher-playstore.png        (512 full-bleed square)
"""

import math
import os
from PIL import Image, ImageDraw, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO, "app", "src", "main", "res")

SS = 4  # supersampling factor

DROP_TOP = (89, 168, 255)     # gradient start (bulb side is bottom; apex is top)
DROP_BOTTOM = (33, 88, 224)
BG_TOP = (243, 248, 255)
BG_BOTTOM = (221, 235, 252)
HIGHLIGHT = (255, 255, 255, 110)


def drop_outline(cx, apex_y, scale):
    """Return list of (x, y) points for a teardrop: pointed apex on top,
    circular bulb on the bottom. `scale` = bulb radius in pixels."""
    r = scale
    # Circle center sits so that the apex-to-center distance d gives a nice cone.
    d = r / math.sin(math.radians(28.6))
    center = (cx, apex_y + d)
    alpha = math.asin(r / d)
    pts = []
    # Tangent points from the apex; base direction points down (+y on screen),
    # so rotating by -alpha opens to the RIGHT and +alpha to the LEFT.
    L = math.sqrt(d * d - r * r)
    sin_a, cos_a = math.sin(alpha), math.cos(alpha)
    t_right = (cx + L * sin_a, apex_y + L * cos_a)
    t_left = (cx - L * sin_a, apex_y + L * cos_a)

    # Bulb arc: from t_left around the SCREEN BOTTOM to t_right. In y-down
    # coords the bottom of the circle sits at angle +pi/2, so the sweep must
    # run decreasing from ang_left, wrapping past -pi, to ang_right - 2pi.
    ang_left = math.atan2(t_left[1] - center[1], t_left[0] - center[0])
    ang_right = math.atan2(t_right[1] - center[1], t_right[0] - center[0])
    target = ang_right
    while target > ang_left:
        target -= 2 * math.pi
    steps = 96
    for i in range(steps + 1):
        a = ang_left + (target - ang_left) * i / steps
        pts.append((center[0] + r * math.cos(a), center[1] + r * math.sin(a)))

    # Sides: quadratic bezier t_right -> apex -> t_left, bowed slightly outward
    apex = (cx, apex_y)

    def qbez(p0, p1, p2, n):
        out = []
        for i in range(1, n):
            t = i / n
            mt = 1 - t
            x = mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0]
            y = mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1]
            out.append((x, y))
        return out

    def side(t_pt, sign):
        mx = (t_pt[0] + apex[0]) / 2
        my = (t_pt[1] + apex[1]) / 2
        # push control away from the drop's vertical axis
        ctrl = (mx + sign * 0.14 * abs(t_pt[0] - apex[0]), my)
        return qbez(t_pt, ctrl, apex, 40)

    pts += side(t_right, +1)      # right side up to apex
    pts += side(t_left, -1)[::-1]  # apex down to left tangent
    return pts, center, r


def vertical_gradient(size, top, bottom):
    w, h = size
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        grad.putpixel(
            (0, y),
            tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)),
        )
    return grad.resize((w, h))


def draw_drop(canvas_size, drop_box):
    """Render the drop into an RGBA image of canvas_size, drop inside drop_box
    (left, top, right, bottom)."""
    size = canvas_size[0] * SS, canvas_size[1] * SS
    left, top, right, bottom = drop_box
    box = [v * SS for v in (left, top, right, bottom)]
    bw, bh = box[2] - box[0], box[3] - box[1]
    # fit a drop with apex_y=0.10*h', bulb bottom at 1.0*h'
    apex_y = box[1] + 0.10 * bh
    bulb_bottom = box[3]
    # total drop height = d + r  where d = r/sin(28.6deg) => r = h/(1/sin + 1)
    r = bh / (1 / math.sin(math.radians(28.6)) + 1)
    cx = box[0] + bw / 2

    img = Image.new("RGBA", size, (0, 0, 0, 0))
    mask = Image.new("L", size, 0)
    mdraw = ImageDraw.Draw(mask)
    pts, center, _ = drop_outline(cx, apex_y, r)
    # extend the bulb arc points list built earlier is fine; polygon close
    mdraw.polygon(pts, fill=255)

    grad = vertical_gradient(size, DROP_TOP, DROP_BOTTOM).convert("RGBA")
    # map gradient so apex gets DROP_TOP and bulb bottom DROP_BOTTOM
    drop_h = bulb_bottom - apex_y
    grad_full = Image.new("RGBA", size)
    gdraw = ImageDraw.Draw(grad_full)
    for y in range(size[1]):
        t = min(max((y - apex_y) / drop_h, 0.0), 1.0)
        col = tuple(
            int(DROP_TOP[i] + (DROP_BOTTOM[i] - DROP_TOP[i]) * t) for i in range(3)
        ) + (255,)
        gdraw.line([(0, y), (size[0], y)], fill=col)

    img = Image.composite(grad_full, img, mask)

    # soft highlight on the upper-left of the bulb
    hl = Image.new("RGBA", size, (0, 0, 0, 0))
    hdraw = ImageDraw.Draw(hl)
    hx = center[0] - r * 0.42
    hy = center[1] - r * 0.38
    hw, hh = r * 0.46, r * 0.62
    hdraw.ellipse(
        [hx - hw / 2, hy - hh / 2, hx + hw / 2, hy + hh / 2], fill=HIGHLIGHT
    )
    hl = hl.filter(ImageFilter.GaussianBlur(radius=r * 0.16))
    hl.putalpha(hl.split()[3].point(lambda a: int(a * 0.9)))
    img = Image.alpha_composite(img, Image.composite(hl, Image.new("RGBA", size, (0, 0, 0, 0)), mask))

    return img.resize(canvas_size, Image.LANCZOS)


def rounded_bg(size, radius_frac, circle=False):
    ss = size[0] * SS, size[1] * SS
    mask = Image.new("L", ss, 0)
    d = ImageDraw.Draw(mask)
    if circle:
        d.ellipse([0, 0, ss[0] - 1, ss[1] - 1], fill=255)
    else:
        r = int(min(ss) * radius_frac)
        d.rounded_rectangle([0, 0, ss[0] - 1, ss[1] - 1], radius=r, fill=255)
    grad = vertical_gradient(ss, BG_TOP, BG_BOTTOM).convert("RGBA")
    out = Image.new("RGBA", ss, (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out.resize(size, Image.LANCZOS)


def save_webp(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "WEBP", quality=95, method=6)
    print("wrote", path)


def main():
    densities = {
        "mdpi": 1,
        "hdpi": 1.5,
        "xhdpi": 2,
        "xxhdpi": 3,
        "xxxhdpi": 4,
    }

    # Adaptive foreground layers: 108dp canvas, drop inside the 66dp safe zone.
    for name, scale in densities.items():
        px = int(108 * scale)
        fg = draw_drop((px, px), (px * 0.26, px * 0.22, px * 0.74, px * 0.80))
        save_webp(fg, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.webp"))

        # Legacy launcher icon (48dp rounded square) + round variant
        icon_px = int(48 * scale)
        for round_name, circle in (("ic_launcher", False), ("ic_launcher_round", True)):
            base = rounded_bg((icon_px, icon_px), 0.22, circle=circle)
            # drop occupies ~66% of the legacy icon height
            drop = draw_drop(
                (icon_px, icon_px),
                (icon_px * 0.24, icon_px * 0.14, icon_px * 0.76, icon_px * 0.84),
            )
            base.alpha_composite(drop)
            save_webp(base, os.path.join(RES, f"mipmap-{name}", f"{round_name}.webp"))

    # README / store art
    app_icon = rounded_bg((1024, 1024), 0.22)
    app_icon.alpha_composite(
        draw_drop((1024, 1024), (1024 * 0.24, 1024 * 0.13, 1024 * 0.76, 1024 * 0.85))
    )
    app_icon.save(os.path.join(RES, "AppIcon.png"), "PNG")
    print("wrote", os.path.join(RES, "AppIcon.png"))

    ps = vertical_gradient((512, 512), BG_TOP, BG_BOTTOM).convert("RGBA")
    ps.alpha_composite(draw_drop((512, 512), (512 * 0.24, 512 * 0.12, 512 * 0.76, 512 * 0.86)))
    ps.save(os.path.join(REPO, "app", "src", "main", "ic_launcher-playstore.png"), "PNG")
    print("wrote app/src/main/ic_launcher-playstore.png")


if __name__ == "__main__":
    main()
