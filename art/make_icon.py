"""Draws the Quarry icon as 64x64 pixel art, then scales it to 512x512.

A hunter's tracking compass glowing red, its needle pointing up at the
Ender Dragon spread across the End sky.
Run:  python art/make_icon.py   ->  art/icon.png (512) and art/icon-64.png (1:1 sprite)
"""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw

N = 64
OUT = Path(__file__).parent
random.seed(3)

img = Image.new("RGB", (N, N))
px = img.load()


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def put(x, y, c):
    x, y = int(round(x)), int(round(y))
    if 0 <= x < N and 0 <= y < N:
        px[x, y] = c


def get(x, y):
    return px[min(max(int(x), 0), N - 1), min(max(int(y), 0), N - 1)]


def blend(c1, c2, a):
    a = min(max(a, 0.0), 1.0)
    return tuple(round(c1[i] + (c2[i] - c1[i]) * a) for i in range(3))


BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def dither(bands, t, x, y):
    t = min(max(t, 0.0), 1.0) * (len(bands) - 1)
    i = min(int(t), len(bands) - 2)
    return bands[i + 1] if (t - i) * 16 > BAYER[y % 4][x % 4] + 0.5 else bands[i]


# ---------------- End sky ----------------
SKY = [hexc(h) for h in ("#07040f", "#100822", "#1b0d36", "#2a1450", "#3a1d66")]
for y in range(N):
    for x in range(N):
        # brighter purple haze toward the middle-top, behind the dragon
        d = math.hypot((x - 32) / 1.2, y - 22) / 34
        px[x, y] = dither(SKY, 1 - d, x, y)
# stars
for _ in range(40):
    x, y = random.randint(0, 63), random.randint(0, 40)
    put(x, y, random.choice((hexc("#c9b8ff"), hexc("#ffffff"), hexc("#8a74c9"))))

# pale End glow behind the dragon so its silhouette reads
HALO = [hexc(h) for h in ("#2a1450", "#43207a", "#6334a6", "#8a56cc", "#b48ae8")]
for y in range(N):
    for x in range(N):
        d = math.hypot((x - 32) / 31, (y - 15) / 17)
        if d < 1:
            put(x, y, dither(HALO, (1 - d) ** 0.9, x, y))

# ---------------- end stone island at the bottom ----------------
STONE = [hexc(h) for h in ("#8f8a5c", "#b5ae74", "#d6cf8f", "#e8e2a6")]
for x in range(N):
    top = 50 + int(2.5 * math.sin(x * 0.21) + 1.5 * math.sin(x * 0.63 + 2))
    for y in range(top, N):
        t = 0.9 - (y - top) / 16 + random.uniform(-0.15, 0.15)
        put(x, y, dither(STONE, t, x, y))
    put(x, top, STONE[3])

# ---------------- the Ender Dragon (silhouette, wings spread) ----------------
DRAGON = hexc("#0c0814")
DRAGON_EDGE = hexc("#3b2560")
EYE = hexc("#e45cff")
mask = Image.new("L", (N, N), 0)
md = ImageDraw.Draw(mask)
cx, cy = 32, 17
# body + neck + head
md.polygon([(29, 13), (35, 13), (36, 26), (33, 33), (31, 33), (28, 26)], fill=255)
md.polygon([(30, 8), (34, 8), (35, 13), (29, 13)], fill=255)  # neck
md.polygon([(29, 3), (35, 3), (36, 8), (28, 8)], fill=255)           # head
md.polygon([(31, 8), (33, 8), (33, 10), (31, 10)], fill=255)  # snout
md.polygon([(28, 0), (29, 0), (30, 3), (29, 3)], fill=255)             # horns
md.polygon([(35, 0), (36, 0), (35, 3), (34, 3)], fill=255)
md.polygon([(31, 33), (33, 33), (34, 40), (32, 44), (30, 40)], fill=255)  # tail
for side in (-1, 1):
    # wing: leading edge sweeps up and out, trailing edge is scalloped
    tip = (cx + side * 30, 6)
    pts = [(cx + side * 3, 13), (cx + side * 14, 7), tip,
           (cx + side * 27, 14), (cx + side * 22, 15), (cx + side * 21, 22),
           (cx + side * 15, 20), (cx + side * 13, 26), (cx + side * 7, 22), (cx + side * 3, 24)]
    md.polygon(pts, fill=255)
