package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * <b>已知代价 2 的缓解 (ii)</b>:{@code newCachedThreadPool} 仍在代码里(住进了 {@link TurnAdmission}),
 * 只是<b>变得不可达</b>——准入在前之后它的「无上限」永远够不着。但<b>它还在</b>:
 * 日后有人在准入之外另起一条提交路径,就回到今天。
 *
 * <p>形态 (d) 已经把主要缓解做成了结构性的(池归准入对象,{@code GameController} <b>手边根本没有那个
 * 东西可绕</b>);本条是那层结构的<b>看门人</b>:行为等价时行为测试抓不到(自建一个池、绕开准入直接
 * {@code execute},公开行为可以完全一样),<b>只有源码级断言抓得到</b>
 * (同 ADR-021 刀 2 的 {@code "vigor"} 与刀 1 的族层先例)。
 *
 * <p>⚠️ <b>两个洞在写的时候就堵上</b>(ADR-022 测试面点名):
 * <ol>
 *   <li><b>作用域写死 = 就 {@code GameController.java} 一个文件。</b> 不写作用域 = 全仓扫描 =
 *       <b>打到准入类自己,断言当场自相矛盾</b>(它<b>必须</b>持有池,那是形态 (d) 的全部内容)。
 *       扫一个文件也意味着<b>不需要任何排除项</b>——而排除项就是洞。</li>
 *   <li><b>先断言目标文件读得到,再断言它的内容。</b> 源码扫描类断言的经典死法是
 *       「文件改名 / 移动 → 扫描零命中 → 绿灯」(ADR-018 §4.14 那族的原样重演,
 *       也同 ADR-021 刀 2「遍历清单的守护、清单本身没人守」那个洞)。</li>
 * </ol>
 *
 * <p>⚠️ 断言扫的是<b>字面量</b>,故 {@code GameController} 的<b>注释里也不许出现</b>这两个词
 * (那里已改用「线程池」指代)。偏严是有意的:一条无排除项的 {@code doesNotContain} 没有缝可钻。
 */
class GameControllerNoThreadPoolSourceTest {

	private static final Path CONTROLLER = Path
			.of("src/main/java/com/aiuniverse/server/web/GameController.java");

	@Test
	void gameControllerHoldsNoThreadPoolOfItsOwn() throws IOException {
		// 洞 2:先证明我们真的读到了那个文件(改名 / 移动会让下面的断言变成空转)。
		assertThat(CONTROLLER)
				.as("目标文件不存在 = 这条断言什么也没在看(CWD=%s)", Path.of(".").toAbsolutePath())
				.isRegularFile();
		String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
		assertThat(source).as("先证明读到的是 GameController 本体").contains("class GameController");

		assertThat(source)
				.as("池归 TurnAdmission 所有:controller 手边不该有任何可绕过准入的线程池")
				.doesNotContain("ExecutorService")
				.doesNotContain("Executors.");
	}

	/** 反面对照:准入类<b>必须</b>持有池——上面那条之所以能不写排除项,靠的是作用域只有一个文件。 */
	@Test
	void turnAdmissionIsTheOneThatOwnsThePool() throws IOException {
		Path admission = Path.of("src/main/java/com/aiuniverse/server/web/TurnAdmission.java");
		assertThat(admission).isRegularFile();
		assertThat(Files.readString(admission, StandardCharsets.UTF_8))
				.contains("Executors.newCachedThreadPool()");
	}
}
