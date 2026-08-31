// 基于 fetch + ReadableStream 的 SSE 解析(平台 IO,仅本文件 + h5GameApi.ts 触碰)。
//
// 为何不用浏览器原生 EventSource:回合端点是 `POST /api/game/{saveId}/turn` 且带 JSON body,
// 而 EventSource 只能 GET、不能带 body、不能自定义方法 —— 接不了这个 wire(brief 的「用
// EventSource」是基于错误前提,见交付说明的偏差报告)。fetch + 流式读 body 是 POST-SSE 的
// 标准做法,且把传输细节关在 api/ 内,逻辑层依旧只见 TurnStream(ADR-003 边界不受影响)。
//
// 本模块只做「字节流 → (event,data) 帧」的纯传输解析;语义映射在 h5GameApi.ts。
//
// ADR-022 立字 10:**本模块不得把 HTTP 状态翻译成业务 code**。非 2xx 只上报「状态码 + body 原文」,
// 「这在业务上是什么」由 h5GameApi.ts 决定。这条不是靠自觉——`SseFailure` 的 http 分支里
// 根本没有 code 字段(构造保证),另有一条源码级断言钉住(见 h5GameApi.test.ts)。

/** 一条解析出来的 SSE 帧。data 是拼接后的原始字符串(通常是一行 JSON)。 */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * 一次 SSE 请求的失败。只报「发生了什么」,不解释「这在业务上是什么」(ADR-022 立字 10)。
 *
 * ⚠️ `transport` 的 `reason` 是**传输事实**不是业务 code:`network` 这个词
 * h5GameApi.ts 自己的三处 fetch catch 也在用,是共享的传输词汇;立字 10 的射程是
 * 「HTTP **状态** → 业务 code」,不含这两条,故它们留在本模块、行为逐字不变。
 */
export type SseFailure =
  /** 非 2xx。body = 响应体原文(读不到则空串);**本模块不解析它、也不据 status 下任何业务判断**。 */
  | { kind: 'http'; status: number; body: string }
  /** 传输层失败:网络不通 / 2xx 但无可读流。 */
  | { kind: 'transport'; reason: 'network' | 'no_body'; message: string };

export interface SseHandlers {
  onFrame(frame: SseFrame): void;
  /** HTTP/网络/解析层失败(非 2xx、断流、fetch reject)。语义由上层赋予。 */
  onError(failure: SseFailure): void;
  /** 流自然结束或出错后,统一收口(只触发一次)。 */
  onClose(): void;
}

export interface SseHandle {
  close(): void;
}

/**
 * 发起一次流式 POST 并解析 SSE 帧。立即返回可取消的句柄;请求与读取在内部异步进行。
 *
 * @param url     端点(相对路径,经 Vite proxy 到后端)
 * @param body    JSON 请求体
 * @param handlers 帧/错误/收口回调
 * @param headers  附加请求头(如 ADR-016 的 X-Device-Id;与固定头合并,后者不被覆盖)
 */
export function streamSsePost(
  url: string,
  body: unknown,
  handlers: SseHandlers,
  headers?: Record<string, string>,
): SseHandle {
  const controller = new AbortController();
  let closed = false;

  const finish = () => {
    if (closed) return;
    closed = true;
    handlers.onClose();
  };

  const fail = (failure: SseFailure) => {
    if (closed) return;
    handlers.onError(failure);
    finish();
  };

  // microtask 后再发起,给调用方一个同步窗口注册全部回调(契约承诺,避免竞态)。
  queueMicrotask(() => {
    if (closed) return;
    fetch(url, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
      .then(async (resp) => {
        if (!resp.ok) {
          // 只交状态码与 body 原文:不解析、不翻译(ADR-022 立字 10)。
          fail({ kind: 'http', status: resp.status, body: await readErrorBody(resp) });
          return;
        }
        if (!resp.body) {
          fail({ kind: 'transport', reason: 'no_body', message: '响应无可读流' });
          return;
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        // SSE 帧以空行(\n\n)分隔;跨 chunk 边界靠 buffer 累积。
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let sep: number;
          while ((sep = indexOfFrameBoundary(buffer)) !== -1) {
            const rawFrame = buffer.slice(0, sep);
            buffer = buffer.slice(sep).replace(/^(\r?\n){2}/, '');
            const frame = parseFrame(rawFrame);
            if (frame && !closed) handlers.onFrame(frame);
          }
        }
        // flush 末帧(若服务端最后一帧后没补空行)。
        const tail = parseFrame(buffer);
        if (tail && !closed) handlers.onFrame(tail);
        finish();
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) {
          finish(); // 主动取消,不算错误。
          return;
        }
        fail({
          kind: 'transport',
          reason: 'network',
          message: e instanceof Error ? e.message : '网络错误',
        });
      });
  });

  return {
    close() {
      if (closed) return;
      controller.abort();
      finish();
    },
  };
}

/** 找到帧边界(\n\n 或 \r\n\r\n)的起点偏移;无则 -1。 */
function indexOfFrameBoundary(buf: string): number {
  const lf = buf.indexOf('\n\n');
  const crlf = buf.indexOf('\r\n\r\n');
  if (lf === -1) return crlf;
  if (crlf === -1) return lf;
  return Math.min(lf, crlf);
}

/** 解析一个 SSE 帧文本 → {event,data}。无 data 行返回 null(注释/心跳帧忽略)。 */
function parseFrame(raw: string): SseFrame | null {
  const lines = raw.split(/\r?\n/);
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith(':')) continue; // 注释/心跳
    if (line.startsWith('event:')) {
      event = line.slice(6).replace(/^ /, '');
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}

/**
 * 读非 2xx 的 body 原文;**任何读不到的情形一律空串**(body 已被消费、流中途出错、
 * 或拿到的根本不是一个规规矩矩的 Response)。
 *
 * ⚠️ 这个 try/catch 不是防御性废话:若让它抛,异常会落进外层 `.catch`,
 * 于是一个 HTTP 错误会被报成一条 `network` —— **不是静默,是说假话**,比不报更难查。
 */
async function readErrorBody(resp: Response): Promise<string> {
  try {
    return await resp.text();
  } catch {
    return '';
  }
}
