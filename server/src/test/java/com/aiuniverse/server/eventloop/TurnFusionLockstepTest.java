package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-013 Slice D / ADR-014 · event-loop 融合指令 lockstep 守护(按 combo 分组):「融合世界 · 每回合裁决与收敛」
 * 段的核心短语在两处({@code prompts/event-loop.md} 融合段 / 运行时 {@code TurnPromptBuilder} 融合指令)逐条一致
 * ——防止只改 .md 运行时失效、或只改一边漂移;并钉「只在 hybrid 局注入,单体 prompt 无此段」。
 *
 * <p><b>分组(ADR-014 骨架参数化)</b>:结构指令短语(收敛/通关判定/有据恢复的规矩本体)对每个已登记 combo 的
 * hybrid prompt 都必须在;per-combo 短语(裁决口吻文案槽)只对本 combo 在。
 */
class TurnFusionLockstepTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	/** 骨架结构短语(裁决进循环 + 收敛 + 通关判定 + 有据恢复):每个 combo 的 hybrid prompt 与 .md 都必须含。 */
	private static final String[] SKELETON_PHRASES = {
			"【融合世界 · 每回合裁决与收敛】",
			"把「辨真伪」做进每一回合的循环",    // 裁决:进循环、守则墙不作背景板
			"不得原地回环",                     // 收敛:不回环
			"每回合至少推进一点",                // 收敛:强制推进
			"不要拖延磨回合",                   // 收敛:condition 接近达成时主动给 ending
			"【通关判定】",                     // E-2:成功 condition 逐项核对,齐则必须提议 ending
			"条件齐了就通关",                   // E-2:治「达成不给通关」(实测胜利线达成拖 99 回合)
			"给出至少一个通往它的选项",          // E-2:仅差一项时叙事+选项向补齐引导
			"【有据恢复】",                     // E-2:恢复手段 stateUpdate 真的上调(F-003 有据恢复)
			"别让恢复手段沦为口头叙事",          // E-2:治「恢复只在嘴上、数值不动」
	};

	/** per-combo 短语(文案槽,key = host×foreign):只对本 combo 的 hybrid prompt(与 .md)必须含。 */
	private static final Map<String, String[]> COMBO_PHRASES = new LinkedHashMap<>();
	static {
		COMBO_PHRASES.put("cultivation×rules_creepy", new String[] {
				"误信心魔伪笔",                 // 裁决:伪笔应伤道心
				"稳道心 / 长境界 / 得线索",      // 裁决回报口吻
				"如还差识破一条伪笔",            // 通关判定「仅差一项」示例
				"参悟心法 / 调息 / 丹药等",      // 有据恢复手段示例
		});
		COMBO_PHRASES.put("rules_creepy×apocalypse", new String[] {
				"误信假页",                     // 裁决:假页首伤理智(ADR-014 round 1.5)
				"保住补给 / 稳住理智 / 得物证线索", // 裁决回报口吻
				"如还差识破一条假页",            // 通关判定「仅差一项」示例(比对物证/查验尸体)
				"配给日 / 搜刮 / 以页换粮等",    // 有据恢复手段示例(资源经济三渠道)
				"【昼夜节律 · 多日尺度】",       // E'-1:治整局压缩单夜
				"禁止把整局压缩在单夜内",         // E'-1:时间真实流动
				"【补给消耗与行为挂钩】",         // E''-1:静卧/躲藏休整轻微消耗(治夜间结构性必死)
				"熬到天亮」是可存活的策略而非倒计时", // E''-1:夜间休整不全额扣减
				"【补给通道 · 兑现保证】",       // E''-2:发牌保证升级为兑现语义
				"不许发空头牌",                  // E''-2:治「叙事拒付、数值不动」(heap 取证第 3 局)
				"与叙事相称的实际补给增量",       // E''-2:领半袋=明显回升(+15~25)
				"连续多回合无任何补给途径是错误",  // E'-2:紧缺档后每回合至少一条补给路径
				"【断粮收束 · 倒计时】",         // E''-3:断粮≠瞬死,数个回合倒计时
				"倒计时解除",                    // E''-3:找到应急粮可拉回(有据恢复)
		});
	}

	@Test
	void fusionDirectiveStaysLockstepBetweenMdAndRuntimePerCombo() throws IOException {
		String eventMd = readMd("event-loop.md");

		for (Map.Entry<String, String[]> combo : COMBO_PHRASES.entrySet()) {
			String[] hostForeign = combo.getKey().split("×");
			String hybridRuntime = builder.buildTurnPrompt(hybridEngine(hostForeign[0], hostForeign[1]), "A", "行动");

			for (String phrase : SKELETON_PHRASES) {
				assertThat(eventMd).as("event-loop.md 融合段含 lockstep 短语:%s", phrase).contains(phrase);
				assertThat(hybridRuntime).as("%s 融合指令含骨架短语:%s", combo.getKey(), phrase).contains(phrase);
			}
			for (String phrase : combo.getValue()) {
				assertThat(eventMd).as("event-loop.md 融合段含 %s 短语:%s", combo.getKey(), phrase).contains(phrase);
				assertThat(hybridRuntime).as("%s 融合指令含本组合短语:%s", combo.getKey(), phrase).contains(phrase);
			}
		}
	}

	@Test
	void singleModePromptCarriesNoFusionDirective() {
		// 单体局(四个基础世界)prompt 无融合段(%8$s 注入空串,逐字不变的 parity 线)。
		for (String archetype : new String[] { "rules_creepy", "apocalypse", "cthulhu", "cultivation" }) {
			ObjectNode world = mapper.createObjectNode();
			world.putArray("archetypes").add(archetype);
			world.putObject("character").putObject("attributes").put("hp", 90);
			world.putArray("rules");
			world.putArray("endings");
			String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "行动");
			assertThat(p).as("单体 %s 无融合指令", archetype)
					.doesNotContain("【融合世界 · 每回合裁决与收敛】");
		}
	}

	/** 构造该 combo 的 hybrid 局引擎(attributes 按融合轴集给初值)。 */
	private Engine hybridEngine(String host, String foreign) {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add(host).add(foreign);
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		for (var axis : registry.fusedAxes(host, foreign)) {
			attrs.put(axis.key(), 66);
		}
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/** 从 server 模块向上定位仓库根 prompts/<name>(surefire CWD=server 模块目录)。 */
	private String readMd(String name) throws IOException {
		for (Path p : new Path[] { Path.of("..", "prompts", name), Path.of("prompts", name) }) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 prompts/" + name + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
