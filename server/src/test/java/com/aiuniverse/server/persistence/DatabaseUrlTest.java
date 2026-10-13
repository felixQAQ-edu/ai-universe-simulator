package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-032 测试面 11 · {@code DATABASE_URL} 解析(§4.3 全部情形)。不需要 Docker。
 *
 * <p>密码一律用独特字面量 {@link #SECRET}(及其百分号编码形 {@link #SECRET_ENC}),断言它<b>不出现在</b>
 * jdbc url、toString 与任何异常文本里 —— 「不回显原值」要可被断言,不是只写在注释里。
 */
class DatabaseUrlTest {

	/** 含 @ : / 三个必须编码的字符。 */
	static final String SECRET = "Zq9@pw:s/ecret-7f3a";
	static final String SECRET_ENC = "Zq9%40pw%3As%2Fecret-7f3a";

	@ParameterizedTest
	@ValueSource(strings = { "postgres", "postgresql" })
	void bothSchemesParse(String scheme) {
		DatabaseUrl u = DatabaseUrl.parse(scheme + "://alice:" + SECRET_ENC + "@db.internal:6543/wanjie");
		assertThat(u.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/wanjie");
		assertThat(u.username()).isEqualTo("alice");
		assertThat(u.password()).isEqualTo(SECRET);
		assertThat(u.host()).isEqualTo("db.internal");
		assertThat(u.port()).isEqualTo(6543);
		assertThat(u.database()).isEqualTo("wanjie");
	}

	@Test
	void passwordNeverEntersTheJdbcUrlOrToString() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://alice:" + SECRET_ENC + "@h/db?sslmode=require");
		assertThat(u.jdbcUrl()).doesNotContain(SECRET).doesNotContain(SECRET_ENC).doesNotContain("alice");
		assertThat(u.toString()).doesNotContain(SECRET).doesNotContain(SECRET_ENC);
	}

	@Test
	void missingPortDefaultsTo5432() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://u:p@h/db");
		assertThat(u.port()).isEqualTo(5432);
		assertThat(u.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/db");
	}

	@Test
	void percentEncodedUserAndPasswordAreDecoded() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://us%40er%3Ax%2Fy:" + SECRET_ENC + "@h:5432/db");
		assertThat(u.username()).isEqualTo("us@er:x/y");
		assertThat(u.password()).isEqualTo(SECRET);
	}

	@Test
	void plusInUserinfoIsALiteralPlusNotASpace() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://u:a+b@h/db");
		assertThat(u.password()).isEqualTo("a+b");
	}

	@Test
	void noPasswordGivesEmptyPassword() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://alice@h:5432/db");
		assertThat(u.username()).isEqualTo("alice");
		assertThat(u.password()).isEmpty();
	}

	@Test
	void queryIsCarriedThroughVerbatim() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://u:p@h:5432/db?sslmode=require&connect_timeout=3");
		assertThat(u.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/db?sslmode=require&connect_timeout=3");
	}

	@Test
	void ipv6HostKeepsBrackets() {
		DatabaseUrl u = DatabaseUrl.parse("postgres://u:p@[fdaa:0:1::3]:5433/db");
		assertThat(u.host()).isEqualTo("[fdaa:0:1::3]");
		assertThat(u.jdbcUrl()).isEqualTo("jdbc:postgresql://[fdaa:0:1::3]:5433/db");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"postgres://u:" + SECRET_ENC + "@h:5432", // 缺库名
			"postgres://u:" + SECRET_ENC + "@h:5432/", // 库名为空
			"mysql://u:" + SECRET_ENC + "@h:5432/db", // scheme 错
			"postgres://u:" + SECRET_ENC + "@:5432/db", // 缺主机
			"postgres://u:" + SECRET + "@h:5432/db", // 未编码的 @ : / → 非法 URI 或解析不出主机
			"postgres://u:%zz" + SECRET_ENC + "@h/db", // 非法百分号编码
			"postgres ://u:" + SECRET_ENC + "@h/db", // 非法 URI 字符
	})
	void invalidUrlsFailWithoutLeakingThePassword(String raw) {
		assertThatThrownBy(() -> DatabaseUrl.parse(raw)).isInstanceOf(DatabaseUrl.InvalidDatabaseUrlException.class)
				.satisfies(t -> {
					assertThat(t.getCause()).as("不带 cause(URISyntaxException 的 message 含原值)").isNull();
					assertThat(t.getMessage()).doesNotContain(SECRET).doesNotContain(SECRET_ENC)
							.doesNotContain("Zq9").contains("DATABASE_URL 无法解析");
				});
	}

	@Test
	void describeJdbcUrlNeverEchoesTheQuery() {
		String[] d = DatabaseUrl.describeJdbcUrl("jdbc:postgresql://h:7777/wanjie?password=" + SECRET_ENC);
		assertThat(d).containsExactly("h", "7777", "wanjie");
		assertThat(String.join(",", d)).doesNotContain(SECRET_ENC);
		assertThat(DatabaseUrl.describeJdbcUrl("not-a-jdbc-url")).isNull();
	}
}
