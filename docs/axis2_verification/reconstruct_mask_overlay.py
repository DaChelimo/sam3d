"""
End-to-end SAM confirmation: did the FIXED-convention AXIS_2 prompt segment the fibula
(where we drew) or its diagonal mirror?

The engine saves the mask scrambled: load3dmatrix builds the cube as (H,W,N) but the save path's
load_dicom_series stacks as (N,H,W), and remove_symmetrical_cube_padding crops against that (N,H,W)
shape. Net effect (derived from the source):
    slice_{k}.dcm.pixel_array[b, c]  ==  cube_mask[H=k, W=25+b, N=25+c]
i.e. mask DICOM "slice k" is a fixed-H plane, NOT the axial (N) slice k. So to view the mask on the
AXIS_2 (N=300) plane we drew on, we must reconstruct:
    mask_cube[h, w, n] = M[h, w-25, n-25]      where M[k] = slice_{k}.dcm.pixel_array
    => mask_cube[:, 25:537, 300] = M[:, :, 275]
(leftH=leftW=25, leftN=0 for this S=562 cube.)
"""
import sys, os, glob
sys.path.insert(0, "/Users/DaChelimo/Documents/Research/SAM3D-GCODE")
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pydicom

OUT = "/Users/DaChelimo/Documents/Research/sam3d/docs/axis2_verification"
MASK_DIR = "/Users/DaChelimo/Documents/Research/OUTPUT_axis2/temp/segmentation_mask"
N_SLICE = 300
LEFT = 25  # leftH = leftW for S=562 (H=W=512); leftN = 0

cube = np.load("/tmp/axis2/cube_f16.npy").astype(np.float32)
S = cube.shape[0]

# ---- Load mask DICOMs into M[k] = slice_k.dcm.pixel_array, k = 0..S-1 ----
files = glob.glob(os.path.join(MASK_DIR, "slice_*.dcm"))
assert files, f"no mask DICOMs in {MASK_DIR} yet"
def kof(p): return int(os.path.basename(p).split("_")[1].split(".")[0])
files.sort(key=kof)
ks = [kof(f) for f in files]
print(f"mask slices: {len(files)}  k range [{min(ks)}, {max(ks)}]")

# The engine's save_dicom_series writes an int64 (8-byte) 0/1 mask into a 16-bit DICOM header, so
# ds.pixel_array mis-reads it as 4 frames. Read the raw PixelData as int64 instead.
def read_mask_slice(path):
    raw = pydicom.dcmread(path).PixelData
    return np.frombuffer(raw, dtype="<i8").reshape(512, 512)
first = read_mask_slice(files[0])
print("mask slice shape/dtype:", first.shape, first.dtype, " unique=", np.unique(first)[:6])
H2, W2 = first.shape  # 512, 512
M = np.zeros((S, H2, W2), dtype=np.float32)
for f in files:
    M[kof(f)] = read_mask_slice(f).astype(np.float32)
M = (M > 0).astype(np.float32)  # binarise (mask is 0/1)
print("total nonzero mask voxels:", int(M.sum()))

# ---- Where is the mask in CUBE coordinates? (sanity / quantitative) ----
# nonzero M[k,b,c] -> cube voxel (h=k, w=b+LEFT, n=c+LEFT)
nz = np.argwhere(M > 0)
if len(nz):
    h = nz[:, 0]; w = nz[:, 1] + LEFT; n = nz[:, 2] + LEFT
    print(f"mask cube centroid (h,w,n) = ({h.mean():.0f}, {w.mean():.0f}, {n.mean():.0f})")
    print(f"  h range [{h.min()},{h.max()}]  w range [{w.min()},{w.max()}]  n range [{n.min()},{n.max()}]")
    print("EXPECT near the fibula: h~210-255, w~290-330, n~300 (the click).")
    print("Diagonal-mirror failure would instead show h~290-330, w~210-255.")

# ---- Reconstruct the AXIS_2 (N) plane of the mask and overlay on the input slice ----
# Max-project the mask over a small N band around 300 so a few-voxel-thick blob is visible.
band = range(max(0, N_SLICE - LEFT - 4), min(W2, N_SLICE - LEFT + 5))   # c = n-LEFT
mask_hw = np.zeros((S, S), dtype=np.float32)
mask_hw[:, LEFT:LEFT + W2] = M[:, :, list(band)].max(axis=2)            # cube[:,25:537,~300]

fig, ax = plt.subplots(1, 2, figsize=(18, 9))
for a in ax:
    a.imshow(cube[:, :, N_SLICE], cmap="gray", origin="upper")
    a.set_xlim(120, 420); a.set_ylim(420, 120)
    a.set_xlabel("screen x = w (voxelX)"); a.set_ylabel("screen y = h (voxelY)")
    a.plot([0, S], [0, S], color="yellow", lw=0.5, alpha=0.6)
# left: the prompt we drew (FIXED engine targets, lime)
clicks = [(305, 208), (328, 226), (312, 252), (290, 230)]
for vx, vy in clicks:
    ax[0].plot(vx, vy, "o", mfc="none", mec="lime", ms=14, mew=2)
ax[0].set_title("What we drew: positive prompt on the fibula (lime)")
# right: the SAM mask reconstructed onto the same n=300 plane (red)
red = np.zeros((S, S, 4)); red[..., 0] = 1.0; red[..., 3] = (mask_hw > 0) * 0.55
ax[1].imshow(red, origin="upper")
ax[1].set_title("SAM segmentation mask on n=300 (red) — must sit on the fibula, not the mirror")
fig.tight_layout(); fig.savefig(f"{OUT}/sam_mask_overlay_n300.png", dpi=85); plt.close(fig)
print("wrote sam_mask_overlay_n300.png")
