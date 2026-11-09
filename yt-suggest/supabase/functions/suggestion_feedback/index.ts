const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Accept, Content-Type, Authorization, apikey",
  "Cache-Control": "no-store",
};

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8" },
  });
}

function rest(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("apikey", SERVICE_ROLE_KEY);
  headers.set("Authorization", `Bearer ${SERVICE_ROLE_KEY}`);
  headers.set("Accept", "application/json");
  return fetch(`${SUPABASE_URL}/rest/v1/${path}`, { ...init, headers });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "server_not_configured" }, 500);
  }

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch (_error) {
    return json({ error: "invalid_json" }, 400);
  }

  const videoId = typeof payload.video_id === "string" ? payload.video_id.trim() : "";
  const feedbackType = typeof payload.feedback_type === "string" ? payload.feedback_type.trim() : "";
  const reason = typeof payload.reason === "string" ? payload.reason.trim().slice(0, 1000) : "";
  const suggestionId = typeof payload.suggestion_id === "number" && Number.isInteger(payload.suggestion_id)
    ? payload.suggestion_id
    : null;

  if (!videoId || videoId.length > 64) {
    return json({ error: "invalid_video_id" }, 400);
  }
  if (feedbackType !== "known" && feedbackType !== "dislike") {
    return json({ error: "invalid_feedback_type" }, 400);
  }

  const now = new Date().toISOString();

  const feedback = await rest("suggestion_feedback?on_conflict=video_id", {
    method: "POST",
    headers: { "Content-Type": "application/json", Prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({
      video_id: videoId,
      feedback_type: feedbackType,
      reason,
      suggestion_id: suggestionId,
    }),
  });
  if (!feedback.ok) {
    await feedback.body?.cancel();
    return json({ error: "feedback_failed", status: feedback.status }, 502);
  }
  await feedback.body?.cancel();

  const currentQuery = new URLSearchParams({
    valid_from: `lte.${now}`,
    valid_until: `gt.${now}`,
  });
  const current = await rest(`daily_suggestion?${currentQuery}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ valid_until: now }),
  });
  await current.body?.cancel();

  const job = await rest("suggestion_jobs", {
    method: "POST",
    headers: { "Content-Type": "application/json", Prefer: "return=representation" },
    body: JSON.stringify({
      video_id: videoId,
      feedback_type: feedbackType,
      reason,
      suggestion_id: suggestionId,
    }),
  });
  if (!job.ok) {
    await job.body?.cancel();
    return json({ error: "job_failed", status: job.status }, 502);
  }
  const rows = await job.json();
  const jobId = Array.isArray(rows) && rows.length > 0 ? rows[0]?.id ?? null : null;

  return json({ ok: true, job_id: jobId }, 200);
});
