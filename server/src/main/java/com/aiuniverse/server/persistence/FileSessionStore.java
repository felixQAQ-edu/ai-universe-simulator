package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.GameSession;
import com.aiuniverse.server.eventloop.TurnPhase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SessionStore} 的文件实现(ADR-015 C3):落盘目录里每 saveId 一个 {@code <saveId>.json},
 * 文档 = {@code Engine.toPersistedState()}(视图 1 全量,含 isTrue/hiddenLogic,<b>绝不出网</b>)
 * + session 层 {@code currentActions}/{@code phaseHint}。
 *
 * <ul>
 *   <li><b>原子写</b>:先写 {@code <saveId>.json.tmp} 再 {@code ATOMIC_MOVE}(移动失败回退普通 move),
 *       崩溃不留半个 JSON——盘上永远是最后一个完整回合(ADR-015 已知限制 1,特性非 bug)。</li>
 *   <li><b>路径安全断言(附录 A 第 3 条)</b>:构造时断言落盘目录不位于 classpath static resources
 *       ({@code static}/{@code public})之下;违反 → {@link IllegalStateException} <b>拒绝启动,不降级</b>
 *       (视图 1 全量落盘,进 web 根 = 整局隐藏逻辑可被直接 GET,CONTEXT §三.9)。打包成 jar 时
 *       classpath static 非文件系统目录(jar: URL),检查自然跳过——目录也不可能位于 jar 内。</li>
 *   <li><b>回载容错</b>:单文件损坏/拒载/archetype 不识 → WARN + 跳过 + 保留原文件(留尸检),
 *       其余照载;一局坏档不杀死整个服务。</li>
 *   <li><b>轴语义集不落盘</b>(ADR-015):回载时由 {@code world.archetypes} 经
 *       {@link ArchetypeRegistry#resolveAxes} 原路重派生(与播种同一真理源)。</li>
 * </ul>
 *
 * <p>落盘目录 = {@code aiuniverse.session.store-dir}(默认 {@code ./data},本地友好);
 * 部署时环境变量 <b>{@code AIUNIVERSE_SESSION_STORE_DIR}</b> 覆盖到持久卷挂载点(如 {@code /data}),
 * 与 ADR-015「配置经 env 覆盖」口径一致。
 */
@Service
public class FileSessionStore implements SessionStore {

	private static final Logger log = LoggerFactory.getLogger(FileSessionStore.class);

	/** saveId 只允许 UUID 形字符(服务端生成;防御:含路径分隔等一律拒写,防目录逃逸)。 */
	private static final Pattern SAFE_SAVE_ID = Pattern.compile("[A-Za-z0-9-]+");

	private final Path dir;
	private final ObjectMapper mapper;
	private final ArchetypeRegistry archetypes;

	public FileSessionStore(@Value("${aiuniverse.session.store-dir:./data}") String storeDir,
			ObjectMapper mapper, ArchetypeRegistry archetypes) {
		this.mapper = mapper;
		this.archetypes = archetypes;
		this.dir = Path.of(storeDir).toAbsolutePath().normalize();
		assertOutsideWebRoot(this.dir, classpathWebRoots());
		try {
			Files.createDirectories(this.dir);
		} catch (IOException e) {
			throw new IllegalStateException("会话落盘目录不可创建:" + this.dir, e);
		}
		log.info("[session-store] 落盘目录 = {}", this.dir);
	}

	// ── 写盘(best-effort,绝不抛)──────────────────────────────────────

	@Override
	public void persist(GameSession session) {
		String saveId = session.saveId();
		if (saveId == null || !SAFE_SAVE_ID.matcher(saveId).matches()) {
			log.error("[session-store] saveId 含非法字符,拒写(防目录逃逸):{}", saveId);
			return;
		}
		try {
			ObjectNode doc = session.engine().toPersistedState();
			ArrayNode actions = session.currentActions();
			doc.set("currentActions", actions == null ? mapper.createArrayNode() : actions.deepCopy());
			doc.put("phaseHint", session.phase().get().name()); // 仅取证用;回载按 status 重置,不读它
			Path tmp = dir.resolve(saveId + ".json.tmp");
			Path target = dir.resolve(saveId + ".json");
			Files.writeString(tmp, mapper.writeValueAsString(doc), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException atomicUnsupported) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			log.error("[session-store] save={} 写盘失败(局面继续活在内存,本回合丢续局):{}",
					saveId, e.toString());
		}
	}

	// ── 启动回载(单文件容错)─────────────────────────────────────────

	@Override
	public List<GameSession> loadAll() {
		List<GameSession> loaded = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return loaded;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
				GameSession session = loadOne(p);
				if (session != null) {
					loaded.add(session);
				}
			});
		} catch (IOException e) {
			log.error("[session-store] 落盘目录扫描失败(以空档启动):{}", e.toString());
		}
		return loaded;
	}

	/** 单档回载;任何失败 → WARN + null(调用方跳过),文件保留原样(留尸检)。 */
	private GameSession loadOne(Path file) {
		String name = file.getFileName().toString();
		String saveId = name.substring(0, name.length() - ".json".length());
		try {
			JsonNode doc = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
			// 轴语义集不落盘:由 world.archetypes 经 registry 原路重派生(与播种同一真理源)。
			List<String> ids = new ArrayList<>();
			doc.path("world").path("archetypes").forEach(a -> ids.add(a.asString("")));
			List<AttributeAxis> axes = archetypes.resolveAxes(ids);
			Engine engine = Engine.restore(doc, mapper, ArchetypeRegistry.accumulationKeys(axes),
					ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
			JsonNode actions = doc.get("currentActions");
			ArrayNode initial = actions != null && actions.isArray()
					? (ArrayNode) actions.deepCopy()
					: mapper.createArrayNode();
			GameSession session = new GameSession(saveId, engine, initial);
			// phase 按 status 重置(AtomicReference 运行时态不落盘,ADR-015 勘察 2)。
			session.phase().set("ended".equals(engine.status()) ? TurnPhase.ENDED : TurnPhase.AWAITING_ACTION);
			return session;
		} catch (Exception e) {
			log.warn("[session-store] 存档回载失败,跳过并保留原文件(留尸检):{} — {}", file, e.toString());
			return null;
		}
	}

	// ── 路径安全断言(附录 A 第 3 条:落盘目录不得位于 static resources 之下)──

	/** classpath 上会被当 web 根伺服的文件系统目录(打包 jar 时为 jar: URL,自然不参与比较)。 */
	private List<Path> classpathWebRoots() {
		List<Path> roots = new ArrayList<>();
		for (String loc : List.of("static", "public")) {
			URL url = getClass().getClassLoader().getResource(loc);
			if (url != null && "file".equals(url.getProtocol())) {
				try {
					roots.add(Path.of(url.toURI()).toAbsolutePath().normalize());
				} catch (URISyntaxException ignored) {
					// 无法解析的 URL 不参与比较(不可能是可写文件系统目录)
				}
			}
		}
		return roots;
	}

	/** 纯校验(包私有供测试):dir 位于任一 web 根之下 → 拒绝启动,不降级。 */
	static void assertOutsideWebRoot(Path dir, List<Path> webRoots) {
		for (Path root : webRoots) {
			if (dir.startsWith(root)) {
				throw new IllegalStateException("会话落盘目录位于 static web 根之下(视图 1 全量含 "
						+ "isTrue/hiddenLogic,绝不出网,CONTEXT §三.9):" + dir + " ⊆ " + root
						+ ";请改配 aiuniverse.session.store-dir(env AIUNIVERSE_SESSION_STORE_DIR)");
			}
		}
	}
}
