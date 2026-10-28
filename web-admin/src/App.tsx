import React, {
  useEffect,
  useState,
  useCallback,
  createContext,
  useContext,
  Suspense,
} from "react";
const OperationError = createContext("");
import { AdminApi } from "./api";
import type {
  Source,
  Scan,
  Episode,
  Tag,
  Candidate,
  Policy,
  Analytics,
  Day,
} from "./types";
import {
  filterEpisodes,
  monthCells,
  minutes,
  tagPayload,
  watchDuration,
  watchKindLabel,
} from "./model";
const titles = {
  sources: "资源连接",
  library: "片库管理",
  tags: "标签管理",
  calendar: "观看日历",
  analytics: "观看统计",
  policy: "观看规则",
  provider: "资料服务",
  backup: "备份与恢复",
};
type Page = keyof typeof titles;
const icons: Record<Page, string> = {
  sources: "▱",
  library: "▥",
  tags: "◇",
  calendar: "▦",
  analytics: "▥",
  policy: "◷",
  provider: "⚙",
  backup: "↥",
};
const descriptions: Record<Page, string> = {
  sources: "连接家庭资源，选择需要导入的目录。视频始终留在原处。",
  library: "整理每一部动画，让孩子轻松找到想看的故事。",
  tags: "用 K / V 多值标签，为孩子编排自己的观看路线。",
  calendar: "每一段实际观看，都有清晰的记录。",
  analytics: "按当前标签回溯全部历史；原始观看时长始终保持不变。",
  policy: "给每天的观看留出边界，也为当下保留弹性。",
  provider: "自动资料服务由电视调用，凭据仅保存在电视。",
  backup: "把片库、标签和观看记录妥善保存。",
};
function Empty({
  title,
  children,
}: {
  title: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">♧</span>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
function Modal({
  title,
  close,
  children,
}: {
  title: string;
  close: () => void;
  children: React.ReactNode;
}) {
  const error = useContext(OperationError);
  return (
    <div className="veil">
      <section
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="modal"
      >
        <header>
          <h2>{title}</h2>
          <button aria-label="关闭窗口" onClick={close}>
            ×
          </button>
        </header>
        {error && (
          <div role="alert" className="error">
            {error}
          </div>
        )}
        {children}
      </section>
    </div>
  );
}
function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}
export function App({ api }: { api: AdminApi }) {
  const [authenticated, setAuthenticated] = useState<boolean | null>(null),
    [error, setError] = useState(""),
    [password, setPassword] = useState(""),
    [pending, setPending] = useState(false),
    [page, setPage] = useState<Page>("library");
  const connect = useCallback(async () => {
    setError("");
    setAuthenticated(null);
    try {
      setAuthenticated((await api.session()).authenticated);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [api]);
  useEffect(() => {
    api.onExpired(() => setAuthenticated(false));
    void connect();
  }, [api, connect]);
  if (authenticated !== true)
    return (
      <div className="login">
        <div className="login-card">
          <div className="brand">♧ 小芽 · 家长端</div>
          <span className="eyebrow">陪伴每一次成长</span>
          <h1>欢迎回到家庭影院</h1>
          <p>在电脑上整理资源，在电视上安心观看。</p>
          {authenticated === null && !error ? (
            <p role="status">正在连接电视…</p>
          ) : (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                setPending(true);
                setError("");
                try {
                  const s = await api.login(password);
                  setPassword("");
                  setAuthenticated(s.authenticated);
                } catch (err) {
                  setError((err as Error).message);
                } finally {
                  setPending(false);
                }
              }}
            >
              <Field label="管理员密码">
                <input
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </Field>
              <button className="primary wide" disabled={pending}>
                {pending ? "正在登录…" : "登录管理端"}
              </button>
            </form>
          )}
          {error && (
            <div className="error" role="alert">
              {error}
              <button onClick={() => void connect()}>重新连接</button>
            </div>
          )}
          <aside className="notice">
            首次使用请在电视中开启管理服务并设置独立密码。此管理端仅适配电脑浏览器。
          </aside>
          <p className="fine">
            局域网 HTTP
            传输未加密，请仅在可信家庭网络使用。电视休眠或关闭应用后可能无法访问。
          </p>
        </div>
      </div>
    );
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">♧ 小芽 · 家长端</div>
        <p className="connection">
          <i /> 家庭电视 / 已连接
        </p>
        <nav>
          {(Object.keys(titles) as Page[]).map((k) => (
            <button
              key={k}
              className={page === k ? "active" : ""}
              onClick={() => {
                setPage(k);
                setError("");
              }}
            >
              <span aria-hidden>{icons[k]}</span>
              {titles[k]}
            </button>
          ))}
        </nav>
        <footer>
          <p>局域网管理已开启</p>
          <small>{location.host || "家庭电视"}</small>
          <button
            onClick={async () => {
              try {
                await api.logout();
                setAuthenticated(false);
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            退出登录 ↗
          </button>
        </footer>
      </aside>
      <main>
        <header className="page-head">
          <div>
            <h1>{titles[page]}</h1>
            <p>{descriptions[page]}</p>
          </div>
          <span className="badge">家长管理</span>
        </header>
        {error && (
          <div role="alert" className="error">
            {error}
            <button onClick={() => setError("")}>关闭</button>
          </div>
        )}
        <Workspace key={page} page={page} api={api} />
        <footer className="main-footer">
          数据保存在电视 · 仅实际播放计时 · 视频无需复制
        </footer>
      </main>
    </div>
  );
}
function Workspace({ page, api }: { page: Page; api: AdminApi }) {
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const reload = () => setRevision((x) => x + 1);
  const act = async (
    work: () => Promise<unknown>,
    message = "已保存到电视",
  ) => {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await work();
      setNotice(message);
      reload();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const shared = { api, revision, act, busy };
  return (
    <>
      <div aria-live="polite">
        {error && (
          <div role="alert" className="error">
            {error}
            <button
              onClick={() => {
                setError("");
                reload();
              }}
            >
              重试加载
            </button>
          </div>
        )}
        {notice && (
          <div className="success" role="status">
            ✓ {notice}
          </div>
        )}
      </div>
      <OperationError.Provider value={error}>
        <ErrorBoundary key={page} onError={setError}>
          <Suspense fallback={<Loading />}>
            <PageContent page={page} {...shared} onError={setError} />
          </Suspense>
        </ErrorBoundary>
      </OperationError.Provider>
    </>
  );
}
class ErrorBoundary extends React.Component<
  { children: React.ReactNode; onError: (s: string) => void },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch() {
    this.props.onError("电视数据格式不兼容，请更新应用并重试。");
  }
  render() {
    return this.state.failed ? (
      <Empty title="无法显示数据">请刷新页面重新连接电视。</Empty>
    ) : (
      this.props.children
    );
  }
}
type Shared = {
  api: AdminApi;
  revision: number;
  busy: boolean;
  act: (work: () => Promise<unknown>, message?: string) => Promise<boolean>;
  onError: (s: string) => void;
};
function useData<T>(
  api: AdminApi,
  path: string,
  revision: number,
  onError: (s: string) => void,
) {
  const [result, setResult] = useState<{ path: string; data: T } | null>(null),
    [fetching, setFetching] = useState(true);
  useEffect(() => {
    let live = true;
    setFetching(true);
    api
      .request<T>(path)
      .then((data) => {
        if (live) setResult({ path, data });
      })
      .catch((e) => {
        if (live) onError(e.message);
      })
      .finally(() => {
        if (live) setFetching(false);
      });
    return () => {
      live = false;
    };
  }, [api, path, revision]);
  const data = result?.path === path ? result.data : null;
  return { data, loading: fetching && data === null };
}
function PageContent(p: Shared & { page: Page }) {
  switch (p.page) {
    case "library":
      return <Library {...p} />;
    case "sources":
      return <Sources {...p} />;
    case "tags":
      return <Tags {...p} />;
    case "calendar":
    case "analytics":
      return <Viewing {...p} calendar={p.page === "calendar"} />;
    case "policy":
      return <Rules {...p} />;
    case "provider":
      return <Provider {...p} />;
    case "backup":
      return <Backup {...p} />;
  }
}
function Loading() {
  return (
    <div className="loading" role="status">
      ◌ 正在从电视加载…
    </div>
  );
}
function Library(p: Shared) {
  const { data: episodes, loading } = useData<Episode[]>(
      p.api,
      "/library",
      p.revision,
      p.onError,
    ),
    { data: tags } = useData<Tag[]>(p.api, "/tags", p.revision, p.onError);
  const [query, setQuery] = useState(""),
    [status, setStatus] = useState("all"),
    [filters, setFilters] = useState<Record<string, string[]>>({}),
    [selected, setSelected] = useState<string[]>([]),
    [editing, setEditing] = useState<Episode | null | undefined>(undefined),
    [batch, setBatch] = useState<"tags" | "mapping" | null>(null),
    [candidate, setCandidate] = useState<Episode | null>(null),
    [assets, setAssets] = useState<Episode | null>(null);
  if (loading) return <Loading />;
  if (!episodes)
    return <Empty title="片库暂时无法读取">请重试连接电视。</Empty>;
  const rows = filterEpisodes(episodes, query, status, filters);
  const ids = selected.filter((id) => episodes.some((e) => e.id === id));
  return (
    <>
      <div className="toolbar">
        <div className="search">
          ⌕{" "}
          <input
            aria-label="搜索片库"
            placeholder="搜索动画、集名或片库"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <select
          aria-label="匹配状态"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="all">全部状态</option>
          <option value="matched">已匹配</option>
          <option value="pending">待确认</option>
          <option value="unmatched">未匹配</option>
        </select>
        <button className="primary push" onClick={() => setEditing(null)}>
          ＋ 手动新建剧集
        </button>
      </div>
      {tags?.length ? (
        <div className="filter-tags">
          {tags.map((t) => (
            <details key={t.id}>
              <summary>
                {t.key}{" "}
                {filters[t.key]?.length ? `· ${filters[t.key].length}` : "⌄"}
              </summary>
              <div>
                {t.values.map((v) => (
                  <label key={v}>
                    <input
                      type="checkbox"
                      checked={(filters[t.key] ?? []).includes(v)}
                      onChange={(e) =>
                        setFilters({
                          ...filters,
                          [t.key]: e.target.checked
                            ? [...(filters[t.key] ?? []), v]
                            : (filters[t.key] ?? []).filter((x) => x !== v),
                        })
                      }
                    />
                    {v}
                  </label>
                ))}
              </div>
            </details>
          ))}
          <span className="fine">同 K 多值 OR · 不同 K AND</span>
        </div>
      ) : null}
      <div className="selection">
        <span>
          已选择 <strong>{ids.length}</strong> 集 · 共 {rows.length} 集
        </span>
        <button disabled={!ids.length} onClick={() => setBatch("tags")}>
          批量标签
        </button>
        <button disabled={!ids.length} onClick={() => setBatch("mapping")}>
          整季映射 / 调整集号
        </button>
      </div>
      {episodes.length === 0 ? (
        <Empty title="片库还是空的">
          先连接资源并选择目录，也可以手动创建动画资料。
        </Empty>
      ) : rows.length === 0 ? (
        <Empty title="没有符合条件的剧集">试试其他名称、状态或标签。</Empty>
      ) : (
        <div className="panel table-wrap">
          <table>
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    aria-label="选择当前全部剧集"
                    checked={
                      rows.length > 0 && rows.every((e) => ids.includes(e.id))
                    }
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...new Set([...ids, ...rows.map((e) => e.id)])]
                          : ids.filter((id) => !rows.some((e) => e.id === id)),
                      )
                    }
                  />
                </th>
                <th>剧集 / 动画</th>
                <th>片库与季集</th>
                <th>标签</th>
                <th>资料状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td>
                    <input
                      aria-label={`选择 ${e.title}`}
                      type="checkbox"
                      checked={ids.includes(e.id)}
                      onChange={(v) =>
                        setSelected(
                          v.target.checked
                            ? [...ids, e.id]
                            : ids.filter((x) => x !== e.id),
                        )
                      }
                    />
                  </td>
                  <td>
                    <div className="media-cell">
                      <div className="artwork">
                        {e.artworkLocalUrl?.startsWith("/api/v1/artwork/") ||
                        (e.artworkUrl && /^https?:\/\//.test(e.artworkUrl)) ? (
                          <img
                            src={
                              e.artworkLocalUrl?.startsWith("/api/v1/artwork/")
                                ? e.artworkLocalUrl
                                : e.artworkUrl
                            }
                            alt=""
                          />
                        ) : (
                          "▻"
                        )}
                      </div>
                      <div>
                        <strong>{e.title}</strong>
                        <small>{e.seriesTitle}</small>
                      </div>
                    </div>
                  </td>
                  <td>
                    {e.libraryName}
                    <small>
                      S{String(e.seasonNumber).padStart(2, "0")} · E
                      {String(e.episodeNumber).padStart(2, "0")}
                    </small>
                  </td>
                  <td>
                    {Object.entries(e.tags).flatMap(([k, vs]) =>
                      vs.map((v) => (
                        <span className="chip" key={k + v}>
                          {k} · {v}
                        </span>
                      )),
                    )}
                  </td>
                  <td>
                    <span className={`status ${e.status}`}>
                      {
                        {
                          matched: "已匹配",
                          pending: "待确认",
                          unmatched: "未匹配",
                        }[e.status]
                      }
                    </span>
                    {!e.available && <small className="warn">资源不可用</small>}
                  </td>
                  <td>
                    <button className="text" onClick={() => setEditing(e)}>
                      编辑
                    </button>
                    <button className="text" onClick={() => setCandidate(e)}>
                      候选匹配
                    </button>
                    <button className="text" onClick={() => setAssets(e)}>
                      资源与区间
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="fine">
        手工编辑字段受保护，重新扫描不会覆盖。删除源文件只标记不可用，保留观看记录。
      </p>
      {editing !== undefined && (
        <EpisodeEditor
          episode={editing}
          close={() => setEditing(undefined)}
          {...p}
        />
      )}{" "}
      {batch === "tags" && (
        <BulkTags
          ids={ids}
          tags={tags ?? []}
          close={() => setBatch(null)}
          {...p}
        />
      )}{" "}
      {batch === "mapping" && (
        <Mapping ids={ids} close={() => setBatch(null)} {...p} />
      )}{" "}
      {candidate && (
        <Candidates
          episode={candidate}
          close={() => setCandidate(null)}
          {...p}
        />
      )}{" "}
      {assets && (
        <AssetMapping episode={assets} close={() => setAssets(null)} {...p} />
      )}
    </>
  );
}
function EpisodeEditor(
  p: Shared & { episode: Episode | null; close: () => void },
) {
  const [form, setForm] = useState({
    title: p.episode?.title ?? "",
    seriesTitle: p.episode?.seriesTitle ?? "",
    libraryName: p.episode?.libraryName ?? "",
    seasonNumber: p.episode?.seasonNumber ?? 1,
    episodeNumber: p.episode?.episodeNumber ?? 1,
    synopsis: p.episode?.synopsis ?? "",
    artworkUrl: p.episode?.artworkUrl ?? "",
    lockedFields: p.episode?.lockedFields ?? [
      "title",
      "seriesTitle",
      "seasonNumber",
      "episodeNumber",
      "synopsis",
      "artworkUrl",
    ],
  });
  return (
    <Modal title={p.episode ? "编辑剧集资料" : "手动新建剧集"} close={p.close}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (
            await p.act(() =>
              p.api.request(
                p.episode
                  ? `/library/${encodeURIComponent(p.episode.id)}`
                  : "/library",
                {
                  method: p.episode ? "PATCH" : "POST",
                  body: JSON.stringify(form),
                },
              ),
            )
          )
            p.close();
        }}
      >
        <div className="form-grid">
          {(["title", "seriesTitle", "libraryName"] as const).map((k, i) => (
            <Field key={k} label={["集名", "动画名称", "所属片库"][i]}>
              <input
                required
                value={form[k]}
                onChange={(e) =>
                  setForm({
                    ...form,
                    [k]: e.target.value,
                    lockedFields: [...new Set([...form.lockedFields, k])],
                  })
                }
              />
            </Field>
          ))}
          {(["seasonNumber", "episodeNumber"] as const).map((k, i) => (
            <Field key={k} label={["季号", "集号"][i]}>
              <input
                required
                type="number"
                min={k === "episodeNumber" ? 1 : 0}
                value={form[k]}
                onChange={(e) =>
                  setForm({
                    ...form,
                    [k]: Number(e.target.value),
                    lockedFields: [...new Set([...form.lockedFields, k])],
                  })
                }
              />
            </Field>
          ))}
        </div>
        <Field label="简介">
          <textarea
            value={form.synopsis}
            onChange={(e) =>
              setForm({
                ...form,
                synopsis: e.target.value,
                lockedFields: [...new Set([...form.lockedFields, "synopsis"])],
              })
            }
          />
        </Field>
        <Field label="封面地址">
          <input
            type="url"
            value={form.artworkUrl}
            onChange={(e) =>
              setForm({
                ...form,
                artworkUrl: e.target.value,
                lockedFields: [
                  ...new Set([...form.lockedFields, "artworkUrl"]),
                ],
              })
            }
          />
        </Field>
        <details>
          <summary>字段保护</summary>
          <div className="check-grid">
            {[
              "title",
              "seriesTitle",
              "seasonNumber",
              "episodeNumber",
              "synopsis",
              "artworkUrl",
            ].map((k) => (
              <label key={k}>
                <input
                  type="checkbox"
                  checked={form.lockedFields.includes(k)}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      lockedFields: e.target.checked
                        ? [...form.lockedFields, k]
                        : form.lockedFields.filter((x) => x !== k),
                    })
                  }
                />
                {
                  {
                    title: "集名",
                    seriesTitle: "动画",
                    seasonNumber: "季号",
                    episodeNumber: "集号",
                    synopsis: "简介",
                    artworkUrl: "封面",
                  }[k]
                }
              </label>
            ))}
          </div>
        </details>
        <p className="fine">仅显式取消保护后，自动资料才可更新对应字段。</p>
        <div className="actions">
          <button type="button" onClick={p.close}>
            取消
          </button>
          <button className="primary" disabled={p.busy}>
            保存资料
          </button>
        </div>
      </form>
    </Modal>
  );
}
function BulkTags(
  p: Shared & { ids: string[]; tags: Tag[]; close: () => void },
) {
  const [key, setKey] = useState(p.tags[0]?.key ?? ""),
    [values, setValues] = useState<string[]>([]),
    [operation, setOperation] = useState("append"),
    [count, setCount] = useState<number | null>(null);
  const payload = tagPayload(p.ids, key, values, operation);
  return (
    <Modal title="批量标签" close={p.close}>
      <p>
        当前选中 {p.ids.length} 集。只修改现有选集，后续新增集不会自动继承。
      </p>
      <div className="form-grid">
        <Field label="标签 K">
          <select
            value={key}
            onChange={(e) => {
              setKey(e.target.value);
              setValues([]);
              setCount(null);
            }}
          >
            {p.tags.map((t) => (
              <option key={t.id}>{t.key}</option>
            ))}
          </select>
        </Field>
        <Field label="操作方式">
          <select
            value={operation}
            onChange={(e) => {
              setOperation(e.target.value);
              setCount(null);
            }}
          >
            <option value="append">追加值，保留原有标签</option>
            <option value="remove">移除指定值</option>
            <option value="replace">替换该 K 的全部值</option>
          </select>
        </Field>
      </div>
      <div className="check-grid">
        {(p.tags.find((t) => t.key === key)?.values ?? []).map((v) => (
          <label key={v}>
            <input
              type="checkbox"
              checked={values.includes(v)}
              onChange={(e) => {
                setValues(
                  e.target.checked
                    ? [...values, v]
                    : values.filter((x) => x !== v),
                );
                setCount(null);
              }}
            />
            {v}
          </label>
        ))}
      </div>
      {!p.tags.length && <p>请先在标签管理中创建标签。</p>}
      {count !== null && (
        <div className="notice">将修改 {count} 集现有剧集</div>
      )}
      <div className="actions">
        <button onClick={p.close}>取消</button>
        {count === null ? (
          <button
            disabled={
              p.busy || !key || (!values.length && operation !== "replace")
            }
            onClick={() =>
              void p.act(async () => {
                const d = await p.api.request<{ affectedCount: number }>(
                  "/library/tags/preview",
                  { method: "POST", body: JSON.stringify(payload) },
                );
                setCount(d.affectedCount);
              }, "预览已生成，请核对影响范围")
            }
          >
            预览影响范围
          </button>
        ) : (
          <button
            className="primary"
            disabled={p.busy}
            onClick={async () => {
              if (
                await p.act(() =>
                  p.api.request("/library/tags", {
                    method: "POST",
                    body: JSON.stringify({ ...payload, confirmed: true }),
                  }),
                )
              )
                p.close();
            }}
          >
            确认应用标签
          </button>
        )}
      </div>
    </Modal>
  );
}
function Mapping(p: Shared & { ids: string[]; close: () => void }) {
  const [seriesTitle, setSeries] = useState(""),
    [seasonNumber, setSeason] = useState(1),
    [startEpisode, setStart] = useState(1),
    [rows, setRows] = useState<
      | {
          id: string;
          title: string;
          seasonNumber: number;
          episodeNumber: number;
        }[]
      | null
    >(null);
  const payload = {
    episodeIds: p.ids,
    seriesTitle,
    seasonNumber,
    startEpisode,
  };
  return (
    <Modal title="整季映射 / 批量调整集号" close={p.close}>
      <p>按当前所选剧集顺序连续编号。提交前请逐条核对。</p>
      <div className="form-grid">
        <Field label="目标动画">
          <input
            value={seriesTitle}
            onChange={(e) => {
              setSeries(e.target.value);
              setRows(null);
            }}
          />
        </Field>
        <Field label="目标季号">
          <input
            type="number"
            min={0}
            value={seasonNumber}
            onChange={(e) => {
              setSeason(Number(e.target.value));
              setRows(null);
            }}
          />
        </Field>
        <Field label="起始集号">
          <input
            type="number"
            min={1}
            value={startEpisode}
            onChange={(e) => {
              setStart(Number(e.target.value));
              setRows(null);
            }}
          />
        </Field>
      </div>
      {rows && (
        <div className="panel">
          <table>
            <thead>
              <tr>
                <th>原剧集</th>
                <th>目标编号</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.title}</td>
                  <td>
                    S{r.seasonNumber} E{r.episodeNumber}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="actions">
        <button onClick={p.close}>取消</button>
        {rows ? (
          <button
            className="primary"
            disabled={p.busy}
            onClick={async () => {
              if (
                await p.act(() =>
                  p.api.request("/library/mapping", {
                    method: "POST",
                    body: JSON.stringify({ ...payload, confirmed: true }),
                  }),
                )
              )
                p.close();
            }}
          >
            确认映射 {rows.length} 集
          </button>
        ) : (
          <button
            disabled={p.busy || !seriesTitle.trim()}
            onClick={() =>
              void p.act(async () => {
                setRows(
                  (
                    await p.api.request<{ rows: NonNullable<typeof rows> }>(
                      "/library/mapping/preview",
                      { method: "POST", body: JSON.stringify(payload) },
                    )
                  ).rows,
                );
              }, "映射预览已生成")
            }
          >
            生成逐集预览
          </button>
        )}
      </div>
    </Modal>
  );
}
function Candidates(p: Shared & { episode: Episode; close: () => void }) {
  const { data, loading } = useData<Candidate[]>(
    p.api,
    `/library/${encodeURIComponent(p.episode.id)}/candidates`,
    0,
    p.onError,
  );
  return (
    <Modal title={`候选匹配 · ${p.episode.title}`} close={p.close}>
      <p>请核对动画身份、季集与证据。模糊名称相似不能直接确认。</p>
      {loading ? (
        <Loading />
      ) : data?.length ? (
        data.map((c) => (
          <article className="candidate" key={c.id}>
            <h3>
              {c.title} · S{c.seasonNumber} E{c.episodeNumber}
            </h3>
            <ul>
              {c.evidence.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
            <button
              disabled={p.busy}
              onClick={async () => {
                if (
                  await p.act(
                    () =>
                      p.api.request(
                        `/library/${encodeURIComponent(p.episode.id)}/match`,
                        {
                          method: "POST",
                          body: JSON.stringify({ candidateId: c.id }),
                        },
                      ),
                    "匹配已确认",
                  )
                )
                  p.close();
              }}
            >
              确认这个候选
            </button>
          </article>
        ))
      ) : (
        <Empty title="暂无候选">
          可先手动编辑资料；未配置在线服务时仍可正常维护片库。
        </Empty>
      )}
    </Modal>
  );
}
type AssetRow = {
  id: string;
  name: string;
  sourceId: string;
  path: string;
  selected: boolean;
  startMs?: number;
  endMs?: number;
};
function AssetMapping(p: Shared & { episode: Episode; close: () => void }) {
  const { data, loading } = useData<AssetRow[]>(
    p.api,
    `/library/${encodeURIComponent(p.episode.id)}/assets`,
    0,
    p.onError,
  );
  const [mapping, setMapping] = useState<
    Record<string, { start: string; end: string }>
  >({});
  useEffect(() => {
    if (data)
      setMapping(
        Object.fromEntries(
          data
            .filter((a) => a.selected)
            .map((a) => [
              a.id,
              {
                start: a.startMs === undefined ? "" : String(a.startMs / 1000),
                end: a.endMs === undefined ? "" : String(a.endMs / 1000),
              },
            ]),
        ),
      );
  }, [data]);
  return (
    <Modal title={`资源与播放区间 · ${p.episode.title}`} close={p.close}>
      <p>
        选择文件关联到本逻辑集。一个逻辑集可有多个资源；合辑文件可分别关联到不同逻辑集并指定播放起止。留空表示整段资源。
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const assets = Object.entries(mapping).map(([assetId, v]) => ({
            assetId,
            ...(v.start !== ""
              ? { startMs: Math.round(Number(v.start) * 1000) }
              : {}),
            ...(v.end !== ""
              ? { endMs: Math.round(Number(v.end) * 1000) }
              : {}),
          }));
          if (
            assets.some(
              (a) =>
                (a.startMs ?? 0) < 0 ||
                (a.endMs !== undefined && a.endMs <= (a.startMs ?? 0)),
            )
          ) {
            p.onError("播放终点必须大于起点，且区间不能为负数。");
            return;
          }
          if (
            await p.act(
              () =>
                p.api.request(
                  `/library/${encodeURIComponent(p.episode.id)}/assets`,
                  { method: "PUT", body: JSON.stringify({ assets }) },
                ),
              "资源映射已保存，原有标签与观看记录保持关联",
            )
          )
            p.close();
        }}
      >
        {loading ? (
          <Loading />
        ) : data?.length ? (
          <div className="asset-list">
            {data.map((a) => (
              <article className="candidate" key={a.id}>
                <label>
                  <input
                    type="checkbox"
                    aria-label={`关联 ${a.name}`}
                    checked={!!mapping[a.id]}
                    onChange={(e) =>
                      setMapping((old) => {
                        const next = { ...old };
                        if (e.target.checked)
                          next[a.id] = { start: "", end: "" };
                        else delete next[a.id];
                        return next;
                      })
                    }
                  />
                  <strong>{a.name}</strong>
                </label>
                <small className="asset-path">{a.path}</small>
                {mapping[a.id] && (
                  <div className="form-grid">
                    <Field label={`${a.name} 起点（秒）`}>
                      <input
                        type="number"
                        min="0"
                        step="0.001"
                        value={mapping[a.id].start}
                        onChange={(e) =>
                          setMapping({
                            ...mapping,
                            [a.id]: { ...mapping[a.id], start: e.target.value },
                          })
                        }
                        placeholder="0"
                      />
                    </Field>
                    <Field label={`${a.name} 终点（秒）`}>
                      <input
                        type="number"
                        min="0"
                        step="0.001"
                        value={mapping[a.id].end}
                        onChange={(e) =>
                          setMapping({
                            ...mapping,
                            [a.id]: { ...mapping[a.id], end: e.target.value },
                          })
                        }
                        placeholder="文件结束"
                      />
                    </Field>
                  </div>
                )}
              </article>
            ))}
          </div>
        ) : (
          <Empty title="暂无可关联资源">请先连接资源目录并完成扫描。</Empty>
        )}
        <div className="actions">
          <button type="button" onClick={p.close}>
            取消
          </button>
          <button className="primary" disabled={p.busy || loading || !data}>
            保存资源映射
          </button>
        </div>
      </form>
    </Modal>
  );
}

