-- ADR-025 刀 1 · 叙事历史的两张表(仅 pg profile;Flyway 管理)。
-- 内存仍是权威;这里只替换文件层(每 saveId 一份快照)并多出一张 append-only 的事件表。

-- 一局一行:快照 = SessionDocument.encode 产的那份文档(视图 1 全量,含 isTrue / hiddenLogic)。
-- ⚠️ 读 API(刀 2)永不读 snapshot 列 —— 它不是给玩家看的。
-- 刻意不建 version 列:乐观锁要在 turn_request 那一刀、两段式成立之后才有意义;
-- 今天单写者由忙态 CAS 保证(ADR-015 勘察 2)。建了不读的列会让后来者以为乐观锁已经在了。
-- snapshot 用 json 而不是 JSONB(刀 1 裁定):JSONB 重排对象键序 → restore 往返不再逐字节
-- → 重启后视图 2(喂模型的那份)字节变化,撞 ADR-025 已知代价 1 的往返守护;
-- 快照只按 save_id 整份读写、从不在库内查询其内部字段,JSONB 的收益用不上;
-- json 仍校验合法性,故不选 text。
CREATE TABLE game_session (
    save_id    TEXT        PRIMARY KEY,
    snapshot   json        NOT NULL,
    turn       INTEGER     NOT NULL,
    status     TEXT        NOT NULL,
    -- 来源标记:由**首次插入该行的代码路径**写下,之后任何更新都不改它(ADR-025 决策 2)。
    -- native = 本 profile 下开局;import = 从文件存档导入(刀 4)。绝不按「有没有 turn 0」推断。
    source     TEXT        NOT NULL CHECK (source IN ('native', 'import')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 一回合一行,只存玩家本来就看过的东西(与内存 log 条目同形)。
-- turn 0 = 开场叙事(player_action 为空,开场之前没有玩家动作)。
CREATE TABLE game_event (
    save_id       TEXT        NOT NULL REFERENCES game_session (save_id),
    turn          INTEGER     NOT NULL CHECK (turn >= 0),
    narrative     TEXT        NOT NULL,
    player_action TEXT,
    written_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (save_id, turn)
);
