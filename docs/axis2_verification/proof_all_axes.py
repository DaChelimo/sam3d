"""
Completeness check for ALL THREE axes (the plan asks to "re-derive AXIS_1/AXIS_0 too").

Criterion (axis-independent): the engine consumes a stored point positionally as the cube array
index, so embedVoxel(axis, slice, voxelX, voxelY) must equal the ARRAY INDEX of the voxel the user
clicked. The clicked voxel is fixed by Dcm4cheLoader.loadSliceBitmap's per-axis screen orientation:

  AXIS_0 (fix H=slice): cube[slice, w, n] shown at screen (x=w, y=n)  -> click (vx,vy) hits (slice, vx, vy)
  AXIS_1 (fix W=slice): cube[h, slice, n] shown at screen (x=h, y=n)  -> click (vx,vy) hits (vx, slice, vy)
  AXIS_2 (fix N=slice): cube[h, w, slice] shown at screen (x=w, y=h)  -> click (vx,vy) hits (vy, vx, slice)

We pick a distinctive asymmetric voxel (the fibula at cube (h,w,n)=(230,310,300)), and for each axis
compute the on-screen click that lands on it, then check OLD vs FIXED embedVoxel against the true index.
"""
import numpy as np, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
cube = np.load("/tmp/axis2/cube_f16.npy").astype(np.float32)
OUT = "/Users/DaChelimo/Documents/Research/sam3d/docs/axis2_verification"

def embed_OLD(axis, slc, vx, vy):
    return [(slc, vx, vy), (vx, slc, vy), (vx, vy, slc)][axis]   # AXIS_2 = (vx,vy,slc)  [buggy]
def embed_FIXED(axis, slc, vx, vy):
    return [(slc, vx, vy), (vx, slc, vy), (vy, vx, slc)][axis]   # AXIS_2 = (vy,vx,slc)  [fixed]

# Distinctive asymmetric cube voxel (the fibula). h != w != n so transposes are detectable.
V = (230, 310, 300)   # (h, w, n)
h0, w0, n0 = V

# For each axis: which slice is fixed, and where V appears on screen (per loadSliceBitmap), so the
# click that lands on V is (vx, vy) = that screen position.
cases = {
    0: dict(slc=h0, vx=w0, vy=n0),   # fix H; screen (x=w, y=n)
    1: dict(slc=w0, vx=h0, vy=n0),   # fix W; screen (x=h, y=n)
    2: dict(slc=n0, vx=w0, vy=h0),   # fix N; screen (x=w, y=h)
}
print(f"True cube array index of the clicked voxel V = {V} (h,w,n)\n")
print(f"{'axis':>4} | {'fixed slice':>11} | {'click(vx,vy)':>13} | {'OLD embed':>14} | {'FIXED embed':>14} | OLD ok? FIXED ok?")
for ax in (0, 1, 2):
    c = cases[ax]
    old = embed_OLD(ax, c['slc'], c['vx'], c['vy'])
    fix = embed_FIXED(ax, c['slc'], c['vx'], c['vy'])
    print(f"{ax:>4} | {c['slc']:>11} | {str((c['vx'],c['vy'])):>13} | {str(old):>14} | {str(fix):>14} | "
          f"{str(old==V):>5}   {str(fix==V):>5}")

# Visual: AXIS_0 plane (cube[h0,:,:]) and AXIS_1 plane (cube[:,w0,:]) with V marked at its screen pos.
fig, ax = plt.subplots(1, 2, figsize=(16, 8))
# AXIS_0: fix H=h0, screen x=w, y=n  -> show cube[h0,:,:] as [w(row?), n] ... imshow needs [row=y=n, col=x=w]
img0 = cube[h0, :, :].T                # transpose so rows=n (y), cols=w (x)
ax[0].imshow(img0, cmap="gray", origin="upper")
ax[0].plot(w0, n0, "o", mfc="none", mec="lime", ms=16, mew=2.2)
ax[0].set_title(f"AXIS_0 slice H={h0}: V appears at screen (x=w={w0}, y=n={n0})")
ax[0].set_xlabel("x = w (voxelX)"); ax[0].set_ylabel("y = n (voxelY)")
# AXIS_1: fix W=w0, screen x=h, y=n  -> cube[:,w0,:] is [h, n]; imshow needs [row=y=n, col=x=h] => transpose
img1 = cube[:, w0, :].T                # rows=n (y), cols=h (x)
ax[1].imshow(img1, cmap="gray", origin="upper")
ax[1].plot(h0, n0, "o", mfc="none", mec="lime", ms=16, mew=2.2)
ax[1].set_title(f"AXIS_1 slice W={w0}: V appears at screen (x=h={h0}, y=n={n0})")
ax[1].set_xlabel("x = h (voxelX)"); ax[1].set_ylabel("y = n (voxelY)")
fig.tight_layout(); fig.savefig(f"{OUT}/proof_axes01_planes.png", dpi=80); plt.close(fig)
print("\nwrote proof_axes01_planes.png")
