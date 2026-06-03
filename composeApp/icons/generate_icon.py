#!/usr/bin/env python3
"""Generate the SAM3D app icon (Carbon-blue squircle + white cube wireframe) in .png/.ico/.icns.

Run with the sam3d env (has Pillow):
    /opt/anaconda3/envs/sam3d/bin/python composeApp/icons/generate_icon.py

The mark mirrors the in-app header glyph (CarbonIcons / WizardShell.AppMark) so brand reads
consistently from dock to title bar. Committed outputs are referenced by build.gradle.kts.
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
S = 1024
CARBON_BLUE = (15, 98, 254, 255)   # #0F62FE
WHITE = (255, 255, 255, 255)


def rounded_rect(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def cube(draw):
    # Front face square + isometric top/right edges, centred in the canvas.
    lw = 26
    x0, y0, x1, y1 = 322, 430, 680, 788          # front face
    d = 122                                        # depth offset (up-right)
    pts_front = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    # front face
    draw.line(pts_front + [pts_front[0]], fill=WHITE, width=lw, joint="curve")
    # top face
    draw.line([(x0, y0), (x0 + d, y0 - d)], fill=WHITE, width=lw)
    draw.line([(x1, y0), (x1 + d, y0 - d)], fill=WHITE, width=lw)
    draw.line([(x0 + d, y0 - d), (x1 + d, y0 - d)], fill=WHITE, width=lw)
    # right face
    draw.line([(x1, y1), (x1 + d, y1 - d)], fill=WHITE, width=lw)
    draw.line([(x1 + d, y0 - d), (x1 + d, y1 - d)], fill=WHITE, width=lw)
    # round the joints
    r = lw // 2
    for (x, y) in [(x0, y0), (x1, y0), (x1, y1), (x0, y1),
                   (x0 + d, y0 - d), (x1 + d, y0 - d), (x1 + d, y1 - d)]:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)


def build_base():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    margin = int(S * 0.085)
    rounded_rect(d, [margin, margin, S - margin, S - margin], radius=int(S * 0.225), fill=CARBON_BLUE)
    cube(d)
    return img


def main():
    base = build_base()
    base.save(os.path.join(HERE, "AppIcon.png"))
    base.resize((512, 512), Image.LANCZOS).save(os.path.join(HERE, "AppIcon_512.png"))
    base.save(os.path.join(HERE, "AppIcon.ico"),
              sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    try:
        base.save(os.path.join(HERE, "AppIcon.icns"))
        print("wrote .png, .ico, .icns")
    except Exception as exc:  # Pillow without icns-save → fall back to iconutil (see PACKAGING.md)
        print(f".icns via Pillow failed ({exc}); generate it with iconutil — see docs/PACKAGING.md")


if __name__ == "__main__":
    main()
