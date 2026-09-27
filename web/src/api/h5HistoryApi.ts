// HistoryApi 的 H5 实现(ADR-025 刀 3)。平台 IO 只在这里(ADR-003)。
//
// 错误码只供逻辑层分支与测试,**不进任何玩家可见文案**(刀 3 口径 I):
//   501 history_unavailable / 404 session_not_found / 503 history_read_failed / 400 invalid_after_turn
//   —— 以 body 为准,body 缺失时按状态兜底;网络失败 = network;形状不对 = bad_response。

import type { HistoryApi, HistoryEntry, HistoryPage } from './contract';
import { GameApiError } from './contract';

export function createH5HistoryApi(baseUrl = ''): HistoryApi {
  return {
    async readHistory(saveId: string, afterTurn?: number | null): Promise<HistoryPage> {
      const q = afterTurn === undefined || afterTurn === null ? '' : `?afterTurn=${afterTurn}`;
      let resp: Response;
      try {
        resp = await fetch(`${baseUrl}/api/game/${encodeURIComponent(saveId)}/history${q}`, {
          method: 'GET',
        });
      } catch (e) {
        throw new GameApiError('network', e instanceof Error ? e.message : 'network');
      }
      if (!resp.ok) {
        const body = await safeJson(resp);
        const code = (body as { error?: { code?: string } } | null)?.error?.code;
        throw new GameApiError(code ?? statusCode(resp.status), `HTTP ${resp.status}`);
      }
      const data = (await safeJson(resp)) as Partial<HistoryPage> | null;
      if (!data || !Array.isArray(data.entries)) {
        throw new GameApiError('bad_response', 'bad_response');
      }
      return {
        saveId: typeof data.saveId === 'string' ? data.saveId : saveId,
        entries: data.entries.filter(isEntry),
        nextAfterTurn: typeof data.nextAfterTurn === 'number' ? data.nextAfterTurn : null,
      };
    },
  };
}

function statusCode(status: number): string {
  if (status === 501) return 'history_unavailable';
  if (status === 404) return 'session_not_found';
  if (status === 503) return 'history_read_failed';
  return `http_${status}`;
}

/** 未知 kind 的条目丢弃(前向兼容),不让一条不认识的东西把整页弄坏。 */
function isEntry(e: unknown): e is HistoryEntry {
  if (!e || typeof e !== 'object') return false;
  const x = e as Record<string, unknown>;
  if (x.kind === 'event') return typeof x.turn === 'number' && typeof x.narrative === 'string';
  if (x.kind === 'gap') {
    return (
      (x.reason === 'before_recording' || x.reason === 'write_failed') &&
      typeof x.fromTurn === 'number' &&
      typeof x.toTurn === 'number'
    );
  }
  return false;
}

async function safeJson(resp: Response): Promise<unknown> {
  try {
    return await resp.json();
  } catch {
    return null;
  }
}

/** 默认实例。 */
export const historyApi: HistoryApi = createH5HistoryApi();
