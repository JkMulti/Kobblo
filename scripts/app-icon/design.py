"""Kobblo's launcher icon: Browse's blue folder with a gallery's landscape along its foot, on a white tile.

Everything is drawn in the adaptive icon's 108-unit space, where a launcher shows the middle 72 and
keeps the middle 66 (a circle) for sure. The folder is FileArt.kt's FolderArt, moved and scaled into
place, so the icon and Browse stay the same folder. On its front, two ranges of hills in the folder's
own deeper blues rest on its bottom edge, under a white sun; every corner is a true arc.
"""
import math
from pathx import transform, rr, circle, fmt

# FileArt.kt's FolderArt, in its 64-unit space: the back panel with its tab, the front panel, and the
# light along the front's top edge.
BACK = "M5 16.5A5.5 5.5 0 0 1 10.5 11h12.2c1.5 0 3 .6 4 1.6l2.6 2.5c1 1 2.5 1.6 4 1.6H53.5A5.5 5.5 0 0 1 59 22.2V30H5Z"
FRONT = "M5 26.5A4.5 4.5 0 0 1 9.5 22H54.5A4.5 4.5 0 0 1 59 26.5V47.5A5.5 5.5 0 0 1 53.5 53H10.5A5.5 5.5 0 0 1 5 47.5Z"
EDGE = "M9.5 22h45A4.5 4.5 0 0 1 59 26.5v.7A4.5 4.5 0 0 0 54.5 22.7h-45A4.5 4.5 0 0 0 5 27.2v-.7A4.5 4.5 0 0 1 9.5 22Z"
# The front panel's foot, from its right side round to its left: where the hills end.
FOOT = "L59 47.5A5.5 5.5 0 0 1 53.5 53H10.5A5.5 5.5 0 0 1 5 47.5Z"
BACK_COLORS = ("#2F8EEB", "#2479D6")
FRONT_COLORS = ("#6FC6FF", "#3EA3F7")
FAR_HILLS_COLORS = ("#3496EE", "#2A83E0")
NEAR_HILLS_COLORS = ("#2479D6", "#1B66C4")
TILE_COLORS = ("#FFFFFF", "#E9F1FB")

SCALE = 0.90        # The folder is 48.6 of the 72 a launcher shows, and inside the 66 safe circle.
MONO_SCALE = 0.78   # Themed icons sit a little smaller, as the system's own do.
MONO_GAP = 2.0      # In one color, a gap tells the back panel from the front, and the sky from the hills.

# The landscape, in the folder's space, as points left to right with each corner's radius. Each range
# starts on the panel's left side (x 5) and ends on its right side (x 59) or its foot (y 53); below it,
# the panel's foot. The near hill is the big one on the left, the far one shows behind it on the right.
FAR_HILLS = ([(5, 47), (43, 35.5), (59, 43)], [0, 4.4, 0])
NEAR_HILLS = ([(5, 40.5), (20.5, 33.5), (45.5, 53)], [0, 4.6, 0])
SUN = (47.5, 28.8, 2.9)
MONO_HILLS_ALPHA = 0.5  # In one color the hills are a lighter tone of it, so they read as hills.


def fillet(points, radii):
    """Lines through points, from points[0] (the current point), each inner corner rounded by a true arc of
    radii[i], tangent to both sides, the way a design tool rounds a corner."""
    d = ""
    for i in range(1, len(points) - 1):
        (ax, ay), (bx, by), (cx, cy) = points[i - 1], points[i], points[i + 1]
        r = radii[i]
        if r <= 0:
            d += f"L{fmt(bx)} {fmt(by)}"
            continue
        ul, vl = math.hypot(ax - bx, ay - by), math.hypot(cx - bx, cy - by)
        u, v = ((ax - bx) / ul, (ay - by) / ul), ((cx - bx) / vl, (cy - by) / vl)
        theta = math.acos(max(-1.0, min(1.0, u[0] * v[0] + u[1] * v[1])))
        t = r / math.tan(theta / 2)
        assert t < ul and t < vl, f"radius {r} too big for the corner at {points[i]}"
        turn = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        d += (f"L{fmt(bx + u[0] * t)} {fmt(by + u[1] * t)}"
              f"A{fmt(r)} {fmt(r)} 0 0 {1 if turn > 0 else 0} {fmt(bx + v[0] * t)} {fmt(by + v[1] * t)}")
    return d + f"L{fmt(points[-1][0])} {fmt(points[-1][1])}"


