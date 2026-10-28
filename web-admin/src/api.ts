export class ApiError extends Error {
  status: number;
  constructor(message: string, status = 0) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}
export class AdminApi {
  private fetcher: typeof fetch;
  private expired: () => void;
  private csrfToken = "";
  constructor(
    fetcher: typeof fetch = (...args) => fetch(...args),
    expired: () => void = () => {},
  ) {
    this.fetcher = fetcher;
    this.expired = expired;
  }
  onExpired(handler: () => void) {
    this.expired = handler;
  }
  async session() {
    const session = await this.request<{
      authenticated: boolean;
      csrfToken?: string;
    }>("/auth/session");
    this.csrfToken = session.csrfToken ?? "";
    return session;
  }
  async login(password: string) {
    const session = await this.request<{
      authenticated: boolean;
      csrfToken?: string;
    }>("/auth/login", { method: "POST", body: JSON.stringify({ password }) });
    this.csrfToken = session.csrfToken ?? "";
    return session;
  }
  async logout() {
    await this.request("/auth/logout", { method: "POST", body: "{}" });
    this.csrfToken = "";
  }
  async request<T = Record<string, unknown>>(
    path: string,
    options: RequestInit = {},
  ): Promise<T> {
    if (!/^\/[a-z]/.test(path) || path.includes("..") || path.includes("\\"))
      throw new ApiError("无效的 API 路径");
    const headers = new Headers(options.headers);
    headers.set("Accept", "application/json");
    const mutation =
      options.method && !["GET", "HEAD"].includes(options.method.toUpperCase());
    if (mutation) {
      headers.set("Content-Type", "application/json");
      if (this.csrfToken) headers.set("X-CSRF-Token", this.csrfToken);
    }
    let response: Response;
    try {
      response = await this.fetcher(`/api/v1${path}`, {
        ...options,
        headers,
        credentials: "same-origin",
        cache: "no-store",
      });
    } catch {
      throw new ApiError(
        "无法连接电视。请确认电视应用已打开，电脑与电视处于同一局域网。",
      );
    }
    const raw = await response.text();
    let data: any = {};
    try {
      data = raw ? JSON.parse(raw) : {};
    } catch {
      throw new ApiError("电视返回了无效响应，请重试。", response.status);
    }
    if (!response.ok) {
      if (response.status === 401) {
        this.csrfToken = "";
        this.expired();
      }
      throw new ApiError(
        typeof data.message === "string"
          ? data.message
          : `请求失败（${response.status}）`,
        response.status,
      );
    }
    return data as T;
  }
}
