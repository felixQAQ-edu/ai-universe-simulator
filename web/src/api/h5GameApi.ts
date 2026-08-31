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
  WorldCatalog,
} from './contract';
import { GameApiError } from './contract';
import { getDeviceId } from './deviceId';
import { streamSsePost } from './sse';
import type { SseFailure } from './sse';
import type { Archetype } from '../types/schema';

/** 默认基址空串 → 相对路径 `/api/...`,经 Vite dev proxy / 同源部署到后端。 */
export function createH5GameApi(baseUrl = ''): GameApi {
  return {
    async listArchetypes(): Promise<WorldCatalog> {
      let resp: Response;
      try {
        resp = await fetch(`${baseUrl}/api/archetypes`, { method: 'GET' });
      } catch (e) {
        throw new GameApiError('network', e instanceof Error ? e.message : '网络错误');
      }
      if (!resp.ok) {
        throw new GameApiError('archetypes_failed', `世界列表加载失败(HTTP ${resp.status})`);
      }
      const data = (await safeJson(resp)) as Partial<WorldCatalog> | null;
      // 两张表各自容错:老后端(无 fusions 字段)→ 空组合表 = 拖拽一律判无效组合,
      // 不报错、不阻断选择屏(同 severity 四种缺省一律安全降级的口径,ADR-018)。
      return {
        archetypes: Array.isArray(data?.archetypes) ? data.archetypes : [],
        fusions: Array.isArray(data?.fusions) ? data.fusions : [],
      };
    },

    async initGame(archetypes: Archetype | readonly Archetype[]): Promise<InitResult> {
      // ADR-013:单值走旧 wire {archetype}(向后兼容),有序多值(host 在前)走 {archetypes:[...]}。
      const list = Array.isArray(archetypes) ? archetypes : [archetypes];
      const body = list.length > 1 ? { archetypes: list } : { archetype: list[0] };
      let resp: Response;
      try {
        resp = await fetch(`${baseUrl}/api/game/init`, {
          method: 'POST',
          // X-Device-Id = 软闸设备键(ADR-016),服务端单设备日 init 计数用。
          headers: { 'Content-Type': 'application/json', 'X-Device-Id': getDeviceId() },
          body: JSON.stringify(body),
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
      // 数值轴元数据缺省兜底(老后端 / 异常响应不至于让面板崩)。
      return { ...data, attributes: Array.isArray(data.attributes) ? data.attributes : [] };
    },

    async resumeGame(saveId: string): Promise<InitResult> {
      let resp: Response;
      try {
        resp = await fetch(`${baseUrl}/api/game/${saveId}/state`, { method: 'GET' });
      } catch (e) {
        throw new GameApiError('network', e instanceof Error ? e.message : '网络错误');
      }
      if (!resp.ok) {
        const body = await safeJson(resp);
        const err = (body as { error?: { code?: string; message?: string } } | null)?.error;
        throw new GameApiError(
          err?.code ?? (resp.status === 404 ? 'session_not_found' : 'resume_failed'),
          err?.message ?? `续局失败(HTTP ${resp.status})`,
        );
      }
      const data = (await safeJson(resp)) as InitResult | null;
      if (!data || !data.saveId || !data.world) {
        throw new GameApiError('bad_response', '续局响应格式异常');
      }
      return { ...data, attributes: Array.isArray(data.attributes) ? data.attributes : [] };
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
              const d = parseDelta(frame.data);
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
        onError(failure) {
          emit(handlers.error, toStreamError(failure));
        },
        onClose() {
          emit(handlers.close, undefined as never);
        },
        // X-Device-Id = 软闸设备键(ADR-016),服务端单设备日回合计数用。
      }, { 'X-Device-Id': getDeviceId() });

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

/** delta 专用解析:wire 里数值轴是 top-level 字段,收进 attributes map(对 key 无知,通吃 hp/san 与 hp/hunger)。 */
const DELTA_STRUCTURAL = new Set(['turn', 'status', 'discoveredRules', 'availableActions']);

function parseDelta(data: string): TurnDelta | null {
  const raw = parseField<Record<string, unknown>>(data);
  if (!raw) return null;
  const attributes: Record<string, number> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (!DELTA_STRUCTURAL.has(k) && typeof v === 'number') attributes[k] = v;
  }
  return {
    turn: Number(raw.turn ?? 0),
    status: raw.status === 'ended' ? 'ended' : 'ongoing',
    attributes,
    discoveredRules: Array.isArray(raw.discoveredRules)
      ? (raw.discoveredRules as TurnDelta['discoveredRules'])
      : [],
    availableActions: Array.isArray(raw.availableActions)
      ? (raw.availableActions as TurnDelta['availableActions'])
      : [],
  };
}

/**
 * 传输层失败 → 逻辑层的 `StreamError`(ADR-022 立字 8/9:语义映射住在这一层,不在 `sse.ts`)。
 *
 * 优先级链(**顺序不可反**):
 * ```
 * transport                  → code = reason           (与本刀之前逐字相同)
 * http 且 body 有 error.code → 取 body 的               (后端主动发的码,如刀 2 的 server_at_capacity)
 * http 且 status === 404     → session_not_found
 * http 且 status >= 500      → service_unavailable
 * http 其他                  → http_${status}          (最后兜底)
 * ```
 *
 * ⚠️ **body 优先必须压过状态兜底**:刀 2 的准入拒绝是「503 **带**结构化 body」,而 Fly 网关的 503
 * 是**裸**的 —— 同一个状态码两种情形,**靠 body 区分,不靠状态码**。故绝不许在状态兜底里写
 * `503 → server_at_capacity`:那会给网关故障贴上「过几秒再点一次」,
 * 一句在那个情形下**确凿是错的**建议(ADR-022 裁定 3 的禁令)。
 * ⚠️ `code` 与 `message` **各自独立兜底**(同 `resumeGame` 的既有形状),
 * 故「code 取自 body、message 取自状态兜底」是允许的混合。
 */
function toStreamError(failure: SseFailure): StreamError {
  if (failure.kind === 'transport') {
    return { code: failure.reason, message: failure.message };
  }
  const err = (safeParse(failure.body) as { error?: { code?: string; message?: string } } | null)
    ?.error;
  const fallback = statusFallback(failure.status);
  return { code: err?.code ?? fallback.code, message: err?.message ?? fallback.message };
}

/**
 * 状态 → code/文案的兜底表(body 缺失或非 JSON 时用)。
 * **它是常驻设施不是过渡物**:刀 2 给 404 补了结构化 body 之后,这张表仍是 body 缺失时的兜底。
 */
function statusFallback(status: number): StreamError {
  if (status === 404) {
    // 文案里的两个动作都必须是玩家真做得到的:「返回」= SceneBanner 顶部的 BackButton,
    // 「继续上局」= 返回后选择屏那个按钮(`reset()` 刻意不清 `resumableSaveId`,线 C「退出不弃局」)。
    return { code: 'session_not_found', message: '会话已失效,返回后点『继续上局』可接续' };
  }
  if (status >= 500) {
    // ⚠️ 不枚举 500/502/503/504 —— 枚举会留缝。
    // ⚠️ 不写「过几秒」:部署窗口与网关故障不是一个量级,**给错的时间预期比不给更糟**;
    //    「过几秒」是 server_at_capacity 的专属,因为只有那一条我们确实知道它是秒级的。
    return { code: 'service_unavailable', message: '服务暂时不可用,请稍后再试' };
  }
  return { code: `http_${status}`, message: `请求失败(HTTP ${status})` };
}

/**
 * 解析 body **原文字符串**,失败一律 null。
 *
 * ⚠️ 与下面的 `safeJson` **是两个函数,不是重复代码**:本函数吃 `string`(SSE 路径拿到的是
 * `sse.ts` 交上来的 body 原文),`safeJson` 吃 `Response`(init/resume 手里是 Response)。
 * **输入类型不同,不是同一个判断的两份拷贝**(非 ADR-018 §4.1 的两份真相);
 * 合并要么改 `safeJson` 的签名 —— 那会让 init/resume 的既有测试桩(只有 `json:`)全部失效。
 */
function safeParse(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

/**
 * 从 `Response` 读 JSON,失败一律 null。
 *
 * ⚠️ 与上面的 `safeParse` 是**两个函数**(理由见那边的注释):**别合并**。
 */
async function safeJson(resp: Response): Promise<unknown> {
  try {
    return await resp.json();
  } catch {
    return null;
  }
}

/** 默认实例(逻辑/状态层用它,Phase 4 换 WS 时只改这一行的工厂)。 */
export const gameApi: GameApi = createH5GameApi();
