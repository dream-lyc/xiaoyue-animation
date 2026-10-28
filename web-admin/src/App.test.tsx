import React from "react";
import { it, expect, vi, afterEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  cleanup,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { AdminApi } from "./api";
afterEach(cleanup);
function service(routes: Record<string, unknown>) {
  const calls: { path: string; body: any }[] = [];
  const api = new AdminApi(async (path, options) => {
    const p = String(path).replace("/api/v1", "");
    calls.push({
      path: p,
      body: options?.body ? JSON.parse(String(options.body)) : null,
    });
    return new Response(JSON.stringify(routes[p] ?? {}), { status: 200 });
  });
  return { api, calls };
}
it("requires login and only shows the library after authentication", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: false },
    "/auth/login": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
  });
  render(<App api={api} />);
  await screen.findByLabelText("管理员密码");
  await userEvent.type(screen.getByLabelText("管理员密码"), "secret");
  await userEvent.click(screen.getByRole("button", { name: "登录管理端" }));
  await screen.findByText("片库还是空的");
  expect(calls.find((x) => x.path === "/auth/login")?.body).toEqual({
    password: "secret",
  });
  expect(screen.queryByLabelText("管理员密码")).toBeNull();
});
it("previews the selected logical episodes before submitting transactional bulk tags", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [
      {
        id: "e1",
        title: "种子",
        seriesTitle: "花园",
        libraryName: "动画",
        seasonNumber: 1,
        episodeNumber: 1,
        synopsis: "",
        status: "unmatched",
        available: true,
        lockedFields: [],
        tags: {},
      },
    ],
    "/tags": [{ id: "t", key: "路线", values: ["常规一"] }],
    "/library/tags/preview": { affectedCount: 1 },
  });
  render(<App api={api} />);
  await screen.findByText("种子");
  await userEvent.click(screen.getByLabelText("选择 种子"));
  await userEvent.click(screen.getByRole("button", { name: "批量标签" }));
  await userEvent.click(
    within(screen.getByRole("dialog")).getByLabelText("常规一"),
  );
  await userEvent.click(screen.getByRole("button", { name: "预览影响范围" }));
  await screen.findByText("将修改 1 集现有剧集");
  expect(calls.some((x) => x.path === "/library/tags")).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "确认应用标签" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/library/tags")?.body).toMatchObject({
      episodeIds: ["e1"],
      values: ["常规一"],
      operation: "append",
      confirmed: true,
    }),
  );
});
it("shows a recoverable connection failure instead of fabricated library contents", async () => {
  const api = new AdminApi(async () => {
    throw new TypeError("network");
  });
  render(<App api={api} />);
  await screen.findByText(/无法连接电视/);
  expect(screen.getByRole("button", { name: "重新连接" })).toBeTruthy();
});
it("saves default quota without overwriting today override", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/policy": {
      defaultMinutes: 45,
      todayMinutes: 60,
      watchedSeconds: 1200,
      state: "available",
      timezone: "Asia/Shanghai",
      todayOverride: true,
    },
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "观看规则" }));
  const field = await screen.findByLabelText("每日默认分钟数");
  await userEvent.clear(field);
  await userEvent.type(field, "30");
  await userEvent.click(screen.getByRole("button", { name: "保存默认规则" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/policy" && x.body)?.body).toEqual({
      defaultMinutes: 30,
    }),
  );
});
it("browses adapter directories and saves selected roots", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/sources": [
      {
        id: "s",
        name: "家庭 NAS",
        type: "webdav",
        endpoint: "https://nas",
        roots: [],
        credentialConfigured: true,
      },
    ],
    "/scans": [],
    "/sources/s/directories?path=%2F": {
      path: "/",
      directories: [{ name: "动画", path: "/cartoons" }],
    },
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
  await userEvent.click(
    await screen.findByRole("button", { name: "选择目录" }),
  );
  await userEvent.click(await screen.findByLabelText("导入 动画"));
  await userEvent.click(screen.getByRole("button", { name: "保存目录并扫描" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/sources/s" && x.body)?.body).toEqual({
      roots: ["/cartoons"],
    }),
  );
});
it("supports dictionary editing without silently collapsing multiple values", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "标签管理" }));
  await userEvent.type(screen.getByLabelText("标签名称 K"), "语言");
  await userEvent.type(
    screen.getByLabelText("标签值 V（每行一个）"),
    "英语\n国语",
  );
  await userEvent.click(screen.getByRole("button", { name: "保存标签" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/tags" && x.body)?.body).toEqual({
      key: "语言",
      values: ["英语", "国语"],
    }),
  );
});
it("renders day zero independently and keeps summary totals deduplicated", async () => {
  const today = new Date().toLocaleDateString("sv-SE");
  const month = today.slice(0, 7);
  const from = month + "-01";
  const last = new Date(
    Number(month.slice(0, 4)),
    Number(month.slice(5)),
    0,
  ).getDate();
  const { api } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    [`/analytics?from=${from}&to=${month}-${last}&dimension=series&tags=%7B%7D`]:
      { totalSeconds: 600, episodeCount: 1, watchDays: 1, days: [], rows: [] },
    [`/analytics/day?date=${today}`]: {
      date: today,
      totalSeconds: 0,
      graceSeconds: 0,
      entries: [],
    },
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "观看日历" }));
  await screen.findByText("这一天没有观看记录");
});
it("omits blank provider credentials and never echoes configured keys", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/provider": {
      tmdbConfigured: true,
      openSubtitlesConfigured: false,
      status: "ready",
    },
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资料服务" }));
  await screen.findByText("TMDB 已配置");
  await userEvent.type(
    screen.getByLabelText("OpenSubtitles API key"),
    "new-key",
  );
  await userEvent.click(screen.getByRole("button", { name: "保存服务凭据" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/provider" && x.body)?.body).toEqual({
      openSubtitlesKey: "new-key",
    }),
  );
});
it("requires explicit confirmation after selecting backup before restore", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "备份与恢复" }));
  expect(
    (
      screen.getByRole("button", {
        name: "验证并恢复备份",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(calls.some((x) => x.path === "/restore")).toBe(false);
});
it("keeps a bulk preview visible while refreshing the library over a slow LAN", async () => {
  let libraryReads = 0;
  const episode = {
    id: "e",
    title: "花",
    seriesTitle: "花园",
    libraryName: "动画",
    seasonNumber: 1,
    episodeNumber: 1,
    synopsis: "",
    status: "matched",
    available: true,
    lockedFields: [],
    tags: {},
  };
  const api = new AdminApi(async (path) => {
    const p = String(path).replace("/api/v1", "");
    if (p === "/library" && ++libraryReads > 1)
      return await new Promise<Response>(() => {});
    const data: Record<string, unknown> = {
      "/auth/session": { authenticated: true, csrfToken: "a" },
      "/library": [episode],
      "/tags": [{ id: "t", key: "路线", values: ["一"] }],
      "/library/tags/preview": { affectedCount: 1 },
    };
    return new Response(JSON.stringify(data[p] ?? {}));
  });
  render(<App api={api} />);
  await userEvent.click(await screen.findByLabelText("选择 花"));
  await userEvent.click(screen.getByRole("button", { name: "批量标签" }));
  await userEvent.click(
    within(screen.getByRole("dialog")).getByLabelText("一"),
  );
  await userEvent.click(screen.getByRole("button", { name: "预览影响范围" }));
  await new Promise((r) => setTimeout(r, 30));
  expect(screen.queryByRole("button", { name: "确认应用标签" })).not.toBeNull();
});
it("reconfigures source credentials after restore without reading existing secrets", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/sources": [
      {
        id: "s",
        name: "NAS",
        type: "webdav",
        endpoint: "https://nas",
        roots: ["/"],
        credentialConfigured: false,
      },
    ],
    "/scans": [],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
  await userEvent.click(
    await screen.findByRole("button", { name: "更新凭据" }),
  );
  expect((screen.getByLabelText("密码") as HTMLInputElement).value).toBe("");
  await userEvent.type(screen.getByLabelText("用户名"), "parent");
  await userEvent.type(screen.getByLabelText("密码"), "private");
  await userEvent.click(screen.getByRole("button", { name: "保存凭据" }));
  await waitFor(() =>
    expect(calls.find((x) => x.path === "/sources/s" && x.body)?.body).toEqual({
      username: "parent",
      secret: "private",
    }),
  );
});
it("maps one source asset to a logical episode with explicit playback time boundaries", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [
      {
        id: "e",
        title: "第一集",
        seriesTitle: "合集",
        libraryName: "动画",
        seasonNumber: 1,
        episodeNumber: 1,
        synopsis: "",
        status: "unmatched",
        available: false,
        lockedFields: [],
        tags: {},
      },
    ],
    "/tags": [],
    "/library/e/assets": [
      {
        id: "file",
        name: "合集.mp4",
        sourceId: "s",
        path: "/合集.mp4",
        selected: false,
      },
    ],
  });
  render(<App api={api} />);
  await userEvent.click(
    await screen.findByRole("button", { name: "资源与区间" }),
  );
  await userEvent.click(await screen.findByLabelText("关联 合集.mp4"));
  await userEvent.type(screen.getByLabelText("合集.mp4 起点（秒）"), "60");
  await userEvent.type(screen.getByLabelText("合集.mp4 终点（秒）"), "120");
  await userEvent.click(screen.getByRole("button", { name: "保存资源映射" }));
  await waitFor(() =>
    expect(
      calls.find((x) => x.path === "/library/e/assets" && x.body)?.body,
    ).toEqual({ assets: [{ assetId: "file", startMs: 60000, endMs: 120000 }] }),
  );
});
it("automatically protects manually changed metadata from the next scan", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [
      {
        id: "e",
        title: "原集名",
        seriesTitle: "动画",
        libraryName: "片库",
        seasonNumber: 1,
        episodeNumber: 1,
        synopsis: "",
        status: "matched",
        available: true,
        lockedFields: [],
        tags: {},
      },
    ],
    "/tags": [],
  });
  render(<App api={api} />);
  await userEvent.click(await screen.findByRole("button", { name: "编辑" }));
  await userEvent.clear(screen.getByRole("textbox", { name: "集名" }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "集名" }),
    "手工集名",
  );
  await userEvent.click(screen.getByRole("button", { name: "保存资料" }));
  await waitFor(() =>
    expect(
      calls.find((x) => x.path === "/library/e" && x.body)?.body.lockedFields,
    ).toContain("title"),
  );
});
it("queries an arbitrary inclusive viewing range without altering calendar day detail", async () => {
  const paths: string[] = [];
  const api = new AdminApi(async (path) => {
    const p = String(path).replace("/api/v1", "");
    paths.push(p);
    const data =
      p === "/auth/session"
        ? { authenticated: true, csrfToken: "a" }
        : p.startsWith("/analytics?")
          ? {
              totalSeconds: 0,
              episodeCount: 0,
              watchDays: 0,
              days: [],
              rows: [],
            }
          : p.startsWith("/analytics/day")
            ? {
                date: "2026-01-01",
                totalSeconds: 0,
                graceSeconds: 0,
                entries: [],
              }
            : [];
    return new Response(JSON.stringify(data));
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "观看统计" }));
  const from = await screen.findByLabelText("开始日期");
  const to = screen.getByLabelText("结束日期");
  await userEvent.clear(from);
  await userEvent.type(from, "2026-01-01");
  await userEvent.clear(to);
  await userEvent.type(to, "2026-02-02");
  await userEvent.click(screen.getByRole("button", { name: "应用日期范围" }));
  await waitFor(() =>
    expect(
      paths.some((p) =>
        p.startsWith("/analytics?from=2026-01-01&to=2026-02-02"),
      ),
    ).toBe(true),
  );
});
it("shows a rejected edit inside the open dialog so the parent can correct it", async () => {
  const api = new AdminApi(async (path, options) => {
    const p = String(path).replace("/api/v1", "");
    if (p === "/library/e" && options?.method === "PATCH")
      return new Response(JSON.stringify({ message: "集号冲突，请重新填写" }), {
        status: 409,
      });
    const routes: Record<string, unknown> = {
      "/auth/session": { authenticated: true, csrfToken: "a" },
      "/library": [
        {
          id: "e",
          title: "原集名",
          seriesTitle: "动画",
          libraryName: "片库",
          seasonNumber: 1,
          episodeNumber: 1,
          synopsis: "",
          status: "matched",
          available: true,
          lockedFields: [],
          tags: {},
        },
      ],
      "/tags": [],
    };
    return new Response(JSON.stringify(routes[p] ?? {}));
  });
  render(<App api={api} />);
  await userEvent.click(await screen.findByRole("button", { name: "编辑" }));
  await userEvent.click(screen.getByRole("button", { name: "保存资料" }));
  expect(
    await within(screen.getByRole("dialog")).findByText("集号冲突，请重新填写"),
  ).toBeTruthy();
});
it("prefers the authenticated local artwork cache over a remote provider URL", async () => {
  const { api } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [
      {
        id: "e",
        title: "本地封面",
        seriesTitle: "动画",
        libraryName: "片库",
        seasonNumber: 1,
        episodeNumber: 1,
        synopsis: "",
        artworkUrl: "https://remote.test/cover.jpg",
        artworkLocalUrl: "/api/v1/artwork/e",
        status: "matched",
        available: true,
        lockedFields: [],
        tags: {},
      },
    ],
    "/tags": [],
  });
  const { container } = render(<App api={api} />);
  await screen.findByText("本地封面");
  expect(container.querySelector(".artwork img")?.getAttribute("src")).toBe(
    "/api/v1/artwork/e",
  );
});
it("shows persistent metadata backoff as waiting and allows cancelling the scheduled retry", async () => {
  const { api, calls } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/sources": [],
    "/scans": [
      {
        id: "job",
        sourceId: "s",
        stage: "waiting_retry",
        status: "waiting",
        discovered: 1,
        matched: 0,
        completed: 0,
        cursor: { retryAtMillis: Date.now() + 60000 },
      },
    ],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
  await screen.findByText("等待自动重试");
  expect(screen.getByText(/下次重试/)).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: "取消扫描" }));
  await waitFor(() =>
    expect(calls.some((x) => x.path === "/scans/job/cancel")).toBe(true),
  );
});

