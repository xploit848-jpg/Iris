import http from "node:http";

const port = Number(process.env.PORT || 8787);
const apiKey = process.env.OPENAI_API_KEY || "";

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

async function createSession(res) {
  if (!apiKey) {
    send(res, 500, { error: "OPENAI_API_KEY is not configured on the session server" });
    return;
  }
  try {
    const response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        session: {
          type: "realtime",
          model: "gpt-realtime",
          audio: {
            input: { turn_detection: { type: "server_vad" } },
            output: { voice: "marin" }
          }
        }
      })
    });
    const data = await response.json();
    send(res, response.status, data);
  } catch (error) {
    send(res, 502, { error: "Could not create a Realtime session" });
  }
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    send(res, 200, { ok: true });
    return;
  }
  if (req.method === "POST" && req.url === "/session") {
    createSession(res);
    return;
  }
  send(res, 404, { error: "Not found" });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`IRIS Realtime session server listening on ${port}`);
});
