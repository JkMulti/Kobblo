"""Path data helpers: parse SVG/VectorDrawable path data, make it absolute, and scale + move it."""
import re

_TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
_ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def absolute(d):
    """Returns [(cmd, [args])] with every command absolute, H/V turned into L."""
    toks = _TOKEN.findall(d)
    out, i, cmd = [], 0, None
    x = y = sx = sy = 0.0
    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append(("Z", []))
                x, y = sx, sy
                continue
        n = _ARITY[cmd.upper()]
        args = [float(v) for v in toks[i:i + n]]
        i += n
        rel = cmd.islower()
        C = cmd.upper()
        if C == "M":
            nx, ny = (x + args[0], y + args[1]) if rel else args
            out.append(("M", [nx, ny]))
            x, y = sx, sy = nx, ny
            cmd = "l" if rel else "L"  # implicit lineto after moveto
        elif C == "L" or C == "T":
            nx, ny = (x + args[0], y + args[1]) if rel else args
            out.append((C, [nx, ny]))
            x, y = nx, ny
        elif C == "H":
            nx = x + args[0] if rel else args[0]
            out.append(("L", [nx, y]))
            x = nx
        elif C == "V":
            ny = y + args[0] if rel else args[0]
            out.append(("L", [x, ny]))
            y = ny
        elif C in "CSQ":
            pts = []
            for k in range(0, len(args), 2):
                pts += [x + args[k], y + args[k + 1]] if rel else [args[k], args[k + 1]]
            out.append((C, pts))
            x, y = pts[-2], pts[-1]
        elif C == "A":
            rx, ry, rot, la, sw, ex, ey = args
            if rel:
                ex, ey = x + ex, y + ey
            out.append(("A", [rx, ry, rot, la, sw, ex, ey]))
            x, y = ex, ey
    return out


def transform(d, s=1.0, tx=0.0, ty=0.0):
    """Uniform scale then translate, as absolute path data."""
    parts = []
    for C, a in absolute(d):
        if C == "Z":
            parts.append("Z")
        elif C == "A":
            rx, ry, rot, la, sw, ex, ey = a
            parts.append(f"A{fmt(rx*s)} {fmt(ry*s)} {fmt(rot)} {int(la)} {int(sw)} {fmt(ex*s+tx)} {fmt(ey*s+ty)}")
        else:
            pts = [fmt(v * s + (tx if k % 2 == 0 else ty)) for k, v in enumerate(a)]
            parts.append(C + " ".join(pts))
    return "".join(parts)


def rr(x, y, w, h, r):
    """A rounded rectangle, clockwise."""
    return (f"M{fmt(x+r)} {fmt(y)}H{fmt(x+w-r)}A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x+w)} {fmt(y+r)}"
            f"V{fmt(y+h-r)}A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x+w-r)} {fmt(y+h)}H{fmt(x+r)}"
            f"A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x)} {fmt(y+h-r)}V{fmt(y+r)}A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x+r)} {fmt(y)}Z")


def rr_ccw(x, y, w, h, r):
    """The same rounded rectangle, counter-clockwise, for a hole under the nonzero rule."""
    return (f"M{fmt(x+r)} {fmt(y)}A{fmt(r)} {fmt(r)} 0 0 0 {fmt(x)} {fmt(y+r)}V{fmt(y+h-r)}"
            f"A{fmt(r)} {fmt(r)} 0 0 0 {fmt(x+r)} {fmt(y+h)}H{fmt(x+w-r)}A{fmt(r)} {fmt(r)} 0 0 0 {fmt(x+w)} {fmt(y+h-r)}"
            f"V{fmt(y+r)}A{fmt(r)} {fmt(r)} 0 0 0 {fmt(x+w-r)} {fmt(y)}Z")


def circle(cx, cy, r, ccw=False):
    sw = 0 if ccw else 1
    return (f"M{fmt(cx-r)} {fmt(cy)}A{fmt(r)} {fmt(r)} 0 1 {sw} {fmt(cx+r)} {fmt(cy)}"
            f"A{fmt(r)} {fmt(r)} 0 1 {sw} {fmt(cx-r)} {fmt(cy)}Z")