function Sources(p: Shared) {
  const [poll, setPoll] = useState(0);
  const { data: sources, loading } = useData<Source[]>(
      p.api,
      "/sources",
      p.revision,
      p.onError,
    ),
    { data: scans } = useData<Scan[]>(
      p.api,
      "/scans",
      p.revision + poll,
      p.onError,
    );
  const [adding, setAdding] = useState(false),
    [browsing, setBrowsing] = useState<Source | null>(null),
    [credentials, setCredentials] = useState<Source | null>(null);
  useEffect(() => {
    if (!scans?.some((s) => s.status === "running" || s.status === "waiting"))
      return;
    const t = setInterval(() => setPoll((v) => v + 1), 5000);
    return () => clearInterval(t);
  }, [scans]);
  return (
    <>
      <div className="toolbar">
        <span>外部资源源 · {sources?.length ?? 0} 个</span>
        <button className="primary push" onClick={() => setAdding(true)}>
          ＋ 添加资源连接
        </button>
      </div>
      <aside className="notice">
        支持外部 OpenList / AList、NAS SMB2/3 与
        WebDAV。凭据保存在电视，页面只显示配置状态。
      </aside>
      {loading ? (
        <Loading />
      ) : sources?.length ? (
        <div className="source-grid">
          {sources.map((s) => (
            <article className="panel source" key={s.id}>
              <header>
                <span className="source-symbol">▱</span>
                <div>
                  <h2>{s.name}</h2>
                  <small>{s.type.toUpperCase()}</small>
                </div>
                <span className="badge push">
                  {s.credentialConfigured ? "凭据已配置" : "需配置凭据"}
                </span>
              </header>
              <p className="endpoint">{s.endpoint}</p>
              <p className="fine">
                已选目录：{s.roots.length ? s.roots.join("、") : "尚未选择"}
              </p>
              <div className="actions">
                <button
                  disabled={p.busy}
                  onClick={() =>
                    void p.act(async () => {
                      const r = await p.api.request<{ message: string }>(
                        `/sources/${encodeURIComponent(s.id)}/test`,
                        { method: "POST", body: "{}" },
                      );
                      if (r.message) alert(r.message);
                    }, "连接测试完成")
                  }
                >
                  测试连接
                </button>
                <button onClick={() => setCredentials(s)}>更新凭据</button>
                <button onClick={() => setBrowsing(s)}>选择目录</button>
                <button
                  className="primary"
                  disabled={p.busy || !s.roots.length}
                  onClick={() =>
                    void p.act(
                      () =>
                        p.api.request(
                          `/sources/${encodeURIComponent(s.id)}/scan`,
                          { method: "POST", body: "{}" },
                        ),
                      "扫描任务已提交",
                    )
                  }
                >
                  重新扫描
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <Empty title="还没有资源连接">
          添加家庭 NAS 或外部列表服务，开始整理第一部动画。
        </Empty>
      )}
      <h2 className="section-heading">
        扫描任务 <span>发现 → 匹配 → 补全</span>
      </h2>
      {scans?.length ? (
        <div className="panel">
          <table>
            <thead>
              <tr>
                <th>资源源</th>
                <th>当前阶段</th>
                <th>发现 / 匹配 / 完成</th>
                <th>状态与操作</th>
              </tr>
            </thead>
            <tbody>
              {scans.map((s) => (
                <tr key={s.id}>
                  <td>
                    {sources?.find((x) => x.id === s.sourceId)?.name ??
                      s.sourceId}
                  </td>
                  <td>
                    {{
                      discovering: "发现文件",
                      matching: "匹配资料",
                      enriching: "补全资料",
                      completed: "已完成",
                      waiting_retry: "资料服务退避中",
                      retry_exhausted: "重试次数已用尽",
                      cancelled: "已取消",
                    }[s.stage] ?? s.stage}
                    {s.error && <small className="warn">{s.error}</small>}
                  </td>
                  <td>
                    {s.discovered} / {s.matched} / {s.completed}
                  </td>
                  <td>
                    <span className="chip">
                      {
                        {
                          running: "扫描中",
                          waiting: "等待自动重试",
                          failed: "失败",
                          cancelled: "已取消",
                          completed: "已完成",
                        }[s.status]
                      }
                    </span>
                    {s.status === "waiting" && (
                      <small>
                        下次重试：
                        {(s.cursor?.retryAtMillis ?? s.retryAt)
                          ? new Date(
                              (s.cursor?.retryAtMillis ?? s.retryAt)!,
                            ).toLocaleString("zh-CN")
                          : "等待电视重新安排"}
                      </small>
                    )}
                    {s.status === "running" || s.status === "waiting" ? (
                      <button
                        disabled={p.busy}
                        onClick={() =>
                          void p.act(
                            () =>
                              p.api.request(
                                `/scans/${encodeURIComponent(s.id)}/cancel`,
                                { method: "POST", body: "{}" },
                              ),
                            "已请求取消",
                          )
                        }
                      >
                        取消扫描
                      </button>
                    ) : s.status !== "completed" ? (
                      <button
                        disabled={p.busy}
                        onClick={() =>
                          void p.act(
                            () =>
                              p.api.request(
                                `/scans/${encodeURIComponent(s.id)}/retry`,
                                { method: "POST", body: "{}" },
                              ),
                            "已请求重试",
                          )
                        }
                      >
                        重试 / 继续
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="panel quiet">
          暂无扫描任务。保存所选目录后将自动开始扫描与匹配。
        </div>
      )}
      {adding && <SourceEditor {...p} close={() => setAdding(false)} />}{" "}
      {credentials && (
        <CredentialEditor
          {...p}
          source={credentials}
          close={() => setCredentials(null)}
        />
      )}{" "}
      {browsing && (
        <Directories {...p} source={browsing} close={() => setBrowsing(null)} />
      )}
    </>
  );
}

type SourceAuthMode = "password" | "token";
function SourceCredentialFields(p: {
  type: string;
  mode: SourceAuthMode;
  onMode: (mode: SourceAuthMode) => void;
  username: string;
  onUsername: (v: string) => void;
  secret: string;
  onSecret: (v: string) => void;
  required?: boolean;
}) {
  const list = p.type === "openlist" || p.type === "alist",
    token = list && p.mode === "token";
  return (
    <>
      {list && (
        <Field label="认证方式">
          <select
            value={p.mode}
            onChange={(e) => p.onMode(e.target.value as SourceAuthMode)}
          >
            <option value="password">账号密码登录</option>
            <option value="token">访问令牌（Token）</option>
          </select>
        </Field>
      )}
      <div className="form-grid">
        {!token && (
          <Field label="用户名">
            <input
              required={list}
              autoComplete="off"
              value={p.username}
              onChange={(e) => p.onUsername(e.target.value)}
            />
          </Field>
        )}
        <Field label={token ? "访问令牌（Token）" : list ? "登录密码" : "密码"}>
          <input
            required={p.required || list}
            type="password"
            autoComplete="new-password"
            value={p.secret}
            onChange={(e) => p.onSecret(e.target.value)}
          />
        </Field>
      </div>
      {list && (
        <p className="fine">
          {token
            ? `请从 ${p.type === "openlist" ? "OpenList" : "AList"} 管理后台获取访问令牌（Token），请勿填写账号登录密码。`
            : `使用 ${p.type === "openlist" ? "OpenList" : "AList"} 用户名与登录密码，连接时会自动登录获取访问令牌。`}
        </p>
      )}
    </>
  );
}
function SourceEditor(p: Shared & { close: () => void }) {
  const [form, setForm] = useState({
    name: "",
    type: "openlist",
    endpoint: "",
    username: "",
    secret: "",
    roots: [] as string[],
  });
  const [authMode, setAuthMode] = useState<SourceAuthMode>("password"),
    [login, setLogin] = useState({ username: "", secret: "" }),
    [token, setToken] = useState("");
  const list = form.type === "openlist" || form.type === "alist";
  return (
    <Modal title="添加资源连接" close={p.close}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const payload = list
            ? {
                name: form.name,
                type: form.type,
                endpoint: form.endpoint,
                roots: form.roots,
                authMode,
                ...(authMode === "password" ? login : { secret: token }),
              }
            : form;
          if (
            await p.act(
              () =>
                p.api.request("/sources", {
                  method: "POST",
                  body: JSON.stringify(payload),
                }),
              "连接已保存，请选择导入目录",
            )
          )
            p.close();
        }}
      >
        <Field label="连接名称">
          <input
            required
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
          />
        </Field>
        <Field label="连接类型">
          <select
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value })}
          >
            <option value="openlist">OpenList</option>
            <option value="alist">AList</option>
            <option value="smb">SMB2 / SMB3</option>
            <option value="webdav">WebDAV</option>
          </select>
        </Field>
        <Field label="服务地址">
          <input
            required
            placeholder={
              form.type === "smb"
                ? "smb://nas/share"
                : "https://nas.example/dav"
            }
            value={form.endpoint}
            onChange={(e) => setForm({ ...form, endpoint: e.target.value })}
          />
        </Field>
        <SourceCredentialFields
          type={form.type}
          mode={authMode}
          onMode={setAuthMode}
          username={list ? login.username : form.username}
          onUsername={(username) =>
            list
              ? setLogin({ ...login, username })
              : setForm({ ...form, username })
          }
          secret={
            list
              ? authMode === "password"
                ? login.secret
                : token
              : form.secret
          }
          onSecret={(secret) =>
            list
              ? authMode === "password"
                ? setLogin({ ...login, secret })
                : setToken(secret)
              : setForm({ ...form, secret })
          }
        />
        <p className="fine">凭据仅发送给本电视管理服务，不在浏览器持久保存。</p>
        <div className="actions">
          <button type="button" onClick={p.close}>
            取消
          </button>
          <button className="primary" disabled={p.busy}>
            保存连接
          </button>
        </div>
      </form>
    </Modal>
  );
}
function CredentialEditor(p: Shared & { source: Source; close: () => void }) {
  const [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [token, setToken] = useState(""),
    [authMode, setAuthMode] = useState<SourceAuthMode>(
      p.source.authMode ?? "token",
    );
  const list = p.source.type === "openlist" || p.source.type === "alist";
  return (
    <Modal title={`更新凭据 · ${p.source.name}`} close={p.close}>
      <p>现有凭据不会回显。账号密码模式请重新填写用户名和登录密码。</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const payload = list
            ? {
                authMode,
                ...(authMode === "password"
                  ? { username, secret: password }
                  : { secret: token }),
              }
            : { username, secret: password };
          if (
            await p.act(
              () =>
                p.api.request(`/sources/${encodeURIComponent(p.source.id)}`, {
                  method: "PATCH",
                  body: JSON.stringify(payload),
                }),
              "资源凭据已更新",
            )
          )
            p.close();
        }}
      >
        <SourceCredentialFields
          type={p.source.type}
          mode={authMode}
          onMode={setAuthMode}
          username={username}
          onUsername={setUsername}
          secret={list && authMode === "token" ? token : password}
          onSecret={list && authMode === "token" ? setToken : setPassword}
          required
        />
        <div className="actions">
          <button type="button" onClick={p.close}>
            取消
          </button>
          <button className="primary" disabled={p.busy}>
            保存凭据
          </button>
        </div>
      </form>
    </Modal>
  );
}

function Directories(p: Shared & { source: Source; close: () => void }) {
  const [path, setPath] = useState("/"),
    [roots, setRoots] = useState(p.source.roots);
  const { data, loading } = useData<{
    path: string;
    directories: { name: string; path: string }[];
  }>(
    p.api,
    `/sources/${encodeURIComponent(p.source.id)}/directories?path=${encodeURIComponent(path)}`,
    0,
    p.onError,
  );
  return (
    <Modal title={`选择目录 · ${p.source.name}`} close={p.close}>
      <div className="toolbar">
        <button
          disabled={path === "/"}
          onClick={() => setPath(path.replace(/\/?[^/]+\/?$/, "") || "/")}
        >
          ↑ 上一级
        </button>
        <code>{path}</code>
      </div>
      {loading ? (
        <Loading />
      ) : (
        <div className="directory-list">
          <label>
            <input
              type="checkbox"
              checked={roots.includes(path)}
              onChange={(e) =>
                setRoots(
                  e.target.checked
                    ? [...new Set([...roots, path])]
                    : roots.filter((r) => r !== path),
                )
              }
            />
            选择当前目录
          </label>
          {data?.directories.map((d) => (
            <div key={d.path}>
              <label>
                <input
                  type="checkbox"
                  aria-label={`导入 ${d.name}`}
                  checked={roots.includes(d.path)}
                  onChange={(e) =>
                    setRoots(
                      e.target.checked
                        ? [...new Set([...roots, d.path])]
                        : roots.filter((r) => r !== d.path),
                    )
                  }
                />
                ▱ {d.name}
              </label>
              <button onClick={() => setPath(d.path)}>打开 →</button>
            </div>
          ))}
          {data && !data.directories.length && (
            <p className="fine">此目录下没有子目录，可以选择当前目录。</p>
          )}
        </div>
      )}
      <p className="fine">
        已选 {roots.length} 个目录：{roots.join("、") || "未选择"}
      </p>
      <div className="actions">
        <button onClick={p.close}>取消</button>
        <button
          className="primary"
          disabled={p.busy || !roots.length}
          onClick={async () => {
            if (
              await p.act(async () => {
                await p.api.request(
                  `/sources/${encodeURIComponent(p.source.id)}`,
                  { method: "PATCH", body: JSON.stringify({ roots }) },
                );
              }, "目录已保存，电视将自动扫描")
            )
              p.close();
          }}
        >
          保存目录并扫描
        </button>
      </div>
    </Modal>
  );
}
function Tags(p: Shared) {
  const { data, loading } = useData<Tag[]>(
    p.api,
    "/tags",
    p.revision,
    p.onError,
  );
  const [editing, setEditing] = useState<string | null>(null),
    [key, setKey] = useState(""),
    [values, setValues] = useState(""),
    [deleting, setDeleting] = useState<Tag | null>(null);
  return (
    <>
      <div className="two-column">
        <section>
          <h2 className="section-heading">标签字典</h2>
          {loading ? (
            <Loading />
          ) : data?.length ? (
            data.map((t) => (
              <article className="panel tag-card" key={t.id}>
                <header>
                  <h3>{t.key}</h3>
                  <button
                    className="text push"
                    onClick={() => {
                      setEditing(t.id);
                      setKey(t.key);
                      setValues(t.values.join("\n"));
                    }}
                  >
                    编辑
                  </button>
                  <button
                    className="text danger"
                    onClick={() => setDeleting(t)}
                  >
                    删除
                  </button>
                </header>
                <div>
                  {t.values.map((v) => (
                    <span className="chip" key={v}>
                      {v}
                    </span>
                  ))}
                </div>
              </article>
            ))
          ) : (
            <Empty title="还没有标签">
              创建一个名称与多个值，用于筛选、播放与统计。
            </Empty>
          )}
          <p className="fine">
            同一集可以同时拥有同 K 多个值；批量操作前会展示受影响集数。
          </p>
        </section>
        <section className="panel form-panel">
          <h2>{editing ? "编辑标签" : "新建标签"}</h2>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (
                await p.act(() =>
                  p.api.request(
                    editing ? `/tags/${encodeURIComponent(editing)}` : "/tags",
                    {
                      method: editing ? "PATCH" : "POST",
                      body: JSON.stringify({
                        key: key.trim(),
                        values: [
                          ...new Set(
                            values
                              .split("\n")
                              .map((s) => s.trim())
                              .filter(Boolean),
                          ),
                        ],
                      }),
                    },
                  ),
                )
              ) {
                setKey("");
                setValues("");
                setEditing(null);
              }
            }}
          >
            <Field label="标签名称 K">
              <input
                required
                value={key}
                onChange={(e) => setKey(e.target.value)}
                placeholder="例如：语言、庆爸路线"
              />
            </Field>
            <Field label="标签值 V（每行一个）">
              <textarea
                required
                rows={7}
                value={values}
                onChange={(e) => setValues(e.target.value)}
                placeholder={"英语\n国语"}
              />
            </Field>
            <div className="actions">
              {editing && (
                <button
                  type="button"
                  onClick={() => {
                    setEditing(null);
                    setKey("");
                    setValues("");
                  }}
                >
                  取消编辑
                </button>
              )}
              <button className="primary" disabled={p.busy}>
                保存标签
              </button>
            </div>
          </form>
        </section>
      </div>
      {deleting && (
        <Modal title="删除标签" close={() => setDeleting(null)}>
          <p>
            删除「{deleting.key}
            」会移除当前标签关联，历史实际观看记录保留，标签统计将重新计算。
          </p>
          <div className="actions">
            <button onClick={() => setDeleting(null)}>取消</button>
            <button
              className="danger"
              disabled={p.busy}
              onClick={async () => {
                if (
                  await p.act(() =>
                    p.api.request(`/tags/${encodeURIComponent(deleting.id)}`, {
                      method: "DELETE",
                    }),
                  )
                )
                  setDeleting(null);
              }}
            >
              确认删除
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
function Viewing(p: Shared & { calendar: boolean }) {
  const today = new Date().toLocaleDateString("sv-SE");
  const [month, setMonth] = useState(today.slice(0, 7)),
    [date, setDate] = useState(today),
    [dimension, setDimension] = useState("series"),
    [filters, setFilters] = useState<Record<string, string[]>>({});
  const [y, m] = month.split("-").map(Number);
  const monthFrom = `${month}-01`,
    monthTo = `${month}-${new Date(y, m, 0).getDate()}`;
  const [range, setRange] = useState({ from: monthFrom, to: monthTo });
  const [draftFrom, setDraftFrom] = useState(monthFrom),
    [draftTo, setDraftTo] = useState(monthTo);
  const from = p.calendar ? monthFrom : range.from,
    to = p.calendar ? monthTo : range.to;
  const { data, loading } = useData<Analytics>(
      p.api,
      `/analytics?from=${from}&to=${to}&dimension=${dimension}&tags=${encodeURIComponent(JSON.stringify(filters))}`,
      p.revision,
      p.onError,
    ),
    { data: day, loading: dayLoading } = useData<Day>(
      p.api,
      `/analytics/day?date=${date}`,
      p.revision,
      p.onError,
    ),
    { data: tags } = useData<Tag[]>(p.api, "/tags", p.revision, p.onError);
  return (
    <>
      <div className="toolbar">
        <input
          aria-label="统计月份"
          type="month"
          value={month}
          onChange={(e) => {
            if (e.target.value) {
              setMonth(e.target.value);
              setDate(e.target.value + "-01");
              const [year, mon] = e.target.value.split("-").map(Number);
              const first = e.target.value + "-01",
                last = e.target.value + "-" + new Date(year, mon, 0).getDate();
              setRange({ from: first, to: last });
              setDraftFrom(first);
              setDraftTo(last);
            }
          }}
        />
        {!p.calendar && (
          <>
            <select
              aria-label="统计维度"
              value={dimension}
              onChange={(e) => setDimension(e.target.value)}
            >
              <option value="series">按动画</option>
              <option value="library">按观看时片库</option>
              <option value="tag">按当前标签</option>
            </select>
            {tags?.map((t) => (
              <details key={t.id}>
                <summary>{t.key} ⌄</summary>
                {t.values.map((v) => (
                  <label className="inline-check" key={v}>
                    <input
                      type="checkbox"
                      checked={(filters[t.key] ?? []).includes(v)}
                      onChange={(e) =>
                        setFilters({
                          ...filters,
                          [t.key]: e.target.checked
                            ? [...(filters[t.key] ?? []), v]
                            : (filters[t.key] ?? []).filter((x) => x !== v),
                        })
                      }
                    />
                    {v}
                  </label>
                ))}
              </details>
            ))}
          </>
        )}
        <span className="fine push">
          {from} — {to}
        </span>
      </div>
      {!p.calendar && (
        <form
          className="toolbar range-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (draftFrom > draftTo) {
              p.onError("开始日期不能晚于结束日期。");
              return;
            }
            setRange({ from: draftFrom, to: draftTo });
          }}
        >
          <label>
            开始{" "}
            <input
              aria-label="开始日期"
              type="date"
              required
              value={draftFrom}
              onChange={(e) => setDraftFrom(e.target.value)}
            />
          </label>
          <label>
            结束{" "}
            <input
              aria-label="结束日期"
              type="date"
              required
              value={draftTo}
              onChange={(e) => setDraftTo(e.target.value)}
            />
          </label>
          <button>应用日期范围</button>
        </form>
      )}
      {loading ? (
        <Loading />
      ) : data ? (
        <>
          <div className="metrics">
            <article>
              <small>实际观看 · 去重合计</small>
              <strong>{minutes(data.totalSeconds)}</strong>
              <p>重复观看按实际用时累计</p>
            </article>
            <article>
              <small>看过的剧集</small>
              <strong>{data.episodeCount} 集</strong>
              <p>相同剧集只计一次</p>
            </article>
            <article>
              <small>有观看的日期</small>
              <strong>{data.watchDays} 天</strong>
              <p>按固定家庭时区统计</p>
            </article>
          </div>
          {p.calendar ? (
            <div className="calendar-layout">
              <section className="panel calendar">
                <h2>
                  {y} 年 {m} 月
                </h2>
                <div className="calendar-grid">
                  {["一", "二", "三", "四", "五", "六", "日"].map((d) => (
                    <span className="weekday" key={d}>
                      周{d}
                    </span>
                  ))}
                  {monthCells(month).map((d, i) =>
                    d ? (
                      <button
                        key={d}
                        className={`${date === d ? "chosen" : ""} ${d === today ? "today" : ""}`}
                        onClick={() => setDate(d)}
                      >
                        <b>{Number(d.slice(-2))}</b>
                        <small>
                          {data.days.find((x) => x.date === d)?.seconds
                            ? minutes(
                                data.days.find((x) => x.date === d)!.seconds,
                              )
                            : "—"}
                        </small>
                      </button>
                    ) : (
                      <span key={`empty${i}`} />
                    ),
                  )}
                </div>
              </section>
              <section className="panel day-detail">
                <h2>
                  {date} <small>每日明细</small>
                </h2>
                {dayLoading ? (
                  <Loading />
                ) : day ? (
                  <>
                    <div className="day-total">{minutes(day.totalSeconds)}</div>
                    <p className="fine">
                      其中本集收尾 {minutes(day.graceSeconds)}
                    </p>
                    {day.entries.length ? (
                      day.entries.map((e) => (
                        <article className="watch-entry" key={e.id}>
                          <strong>{e.title}</strong>
                          <small>
                            {e.seriesTitle} ·{" "}
                            {new Date(e.startedAt).toLocaleTimeString("zh-CN", {
                              hour: "2-digit",
                              minute: "2-digit",
                            })}
                          </small>
                          <span>
                            {watchDuration(e)} · {watchKindLabel(e)}
                          </span>
                        </article>
                      ))
                    ) : (
                      <Empty title="这一天没有观看记录">
                        实际观看 0 分钟。
                      </Empty>
                    )}
                  </>
                ) : null}
              </section>
            </div>
          ) : (
            <>
              <section className="panel trend">
                <h2>每天看了多久</h2>
                {data.days.length ? (
                  <div className="bars">
                    {data.days.map((d) => (
                      <div className="bar-item" key={d.date}>
                        <span>{Math.floor(d.seconds / 60)} 分</span>
                        <div
                          style={{
                            height: `${Math.max(d.seconds ? 3 : 1, Math.round((d.seconds / Math.max(1, ...data.days.map((x) => x.seconds))) * 125))}px`,
                          }}
                        />
                        <small>{d.date.slice(5).replace("-", ".")}</small>
                      </div>
                    ))}
                  </div>
                ) : (
                  <Empty title="这个月还没有观看记录">
                    开始播放后，这里会显示真实的观看趋势。
                  </Empty>
                )}
              </section>
              <div className="panel">
                <table>
                  <thead>
                    <tr>
                      <th>
                        {dimension === "tag"
                          ? "当前标签"
                          : dimension === "library"
                            ? "观看时所属片库"
                            : "动画"}
                      </th>
                      <th>观看时长</th>
                      <th>去重剧集</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((r, i) => (
                      <tr key={r.id ?? r.label}>
                        <td>{r.label}</td>
                        <td>{minutes(r.seconds)}</td>
                        <td>{r.episodeCount} 集</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!data.rows.length && (
                  <p className="quiet">没有符合条件的统计记录。</p>
                )}
              </div>
              <p className="fine">
                同一段观看可以计入多个标签，因此各行不能直接相加。上方合计已去重；修改标签会更新历史分类统计。
              </p>
            </>
          )}
        </>
      ) : (
        <Empty title="统计暂时不可用">请重新连接电视。</Empty>
      )}
    </>
  );
}
function Rules(p: Shared) {
  const { data, loading } = useData<Policy>(
    p.api,
    "/policy",
    p.revision,
    p.onError,
  );
  const [value, setValue] = useState("");
  useEffect(() => {
    if (data) setValue(String(data.defaultMinutes));
  }, [data]);
  return loading ? (
    <Loading />
  ) : data ? (
    <div className="two-column">
      <section className="panel form-panel">
        <span className="eyebrow">每天的陪伴节奏</span>
        <h2>每日默认观看额度</h2>
        <p>没有今日覆盖值的日期使用此规则。</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            await p.act(
              () =>
                p.api.request("/policy", {
                  method: "PUT",
                  body: JSON.stringify({ defaultMinutes: Number(value) }),
                }),
              "默认额度已保存，今日覆盖值保持不变",
            );
          }}
        >
          <Field label="每日默认分钟数">
            <input
              required
              type="number"
              min={0}
              max={1440}
              step={1}
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
          </Field>
          <div className="presets">
            {[30, 45, 60, 90].map((v) => (
              <button
                type="button"
                className={value === String(v) ? "selected" : ""}
                onClick={() => setValue(String(v))}
                key={v}
              >
                {v} 分钟
              </button>
            ))}
          </div>
          <button className="primary" disabled={p.busy}>
            保存默认规则
          </button>
        </form>
        <p className="fine">家庭统计时区：{data.timezone}</p>
      </section>
      <section>
        <div className="panel form-panel">
          <h2>今天的安排</h2>
          <div className="quota">
            <strong>
              {data.todayMinutes} <small>分钟总额度</small>
            </strong>
            <span className="badge">
              {data.todayOverride ? "今日单独设置" : "使用默认值"}
            </span>
          </div>
          <dl>
            <dt>实际已看</dt>
            <dd>{minutes(data.watchedSeconds)}</dd>
            <dt>剩余额度</dt>
            <dd>
              {minutes(
                Math.max(0, data.todayMinutes * 60 - data.watchedSeconds),
              )}
            </dd>
            <dt>播放状态</dt>
            <dd>
              {{
                available: "可以观看",
                "finishing-current-episode": "看完本集后休息",
                finishing_current_episode: "看完本集后休息",
                finishing: "看完本集后休息",
                locked: "已停止新集播放",
              }[data.state] ?? data.state}
            </dd>
          </dl>
        </div>
        <aside className="notice">
          今日总额度请在电视「家长设置」中完成一次性答题验证后修改。修改不会清零已观看时间。额度到时允许本集收尾，之后停止新集。
        </aside>
      </section>
    </div>
  ) : null;
}
function Provider(p: Shared) {
  const { data, loading } = useData<{
    tmdbConfigured: boolean;
    openSubtitlesConfigured: boolean;
    status: string;
  }>(p.api, "/provider", p.revision, p.onError);
  const [tmdb, setTmdb] = useState(""),
    [os, setOs] = useState("");
  return (
    <>
      <aside className="notice">
        未配置在线服务时，仍可本地扫描并手工维护资料。自动识别不会下载字幕。
      </aside>
      {loading ? (
        <Loading />
      ) : (
        data && (
          <section className="panel form-panel narrow">
            <h2>自动资料服务</h2>
            <div className="provider-status">
              <span className="chip">
                {data.tmdbConfigured ? "TMDB 已配置" : "TMDB 未配置"}
              </span>
              <span className="chip">
                {data.openSubtitlesConfigured
                  ? "OpenSubtitles 已配置"
                  : "OpenSubtitles 未配置"}
              </span>
              <p>服务状态：{data.status === "ready" ? "就绪" : data.status}</p>
            </div>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (
                  await p.act(() =>
                    p.api.request("/provider", {
                      method: "PUT",
                      body: JSON.stringify({
                        ...(tmdb.trim() ? { tmdbKey: tmdb.trim() } : {}),
                        ...(os.trim() ? { openSubtitlesKey: os.trim() } : {}),
                      }),
                    }),
                  )
                ) {
                  setTmdb("");
                  setOs("");
                }
              }}
            >
              <Field label="TMDB API key">
                <input
                  type="password"
                  autoComplete="new-password"
                  value={tmdb}
                  placeholder="留空保留现有配置"
                  onChange={(e) => setTmdb(e.target.value)}
                />
              </Field>
              <Field label="OpenSubtitles API key">
                <input
                  type="password"
                  autoComplete="new-password"
                  value={os}
                  placeholder="留空保留现有配置"
                  onChange={(e) => setOs(e.target.value)}
                />
              </Field>
              <p className="fine">
                已保存的密钥不回显。遇到服务限流，电视会按服务端退避规则重试。
              </p>
              <button
                className="primary"
                disabled={p.busy || (!tmdb.trim() && !os.trim())}
              >
                保存服务凭据
              </button>
            </form>
          </section>
        )
      )}
    </>
  );
}
function Backup(p: Shared) {
  const [backup, setBackup] = useState<unknown>(null),
    [filename, setFilename] = useState(""),
    [confirm, setConfirm] = useState(false);
  return (
    <div className="two-column">
      <section className="panel form-panel">
        <span className="eyebrow">保存家庭的小小片库</span>
        <h2>导出本地备份</h2>
        <p>包含片库、人工修改、标签、封面、观看记录、进度与观看规则。</p>
        <aside className="notice">
          不包含视频、活动会话、一次性授权或明文资源凭据。换机恢复后请重新输入资源凭据。
        </aside>
        <button
          className="primary"
          disabled={p.busy}
          onClick={() =>
            void p.act(async () => {
              const data = await p.api.request("/backup");
              const url = URL.createObjectURL(
                new Blob([JSON.stringify(data)], { type: "application/json" }),
              );
              const a = document.createElement("a");
              a.href = url;
              a.download = `child-tv-backup-${new Date().toLocaleDateString("sv-SE")}.json`;
              a.click();
              setTimeout(() => URL.revokeObjectURL(url), 1000);
            }, "备份已生成并开始下载")
          }
        >
          下载完整备份
        </button>
      </section>
      <section className="panel form-panel">
        <h2>从备份恢复</h2>
        <p>
          电视会校验版本与完整性，在恢复前保存当前快照。无效备份会被拒绝并保留原数据。
        </p>
        <Field label="选择备份 JSON 文件">
          <input
            type="file"
            accept=".json,application/json"
            onChange={async (e) => {
              const file = e.target.files?.[0];
              setBackup(null);
              setConfirm(false);
              setFilename(file?.name ?? "");
              if (file)
                try {
                  if (file.size > 48 * 1024 * 1024)
                    throw new Error("备份文件超过 48 MB，请检查文件。");
                  setBackup(JSON.parse(await file.text()));
                } catch (err) {
                  p.onError((err as Error).message);
                }
            }}
          />
        </Field>
        {filename && <p className="fine">已选：{filename}</p>}
        <label className="inline-check">
          <input
            type="checkbox"
            checked={confirm}
            onChange={(e) => setConfirm(e.target.checked)}
          />
          我确认恢复此备份，并理解会替换当前片库与记录
        </label>
        <button
          disabled={p.busy || !backup || !confirm}
          onClick={async () => {
            if (
              await p.act(
                () =>
                  p.api.request("/restore", {
                    method: "POST",
                    body: JSON.stringify({ backup, confirmed: true }),
                  }),
                "恢复成功，会话已失效，请重新登录并配置资源凭据",
              )
            ) {
              setBackup(null);
              setConfirm(false);
              setFilename("");
            }
          }}
        >
          验证并恢复备份
        </button>
      </section>
    </div>
  );
}