def hills(rng):
    """A range of hills: its ridge, then down the panel's right side and round its foot, or, for a range
    that comes down to the foot, along it, and up the panel's left side."""
    points, radii = rng
    end = FOOT if points[-1][0] >= 59 else "H10.5A5.5 5.5 0 0 1 5 47.5Z"
    return f"M{fmt(points[0][0])} {fmt(points[0][1])}" + fillet(points, radii) + end


def _cross(p, r, q, s):
    """Where the line through p (direction r) meets the line through q (direction s)."""
    den = r[0] * s[1] - r[1] * s[0]
    t = ((q[0] - p[0]) * s[1] - (q[1] - p[1]) * s[0]) / den
    return (p[0] + r[0] * t, p[1] + r[1] * t)


def skyline():
    """The top edge of both ranges together, for the one-color icon: the near range up to its peak, down
    its slope to where it meets the far range's, then the far range from its peak on."""
    (n, nr), (f, fr) = NEAR_HILLS, FAR_HILLS
    k = min(range(len(f)), key=lambda i: f[i][1])  # the far range's peak
    meet = _cross(n[1], (n[2][0] - n[1][0], n[2][1] - n[1][1]), f[k - 1], (f[k][0] - f[k - 1][0], f[k][1] - f[k - 1][1]))
    return n[:2] + [meet] + f[k:], nr[:2] + [1.4] + fr[k:]


def raised(points, radii, g):
    """The ridge moved g up, square to every slope, as an offset curve is: each line shifted along its
    normal, corners where the shifted lines meet, a peak's arc g wider and a dip's g tighter. Its ends stay
    on the panel's sides."""
    dirs = [(b[0] - a[0], b[1] - a[1]) for a, b in zip(points, points[1:])]
    norms = [(dy / math.hypot(dx, dy), -dx / math.hypot(dx, dy)) for dx, dy in dirs]  # toward the sky
    lines = [((a[0] + nx * g, a[1] + ny * g), d) for a, d, (nx, ny) in zip(points, dirs, norms)]
    out = [_cross(lines[0][0], lines[0][1], (points[0][0], 0), (0, 1))]
    new_radii = [0]
    for i in range(1, len(points) - 1):
        out.append(_cross(*lines[i - 1], *lines[i]))
        a, b, c = points[i - 1], points[i], points[i + 1]
        turn = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        new_radii.append(radii[i] + g if turn > 0 else max(radii[i] - g, 0))
    out.append(_cross(lines[-1][0], lines[-1][1], (points[-1][0], 0), (0, 1)))
    return out, new_radii + [0]


class Placement:
    """The folder's 64-unit space, scaled about the icon's center."""

    def __init__(self, scale):
        self.s = scale
        self.t = 54 - 32 * scale

    def path(self, d):
        return transform(d, self.s, self.t, self.t)

    def y(self, v):
        return v * self.s + self.t


def background():
    return [dict(d="M0 0H108V108H0Z", grad=(54, 18, 54, 90, TILE_COLORS))]


def foreground():
    p = Placement(SCALE)
    hills_grad = (54, p.y(34), 54, p.y(53))
    return [
        dict(d=p.path(BACK), grad=(54, p.y(11), 54, p.y(30), BACK_COLORS), comment="The back panel and its tab"),
        dict(d=p.path(FRONT), grad=(54, p.y(22), 54, p.y(53), FRONT_COLORS), comment="The front panel, the sky"),
        dict(d=p.path(EDGE), fill="#FFFFFF", alpha=0.4, comment="Light along the front's top edge"),
        dict(d=p.path(hills(FAR_HILLS)), grad=(*hills_grad, FAR_HILLS_COLORS), comment="The far hills"),
        dict(d=p.path(hills(NEAR_HILLS)), grad=(*hills_grad, NEAR_HILLS_COLORS), comment="The near hills"),
        dict(d=p.path(circle(*SUN)), fill="#FFFFFF", comment="The sun"),
    ]


