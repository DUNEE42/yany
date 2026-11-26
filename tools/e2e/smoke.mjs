const baseUrl = process.env.BASE_URL || "http://localhost:3000";
const token = process.env.DEV_TOKEN || "dev-token";
const headers = { Authorization: `Bearer ${token}` };

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function getJson(path) {
  const response = await fetch(`${baseUrl}${path}`, { headers });
  assert(response.ok, `${path} returned HTTP ${response.status}`);
  return response.json();
}

const health = await getJson("/health");
assert(health.status === "ok", "health.status must be ok");
const before = await getJson("/v1/entitlements");
assert(Number.isInteger(before.availableUnits), "availableUnits must be an integer");

const generationId = crypto.randomUUID();
const request = {
  conversationId: "e2e-conversation",
  clientMessageId: crypto.randomUUID(),
  assistantMessageId: crypto.randomUUID(),
  companion: {
    id: "companion-xiaoyu",
    name: "小屿",
    persona: "温柔、稳定、善于倾听",
    speakingStyle: "自然、简短",
    boundaries: ["不生成露骨内容", "不声称自己是真人"],
  },
  modelMode: "standard",
  context: [
    {
      id: crypto.randomUUID(),
      role: "user",
      content: "今天有点累，想找个人说说话。",
      sequence: 1,
    },
  ],
};

const response = await fetch(`${baseUrl}/v1/generations`, {
  method: "POST",
  headers: {
    ...headers,
    "Content-Type": "application/json",
    "Idempotency-Key": generationId,
  },
  body: JSON.stringify(request),
});
assert(response.ok, `generation returned HTTP ${response.status}`);
assert(response.body, "generation response has no stream");

const seen = new Set();
let streamedText = "";
let buffer = "";
let eventName = "message";
const decoder = new TextDecoder();
const reader = response.body.getReader();

while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  const frames = buffer.split("\n\n");
  buffer = frames.pop() || "";
  for (const frame of frames) {
    for (const line of frame.split("\n")) {
      if (line.startsWith("event:")) eventName = line.slice(6).trim();
      if (!line.startsWith("data:")) continue;
      const raw = line.slice(5).trim();
      if (!raw) continue;
      const data = JSON.parse(raw);
      seen.add(eventName);
      if (eventName === "message.delta") streamedText += data.text || "";
    }
  }
}

assert(seen.has("generation.started"), "missing generation.started");
assert(seen.has("message.delta"), "missing message.delta");
assert(seen.has("generation.completed"), "missing generation.completed");
assert(streamedText.trim().length > 0, "stream produced no text");

const after = await getJson("/v1/entitlements");
assert(after.availableUnits <= before.availableUnits, "quota did not settle");

console.log(
  JSON.stringify(
    {
      ok: true,
      provider: health.provider,
      events: [...seen],
      textPreview: streamedText.slice(0, 80),
      quotaBefore: before.availableUnits,
      quotaAfter: after.availableUnits,
    },
    null,
    2,
  ),
);

