#!/usr/bin/env node
// SPDX-License-Identifier: MIT
// ClawSpecs bridge: receives a question (and optionally a JPEG frame) from the ClawSpecs app and
// hands it to your agent through a command you choose. No dependencies, Node 18+.
//
// Environment:
//   CLAWSPECS_TOKEN      required. Shared secret; the app sends it as a Bearer token.
//   CLAWSPECS_AGENT_CMD  required. Executable called as: <cmd> "<question>". Its stdout is the answer.
//                        It also gets CLAWSPECS_IMAGE (path to a JPEG, or empty),
//                        CLAWSPECS_CONVERSATION_ID and CLAWSPECS_LANGUAGE in its environment.
//   CLAWSPECS_HOST       address to listen on (default 127.0.0.1). Use your Tailscale IP, never 0.0.0.0
//                        on a machine reachable from the internet.
//   CLAWSPECS_PORT       default 8791.
//   CLAWSPECS_TIMEOUT_S  how long the agent may take (default 110, below the app's 120 s read timeout).

import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { timingSafeEqual, createHash } from "node:crypto";

const TOKEN = process.env.CLAWSPECS_TOKEN || "";
const CMD = process.env.CLAWSPECS_AGENT_CMD || "";
const HOST = process.env.CLAWSPECS_HOST || "127.0.0.1";
const PORT = Number(process.env.CLAWSPECS_PORT || 8791);
const TIMEOUT_MS = Number(process.env.CLAWSPECS_TIMEOUT_S || 110) * 1000;
const MAX_BODY = 8 * 1024 * 1024;
const MAX_ANSWER = 4000;

if (TOKEN.length < 16) {
  console.error("CLAWSPECS_TOKEN must be set (16+ characters). Try: openssl rand -hex 24");
  process.exit(2);
}
if (!CMD) {
  console.error("CLAWSPECS_AGENT_CMD must point to the command that asks your agent.");
  process.exit(2);
}

const digest = (s) => createHash("sha256").update(s).digest();
const authorized = (req) => {
  const header = req.headers.authorization || "";
  const given = header.startsWith("Bearer ") ? header.slice(7) : "";
  return timingSafeEqual(digest(given), digest(TOKEN));
};

const send = (res, status, obj) => {
  const body = JSON.stringify(obj);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(body);
};

const readBody = (req) =>
  new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error("Body too large"), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });

// One agent run at a time per conversation keeps the agent's session consistent.
const busy = new Set();

function runAgent(question, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(CMD, [question], {
      env: { ...process.env, ...env },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    let err = "";
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      reject(Object.assign(new Error("The agent took too long"), { status: 504 }));
    }, TIMEOUT_MS);
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (err += d));
    child.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(out.trim());
      else reject(Object.assign(new Error(`Agent exited with ${code}: ${err.trim().slice(-300)}`), { status: 502 }));
    });
  });
}

async function handleAsk(req, res) {
  let payload;
  try {
    payload = JSON.parse(await readBody(req));
  } catch (e) {
    return send(res, e.status || 400, { error: e.status ? e.message : "Invalid JSON" });
  }
  const question = String(payload.question || "").trim();
  const conversation = String(payload.conversation_id || "default").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64) || "default";
  const language = String(payload.language || "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 16);
  if (!question) return send(res, 400, { error: "Empty question" });
  if (question.length > 2000) return send(res, 413, { error: "Question too long" });
  if (busy.has(conversation)) return send(res, 409, { error: "Still answering the previous question" });

  busy.add(conversation);
  let dir = null;
  const started = Date.now();
  try {
    let image = "";
    if (payload.image_jpeg_b64) {
      const jpeg = Buffer.from(String(payload.image_jpeg_b64), "base64");
      if (jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return send(res, 400, { error: "Image is not a JPEG" });
      dir = await mkdtemp(join(tmpdir(), "clawspecs-"));
      image = join(dir, "frame.jpg");
      await writeFile(image, jpeg, { mode: 0o600 });
    }
    const answer = await runAgent(question, {
      CLAWSPECS_IMAGE: image,
      CLAWSPECS_CONVERSATION_ID: conversation,
      CLAWSPECS_LANGUAGE: language,
    });
    if (!answer) return send(res, 502, { error: "The agent returned nothing" });
    // Content of questions and answers is never logged, only timing.
    console.log(`ask conv=${conversation} image=${image ? "yes" : "no"} ms=${Date.now() - started}`);
    send(res, 200, { answer: answer.slice(0, MAX_ANSWER) });
  } catch (e) {
    console.error(`ask conv=${conversation} failed: ${e.message.split("\n")[0]}`);
    send(res, e.status || 500, { error: e.status ? e.message.split(":")[0] : "Agent failed" });
  } finally {
    busy.delete(conversation);
    if (dir) await rm(dir, { recursive: true, force: true });
  }
}

const server = createServer((req, res) => {
  if (!authorized(req)) return send(res, 401, { error: "Unauthorized" });
  if (req.method === "GET" && req.url === "/health") return send(res, 200, { ok: true });
  if (req.method === "POST" && req.url === "/ask") return void handleAsk(req, res);
  send(res, 404, { error: "Not found" });
});

server.listen(PORT, HOST, () => console.log(`ClawSpecs bridge on http://${HOST}:${PORT}`));
