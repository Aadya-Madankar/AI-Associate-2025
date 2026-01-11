/**
 * Frame Buffer Service - Continuously captures and stores frames for vision analysis
 * Full quality - no compression for Gemini 2.5 Flash
 */

// Frame buffer configuration
const BUFFER_SIZE = 30; // Store last 30 frames (~30 seconds at 1 FPS)
const CAPTURE_FPS = 1; // Capture 1 frame per second for efficiency
const JPEG_QUALITY = 0.95; // High quality for text readability
// No size limit - Gemini 2.5 Flash handles full resolution

interface BufferedFrame {
    data: string; // Base64 encoded JPEG
    timestamp: number;
    width: number;
    height: number;
}

// Circular buffer for frames
const frameBuffer: BufferedFrame[] = [];
let captureInterval: number | null = null;
let videoElement: HTMLVideoElement | null = null;
let canvasElement: HTMLCanvasElement | null = null;
let isCapturing = false;

/**
 * Initialize the frame buffer with a video element
 */
export function initFrameBuffer(video: HTMLVideoElement | null) {
    videoElement = video;
    if (!canvasElement) {
        canvasElement = document.createElement('canvas');
    }
}

/**
 * Start continuous frame capture
 */
export function startFrameCapture(video?: HTMLVideoElement) {
    if (video) {
        videoElement = video;
    }

    if (!videoElement || isCapturing) {
        return;
    }

    if (!canvasElement) {
        canvasElement = document.createElement('canvas');
    }

    isCapturing = true;
    console.log('[FrameBuffer] Started continuous capture');

    captureInterval = window.setInterval(() => {
        captureFrame();
    }, 1000 / CAPTURE_FPS);

    // Capture first frame immediately
    captureFrame();
}

/**
 * Stop frame capture
 */
export function stopFrameCapture() {
    if (captureInterval) {
        window.clearInterval(captureInterval);
        captureInterval = null;
        console.log('[FrameBuffer] Stopped capture');
    }
    isCapturing = false;
}

/**
 * Clear the frame buffer
 */
export function clearFrameBuffer() {
    frameBuffer.length = 0;
}

/**
 * Capture a single frame to the buffer
 */
function captureFrame() {
    if (!videoElement || !canvasElement) {
        return;
    }

    // Wait for video to be ready
    if (videoElement.readyState < 2 || videoElement.videoWidth === 0) {
        return;
    }

    const ctx = canvasElement.getContext('2d');
    if (!ctx) return;

    // Use full resolution - no scaling for maximum quality
    const width = videoElement.videoWidth;
    const height = videoElement.videoHeight;

    canvasElement.width = width;
    canvasElement.height = height;

    ctx.drawImage(videoElement, 0, 0, width, height);

    const dataUrl = canvasElement.toDataURL('image/jpeg', JPEG_QUALITY);
    const base64Data = dataUrl.slice(dataUrl.indexOf(',') + 1);

    const frame: BufferedFrame = {
        data: base64Data,
        timestamp: Date.now(),
        width,
        height
    };

    // Add to buffer (circular)
    frameBuffer.push(frame);
    if (frameBuffer.length > BUFFER_SIZE) {
        frameBuffer.shift();
    }
}

/**
 * Get the most recent frame from the buffer
 */
export function getLatestFrame(): BufferedFrame | null {
    if (frameBuffer.length === 0) {
        // If no buffered frames, capture one now
        captureFrame();
    }

    return frameBuffer.length > 0 ? frameBuffer[frameBuffer.length - 1] : null;
}

/**
 * Get multiple recent frames for context
 * @param count Number of frames to get
 * @param intervalMs Minimum interval between frames in milliseconds
 */
export function getRecentFrames(count: number = 3, intervalMs: number = 2000): BufferedFrame[] {
    if (frameBuffer.length === 0) {
        captureFrame();
        return frameBuffer.slice();
    }

    const result: BufferedFrame[] = [];
    let lastTimestamp = Infinity;

    // Walk backwards through buffer, picking frames spaced apart
    for (let i = frameBuffer.length - 1; i >= 0 && result.length < count; i--) {
        const frame = frameBuffer[i];
        if (lastTimestamp - frame.timestamp >= intervalMs || result.length === 0) {
            result.unshift(frame);
            lastTimestamp = frame.timestamp;
        }
    }

    return result;
}

/**
 * Get frame buffer stats
 */
export function getBufferStats() {
    return {
        frameCount: frameBuffer.length,
        isCapturing,
        oldestTimestamp: frameBuffer.length > 0 ? frameBuffer[0].timestamp : null,
        newestTimestamp: frameBuffer.length > 0 ? frameBuffer[frameBuffer.length - 1].timestamp : null,
        bufferDuration: frameBuffer.length > 1
            ? frameBuffer[frameBuffer.length - 1].timestamp - frameBuffer[0].timestamp
            : 0
    };
}

/**
 * Manually add a high-quality frame (for on-demand capture)
 */
export function captureHighQualityFrame(video: HTMLVideoElement): BufferedFrame | null {
    if (!video || video.readyState < 2 || video.videoWidth === 0) {
        return null;
    }

    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    // Use higher quality for on-demand captures
    const width = video.videoWidth;
    const height = video.videoHeight;

    canvas.width = width;
    canvas.height = height;
    ctx.drawImage(video, 0, 0, width, height);

    const dataUrl = canvas.toDataURL('image/jpeg', 0.9); // Higher quality
    const base64Data = dataUrl.slice(dataUrl.indexOf(',') + 1);

    const frame: BufferedFrame = {
        data: base64Data,
        timestamp: Date.now(),
        width,
        height
    };

    // Also add to buffer
    frameBuffer.push(frame);
    if (frameBuffer.length > BUFFER_SIZE) {
        frameBuffer.shift();
    }

    return frame;
}
