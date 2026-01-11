/**
 * Vision Service - Analyzes video frames using Gemini Vision API
 * Works alongside the native audio model to provide visual understanding
 * Supports automatic model fallback when quota is exhausted
 */

import { GoogleGenerativeAI, GenerativeModel } from "@google/generative-ai";

const API_KEY = import.meta.env.VITE_GEMINI_API_KEY as string;
const genAI = new GoogleGenerativeAI(API_KEY);

// Vision models in order of preference
const VISION_MODELS = [
    "gemini-2.5-flash",
    "gemini-flash-latest",
    "gemini-3-flash-preview"
];

let currentModelIndex = 0;
let visionModel: GenerativeModel = genAI.getGenerativeModel({
    model: VISION_MODELS[currentModelIndex]
});

// Switch to next model on error
function switchToNextModel(): boolean {
    currentModelIndex++;
    if (currentModelIndex < VISION_MODELS.length) {
        console.log(`[Vision] Switching to model: ${VISION_MODELS[currentModelIndex]}`);
        visionModel = genAI.getGenerativeModel({
            model: VISION_MODELS[currentModelIndex]
        });
        return true;
    }
    console.error('[Vision] All models exhausted');
    currentModelIndex = 0; // Reset for next time
    visionModel = genAI.getGenerativeModel({ model: VISION_MODELS[0] });
    return false;
}

interface VisionAnalysis {
    description: string;
    objects: string[];
    text?: string;
    context: string;
}

// Extract any quoted text or text patterns from description
function extractTextFromDescription(text: string): string | undefined {
    // Look for quoted text
    const quotedMatch = text.match(/"([^"]+)"/);
    if (quotedMatch) return quotedMatch[1];

    // Look for "says:" or "reads:" patterns
    const saysMatch = text.match(/(?:says?|reads?|shows?|displays?):\s*(.+?)(?:\.|$)/i);
    if (saysMatch) return saysMatch[1].trim();

    return undefined;
}

/**
 * Analyze a video frame and return a description
 */
export async function analyzeFrame(base64Image: string, retryCount = 0): Promise<VisionAnalysis | null> {
    const startTime = Date.now();

    try {
        console.log(`[Vision] Using model: ${VISION_MODELS[currentModelIndex]}`);

        // Very short prompt for fastest response
        const prompt = `Briefly describe what you see in this image in 2-3 sentences. If there's text, mention it. Keep under 100 words.`;

        const result = await visionModel.generateContent({
            contents: [{
                role: "user",
                parts: [
                    { text: prompt },
                    {
                        inlineData: {
                            mimeType: "image/jpeg",
                            data: base64Image
                        }
                    }
                ]
            }],
            generationConfig: {
                maxOutputTokens: 2048, // Allow detailed analysis
                temperature: 0.1, // Direct, focused responses
            }
        });

        const response = result.response.text();
        const latency = Date.now() - startTime;
        console.log(`[Vision] Response in ${latency}ms`);
        console.log(`[Vision] Full response:`, response); // Debug log

        // Parse plain text response into our format
        return {
            description: response.slice(0, 500),
            objects: [],
            text: extractTextFromDescription(response),
            context: response
        };
    } catch (error: any) {
        console.error('[Vision] Analysis failed:', error);

        // Check if it's a quota or model error - try next model
        const errorMessage = error?.message || error?.toString() || '';
        const shouldRetry =
            errorMessage.includes('429') ||
            errorMessage.includes('quota') ||
            errorMessage.includes('404') ||
            errorMessage.includes('not found') ||
            errorMessage.includes('RESOURCE_EXHAUSTED');

        if (shouldRetry && retryCount < VISION_MODELS.length) {
            const hasNext = switchToNextModel();
            if (hasNext) {
                console.log(`[Vision] Retrying with ${VISION_MODELS[currentModelIndex]}...`);
                return analyzeFrame(base64Image, retryCount + 1);
            }
        }

        return null;
    }
}

/**
 * Format vision analysis as a message for the audio model
 */
export function formatVisionContext(analysis: VisionAnalysis): string {
    let context = `[Visual Context: ${analysis.description}`;

    if (analysis.objects.length > 0) {
        context += ` | Objects: ${analysis.objects.join(', ')}`;
    }

    if (analysis.text) {
        context += ` | Text visible: "${analysis.text}"`;
    }

    context += ']';
    return context;
}

/**
 * Get current vision model name
 */
export function getCurrentVisionModel(): string {
    return VISION_MODELS[currentModelIndex];
}
