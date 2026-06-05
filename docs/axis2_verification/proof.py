"""
AXIS_2 coordinate-faithfulness proof (parse_prompts level — the engine's real consumer).

What we establish, using the ENGINE's own code (scale_transform.parse_prompts):
  The engine treats a stored point [a,b,c] POSITIONALLY as cube index (dim0=a, dim1=b, dim2=c).
  There is no axis-specific handling (parse_prompts pads all 3 coords uniformly and scales by the
  symmetric cube shape). So the desktop MUST store the cube array index of the clicked voxel.

Display orientation (verified from source — identical across desktop and the Tk tool):
  Desktop Dcm4cheLoader.loadSliceBitmap AXIS_2 : cube[h,w,n] shown at screen (x=w, y=h)
  reprompting3d.update_image (slice_axis=2)    : nii_data[:,:,n] via Image.fromarray -> row=h(y), col=w(x)
  => a click at screen (X, Y) points at cube voxel  (h=Y, w=X, n)  = cube[voxelY, voxelX, n].

Desktop conventions for the stored point on AXIS_2:
  OLD   embedVoxel : (voxelX, voxelY, n)      -> engine reads cube[voxelX, voxelY, n]  (TRANSPOSED)
  FIXED embedVoxel : (voxelY, voxelX, n)      -> engine reads cube[voxelY, voxelX, n]  (== clicked voxel)

reprompting3d.add_point (slice_axis=2), expressed in voxel units (its `scale` divides out):
  y_,x_ = event.x,event.y ; point = (int(x_/scale), int(y_/scale), slice) = (voxelY, voxelX, slice)
  => the engine's OWN annotation tool stores (voxelY, voxelX, slice) = FIXED.
"""
import sys, os, json
sys.path.insert(0, "/Users/DaChelimo/Documents/Research/SAM3D-GCODE")
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import geometry
import scale_transform

OUT = "/Users/DaChelimo/Documents/Research/sam3d/docs/axis2_verification"
cube = np.load("/tmp/axis2/cube_f16.npy").astype(np.float32)
S = cube.shape[0]
N_SLICE = 300

# Clicks (screen voxelX, voxelY) tracing the bright fibula cortical ring at n=300.
clicks = [(305, 208), (328, 226), (312, 252), (290, 230)]

print("== intensity at each click (screen x=w, y=h) -> cube[h=vy, w=vx, n] ==")
for (vx, vy) in clicks:
    print(f"  click (x={vx}, y={vy})  intensity cube[{vy},{vx},{N_SLICE}] = {cube[vy, vx, N_SLICE]:.1f}  (off-diagonal: vx-vy={vx-vy:+d})")

def reprompting3d_addpoint(vx, vy, n):
    """Engine ground truth (voxel units): add_point slice_axis=2 stores (voxelY, voxelX, slice)."""
    return (vy, vx, n)

print("\n== stored-point comparison (AXIS_2) ==")
print(f"  {'click(x,y)':>14} | {'OLD desktop':>14} | {'FIXED desktop':>14} | {'reprompting3d':>14} | OLD==engine? FIXED==engine?")
for (vx, vy) in clicks:
    old = (vx, vy, N_SLICE)
    fix = (vy, vx, N_SLICE)
    eng = reprompting3d_addpoint(vx, vy, N_SLICE)
    print(f"  {str((vx,vy)):>14} | {str(old):>14} | {str(fix):>14} | {str(eng):>14} | "
          f"{str(old==eng):>5}       {str(fix==eng):>5}")

# ---- Prove positional consumption with the ENGINE's parse_prompts ----
# Write a 2-point polyline under each convention, run parse_prompts, map the returned
# normalized segment endpoints back to cube indices, and confirm they equal the stored points.
def engine_cube_index_of(stored_points):
    """Run the real scale_transform.parse_prompts and recover the cube index it assigns."""
    tmp = "/tmp/axis2/_pp"
    os.makedirs(tmp, exist_ok=True)
    with open(tmp + "/points.json", "w") as f:
        json.dump({"positive": [[list(p) for p in stored_points]], "negative": []}, f)
    img_shape = (S, S, S)
    pos_seg, _ = scale_transform.parse_prompts(tmp, None, img_shape)
    # parse_prompts internally added padding_constant pc and scaled by (S + 2pc).
    pc = int((np.sqrt(3) - 1) / 2 * img_shape[0] / 2)
    padded = np.array(img_shape) + pc * 2
    recovered = []
    seen = set()
    for seg in pos_seg:
        for endpoint in seg:
            idx = tuple(np.round(scale_transform.scale_backward(np.array(endpoint), padded) - pc).astype(int))
            if idx not in seen:
                seen.add(idx)
                recovered.append(idx)
    return recovered

print("\n== ENGINE parse_prompts positional check (cube index it actually assigns) ==")
old_poly = [(vx, vy, N_SLICE) for (vx, vy) in clicks]
fix_poly = [(vy, vx, N_SLICE) for (vx, vy) in clicks]
print("  OLD stored points  ->", old_poly)
print("  engine cube index  ->", engine_cube_index_of(old_poly))
print("  FIX stored points  ->", fix_poly)
print("  engine cube index  ->", engine_cube_index_of(fix_poly))

# ---- VISUAL overlay on the real AXIS_2 slice ----
# A stored point (a,b,c) is engine cube voxel cube[a,b,c]; on the AXIS_2 display cube[h,w,n]
# sits at screen (x=w, y=h), so cube[a,b,c] is drawn at screen (x=b, y=a).
def engine_screen_xy(stored):
    a, b, c = stored
    return (b, a)  # (screen x = w = dim1 = b,  screen y = h = dim0 = a)

fig, ax = plt.subplots(figsize=(10, 10))
ax.imshow(cube[:, :, N_SLICE], cmap="gray", origin="upper")
ax.plot([0, S], [0, S], color="yellow", lw=0.6, alpha=0.7)
for i, (vx, vy) in enumerate(clicks):
    # where the user clicked (== where FIXED places the prompt)
    ax.plot(vx, vy, "o", mfc="none", mec="lime", ms=16, mew=2.2,
            label="user click = FIXED engine target" if i == 0 else None)
    # where the OLD convention places the prompt (engine reads (voxelX,voxelY,n))
    ex, ey = engine_screen_xy((vx, vy, N_SLICE))
    ax.plot(ex, ey, "x", color="red", ms=15, mew=2.5,
            label="OLD engine target (transposed)" if i == 0 else None)
ax.set_xlim(120, 420); ax.set_ylim(420, 120)
ax.set_xticks(range(120, 421, 30)); ax.set_yticks(range(120, 421, 30))
ax.grid(True, color="cyan", lw=0.25, alpha=0.4)
ax.set_title(f"AXIS_2 n={N_SLICE}: FIXED (lime ○) lands on the fibula = the click;\n"
             f"OLD (red ✕) is transposed across x=y into soft tissue")
ax.set_xlabel("screen x = w (voxelX)"); ax.set_ylabel("screen y = h (voxelY)")
ax.legend(loc="upper right", fontsize=9)
fig.tight_layout(); fig.savefig(f"{OUT}/proof_overlay_n300.png", dpi=85); plt.close(fig)
print("\nwrote proof_overlay_n300.png")

# ---- Emit points.json for the end-to-end SAM run (FIXED convention) and OLD for reference ----
for name, poly in [("FIXED", fix_poly), ("OLD", old_poly)]:
    with open(f"{OUT}/points_{name}.json", "w") as f:
        json.dump({"positive": [[list(p) for p in poly]], "negative": []}, f, indent=2)
    print(f"wrote points_{name}.json")
