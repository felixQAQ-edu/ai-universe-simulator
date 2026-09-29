-- ADR-026 刀 1 · 回合受理记录(仅 pg profile;Flyway 管理)。V1 冻结,本文件只新增。
-- 一回合一行:服务端决定跑一个回合的那一刻(CAS 成功之后、调模型之前)单独一段短事务插入
-- PROCESSING;回合落地时与快照同一事务(JdbcSessionStore.persist)关成 SUCCEEDED / DEGRADED;
-- 进程内看见异常且回合未落地 → FAILED;重启时仍是 PROCESSING 的 → INTERRUPTED(ADR-026 决策 1/2/4)。
--
-- 刻意不建 FK 到 game_session:受理行描述的是「本进程决定跑这一回合」,它可以先于任何一次成功的
-- 快照写入(create 那次 persist 失败时库里没有会话行)。建 FK 会让那种局面下受理写入失败 → 照跑
-- 而无记录(已决 C),把一个本可记下的回合变成空洞。
-- 刻意不建 version 列:见 ADR-026 决策 5 / 已决 F(单实例下乐观锁拦不到任何冲突)。
CREATE TABLE turn_request (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    save_id     TEXT        NOT NULL,
    base_turn   INTEGER     NOT NULL CHECK (base_turn >= 0),
    target_turn INTEGER     NOT NULL,
    action_id   TEXT        NOT NULL,
    status      TEXT        NOT NULL
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'DEGRADED', 'FAILED', 'INTERRUPTED')),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CHECK (target_turn = base_turn + 1)
);

-- 幂等键 = (save_id, base_turn)(ADR-026 决策 3)。单实例下它是防御性断言,不是闸:
-- 同一 base_turn 的第二个受理已被进程内忙态 CAS 挡住,base_turn 已落地则游标比对先拒。
-- FAILED / INTERRUPTED 不在谓词里 —— 同一 base_turn 在它们之后重试是合法的新受理。
CREATE UNIQUE INDEX turn_request_live_base
    ON turn_request (save_id, base_turn)
    WHERE status IN ('PROCESSING', 'SUCCEEDED', 'DEGRADED');