const sourceAuthCases = [
  ["openlist", "password"],
  ["openlist", "token"],
  ["alist", "password"],
  ["alist", "token"],
  ["smb", null],
  ["webdav", null],
] as const;
it.each(sourceAuthCases)(
  "creates %s with %s authentication",
  async (type, mode) => {
    const { api, calls } = service({
      "/auth/session": { authenticated: true, csrfToken: "a" },
      "/library": [],
      "/tags": [],
      "/sources": [],
      "/scans": [],
    });
    render(<App api={api} />);
    await screen.findByText("片库还是空的");
    await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
    await userEvent.click(
      screen.getByRole("button", { name: "＋ 添加资源连接" }),
    );
    await userEvent.selectOptions(screen.getByLabelText("连接类型"), type);
    if (mode)
      await userEvent.selectOptions(screen.getByLabelText("认证方式"), mode);
    else expect(screen.queryByLabelText("认证方式")).toBeNull();
    await userEvent.type(screen.getByLabelText("连接名称"), "家庭资源");
    await userEvent.type(
      screen.getByLabelText("服务地址"),
      type === "smb" ? "smb://nas/share" : "https://nas.example",
    );
    if (mode === "token") {
      expect(screen.queryByLabelText("用户名")).toBeNull();
      expect(screen.getByText(/管理后台获取访问令牌/)).toBeTruthy();
    } else await userEvent.type(screen.getByLabelText("用户名"), "parent");
    await userEvent.type(
      screen.getByLabelText(
        mode === "token"
          ? "访问令牌（Token）"
          : mode === "password"
            ? "登录密码"
            : "密码",
      ),
      "active-secret",
    );
    await userEvent.click(screen.getByRole("button", { name: "保存连接" }));
    await waitFor(() => {
      const body = calls.find((x) => x.path === "/sources" && x.body)?.body;
      expect(body?.secret).toBe("active-secret");
      if (mode) expect(body?.authMode).toBe(mode);
      else expect(body).not.toHaveProperty("authMode");
      if (mode === "token") expect(body).not.toHaveProperty("username");
      else expect(body?.username).toBe("parent");
    });
  },
);
it.each(sourceAuthCases)(
  "updates %s with %s authentication",
  async (type, mode) => {
    const { api, calls } = service({
      "/auth/session": { authenticated: true, csrfToken: "a" },
      "/library": [],
      "/tags": [],
      "/sources": [
        {
          id: "s",
          name: "资源",
          type,
          authMode: mode ?? undefined,
          endpoint: "https://nas",
          roots: [],
          credentialConfigured: true,
        },
      ],
      "/scans": [],
    });
    render(<App api={api} />);
    await screen.findByText("片库还是空的");
    await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
    await userEvent.click(
      await screen.findByRole("button", { name: "更新凭据" }),
    );
    if (mode)
      expect(
        (screen.getByLabelText("认证方式") as HTMLSelectElement).value,
      ).toBe(mode);
    else expect(screen.queryByLabelText("认证方式")).toBeNull();
    if (mode === "token") expect(screen.queryByLabelText("用户名")).toBeNull();
    else await userEvent.type(screen.getByLabelText("用户名"), "parent");
    await userEvent.type(
      screen.getByLabelText(
        mode === "token"
          ? "访问令牌（Token）"
          : mode === "password"
            ? "登录密码"
            : "密码",
      ),
      "new-secret",
    );
    await userEvent.click(screen.getByRole("button", { name: "保存凭据" }));
    await waitFor(() =>
      expect(
        calls.find((x) => x.path === "/sources/s" && x.body)?.body,
      ).toEqual(
        mode === "token"
          ? { authMode: "token", secret: "new-secret" }
          : mode === "password"
            ? { authMode: "password", username: "parent", secret: "new-secret" }
            : { username: "parent", secret: "new-secret" },
      ),
    );
  },
);
it.each(["create", "edit"])(
  "keeps password and token inputs separate while switching in %s",
  async (kind) => {
    const { api, calls } = service({
      "/auth/session": { authenticated: true, csrfToken: "a" },
      "/library": [],
      "/tags": [],
      "/sources":
        kind === "edit"
          ? [
              {
                id: "s",
                name: "资源",
                type: "openlist",
                authMode: "password",
                endpoint: "https://nas",
                roots: [],
                credentialConfigured: true,
              },
            ]
          : [],
      "/scans": [],
    });
    render(<App api={api} />);
    await screen.findByText("片库还是空的");
    await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
    await userEvent.click(
      await screen.findByRole("button", {
        name: kind === "edit" ? "更新凭据" : "＋ 添加资源连接",
      }),
    );
    if (kind === "create") {
      await userEvent.type(screen.getByLabelText("连接名称"), "资源");
      await userEvent.type(screen.getByLabelText("服务地址"), "https://nas");
    }
    const mode = screen.getByLabelText("认证方式");
    await userEvent.type(screen.getByLabelText("用户名"), "parent");
    await userEvent.type(screen.getByLabelText("登录密码"), "account-password");
    await userEvent.selectOptions(mode, "token");
    expect(
      (screen.getByLabelText("访问令牌（Token）") as HTMLInputElement).value,
    ).toBe("");
    await userEvent.type(
      screen.getByLabelText("访问令牌（Token）"),
      "access-token",
    );
    await userEvent.selectOptions(mode, "password");
    expect((screen.getByLabelText("登录密码") as HTMLInputElement).value).toBe(
      "account-password",
    );
    expect((screen.getByLabelText("用户名") as HTMLInputElement).value).toBe(
      "parent",
    );
    await userEvent.selectOptions(mode, "token");
    expect(
      (screen.getByLabelText("访问令牌（Token）") as HTMLInputElement).value,
    ).toBe("access-token");
    await userEvent.click(
      screen.getByRole("button", {
        name: kind === "edit" ? "保存凭据" : "保存连接",
      }),
    );
    await waitFor(() => {
      const body = calls.find(
        (x) =>
          x.path === (kind === "edit" ? "/sources/s" : "/sources") && x.body,
      )?.body;
      expect(body?.authMode).toBe("token");
      expect(body?.secret).toBe("access-token");
      expect(body).not.toHaveProperty("username");
    });
  },
);
it("preserves non-list credentials while switching source types", async () => {
  const { api } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/sources": [],
    "/scans": [],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
  await userEvent.click(
    screen.getByRole("button", { name: "＋ 添加资源连接" }),
  );
  const type = screen.getByLabelText("连接类型");
  await userEvent.selectOptions(type, "webdav");
  await userEvent.type(screen.getByLabelText("用户名"), "dav-user");
  await userEvent.type(screen.getByLabelText("密码"), "dav-password");
  await userEvent.selectOptions(type, "openlist");
  expect((screen.getByLabelText("登录密码") as HTMLInputElement).value).toBe(
    "",
  );
  await userEvent.selectOptions(type, "webdav");
  expect((screen.getByLabelText("用户名") as HTMLInputElement).value).toBe(
    "dav-user",
  );
  expect((screen.getByLabelText("密码") as HTMLInputElement).value).toBe(
    "dav-password",
  );
});
it("uses token mode for legacy list metadata without an auth mode", async () => {
  const { api } = service({
    "/auth/session": { authenticated: true, csrfToken: "a" },
    "/library": [],
    "/tags": [],
    "/sources": [
      {
        id: "s",
        name: "旧源",
        type: "alist",
        endpoint: "https://nas",
        roots: [],
        credentialConfigured: true,
      },
    ],
    "/scans": [],
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "资源连接" }));
  await userEvent.click(
    await screen.findByRole("button", { name: "更新凭据" }),
  );
  expect((screen.getByLabelText("认证方式") as HTMLSelectElement).value).toBe(
    "token",
  );
  expect(screen.queryByLabelText("用户名")).toBeNull();
});
it("calendar shows a short mixed session without calling it normal-only", async () => {
  const api = new AdminApi(async (path) => {
    const p = String(path).replace("/api/v1", "");
    const data =
      p === "/auth/session"
        ? { authenticated: true, csrfToken: "a" }
        : p.startsWith("/analytics/day")
          ? {
              date: new Date().toLocaleDateString("sv-SE"),
              totalSeconds: 0,
              graceSeconds: 0,
              entries: [
                {
                  id: "session",
                  title: "短时混合观看",
                  seriesTitle: "动画",
                  startedAt: "2026-10-28T06:00:00Z",
                  endedAt: "2026-10-28T06:00:00.250Z",
                  seconds: 0,
                  durationMillis: 250,
                  kind: "mixed",
                  kinds: ["normal", "grace", "parent_extra"],
                },
              ],
            }
          : p.startsWith("/analytics?")
            ? {
                totalSeconds: 0,
                episodeCount: 1,
                watchDays: 1,
                days: [],
                rows: [],
              }
            : [];
    return new Response(JSON.stringify(data));
  });
  render(<App api={api} />);
  await screen.findByText("片库还是空的");
  await userEvent.click(screen.getByRole("button", { name: "观看日历" }));
  await screen.findByText("短时混合观看");
  expect(
    screen.getByText("不到1秒 · 正常观看 · 本集收尾 · 家长额外一集"),
  ).toBeTruthy();
});
