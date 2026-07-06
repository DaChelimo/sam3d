import os
import urllib.request

DEFAULT_URL = "https://dl.fbaipublicfiles.com/segment_anything/sam_vit_h_4b8939.pth"
DEFAULT_PATH = os.path.join("checkpoints", "sam_vit_h_4b8939.pth")

def ensure_checkpoint(path: str = DEFAULT_PATH, url: str = DEFAULT_URL) -> str | None:
    """Ensure a SAM checkpoint exists at *path*.

    If the file does not exist, attempt to download it from *url*.
    Returns the path if available, otherwise ``None``.
    """
    if os.path.exists(path):
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        print(f"Downloading SAM checkpoint from {url}...")
        urllib.request.urlretrieve(url, path)
        print("Download complete.")
        return path
    except Exception as exc:
        print(f"Failed to download checkpoint: {exc}")
        if os.path.exists(path):
            return path
        return None

if __name__ == "__main__":
    result = ensure_checkpoint()
    if result:
        print(f"Checkpoint available at {result}")
    else:
        print(
            "Could not retrieve checkpoint automatically. "
            "Please place the file manually in the 'checkpoints' directory."
        )
