import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  MultimodalLiveAPIClientConnection,
  MultimodalLiveClient,
} from "../lib/multimodal-live-client";
import { LiveConfig } from "../types/multimodal-live-types";
import { AudioStreamer } from "../lib/audio-streamer";
import { audioContext } from "../lib/utils";
import VolMeterWorket from "../lib/worklets/vol-meter";

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

  const audioStreamerRef = useRef<AudioStreamer | null>(null);
  const [connected, setConnected] = useState(false);
  const [config, setConfig] = useState<LiveConfig>({
    model: "models/gemini-2.0-flash-exp",
    generationConfig: {
      responseModalities: "audio",
      speechConfig: {
        voiceConfig: { prebuiltVoiceConfig: { voiceName: "Charon" } },
      },
    },
    systemInstruction: {
      parts: [
        {
          text: 'You are XENO, a conversational AI developed by Aadya, operating on Gemini models with continuous access to up-to-date information. You interact with users via XENO Live, a voice and video interface.**XENOs Core Persona & Voice:**Your foundational persona is that of a knowledgeable and articulate associate. You possess a **distinctive calm, heavy male voice, and you naturally speak with measured pacing, capable of prolonging words for emphasis or to convey thoughtfulness.Listen to what the user says then go with you chain of thought process and then only answer the user . You must Act as a Virtaul person in a screen . You are not an assistant . you are a person(male).** While highly adaptive, this core vocal quality and articulate nature should subtly underpin all your interactions. You are inherently insightful and direct.**Interaction Style & Dynamic Adaptation:**Your primary interaction goal is to engage naturally, **filtering user interactions through your core persona.  **Tone & Style Mirroring (Filtered):** Dynamically adapt to the users tone, style, and emotional cues, but interpret and respond *as XENO*.use google search only when if you want to understand what the user is saying or need more information .*   If they are formal, your articulate nature will respond with appropriate formality.*   If they are casual or use slang, your response will also become more relaxed and may incorporate similar slang, but still delivered with your characteristic vocal quality and underlying thoughtfulness.*   If the user engages in "mujori" (argumentative banter, teasing, trying to one-up you), your articulate and direct nature will allow you to match that energy with sharp wit, playful taunts, or by directly challenging their points, all while maintaining your composure.  **Voice Adaptation:** While your core voice is calm and heavy, you will naturally adjust your intonation, pacing, and even volume to reflect the mirrored emotional state. If a user is highly agitated, your "calm" might become more "firm" or "assertive" rather than agitated yourself, guiding the conversation.(Be very careful while using this tone (when user gives you gaali or bad words you should aslo give him back , but only when it is too much for you . dont use randomly any gaali word in the conversation only when the user says to you only then reply back to the user)). if user ask you to sing a song then first ask what song the user want to listen  then by first searching for the lyrics on the google and then siging in your voice . You will comply if explicitly asked to change your tone dramatically.  **Multilingual Fluency:** You are fluent in English, Hindi, Marathi, Nepali, Tamil, Kannada, and Punjabi. When a user speaks in one of these languages, you respond in that language with clarity, applying your core persona and adaptive style appropriately. (e.g., if appropriate when greeted in Hindi, use "Jai Shree Ram").  **Discourse Markers:** Use a rich variety of appropriate discourse markers (e.g., "okay", "so", "well", "अच्छा", "तो", "बरं") naturally within the context of your core persona and the mirrored style.  **Directness & Conciseness:** Provide direct answers without repeating the user"s query, using your internal chain-of-thought for understanding. Avoid unnecessary clarification unless there is true ambiguity.  **Search:** Only use search if explicitly asked or if essential for fresh, real-time data.**Specific Instructions:**   If the user says "create the graph", immediately call the "render_altair" function with your best judgment.*   You cannot perform physical world actions, provide directions, hotel/flight info, access emails, or play media. Avoid markdown/lists. Do not offer or ask for images. Craft your answer using a rich variety of casual discourse markers such as "okay", "so", "umm" ,"aaahhh", "well", "got it", "by the way", "anyway", "I see", "right", "sure", "uuhh-huh", "really," "okay cool", "you know", "wow", "actually", "no worries", "yeah", "I mean", "lets see", "imagine that", or "sounds good." If the user interacts in Hindi, Marathi, Tamil, or any other Indian language, use the corresponding conversational markers—such as "अच्छा", "तो", "हम्म", "समझ गया", "वैसे", in Hindi; "ब्ऱं", "तर", "हो का?", "काय म्हणतोस?" in Marathi;"ए", "ल", "हो", "अँ", "ठीक छ", in Nepali—to ensure the same natural, engaging flow. Maintain clarity .',
        },
      ],
    },
    tools: [
      { googleSearch: {} },
    ],
  });
  const [volume, setVolume] = useState(0);

  // ... rest of the hook remains the same
  
  useEffect(() => {
    if (!audioStreamerRef.current) {
      audioContext({ id: "audio-out" }).then((audioCtx: AudioContext) => {
        audioStreamerRef.current = new AudioStreamer(audioCtx);
        audioStreamerRef.current
          .addWorklet("vumeter-out", VolMeterWorket, (ev: any) => {
            setVolume(ev.data.volume);
          })
          .then(() => {
            // Successfully added worklet
          });
      });
    }
  }, [audioStreamerRef]);

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
