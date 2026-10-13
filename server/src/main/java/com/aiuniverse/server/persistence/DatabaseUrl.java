package com.aiuniverse.server.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 平台注入的 {@code DATABASE_URL}({@code postgres://user:pass@host:port/db?…},Fly MPG attach 写入的形状)
 * → JDBC 三件:{@code jdbc:postgresql://host:port/db?…} + username + password(ADR-032 §4.2 / §4.3)。
 *
 * <p><b>密码不进 jdbc url 字符串</b>:拆成单独的 password 属性 —— 驱动 / 连接池的异常信息与日志里会出现 url,
 * 不会出现 password 属性。
 *
 * <p><b>解析失败的异常信息不回显原值</b>(原值里有密码):只说哪一类问题,且不带 {@link URISyntaxException}
 * 作 cause(它的 message 含整个输入串)。
 */
public record DatabaseUrl(String jdbcUrl, String username, String password, String host, int port, String database) {

	static final int DEFAULT_PORT = 5432;

	/** 解析失败。message 保证不含原始 URL(故不含密码)。 */
	public static final class InvalidDatabaseUrlException extends IllegalStateException {
		InvalidDatabaseUrlException(String what) {
			super("DATABASE_URL 无法解析:" + what + "(出于安全,不回显原值;ADR-032 §4.3)");
		}
	}

	public static DatabaseUrl parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidDatabaseUrlException("为空");
		}
		URI uri;
		try {
			uri = new URI(raw.strip());
		} catch (URISyntaxException e) {
			throw new InvalidDatabaseUrlException("不是合法的 URI"); // 刻意不带 cause:它的 message 含原值
		}
		String scheme = uri.getScheme();
		if (!"postgres".equalsIgnoreCase(scheme) && !"postgresql".equalsIgnoreCase(scheme)) {
			throw new InvalidDatabaseUrlException("scheme 须为 postgres:// 或 postgresql://");
		}
		String host = uri.getHost(); // IPv6 带方括号返回,如 [::1]
		if (host == null || host.isEmpty()) {
			throw new InvalidDatabaseUrlException("缺主机名");
		}
		int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
		String rawPath = uri.getRawPath();
		String database = rawPath == null || rawPath.length() <= 1 ? "" : decode(rawPath.substring(1));
		if (database.isEmpty() || database.contains("/")) {
			throw new InvalidDatabaseUrlException("缺库名或库名非法");
		}
		String username = "";
		String password = "";
		String rawUserInfo = uri.getRawUserInfo();
		if (rawUserInfo != null) {
			int colon = rawUserInfo.indexOf(':');
			username = decode(colon < 0 ? rawUserInfo : rawUserInfo.substring(0, colon));
			password = colon < 0 ? "" : decode(rawUserInfo.substring(colon + 1));
		}
		String query = uri.getRawQuery();
		String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + rawPath.substring(1)
				+ (query == null || query.isEmpty() ? "" : "?" + query);
		return new DatabaseUrl(jdbcUrl, username, password, host, port, database);
	}

	/** 百分号解码;{@code +} 在 URI 的 userinfo / path 里是字面加号,不是空格(URLDecoder 默认按表单解码,先转义)。 */
	private static String decode(String s) {
		try {
			return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new InvalidDatabaseUrlException("百分号编码非法");
		}
	}

	/**
	 * 从 {@code jdbc:postgresql://host:port/db?…} 取主机 / 端口 / 库名,只为启动日志「说清现值」。
	 * 取不出就返回 null(调用方打「无法解析」);<b>绝不回显原值</b>(spring.datasource.url 的 query 里可能带 password=)。
	 */
	public static String[] describeJdbcUrl(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
			return null;
		}
		try {
			URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
			if (uri.getHost() == null) {
				return null;
			}
			String path = uri.getPath();
			return new String[] { uri.getHost(), String.valueOf(uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort()),
					path == null || path.length() <= 1 ? "" : path.substring(1) };
		} catch (URISyntaxException e) {
			return null;
		}
	}

	/** 只打可公开的部分;record 默认的 toString 会带 password,这里覆写掉。 */
	@Override
	public String toString() {
		return "DatabaseUrl[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username + "]";
	}
}
