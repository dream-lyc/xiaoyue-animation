import { describe, it, expect, vi } from "vitest";
import { AdminApi, ApiError } from "./api";
describe("authenticated API transport", () => {
  it("keeps passwords out of storage and sends csrf only to same-origin mutations", async () => {
    const store = vi.fn();
    vi.stubGlobal("localStorage", { setItem: store });
    const fetcher = vi
      .fn()
      .mockImplementation(
        async () =>
          new Response(
            JSON.stringify({ authenticated: true, csrfToken: "fresh" }),
            { status: 200 },
          ),
      );
    const api = new AdminApi(fetcher);
    await api.login("parent");
    await api.request("/policy", {
      method: "PUT",
      body: JSON.stringify({ defaultMinutes: 45 }),
    });
    expect(fetcher.mock.calls[0][0]).toBe("/api/v1/auth/login");
    expect(fetcher.mock.calls[1][1].credentials).toBe("same-origin");
    expect(
      new Headers(fetcher.mock.calls[1][1].headers).get("X-CSRF-Token"),
    ).toBe("fresh");
    expect(store).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
  it("expires authentication and exposes server error on 401", async () => {
    const expired = vi.fn();
    const api = new AdminApi(
      async () => new Response('{"message":"会话已过期"}', { status: 401 }),
      expired,
    );
    await expect(api.request("/library")).rejects.toMatchObject({
      message: "会话已过期",
      status: 401,
    });
    expect(expired).toHaveBeenCalledOnce();
  });
  it("refuses cross-origin and traversal paths before a request", async () => {
    const fetcher = vi.fn();
    const api = new AdminApi(fetcher);
    await expect(api.request("https://other.test")).rejects.toBeInstanceOf(
      ApiError,
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("accepts an empty successful mutation response", async () => {
    const api = new AdminApi(async () => new Response(null, { status: 204 }));
    expect(await api.request("/tags/x", { method: "DELETE" })).toEqual({});
  });
});
