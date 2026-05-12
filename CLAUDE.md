# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Develop

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean + release + archive (custom paths in build_release.sh)
./build_release.sh

# Lint
./gradlew lint

# Run unit tests (JVM, no device needed)
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

APK outputs go to `app/build/outputs/apk/`. Releases are signed via keystore config in `local.properties` (ignore in `.gitignore`).

## Architecture

This is a single-module Android app (Kotlin, Jetpack Compose + Material 3) that turns a phone into a remote camera. It streams H.264 video via RTP/UDP using a WebSocket signaling channel with AES-256-GCM encryption.

### Signaling and video pipeline

**WebSocket signaling** — `WebSocketManager` wraps either a `WsServer` (camera host) or `WsClient` (viewer). All messages are JSON (`WsMessage`) encrypted with AES-256-GCM (key derived via PBKDF2 from a password). The client sends `auth` first; unauthenticated connections are rejected. After auth, the client sends `start_rtp` with its local RTP port, and the server responds with its own RTP port and video resolution.

**Camera host (server side):**
1. `RemoteCameraService` (foreground service) manages CameraX + OpenGL rendering + MediaCodec H.264 encoding + `RtpSender`. It exposes two preview modes:
   - **Service-owned**: `bindCamera()` creates a CameraX `Preview` internally, sets up a `SurfaceProvider` that feeds camera frames into a `SurfaceTexture` (GLES OES texture).
   - **External**: Activity creates its own `Preview`, calls `service.getSurfaceProvider()` + `service.bindPreviewUseCase(preview)`.
2. `RenderThread` waits on `frameLock` for the `SurfaceTexture.OnFrameAvailableListener`, then calls `updateTexImage()` and renders the OES texture via GLES to both a preview `EGLSurface` and an encoder `EGLSurface`.
3. `MediaCodec` encodes H.264 at 720p@30fps with 2Mbps bitrate, surface input. A drain thread reads output buffers and feeds NAL units to `RtpSender`.
4. `RtpSender` splits NALUs into RTP packets (FU-A fragmentation for large NALUs), sends via UDP (dual-stack, bound to `::`). It caches SPS/PPS and prepends them to IDR frames and to newly joining clients.
5. Encoding starts/stops on-demand: only when a client is connected.

**Viewer (client side):**
1. `CameraViewerActivity` creates a `SurfaceView`, starts `RtpReceiver` + `H264Decoder`, and connects `WebSocketManager` as client.
2. After auth, it sends `start_rtp` with its local RTP port → server streams RTP back → `RtpReceiver` reassembles packets with a sliding-window jitter buffer, FU-A reassembly, timeout-based gap skipping + keyframe requests.
3. `H264Decoder` (MediaCodec) decodes H.264 to the `SurfaceView`.

### Activity flow

- `MainActivity` — Home screen, handles `rcamera://add?wsUrl=...&port=...&password=...` deep links by forwarding to `AddCameraActivity`.
- `CameraActivity` — Host mode. Binds to `RemoteCameraService`, shows preview + connection info (IPv4/IPv6 toggle, QR code for easy pairing).
- `CameraListActivity` — Lists saved cameras from `CameraStore`. Tapping one opens `CameraViewerActivity`.
- `AddCameraActivity` — QR scanner (MLKit Barcode) or manual input. Saves to `CameraStore`.
- `CameraViewerActivity` — Viewer mode. WebSocket + RTP + H.264 decode pipeline.

### Data layer

`CameraStore` — thin SharedPreferences wrapper (Gson-serialized JSON). Stores:
- List of `RemoteCamera` objects (id, name, wsUrl, password, addedAt)
- Server config: password (`"123456"` default), port (`8888` default)

### Key packages

| Package | Purpose |
|---------|---------|
| `com.dragon.rcamera` | Activities + `RemoteCameraService` |
| `com.dragon.rcamera.websocket` | WebSocket server/client + crypto + messaging |
| `com.dragon.rcamera.rtp` | RTP packetizer, sender, receiver, H.264 decoder |
| `com.dragon.rcamera.data` | Persistence layer (`CameraStore`, `RemoteCamera`) |
| `com.dragon.rcamera.ui.theme` | Compose theme (Material 3) |

### IPv6 / dual-stack

`IpInfo` (in `WsServer.getIpInfo()`) enumerates network interfaces to find global unicast IPv6 vs link-local vs IPv4. RTP sockets bind to `::` for dual-stack support. The UI shows IPv6 with a green "supports remote access" indicator; IPv4 is flagged as LAN-only.
