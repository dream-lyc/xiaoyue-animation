import { it, expect } from "vitest";
import {
  filterEpisodes,
  monthCells,
  minutes,
  tagPayload,
  watchDuration,
  watchKindLabel,
} from "./model";
const episodes = [
  {
    id: "1",
    title: "A",
    seriesTitle: "花园",
    status: "matched",
    tags: { 路线: ["一", "二"], 语言: ["英语"] },
  },
  {
    id: "2",
    title: "B",
    seriesTitle: "花园",
    status: "unmatched",
    tags: { 路线: ["二"], 语言: ["国语"] },
  },
] as any;
it("uses OR within a tag key and AND across tag keys", () => {
  expect(
    filterEpisodes(episodes, "", "all", {
      路线: ["一", "二"],
      语言: ["英语"],
    }).map((e) => e.id),
  ).toEqual(["1"]);
});
it("creates monday-first month cells without utc timezone date shifts", () => {
  const cells = monthCells("2026-02");
  expect(cells[0]).toBeNull();
  expect(cells[6]).toBe("2026-02-01");
  expect(cells.filter(Boolean)).toHaveLength(28);
});
it("deduplicates selected episode IDs and keeps replace explicitly distinct from append", () => {
  expect(tagPayload(["1", "1", "2"], "路线", ["一"], "replace")).toEqual({
    episodeIds: ["1", "2"],
    key: "路线",
    values: ["一"],
    operation: "replace",
  });
});
it("formats actual seconds without claiming unwatched full minutes", () => {
  expect(minutes(90)).toBe("1 分 30 秒");
  expect(minutes(0)).toBe("0 分钟");
});
it("calendar durations preserve positive sub-second viewing", () => {
  expect(watchDuration({ seconds: 0, durationMillis: 250 })).toBe("不到1秒");
  expect(watchDuration({ seconds: 0, durationMillis: 0 })).toBe("0 分钟");
  expect(watchDuration({ seconds: 2, durationMillis: 2500 })).toBe("2 秒");
  expect(watchDuration({ seconds: 90 })).toBe("1 分 30 秒");
});
it("mixed calendar sessions disclose all actual viewing categories", () => {
  expect(
    watchKindLabel({
      kind: "mixed",
      kinds: ["normal", "grace", "parent_extra"],
    }),
  ).toBe("正常观看 · 本集收尾 · 家长额外一集");
  expect(
    watchKindLabel({ kind: "mixed", kinds: ["grace", "parent_extra"] }),
  ).toBe("本集收尾 · 家长额外一集");
  expect(watchKindLabel({ kind: "mixed" })).toBe("混合观看");
  expect(watchKindLabel({ kind: "parent_extra" })).toBe("家长额外一集");
});
