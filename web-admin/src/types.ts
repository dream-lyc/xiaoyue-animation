export type Source = {
  id: string;
  name: string;
  type: "openlist" | "alist" | "smb" | "webdav";
  endpoint: string;
  roots: string[];
  credentialConfigured: boolean;
  authMode?: "password" | "token";
};
export type Scan = {
  id: string;
  sourceId: string;
  stage: string;
  discovered: number;
  matched: number;
  completed: number;
  error?: string;
  status: "running" | "waiting" | "failed" | "cancelled" | "completed";
  retryAt?: number;
  cursor?: { retryAtMillis?: number };
};
export type Episode = {
  id: string;
  title: string;
  seriesTitle: string;
  libraryName: string;
  seasonNumber: number;
  episodeNumber: number;
  synopsis: string;
  artworkUrl?: string;
  artworkLocalUrl?: string;
  status: "matched" | "pending" | "unmatched";
  available: boolean;
  lockedFields: string[];
  tags: Record<string, string[]>;
};
export type Candidate = {
  id: string;
  title: string;
  seasonNumber: number;
  episodeNumber: number;
  evidence: string[];
};
export type Tag = { id: string; key: string; values: string[] };
export type Policy = {
  defaultMinutes: number;
  todayMinutes: number;
  watchedSeconds: number;
  state: string;
  timezone: string;
  todayOverride: boolean;
};
export type Analytics = {
  totalSeconds: number;
  episodeCount: number;
  watchDays: number;
  days: { date: string; seconds: number; graceSeconds: number }[];
  rows: { id?: string; label: string; seconds: number; episodeCount: number }[];
};
export type Day = {
  date: string;
  totalSeconds: number;
  graceSeconds: number;
  entries: {
    id: string;
    title: string;
    seriesTitle: string;
    startedAt: string;
    seconds: number;
    kind: string;
    episodeId?: string;
    sessionId?: string;
    endedAt?: string;
    durationMillis?: number;
    kinds?: string[];
    normalMillis?: number;
    graceMillis?: number;
    parentExtraMillis?: number;
  }[];
};
