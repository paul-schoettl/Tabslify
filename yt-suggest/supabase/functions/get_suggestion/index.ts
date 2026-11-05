const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Accept, Content-Type, Authorization, apikey",
  "Cache-Control": "no-store",
};

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }
  if (req.method !== "GET" && req.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "server_not_configured" }, 500);
  }

  const now = new Date().toISOString();
  const query = new URLSearchParams({
    select: "id,valid_from,valid_until,video_id,title,channel,thumbnail_url,duration,reason,created_at",
    valid_from: `lte.${now}`,
    valid_until: `gt.${now}`,
    order: "valid_until.desc,valid_from.desc",
    limit: "1",
  });

  let upstream: Response;
  try {
    upstream = await fetch(`${SUPABASE_URL}/rest/v1/daily_suggestion?${query}`, {
      headers: {
        apikey: SERVICE_ROLE_KEY,
        Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
        Accept: "application/json",
      },
      cache: "no-store",
    });
  } catch (_error) {
    return json({ error: "upstream_unreachable" }, 502);
  }

  if (!upstream.ok) {
    await upstream.body?.cancel();
    return json({ error: "upstream_error", status: upstream.status }, 502);
  }

  const rows = await upstream.json();
  const suggestion = Array.isArray(rows) ? rows[0] ?? null : null;
  return json({ suggestion, server_time: now }, 200);
});
