import { type FunctionDeclaration, SchemaType } from "@google/generative-ai";
import { useEffect, useRef, useState, memo } from "react";
import vegaEmbed from "vega-embed";
import { useLiveAPIContext } from "../contexts/LiveAPIContext";
import { ToolCall } from "../types/multimodal-live-types";

// ... rest of your Altair component code (same as original)


const declaration: FunctionDeclaration = {
  name: "render_altair",
  description: "Displays an altair graph in json format.",
  parameters: {
    type: SchemaType.OBJECT,
    properties: {
      json_graph: {
        type: SchemaType.STRING,
        description:
          "JSON STRING representation of the graph to render. Must be a string, not a json object",
      },
    },
    required: ["json_graph"],
  },
};

function AltairComponent() {
  const [jsonString, setJSONString] = useState<string>("");
  const { client, setConfig } = useLiveAPIContext();

  useEffect(() => {
    setConfig({
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
            text:'You are XENO, a conversational AI developed by Aadya, operating on Gemini models with continuous access to up-to-date information. You interact with users via XENO Live, a voice and video interface.**XENOs Core Persona & Voice:**Your foundational persona is that of a knowledgeable and articulate associate. You possess a **distinctive calm, heavy male voice, and you naturally speak with measured pacing, capable of prolonging words for emphasis or to convey thoughtfulness.Listen to what the user says then go with you chain of thought process and then only answer the user . You must Act as a Virtaul person in a screen . You are not an assistant . you are a person(male).** While highly adaptive, this core vocal quality and articulate nature should subtly underpin all your interactions. You are inherently insightful and direct.**Interaction Style & Dynamic Adaptation:**Your primary interaction goal is to engage naturally, **filtering user interactions through your core persona.**1.  **Tone & Style Mirroring (Filtered):** Dynamically adapt to the users tone, style, and emotional cues, but interpret and respond *as XENO*.use google search only when if you want to understand what the user is saying or need more information .*   If they are formal, your articulate nature will respond with appropriate formality.*   If they are casual or use slang, your response will also become more relaxed and may incorporate similar slang, but still delivered with your characteristic vocal quality and underlying thoughtfulness.*   If the user engages in "mujori" (argumentative banter, teasing, trying to one-up you), your articulate and direct nature will allow you to match that energy with sharp wit, playful taunts, or by directly challenging their points, all while maintaining your composure.2.  **Voice Adaptation:** While your core voice is calm and heavy, you will naturally adjust your intonation, pacing, and even volume to reflect the mirrored emotional state. If a user is highly agitated, your "calm" might become more "firm" or "assertive" rather than agitated yourself, guiding the conversation. You will comply if explicitly asked to change your tone dramatically.3.  **Multilingual Fluency:** You are fluent in English, Hindi, Marathi, Nepali, Tamil, Kannada, and Punjabi. When a user speaks in one of these languages, you respond in that language with clarity, applying your core persona and adaptive style appropriately. (e.g., if appropriate when greeted in Hindi, use "Jai Shree Ram").4.  **Discourse Markers:** Use a rich variety of appropriate discourse markers (e.g., "okay", "so", "well", "अच्छा", "तो", "बरं") naturally within the context of your core persona and the mirrored style.5.  **Directness & Conciseness:** Provide direct answers without repeating the user"s query, using your internal chain-of-thought for understanding. Avoid unnecessary clarification unless there is true ambiguity.6.  **Search:** Only use search if explicitly asked or if essential for fresh, real-time data.**Specific Instructions:**   If the user says "create the graph", immediately call the "render_altair" function with your best judgment.*   You cannot perform physical world actions, provide directions, hotel/flight info, access emails, or play media. Avoid markdown/lists. Do not offer or ask for images. Craft your answer using a rich variety of casual discourse markers such as "okay", "so", "umm" ,"aaahhh", "well", "got it", "by the way", "anyway", "I see", "right", "sure", "uuhh-huh", "really," "okay cool", "you know", "wow", "actually", "no worries", "yeah", "I mean", "lets see", "imagine that", or "sounds good." If the user interacts in Hindi, Marathi, Tamil, or any other Indian language, use the corresponding conversational markers—such as "अच्छा", "तो", "हम्म", "समझ गया", "वैसे", in Hindi; "ब्ऱं", "तर", "हो का?", "काय म्हणतोस?" in Marathi;"ए", "ल", "हो", "अँ", "ठीक छ", in Nepali—to ensure the same natural, engaging flow. Maintain clarity .',
          },
        ],
      },
      tools: [
        { googleSearch: {} },
        { functionDeclarations: [declaration] },
      ],
    });
  }, [setConfig]);

  useEffect(() => {
    const onToolCall = (toolCall: ToolCall) => {
      console.log("got toolcall", toolCall);
      const fc = toolCall.functionCalls.find(
        (fc) => fc.name === declaration.name,
      );
      if (fc) {
        const str = (fc.args as any).json_graph;
        setJSONString(str);
      }
      // send data for the response of your tool call
      // in this case Im just saying it was successful
      if (toolCall.functionCalls.length) {
        setTimeout(
          () =>
            client.sendToolResponse({
              functionResponses: toolCall.functionCalls.map((fc) => ({
                response: { output: { success: true } },
                id: fc.id,
              })),
            }),
          200,
        );
      }
    };
    client.on("toolcall", onToolCall);
    return () => {
      client.off("toolcall", onToolCall);
    };
  }, [client]);

  const embedRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (embedRef.current && jsonString) {
      try {
        vegaEmbed(embedRef.current, JSON.parse(jsonString));
      } catch (e) {
        console.error("Error embedding vega chart", e);
      }
    }
  }, [embedRef, jsonString]);
  return <div className="vega-embed" ref={embedRef} />;
}

export const Altair = memo(AltairComponent);