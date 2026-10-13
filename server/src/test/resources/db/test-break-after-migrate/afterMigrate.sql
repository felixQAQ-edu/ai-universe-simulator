-- ADR-032 刀 1 测试专用(PgStartupSemanticsTest):Flyway 迁移成功之后把 game_session 改名,
-- 让紧接着的 JdbcSessionStore.loadAll 读表失败 —— 用来钉住「Flyway 已迁移、之后读取失败 → 拒启」。
-- 只经 spring.flyway.locations 显式加入时才生效;生产的 classpath:db/migration 里没有它。
ALTER TABLE game_session RENAME TO game_session_broken_by_test;
