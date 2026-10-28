import type { Episode } from "./types";
export function filterEpisodes(
  rows: Episode[],
  query: string,
  status: string,
  tags: Record<string, string[]>,
): Episode[] {
  return rows.filter(
    (e) =>
      (status === "all" || e.status === status) &&
      `${e.title} ${e.seriesTitle} ${e.libraryName}`
        .toLowerCase()
        .includes(query.toLowerCase()) &&
      Object.entries(tags).every(
        ([key, values]) =>
          !values.length || values.some((v) => (e.tags[key] ?? []).includes(v)),
      ),
  );
}
export function monthCells(month: string): (string | null)[] {
  const [year, m] = month.split("-").map(Number);
  const first = new Date(year, m - 1, 1);
  return [
    ...Array((first.getDay() + 6) % 7).fill(null),
    ...Array.from(
      { length: new Date(year, m, 0).getDate() },
      (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`,
    ),
  ];
}
export function tagPayload(
  ids: string[],
  key: string,
  values: string[],
  operation: string,
) {
  return { episodeIds: [...new Set(ids)], key, values, operation };
}
export function minutes(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (!s) return "0 分钟";
  const h = Math.floor(s / 3600),
    m = Math.floor((s % 3600) / 60),
    r = s % 60;
  return [h ? `${h} 小时` : "", m ? `${m} 分` : "", r ? `${r} 秒` : ""]
    .filter(Boolean)
    .join(" ");
}
export function watchDuration(entry: {
  seconds: number;
  durationMillis?: number;
}): string {
  const milliseconds = entry.durationMillis ?? entry.seconds * 1000;
  return milliseconds > 0 && milliseconds < 1000
    ? "不到1秒"
    : minutes(milliseconds / 1000);
}
export function watchKindLabel(entry: {
  kind: string;
  kinds?: string[];
}): string {
  const kinds = new Set(
    (entry.kinds?.length ? entry.kinds : [entry.kind]).map((kind) =>
      kind === "parent-extra" ? "parent_extra" : kind,
    ),
  );
  const labels = [
    ["normal", "正常观看"],
    ["grace", "本集收尾"],
    ["parent_extra", "家长额外一集"],
  ]
    .filter(([kind]) => kinds.has(kind))
    .map(([, label]) => label);
  return labels.length
    ? labels.join(" · ")
    : entry.kind === "mixed"
      ? "混合观看"
      : "观看记录";
}
