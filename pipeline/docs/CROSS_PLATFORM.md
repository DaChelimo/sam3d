# Cross-Platform Testing Guide

This document provides tips for validating the SAM3D desktop application on Windows, macOS and Linux.

## Local Testing

1. Install **Python 3.11** and **Node.js 18+** on the target system.
2. Clone the repository and install dependencies:
   ```bash
   pip install -r requirements.txt
   npm install
   ```
3. Run the platform build script (`build-windows.bat`, `build-linux.sh` or `build-macos.sh`).
4. Launch the packaged application from `out/make/` and confirm the wizard UI loads and the backend starts.

## Virtual Machine / Container Setup

- **Windows**: Use Hyper-V or VirtualBox to create a Windows 11 VM. Ensure the VM has network access for npm and PyInstaller downloads.
- **macOS**: Apple Silicon requires virtualization via Parallels or VMware Fusion. Enable developer mode to allow unsigned apps.
- **Linux**: Any recent distribution works. Docker can be used to test headless builds with Xvfb for Electron.

## Troubleshooting

- Missing Python modules during the build usually mean the environment was not activated. Run `conda activate SAM3D_GCODE`.
- If the backend executable fails to start, check `backend.log` in the user data directory for errors.
- On Windows, ensure antivirus software is not blocking the `sam3d-backend.exe` file.
- On Linux, some distributions require `libX11` and other desktop libraries to be installed for Electron.

For additional help, open an issue on GitHub or consult the README.