mk = mask.load()
for y in range(N):
    for x in range(N):
        if mk[x, y]:
            edge = any(not (0 <= x + ox < N and 0 <= y + oy < N and mk[x + ox, y + oy])
                       for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            put(x, y, DRAGON_EDGE if edge else DRAGON)
# back spikes
for y in range(15, 32, 3):
    put(32, y, DRAGON_EDGE)

# wing bones
for side in (-1, 1):
    for bx, by in ((14, 7), (22, 15), (15, 20), (7, 22)):
        steps = 30
        for s in range(steps):
            f = s / steps
            x = cx + side * (3 + (bx - 3) * f)
            y = 13 + (by - 13) * f
            if mk[int(round(x)), int(round(y))]:
                put(x, y, hexc("#24163b"))
# glowing eyes
put(30, 5, EYE)
put(34, 5, EYE)

# ---------------- the tracking compass ----------------
CC = (32.0, 47.0)
R = 12.0
GLOW = hexc("#ff3b3b")
for y in range(N):
    for x in range(N):
        d = math.hypot(x - CC[0], y - CC[1])
        if R < d < R + 6:
            put(x, y, blend(get(x, y), GLOW, ((R + 6 - d) / 6) ** 1.8 * 0.75))
for y in range(N):
    for x in range(N):
        d = math.hypot(x + 0.5 - CC[0], y + 0.5 - CC[1])
        if d <= R + 1:
            put(x, y, hexc("#140c10"))
        if d <= R:
            lit = ((CC[0] - x) + (CC[1] - y)) / (2 * R)
            put(x, y, blend(hexc("#6f6a72"), hexc("#dcd8de"), 0.5 + lit))
        if d <= R - 2.4:
            put(x, y, hexc("#221a24") if (x * 3 + y) % 9 else hexc("#2c222f"))
# tick marks
for ang in range(0, 360, 45):
    a = math.radians(ang)
    r = R - 1.2 if ang % 90 == 0 else R - 1.6
    put(CC[0] + math.cos(a) * r, CC[1] + math.sin(a) * r, hexc("#3a3040"))

# needle: red end points straight up at the dragon
ANG = math.radians(-90)
dxn, dyn = math.cos(ANG), math.sin(ANG)
pxn, pyn = -dyn, dxn


def needle(length, colour, shade, sign, width):
    for s in range(int(length * 4)):
        f = s / (length * 4)
        half = (1 - f) * width
        for w in range(-6, 7):
            o = w / 6 * half
            put(CC[0] + sign * dxn * f * length + pxn * o,
                CC[1] + sign * dyn * f * length + pyn * o,
                colour if o < 0 else shade)


needle(9.0, hexc("#ff4040"), hexc("#b81c1c"), 1, 2.6)
needle(6.5, hexc("#e8e4ea"), hexc("#9e98a4"), -1, 2.2)
put(CC[0], CC[1], hexc("#4a4250"))
put(CC[0] - 1, CC[1], hexc("#8a8292"))

# red sparks around the compass
for _ in range(14):
    a = random.uniform(math.pi * 1.05, math.pi * 1.95)   # upper half only
    r = random.uniform(R + 2, R + 7)
    put(CC[0] + math.cos(a) * r, CC[1] + math.sin(a) * r, random.choice((hexc("#ff6a5a"), hexc("#ffb0a0"))))

img.save(OUT / "icon-64.png")
img.resize((512, 512), Image.NEAREST).save(OUT / "icon.png")
print("wrote", OUT / "icon.png")
