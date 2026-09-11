package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * {@link TurnPhase#inFlight()} 的契约(ADR-023 立字 5):在途 = {@code {GENERATING, SETTLING}}。
 *
 * <p><b>它守的是什么</b>:有人改错 {@code inFlight()} 本身(漏掉 SETTLING、或把 ENDED 算进在途)。
 * 前者会让游标比对在那个毫秒窗口里误报 {@code turn_stale}(立字 2 落空),
 * 后者会让结局那回合断流的玩家<b>永远出不去</b>(立字 3 落空)。
 *
 * <p>⚠️ <b>它守不住什么,如实写在这里</b>:<b>「有人加了第五个相位值且忘了在这里做决定」——
 * 本测试不会红</b>(新值默认不在途,而「不在途」恰好是多数情况下看起来对的那一个)。
 * 立字 5 给的是<b>结构</b>上的强制而不是一条会红的测试:判断住在枚举自己身上,
 * 加值的人<b>必须在一处</b>面对它,而不是让四个调用点各自漏掉。
 * <b>别把这条测试读成「第五个值有人看着」。</b>
 */
class TurnPhaseInFlightTest {

	@Test
	void inFlightIsExactlyGeneratingAndSettling() {
		String inFlight = Arrays.stream(TurnPhase.values()).filter(TurnPhase::inFlight)
				.map(Enum::name).collect(Collectors.joining(","));

		assertThat(inFlight).isEqualTo("GENERATING,SETTLING");
	}

	/**
	 * 判 {@code turn_stale} 的那一侧(立字 3)。{@code ENDED} 在这一侧是刻意的:
	 * {@code turn_stale} 只说「你的游标落后于服务端」,<b>在 ENDED 上这句话是真的</b>,
	 * 而它是玩家在结局那回合断流之后唯一的出口。
	 */
	@Test
	void awaitingAndEndedAreNotInFlight() {
		assertThat(TurnPhase.AWAITING_ACTION.inFlight()).isFalse();
		assertThat(TurnPhase.ENDED.inFlight()).as("结局那回合断流之后,它是玩家唯一的出口").isFalse();
	}
}
