// api/ 适配层公共出口。逻辑/状态层从这里 import 契约与默认实例,不深入内部模块。
export type {
  ArchetypeSummary,
  AttributeAxisMeta,
  AxisBand,
  ClientRule,
  ClientWorld,
  DiscoveredRule,
  EndingPayload,
  GameApi,
  InitResult,
  StreamError,
  TurnDelta,
  TurnStream,
} from './contract';
export { GameApiError } from './contract';
export { createH5GameApi, gameApi } from './h5GameApi';
