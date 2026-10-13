package com.aiuniverse.server.persistence;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code pg} profile 开机说清连接池现值(ADR-032 D-5):打的是<b>绑定后的 {@link HikariDataSource} 上的最终值</b>,
 * 不是配置文件里的字面量 —— env {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE} 覆盖时,这一行就是唯一的现值读数
 * (形状照 {@code [turn-admission] 准入容量 N=8})。只读配置、不借连接,DB 不可达也不会因这一行而多等。
 */
@Component
@Profile("pg")
public class PgStartupReport {

	private static final Logger log = LoggerFactory.getLogger(PgStartupReport.class);

	private final int maximumPoolSize;

	public PgStartupReport(DataSource dataSource,
			@Value("${" + PgConnectionEnvironmentPostProcessor.SOURCE_MARKER + ":"
					+ PgConnectionEnvironmentPostProcessor.SOURCE_NONE + "}") String source) {
		this.maximumPoolSize = maximumPoolSize(dataSource);
		log.info("[pg-connection] 连接来源 = {},连接池 maximum-pool-size = {}", source,
				maximumPoolSize < 0 ? "未知(非 Hikari 数据源)" : maximumPoolSize);
	}

	/** 最终池大小;非 Hikari(理论上不会发生)返回 -1。 */
	public int maximumPoolSize() {
		return maximumPoolSize;
	}

	static int maximumPoolSize(DataSource dataSource) {
		try {
			if (dataSource.isWrapperFor(HikariDataSource.class)) {
				return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
			}
		} catch (SQLException e) {
			// 走到这里只说明拆不开包装,不是连接失败(isWrapperFor / unwrap 不建连接)
		}
		return -1;
	}
}
