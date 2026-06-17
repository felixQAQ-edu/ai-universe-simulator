# ending-gen · 结局收敛

> 场景组 D(本次 bake-off 暂不实跑,ADR-001 §1 非范围 / §8 第 4 步)。
> 占位:结局判定目前内联在 [event-loop](event-loop.md) 的 `ending` 字段里(命中
> `endings[].condition` 时返回 `{reached:true,id}`,引擎据此置 `state.status = ended`)。
> 待补 C/D 组时,再决定是否需要独立的结局生成/润色调用。约定见 docs/CONTEXT.md §二 `endings`。
