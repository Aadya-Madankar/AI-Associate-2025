import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  MultimodalLiveAPIClientConnection,
  MultimodalLiveClient,
} from "../lib/multimodal-live-client";
import { LiveConfig } from "../types/multimodal-live-types";
import { AudioStreamer } from "../lib/audio-streamer";
import { audioContext } from "../lib/utils";
import VolMeterWorket from "../lib/worklets/vol-meter";
import { getActiveAgent } from "../config";

export type UseLiveAPIResults = {
  client: MultimodalLiveClient;
  setConfig: (config: LiveConfig) => void;
  config: LiveConfig;
  connected: boolean;
  connect: () => Promise<void>;
  disconnect: () => Promise<void>;
  volume: number;
};

export function useLiveAPI({
  url,
  apiKey,
}: MultimodalLiveAPIClientConnection): UseLiveAPIResults {
  const client = useMemo(
    () => new MultimodalLiveClient({ url, apiKey }),
    [url, apiKey],
  );

  // Get active agent configuration
  const agent = getActiveAgent();

  const audioStreamerRef = useRef<AudioStreamer | null>(null);
  const [connected, setConnected] = useState(false);
  const [config, setConfig] = useState<LiveConfig>({
    // Native audio model for voice - vision handled separately by vision-service
    model: "models/gemini-2.5-flash-native-audio-preview-12-2025",
    generationConfig: {
      responseModalities: ["audio"],
      speechConfig: {
        voiceConfig: { prebuiltVoiceConfig: { voiceName: agent.voice } },
      },
    },
    systemInstruction: {
      parts: [
        {
          text: agent.systemPrompt,
        },
      ],
    },
    tools: [
      // Google Search for real-time web information
      { googleSearch: {} },
      // Code execution for running code
      { codeExecution: {} },
      // Visual analysis tool - allows model to see camera/screen
      {
        functionDeclarations: [{
          name: "analyzeVisual",
          description: "AUTOMATICALLY call this tool whenever you need to see what the user is showing on their camera or screen share. Use this: 1) When user says 'look at this', 'what do you see', 'can you see', 'show you' etc. 2) When user asks about something visible like 'what is this', 'read this', 'help me with this' while video is active. 3) When user references 'my screen', 'this document', 'this receipt' etc. Call this tool AUTOMATICALLY - don't ask permission first.",
          parameters: {
            type: "object",
            properties: {
              query: {
                type: "string",
                description: "What to analyze in the visual - describe what user wants to know (e.g., 'describe what you see', 'read the text on the document', 'identify items on the receipt')"
              }
            },
            required: ["query"]
          }
        }]
      }
    ],
  });
  const [volume, setVolume] = useState(0);

  // Throttle volume updates to prevent render loops
  const lastVolumeUpdateRef = useRef(0);
  const throttledSetVolume = useCallback((vol: number) => {
    const now = Date.now();
    // Only update volume every 100ms to prevent excessive re-renders
    if (now - lastVolumeUpdateRef.current > 100) {
      lastVolumeUpdateRef.current = now;
      setVolume(vol);
    }
  }, []);

  // ... rest of the hook remains the same

  useEffect(() => {
    if (!audioStreamerRef.current) {
      audioContext({ id: "audio-out" }).then((audioCtx: AudioContext) => {
        audioStreamerRef.current = new AudioStreamer(audioCtx);
        audioStreamerRef.current
          .addWorklet("vumeter-out", VolMeterWorket, (ev: any) => {
            throttledSetVolume(ev.data.volume);
          })
          .then(() => {
            // Successfully added worklet
          });
      });
    }
  }, [throttledSetVolume]);

  useEffect(() => {
    const onClose = () => {
      setConnected(false);
    };

    const stopAudioStreamer = () => audioStreamerRef.current?.stop();

    const onAudio = (data: ArrayBuffer) =>
      audioStreamerRef.current?.addPCM16(new Uint8Array(data));

    client
      .on("close", onClose)
      .on("interrupted", stopAudioStreamer)
      .on("audio", onAudio);

    return () => {
      client
        .off("close", onClose)
        .off("interrupted", stopAudioStreamer)
        .off("audio", onAudio);
    };
  }, [client]);

  const connect = useCallback(async () => {
    console.log(config);
    if (!config) {
      throw new Error("config has not been set");
    }
    client.disconnect();
    await client.connect(config);
    setConnected(true);
  }, [client, setConnected, config]);

  const disconnect = useCallback(async () => {
    client.disconnect();
    setConnected(false);
  }, [setConnected, client]);

  return {
    client,
    config,
    setConfig,
    connected,
    connect,
    disconnect,
    volume,
  };
}
