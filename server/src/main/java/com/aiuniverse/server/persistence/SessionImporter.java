package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 文件存档 → {@code game_session} 的导入(ADR-032 §一、「已决 · D-2」)。只写这一张表,{@code source = 'import'};
 * 不写 {@code game_event} / {@code turn_request}(§1.1)。
 *
 * <h2>事务边界(D-2,刀 2 验收要求)</h2>
 * <ol>
 *   <li><b>扫描阶段不开事务、不写库</b>:只读源目录直接下的 {@code *.json}(不递归,与 {@code FileSessionStore.loadAll}
 *       同口径;{@code traces/} 因此天然不在候选里),逐份三分 —— 判据<b>只调</b> {@link SessionDocument#isSaveDocument}
 *       与 {@link SessionDocument#decode}(唯一一份,本类不另写 world 判据,测试面 3 源码级断言守住),
 *       产出不可读名单与候选清单。</li>
 *   <li><b>导入阶段只开一个事务</b>(同一条连接):对每个候选「查已有行 → 插入 → 在同一连接上读回比对」;
 *       末尾在同一事务里做对账(候选数与三项计数、{@code source='import'} 行数增量);<b>全部通过才提交</b>。</li>
 *   <li>任何一档读回与源文件不一致(字节、{@code turn}、{@code status} 任一),或对账对不上 → 抛 →
 *       <b>整批回滚</b>(本批所有插入一起撤掉),退出码 3。</li>
 * </ol>
 *
 * <h2>入库写源文件原文</h2>
 * {@code snapshot} 写<b>源文件的原始文本</b>;decode 只当合法性闸门,<b>绝不 decode → encode</b>
 * (ADR-032 置顶:{@code phaseHint} 会被重置、旧局会补上 {@code {"legacy":true}},重编码必然不等)。
 * {@code turn} / {@code status} 两列取 decode 出的 Engine 读数。
 *
 * <h2>幂等(§1.3 四格)</h2>
 * 从不覆盖已有行:无 → 插入;有且字节相同 → 跳过(重复导入 / 与原生行相同);有且不同 → 跳过并列入
 * 「库与源文件不一致(不覆盖)」—— 这一格<b>不只是「源目录变了」</b>:导入后在 pg 下玩过的局,UPSERT 不改
 * {@code source},也会落进来。
 *
 * <p>⚠️ 本类不防「应用同时在跑」:导入时应用必须停着(见 {@link StoreMigrationTool})。
 */
final class SessionImporter {

	/** 读回那一步(测试面 7 的故障注入接缝)。<b>必须用传入的 jdbc</b> —— 它与插入是同一条事务内连接。 */
	interface ReadBack {
		Row read(JdbcTemplate jdbc, String saveId);
	}

	/** 读源文件那一步(测试面 8 的访问记录接缝)。 */
	interface SourceReader {
		byte[] read(Path file) throws IOException;
	}

	record Row(String snapshot, int turn, String status) {
	}

	static final ReadBack DEFAULT_READ_BACK = (jdbc, saveId) -> jdbc.queryForObject(
			"SELECT snapshot::text, turn, status FROM game_session WHERE save_id = ?",
			(rs, i) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3)), saveId);

	static final SourceReader DEFAULT_SOURCE_READER = Files::readAllBytes;

	static final String INSERT_IMPORT = "INSERT INTO game_session (save_id, snapshot, turn, status, source) "
			+ "VALUES (?, ?::json, ?, ?, 'import')";
	static final String COUNT_IMPORT = "SELECT count(*) FROM game_session WHERE source = 'import'";
	static final String SELECT_EXISTING = "SELECT snapshot::text, source FROM game_session WHERE save_id = ?";

	/** 不可读的原因类别(报告只给类别,不给异常 message —— 解析器的 message 会带出存档内容片段)。 */
	enum Unreadable {
		READ_FAILED("读取失败"),
		NOT_UTF8("不是合法 UTF-8"),
		JSON_PARSE("JSON 解析失败"),
		BAD_FILENAME("文件名非法"),
		DECODE_REFUSED("decode 拒载");

		final String label;

		Unreadable(String label) {
			this.label = label;
		}
	}

	record UnreadableFile(String fileName, Unreadable reason) {
	}

	record Candidate(String saveId, String text, byte[] bytes, int turn, String status) {
	}

	/** 库里已有、未覆盖的行。 */
	record Existing(String saveId, String source) {
	}

	/** 导入结果(报告与退出码由它算出;不含任何存档内容)。 */
	record Result(int candidateFiles, int imported, int skippedNotASave, int skippedDuplicate,
			List<Existing> skippedSameAsExisting, List<Existing> inconsistent, int integrityFailed,
			List<UnreadableFile> unreadable, long importRowsBefore, long importRowsAfter, String failure) {

		int skipped() {
			return skippedNotASave + skippedDuplicate + skippedSameAsExisting.size() + inconsistent.size();
		}

		int exitCode() {
			if (integrityFailed > 0) {
				return StoreMigrationTool.EXIT_INTEGRITY;
			}
			if (!unreadable.isEmpty() || !inconsistent.isEmpty()) {
				return StoreMigrationTool.EXIT_NEEDS_REVIEW;
			}
			return StoreMigrationTool.EXIT_OK;
		}
	}

	/** 读回不一致或对账不平:在事务内抛出,让整批回滚。 */
	static final class IntegrityFailure extends RuntimeException {
		IntegrityFailure(String message) {
			super(message);
		}
	}

	private final ObjectMapper mapper;
	private final ArchetypeRegistry archetypes;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final ReadBack readBack;
	private final SourceReader reader;

	SessionImporter(DataSource dataSource, ObjectMapper mapper, ArchetypeRegistry archetypes, int txTimeoutSeconds,
			ReadBack readBack, SourceReader reader) {
		this.mapper = mapper;
		this.archetypes = archetypes;
		this.jdbc = new JdbcTemplate(dataSource);
		this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.tx.setTimeout(txTimeoutSeconds);
		this.readBack = readBack;
		this.reader = reader;
	}

	Result run(Path dir) throws IOException {
		// ── 扫描阶段:不开事务、不写库 ──────────────────────────────────
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
		}
		List<UnreadableFile> unreadable = new ArrayList<>();
		List<Candidate> candidates = new ArrayList<>();
		int notASave = 0;
		for (Path file : files) {
			String name = file.getFileName().toString();
			Object outcome = classify(file, name);
			if (outcome instanceof Unreadable u) {
				unreadable.add(new UnreadableFile(name, u));
			} else if (outcome instanceof Candidate c) {
				candidates.add(c);
			} else {
				notASave++;
			}
		}
		final int notASaveCount = notASave;

		// ── 导入阶段:一个事务,同一条连接;全部比对与对账通过才提交 ────────
		Counts counts = new Counts();
		try {
			tx.executeWithoutResult(status -> importAll(candidates, files.size(), notASaveCount, unreadable.size(), counts));
		} catch (IntegrityFailure e) {
			long rowsNow = jdbc.queryForObject(COUNT_IMPORT, Long.class);
			return new Result(files.size(), 0, notASaveCount, 0, List.of(), List.of(), 1, unreadable, counts.before,
					rowsNow, e.getMessage());
		}
		long committedAfter = jdbc.queryForObject(COUNT_IMPORT, Long.class);
		return new Result(files.size(), counts.imported, notASaveCount, counts.duplicate, counts.sameAsExisting,
				counts.inconsistent, 0, unreadable, counts.before, committedAfter, null);
	}

	/** 返回 {@link Unreadable}、{@link Candidate},或 null(非存档,计入 skipped)。判据只调 SessionDocument。 */
	private Object classify(Path file, String name) {
		byte[] bytes;
		try {
			bytes = reader.read(file);
		} catch (IOException e) {
			return Unreadable.READ_FAILED;
		}
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return Unreadable.NOT_UTF8;
		}
		JsonNode doc;
		try {
			doc = mapper.readTree(text);
		} catch (Exception e) {
			return Unreadable.JSON_PARSE;
		}
		if (!SessionDocument.isSaveDocument(doc)) {
			return null; // 非存档(月账等):跳过,不报错 —— 与 FileSessionStore.loadAll 同一判据
		}
		// 文件名只在它要变成 save_id 时才检查(非存档不入库,名字无关)。正则复用 FileSessionStore 那一份。
		String saveId = name.substring(0, name.length() - ".json".length());
		if (!FileSessionStore.SAFE_SAVE_ID.matcher(saveId).matches()) {
			return Unreadable.BAD_FILENAME;
		}
		GameSession decoded;
		try {
			decoded = SessionDocument.decode(saveId, doc, mapper, archetypes);
		} catch (Exception e) {
			return Unreadable.DECODE_REFUSED;
		}
		// 入库的是 text(源文件原文),decoded 只提供 turn / status 两列;绝不 encode 它。
		return new Candidate(saveId, text, bytes, decoded.engine().turn(), decoded.engine().status());
	}

	private static final class Counts {
		long before;
		int imported;
		int duplicate;
		final List<Existing> sameAsExisting = new ArrayList<>();
		final List<Existing> inconsistent = new ArrayList<>();
	}

	/** 在事务内跑;任何 {@link IntegrityFailure} 让整批回滚。 */
	private void importAll(List<Candidate> candidates, int fileCount, int notASave, int unreadableCount, Counts c) {
		c.before = jdbc.queryForObject(COUNT_IMPORT, Long.class);
		for (Candidate cand : candidates) {
			List<String[]> existing = jdbc.query(SELECT_EXISTING,
					(rs, i) -> new String[] { rs.getString(1), rs.getString(2) }, cand.saveId());
			if (existing.isEmpty()) {
				jdbc.update(INSERT_IMPORT, cand.saveId(), cand.text(), cand.turn(), cand.status());
				verify(cand, readBack.read(jdbc, cand.saveId()));
				c.imported++;
				continue;
			}
			String[] row = existing.get(0);
			boolean same = Arrays.equals(row[0].getBytes(StandardCharsets.UTF_8), cand.bytes());
			if (same && "import".equals(row[1])) {
				c.duplicate++;
			} else if (same) {
				c.sameAsExisting.add(new Existing(cand.saveId(), row[1]));
			} else {
				c.inconsistent.add(new Existing(cand.saveId(), row[1]));
			}
		}
		// 对账(ADR-025 代价 9 / ADR-032 §1.3):在提交之前、同一事务里算,对不上就整批回滚。
		long after = jdbc.queryForObject(COUNT_IMPORT, Long.class);
		int skipped = notASave + c.duplicate + c.sameAsExisting.size() + c.inconsistent.size();
		if (fileCount != c.imported + skipped + unreadableCount) {
			throw new IntegrityFailure("对账不平:候选 " + fileCount + " ≠ imported " + c.imported + " + skipped " + skipped
					+ " + 不可读 " + unreadableCount);
		}
		if (after - c.before != c.imported) {
			throw new IntegrityFailure("对账不平:source='import' 行数增量 " + (after - c.before) + " ≠ imported " + c.imported);
		}
	}

	private static void verify(Candidate cand, Row row) {
		byte[] stored = row.snapshot() == null ? new byte[0] : row.snapshot().getBytes(StandardCharsets.UTF_8);
		int offset = Arrays.mismatch(stored, cand.bytes());
		if (offset >= 0) {
			throw new IntegrityFailure("读回不一致:" + cand.saveId() + ",第一个不同字节的偏移 = " + offset
					+ "(库 " + stored.length + " 字节 / 源 " + cand.bytes().length + " 字节)");
		}
		if (row.turn() != cand.turn() || !cand.status().equals(row.status())) {
			throw new IntegrityFailure("读回不一致:" + cand.saveId() + ",turn / status 列与 decode 读数不等");
		}
	}
}
