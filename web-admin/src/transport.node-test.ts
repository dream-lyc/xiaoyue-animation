import { test } from "node:test";
import assert from "node:assert/strict";
import { AdminApi } from "./api.ts";
import { filterEpisodes, monthCells, tagPayload, minutes } from "./model.ts";
test("authenticated request carries csrf, cookie credentials and server errors", async () => {
  const calls: any[] = [];
  const api = new AdminApi(async (p: any, o: any) => {
    calls.push([p, o]);
    return new Response('{"authenticated":true,"csrfToken":"fresh"}', {
      status: 200,
    });
  });
  await api.login("parent");
  await api.request("/policy", {
    method: "PUT",
    body: '{"defaultMinutes":45}',
  });
  assert.equal(calls[0][0], "/api/v1/auth/login");
  assert.equal(calls[1][1].credentials, "same-origin");
  assert.equal(new Headers(calls[1][1].headers).get("X-CSRF-Token"), "fresh");
});
test("401 clears session and external paths never execute", async () => {
  let expired = 0;
  const api = new AdminApi(
    async () => new Response('{"message":"会话已过期"}', { status: 401 }),
    () => expired++,
  );
  await assert.rejects(api.request("/library"), /会话已过期/);
  assert.equal(expired, 1);
  await assert.rejects(api.request("https://bad.test"), /路径/);
});
test("multiple tag values use OR and keys AND", () => {
  const rows: any = [
    {
      id: "1",
      title: "A",
      seriesTitle: "S",
      status: "matched",
      tags: { 路线: ["一", "二"], 语言: ["英语"] },
    },
    {
      id: "2",
      title: "B",
      seriesTitle: "S",
      status: "unmatched",
      tags: { 路线: ["二"], 语言: ["国语"] },
    },
  ];
  assert.deepEqual(
    filterEpisodes(rows, "", "all", { 路线: ["一", "二"], 语言: ["英语"] }).map(
      (e: any) => e.id,
    ),
    ["1"],
  );
  assert.deepEqual(tagPayload(["1", "1"], "路线", ["一"], "replace"), {
    episodeIds: ["1"],
    key: "路线",
    values: ["一"],
    operation: "replace",
  });
});
test("calendar dates use monday-first local dates and actual duration", () => {
  const cells = monthCells("2026-02");
  assert.equal(cells[6], "2026-02-01");
  assert.equal(cells.filter(Boolean).length, 28);
  assert.equal(minutes(90), "1 分 30 秒");
});
