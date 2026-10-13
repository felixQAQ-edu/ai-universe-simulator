package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code game_session} → 文件存档的回退导出(ADR-032 §二)。每行写 {@code <save_id>.json} = {@code snapshot::text} 原样,
 * 写法照 {@link FileSessionStore#persist}:先写 {@code .json.tmp} 再 {@code ATOMIC_MOVE}(不支持则普通 move)。
 *
 * <ul>
 *   <li>目标目录必须为空(或不存在,由本类创建)——不与月账、旧存档混写。非空 → 拒绝运行,什么都不写。</li>
 *   <li>{@code save_id} 不匹配 {@code SAFE_SAVE_ID}(复用 {@link FileSessionStore} 那一份)→ 拒写并列出(防目录逃逸)。</li>
 *   <li>每行导出前 decode 一次:载不动的<b>照样导出</b>(保留尸检),但逐行列出 —— 回退到文件 profile 后它们也会被拒载。</li>
 *   <li>带不走 {@code game_event} / {@code turn_request} / {@code source} 等(§2.2,写清不粉饰)。</li>
 * </ul>
 * ⚠️ 导出时应用必须停着(见 {@link StoreMigrationTool})。
 */
final class SessionExporter {

	record Result(int rows, int written, List<String> refused, List<String> undecodable, String failure) {

		int exitCode() {
			if (failure != null) {
				return StoreMigrationTool.EXIT_TOOL_ERROR;
			}
			return refused.isEmpty() && undecodable.isEmpty() ? StoreMigrationTool.EXIT_OK
					: StoreMigrationTool.EXIT_NEEDS_REVIEW;
		}
	}

	private final ObjectMapper mapper;
	private final ArchetypeRegistry archetypes;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	SessionExporter(DataSource dataSource, ObjectMapper mapper, ArchetypeRegistry archetypes, int txTimeoutSeconds) {
		this.mapper = mapper;
		this.archetypes = archetypes;
		this.jdbc = new JdbcTemplate(dataSource);
		this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.tx.setTimeout(txTimeoutSeconds);
		this.tx.setReadOnly(true);
	}

	/** 目标目录为空或不存在 → true。由入口在启动 Spring 之前先查一次(保证「非空则什么都不写」),本类写之前再查一次。 */
	static boolean isEmptyOrAbsent(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return true;
		}
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.findAny().isEmpty();
		}
	}

	Result run(Path dir) throws IOException {
		if (!isEmptyOrAbsent(dir)) {
			throw new IllegalArgumentException("导出目标目录不为空(不与月账、旧存档混写)");
		}
		List<String[]> rows = tx.execute(status -> jdbc.query(
				"SELECT save_id, snapshot::text FROM game_session ORDER BY save_id",
				(rs, i) -> new String[] { rs.getString(1), rs.getString(2) }));
		Files.createDirectories(dir);
		List<String> refused = new ArrayList<>();
		List<String> undecodable = new ArrayList<>();
		int written = 0;
		for (String[] row : rows) {
			String saveId = row[0];
			if (saveId == null || !FileSessionStore.SAFE_SAVE_ID.matcher(saveId).matches()) {
				refused.add(saveId);
				continue;
			}
			try {
				SessionDocument.decode(saveId, mapper.readTree(row[1]), mapper, archetypes);
			} catch (Exception e) {
				undecodable.add(saveId); // 照样导出,只列出
			}
			try {
				Path tmp = dir.resolve(saveId + ".json.tmp");
				Path target = dir.resolve(saveId + ".json");
				Files.writeString(tmp, row[1], StandardCharsets.UTF_8);
				try {
					Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException atomicUnsupported) {
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				}
				written++;
			} catch (IOException e) {
				return new Result(rows.size(), written, refused, undecodable,
						"写出中途失败(已写出 " + written + " 份,目标目录已不完整,须清空后重跑):" + e.getClass().getSimpleName());
			}
		}
		return new Result(rows.size(), written, refused, undecodable, null);
	}
}