def monochrome():
    """The themed icon, one color: the back panel parted from the front, the sky parted from the hills,
    each by a gap, the hills a lighter tone and the sun cut out of the sky."""
    p = Placement(MONO_SCALE)
    cut = 22 - MONO_GAP
    # The back panel's top-right corner is a quarter circle about (53.5, 22.2); stop it at the cut.
    x_cut = 53.5 + math.sqrt(5.5 ** 2 - (22.2 - cut) ** 2)
    back = ("M5 16.5A5.5 5.5 0 0 1 10.5 11h12.2c1.5 0 3 .6 4 1.6l2.6 2.5c1 1 2.5 1.6 4 1.6H53.5"
            f"A5.5 5.5 0 0 1 {fmt(x_cut)} {fmt(cut)}H5Z")
    ridge, radii = skyline()
    top, top_radii = raised(ridge, radii, MONO_GAP)
    # The sky: down the right side to the raised ridge, along it right to left, and up the left side.
    sky = (f"M5 26.5A4.5 4.5 0 0 1 9.5 22H54.5A4.5 4.5 0 0 1 59 26.5L{fmt(top[-1][0])} {fmt(top[-1][1])}"
           + fillet(top[::-1], top_radii[::-1]) + "Z" + circle(*SUN))
    return [
        dict(d=p.path(back), fill="#000000", comment="The back panel and its tab"),
        dict(d=p.path(sky), fill="#000000", rule="evenodd", comment="The sky, the sun cut out of it"),
        dict(d=p.path(hills((ridge, radii))), fill="#000000", alpha=MONO_HILLS_ALPHA, comment="The hills, a lighter tone"),
    ]


# ---------- Output ----------

def _argb(c, alpha=1.0):
    return f"#{round(alpha * 255):02X}{c.lstrip('#').upper()}"


def vector_xml(layers, comment):
    """An Android VectorDrawable, 108dp, for an adaptive icon layer."""
    out = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {comment} -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"']
    if any("grad" in layer for layer in layers):
        out.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    out += ['    android:width="108dp"', '    android:height="108dp"',
            '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for layer in layers:
        if layer.get("comment"):
            out.append(f"    <!-- {layer['comment']} -->")
        attrs = [f'android:pathData="{layer["d"]}"']
        if layer.get("rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        if "grad" in layer:
            x1, y1, x2, y2, (c0, c1) = layer["grad"]
            out.append("    <path " + "\n        ".join(attrs) + ">")
            out += ['        <aapt:attr name="android:fillColor">',
                    f'            <gradient android:type="linear" android:startX="{fmt(x1)}" android:startY="{fmt(y1)}" '
                    f'android:endX="{fmt(x2)}" android:endY="{fmt(y2)}">',
                    f'                <item android:offset="0" android:color="{_argb(c0)}" />',
                    f'                <item android:offset="1" android:color="{_argb(c1)}" />',
                    "            </gradient>", "        </aapt:attr>", "    </path>"]
        else:
            attrs.append(f'android:fillColor="{_argb(layer.get("fill", "#000000"), layer.get("alpha", 1))}"')
            out.append("    <path " + "\n        ".join(attrs) + " />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def squircle(cx, cy, radius, n=5.0, steps=180):
    pts = []
    for k in range(steps):
        a = 2 * math.pi * k / steps
        c, s = math.cos(a), math.sin(a)
        pts.append(f"{fmt(cx + radius * math.copysign(abs(c) ** (2 / n), c))} "
                   f"{fmt(cy + radius * math.copysign(abs(s) ** (2 / n), s))}")
    return "M" + "L".join(pts) + "Z"


# Launcher masks over the 72 a launcher shows: Pixel's circle, a One UI-like squircle, a rounded square.
MASKS = {"circle": circle(54, 54, 36), "squircle": squircle(54, 54, 36), "rounded": rr(18, 18, 72, 72, 16)}


def svg(layers, uid, mask=None, size=192, view=(18, 18, 72, 72)):
    """The layers as an SVG, for previews and the legacy images."""
    defs, body = [], []
    for i, layer in enumerate(layers):
        fill = layer.get("fill", "#000000")
        if "grad" in layer:
            x1, y1, x2, y2, (c0, c1) = layer["grad"]
            defs.append(f'<linearGradient id="{uid}g{i}" gradientUnits="userSpaceOnUse" x1="{fmt(x1)}" y1="{fmt(y1)}" '
                        f'x2="{fmt(x2)}" y2="{fmt(y2)}"><stop offset="0" stop-color="{c0}"/><stop offset="1" stop-color="{c1}"/></linearGradient>')
            fill = f"url(#{uid}g{i})"
        body.append(f'<path d="{layer["d"]}" fill="{fill}" fill-opacity="{layer.get("alpha", 1)}" '
                    f'fill-rule="{layer.get("rule", "nonzero")}"/>')
    content = "".join(body)
    if mask:
        defs.append(f'<clipPath id="{uid}m"><path d="{MASKS[mask]}"/></clipPath>')
        content = f'<g clip-path="url(#{uid}m)">{content}</g>'
    vb = " ".join(fmt(v) for v in view)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="{size}" height="{size}">'
            f'<defs>{"".join(defs)}</defs>{content}</svg>')
