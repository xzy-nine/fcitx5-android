// 小米 MiMo 语音识别（mimo-v2.5-asr）。
//
// 协议：OpenAI Chat Completions 兼容
//   POST https://api.xiaomimimo.com/v1/chat/completions
//   header: api-key: <KEY>
//   body  : messages[0].content[0] = {type: "input_audio", input_audio: {data: "data:audio/wav;base64,..."}}
//           asr_options.language ∈ auto|zh|en
//
// 批次音频：说话中只累积 PCM，松手后整段包成 WAV 一次上传，取回完整结果。

const DEFAULT_ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
const MODELS = [
  { label: "mimo-v2.5-asr", value: "mimo-v2.5-asr" },
  { label: "mimo-v2.5-asr-lite", value: "mimo-v2.5-asr-lite" }
];
const SAMPLE_RATE = 16000;
const MAX_PCM_BYTES = 7000000;
const DEFAULT_STRIP_CHARACTERS = ".,!?;:，。！？；：、…⋯~～";

const STRINGS = {
  zh: {
    name: "小米 MiMo 语音识别",
    apiKey: "API Key",
    apiKeyDesc: "小米开放平台 API Key",
    endpoint: "接口地址",
    endpointDesc: "默认官方地址；自建代理时改这里",
    model: "模型",
    modelDesc: "识别模型；自定义时填完整模型名",
    language: "识别语言",
    auto: "自动",
    zhLabel: "中文",
    enLabel: "英文",
    prompt: "提示文本",
    promptDesc: "可选。给模型的上下文提示，例如专有名词",
    temperature: "温度",
    temperatureDesc: "越小越稳定，默认 0",
    stripPunctuation: "去除标点",
    stripPunctuationDesc: "识别结果末尾的标点会去掉",
    stripCharacters: "去除字符集",
    stripCharactersDesc: "要剥掉的字符集合",
    maxSeconds: "最长音频（秒）",
    maxSecondsDesc: "超过该时长只保留前面的部分，默认 420"
  },
  en: {
    name: "MiMo ASR",
    apiKey: "API Key",
    apiKeyDesc: "Xiaomi open platform API key",
    endpoint: "Endpoint",
    endpointDesc: "Defaults to the official URL; change for a proxy",
    model: "Model",
    modelDesc: "Recognition model; use the full name when custom",
    language: "Language",
    auto: "Auto",
    zhLabel: "Chinese",
    enLabel: "English",
    prompt: "Prompt",
    promptDesc: "Optional context hint, e.g. proper nouns",
    temperature: "Temperature",
    temperatureDesc: "Lower is more deterministic; default 0",
    stripPunctuation: "Strip punctuation",
    stripPunctuationDesc: "Remove trailing punctuation from the result",
    stripCharacters: "Strip characters",
    stripCharactersDesc: "Set of characters to remove",
    maxSeconds: "Max seconds",
    maxSecondsDesc: "Audio beyond this is truncated; default 420"
  }
};

function stringsFor(locale) {
  return locale && String(locale).toLowerCase().startsWith("zh") ? STRINGS.zh : STRINGS.en;
}

function stripTrailing(text, enabled, chars) {
  if (!enabled || !text) return text;
  const set = chars && chars.length > 0 ? chars : DEFAULT_STRIP_CHARACTERS;
  let end = text.length;
  while (end > 0 && set.indexOf(text.charAt(end - 1)) >= 0) end--;
  return text.slice(0, end);
}

let session = { chunks: [], bytes: 0, text: "", done: false };

