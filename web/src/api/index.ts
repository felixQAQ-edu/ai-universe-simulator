// api/ 适配层公共出口。逻辑/状态层从这里 import 契约与默认实例,不深入内部模块。
export type {
  ArchetypeSummary,
  AttributeAxisMeta,
  AxisBand,
  AxisSeverity,
  ClientAction,
  ClientRule,
  ClientWorld,
  DiscoveredRule,
  EndingPayload,
  ExperienceMode,
  CatalogCapabilities,
  FusionCombo,
  GameApi,
  HistoryApi,
  HistoryEntry,
  HistoryEventEntry,
  HistoryGapEntry,
  HistoryPage,
  InitResult,
  StreamError,
  TurnDelta,
  TurnStream,
  WorldCatalog,
} from './contract';
export { GameApiError } from './contract';
export { createH5GameApi, gameApi } from './h5GameApi';
export { createH5HistoryApi, historyApi } from './h5HistoryApi';
export type { NavHistory, NavTag } from './navHistory';
export { createBrowserNavHistory, navHistory } from './navHistory';
export { onPageHidden } from './pageLifecycle';
