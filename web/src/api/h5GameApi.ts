// GameApi 的 H5 实现(平台 IO 集中地)。把已实测定型的 wire(ADR-006/007)映射到
// provider-agnostic 契约(contract.ts):init 走 fetch plain POST,回合走 fetch+SSE。
// 逻辑/状态层只 import contract.ts 的接口与 createH5GameApi 返回的实例,不碰本文件内部。

import type {
  EndingPayload,
  GameApi,
  InitResult,
  StreamError,
  TurnDelta,
  TurnStream,
} from './contract';
import { GameApiError } from './contract';
import { streamSsePost } from './sse';
import type { Archetype } from '../types/schema';

/** 默认基址空串 → 相对路径 `/api/...`,经 Vite dev proxy / 同源部署到后端。 */
export function createH5GameApi(baseUrl = ''): GameApi {
  return {
    async initGame(archetype: Archetype): Promise<InitResult> {
      let resp: Response;
      try {
        resp = await fetch(`${baseUrl}/api/game/init`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ archetype }),
        });
      } catch (e) {
        throw new GameApiError('network', e instanceof Error ? e.message : '网络错误');
      }
      if (!resp.ok) {
        // ADR-007:world-gen 救不回 → 502 {error:{code,message}};归一为 GameApiError。
        const body = await safeJson(resp);
        const err = (body as { error?: { code?: string; message?: string } } | null)?.error;
        throw new GameApiError(
          err?.code ?? 'world_gen_failed',
          err?.message ?? `世界生成失败(HTTP ${resp.status})`,
        );
      }
      const data = (await safeJson(resp)) as InitResult | null;
      if (!data || !data.saveId || !data.world) {
        throw new GameApiError('bad_response', '世界生成响应格式异常');
      }
      return data;
    },

    openTurnStream(saveId: string, turn: number, actionId: string): TurnStream {
      const handlers = {
        narrative: [] as Array<(t: string) => void>,
        delta: [] as Array<(d: TurnDelta) => void>,
        ending: [] as Array<(e: EndingPayload) => void>,
        error: [] as Array<(e: StreamError) => void>,
        close: [] as Array<() => void>,
      };

      const handle = streamSsePost(`${baseUrl}/api/game/${saveId}/turn`, { turn, actionId }, {
        onFrame(frame) {
          switch (frame.event) {
            case 'narrative': {
              const text = parseField<{ text?: string }>(frame.data)?.text;
              if (typeof text === 'string') emit(handlers.narrative, text);
              break;
            }
            case 'delta': {
              const d = parseField<TurnDelta>(frame.data);
              if (d) emit(handlers.delta, d);
              break;
            }
            case 'ending': {
              const e = parseField<EndingPayload>(frame.data);
              if (e) emit(handlers.ending, e);
              break;
            }
            case 'error': {
              const e = parseField<StreamError>(frame.data);
              emit(handlers.error, {
                code: e?.code ?? 'unknown',
                message: e?.message ?? '回合处理失败',
              });
              break;
            }
            // 未知事件名忽略(前向兼容)。
          }
        },
        onError(err) {
          emit(handlers.error, err);
        },
        onClose() {
          emit(handlers.close, undefined as never);
        },
      });

      return {
        onNarrative: (cb) => void handlers.narrative.push(cb),
        onDelta: (cb) => void handlers.delta.push(cb),
        onEnding: (cb) => void handlers.ending.push(cb),
        onError: (cb) => void handlers.error.push(cb),
        onClose: (cb) => void handlers.close.push(cb),
        close: () => handle.close(),
      };
    },
  };
}

function emit<T>(cbs: Array<(arg: T) => void>, arg: T): void {
  for (const cb of cbs) cb(arg);
}

function parseField<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}

async function safeJson(resp: Response): Promise<unknown> {
  try {
    return await resp.json();
  } catch {
    return null;
  }
}

/** 默认实例(逻辑/状态层用它,Phase 4 换 WS 时只改这一行的工厂)。 */
export const gameApi: GameApi = createH5GameApi();