foxy.plugin.register({
  id: "mimo-asr",
  name: stringsFor(foxy.locale).name,
  version: "1.1.0",
  hosts: ["api.xiaomimimo.com"],
  features: [{
    type: "voice.asr",
    id: "mimo-asr",
    label: stringsFor(foxy.locale).name,
    sampleRate: SAMPLE_RATE
  }],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.secret("apiKey", t.apiKey, { description: t.apiKeyDesc });
    ui.text("endpoint", t.endpoint, DEFAULT_ENDPOINT, {
      description: t.endpointDesc
    });
    ui.select("model", t.model, MODELS, "mimo-v2.5-asr", {
      allowCustom: true,
      description: t.modelDesc
    });
    ui.select("language", t.language, [
      { label: t.auto, value: "auto" },
      { label: t.zhLabel, value: "zh" },
      { label: t.enLabel, value: "en" }
    ], "auto");
    ui.text("prompt", t.prompt, "", { description: t.promptDesc });
    ui.number("temperature", t.temperature, "0", {
      description: t.temperatureDesc
    });
    ui.number("maxSeconds", t.maxSeconds, "420", {
      description: t.maxSecondsDesc
    });
    ui.switch("stripPunctuation", t.stripPunctuation, false, {
      description: t.stripPunctuationDesc
    });
    ui.text("stripCharacters", t.stripCharacters, DEFAULT_STRIP_CHARACTERS, {
      visibleWhen: { key: "stripPunctuation", value: "true" },
      description: t.stripCharactersDesc
    });
  },
  async start(context) {
    session = { chunks: [], bytes: 0, text: "", done: false };
    return "";
  },
  audio(context) {
    if (session.done) return;
    if (session.bytes >= MAX_PCM_BYTES) return;
    const maxSeconds = Number(context.maxSeconds || "420");
    const limit = Math.min(MAX_PCM_BYTES, Math.max(1, maxSeconds) * SAMPLE_RATE * 2);
    if (session.bytes + foxy.binary.byteLength(context.pcm) > limit) return;
    session.chunks.push(context.pcm);
    session.bytes += foxy.binary.byteLength(context.pcm);
  },
  async stop(context) {
    if (session.done) return;
    session.done = true;
    if (session.bytes <= 0) {
      foxy.voice.final("");
      foxy.voice.ended();
      return;
    }
    const pcm = foxy.binary.concatBase64(session.chunks);
    const wav = foxy.audio.wavBase64(pcm, String(SAMPLE_RATE));
    const language = context.language ? String(context.language) : "auto";
    const model = context.model ? String(context.model) : "mimo-v2.5-asr";
    const endpoint = context.endpoint ? String(context.endpoint) : DEFAULT_ENDPOINT;
    const asrOptions = { language: language };
    const prompt = context.prompt ? String(context.prompt) : "";
    if (prompt.length > 0) asrOptions.prompt = prompt;
    const temperature = Number(context.temperature || "0");
    if (isFinite(temperature) && temperature > 0) asrOptions.temperature = temperature;

    const body = {
      model: model,
      stream: false,
      messages: [{
        role: "user",
        content: [{
          type: "input_audio",
          input_audio: { data: "data:audio/wav;base64," + wav }
        }]
      }],
      asr_options: asrOptions
    };
    let response = null;
    try {
      response = await foxy.http.post(
        endpoint, "apiKey", JSON.stringify(body),
        JSON.stringify({ "Content-Type": "application/json" }));
    } catch (e) {
      foxy.voice.error("MiMo 识别失败：" + (e && e.message ? e.message : "请求异常"));
      foxy.voice.ended();
      return;
    }
    let text = "";
    try {
      const json = typeof response === "string" ? JSON.parse(response) : response;
      const choice = json && json.choices && json.choices.length > 0 ? json.choices[0] : null;
      if (choice) {
        if (choice.message && choice.message.content != null) {
          text = String(choice.message.content);
        } else if (choice.delta && choice.delta.content != null) {
          text = String(choice.delta.content);
        }
      }
    } catch (e) {
      text = "";
    }
    text = stripTrailing(text.trim(), context.stripPunctuation === "true",
      context.stripCharacters ? String(context.stripCharacters) : DEFAULT_STRIP_CHARACTERS);
    session.text = text;
    foxy.voice.final(session.text);
    foxy.voice.ended();
  },
  onMessage(context) {
    // 该平台只走 HTTP 请求，不使用 WebSocket
  }
});
