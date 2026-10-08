package com.aiuniverse.server.eventloop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-031 刀 5a · 夹具输出前的敏感形态扫描(启发式,不是证明)。扫的是<b>将要写出的夹具全文</b>,不是原始轨迹。
 *
 * <p>任一类命中 → 转换工具拒绝写出夹具(只写报告)。宁可误报让人看一眼,也不让一条真密钥进仓库。
 * 扫描器报「零命中」只说明这几种形态不在里面,不说明文件里没有别的不该公开的东西 —— 人工核对仍然是必需的一步。
 */
final class FixtureScanner {

	private FixtureScanner() {
	}

	/** 一次命中:类别 + 在扫描文本中的偏移 + 打码后的片段(报告里不原样回显疑似密钥)。 */
	record Hit(String kind, int offset, String masked) {
	}

	/** 类别 → 形态。键即报告里的类别名。 */
	static final Map<String, Pattern> PATTERNS = patterns();

	private static Map<String, Pattern> patterns() {
		Map<String, Pattern> p = new LinkedHashMap<>();
		p.put("api-key(sk-…)", Pattern.compile("sk-[A-Za-z0-9_-]{16,}"));
		p.put("api-key(字段名)", Pattern.compile("(?i)api[_-]?key"));
		p.put("authorization", Pattern.compile("(?i)authorization"));
		p.put("bearer", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"));
		p.put("ipv4", Pattern.compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])"));
		// 完整 8 组,或含 `::` 的压缩形式;刻意不收「12:34:56」这类时刻(两三组纯数字)。
		p.put("ipv6", Pattern.compile("(?i)(?<![0-9a-f:])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
				+ "|(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4})?|::(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})(?![0-9a-f:])"));
		p.put("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
		return java.util.Collections.unmodifiableMap(p); // 保序:报告按此顺序列类别
	}

	/** 扫常见敏感形态;另把 {@code literals}(如原 saveId)当字面量查残留,类别名为 {@code 残留:<label>}。 */
	static List<Hit> scan(String text, Map<String, String> literals) {
		List<Hit> hits = new ArrayList<>();
		for (Map.Entry<String, Pattern> e : PATTERNS.entrySet()) {
			Matcher m = e.getValue().matcher(text);
			while (m.find()) {
				hits.add(new Hit(e.getKey(), m.start(), mask(m.group())));
			}
		}
		for (Map.Entry<String, String> e : literals.entrySet()) {
			String lit = e.getValue();
			if (lit == null || lit.isEmpty()) {
				continue;
			}
			for (int i = text.indexOf(lit); i >= 0; i = text.indexOf(lit, i + 1)) {
				hits.add(new Hit("残留:" + e.getKey(), i, mask(lit)));
			}
		}
		return hits;
	}

	private static String mask(String s) {
		if (s.length() <= 6) {
			return s.charAt(0) + "…";
		}
		return s.substring(0, 4) + "…(" + s.length() + " 字符)";
	}
}
