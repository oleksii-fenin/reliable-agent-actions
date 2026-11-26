package io.github.oleksiifenin.raa.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.service.IllegalConfigurationException;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolService;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.time.Clock;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The scan of {@code ReliableTools.wrap(Object)} (ADR-0009): public LangChain4j API only, the same tools as
 * LangChain4j's own {@code ToolService.findTools} finds from 1.20 on, on every supported version.
 */
class ToolBeanScannerTest {

    private final ToolBeanScanner scanner = new ToolBeanScanner();

    // ---- beans

    public static class BaseTools {
        @Tool("Inherited tool")
        public String inherited(@P("a value") String value) {
            return "inherited " + value;
        }

        @Tool("Base description")
        public String overridden(@P("a value") String value) {
            return "base " + value;
        }
    }

    public interface DefaultMethodTools {
        @Tool("Tool in a default method")
        default String fromInterface(@P("a value") String value) {
            return "default " + value;
        }
    }

    /** Own, inherited, overridden and interface default tools, and a method that is not a tool. */
    public static final class ChildTools extends BaseTools implements DefaultMethodTools {
        @Tool(value = "Own tool", returnBehavior = ReturnBehavior.IMMEDIATE)
        public String own(@P("a value") String value) {
            return "own " + value;
        }

        @Override
        @Tool("Child description")
        public String overridden(@P("a value") String value) {
            return "child " + value;
        }

        public String notATool(String value) {
            return value;
        }
    }

    /** An annotated class and a subclass that overrides without {@code @Tool}, as a class-based proxy does. */
    public static class AnnotatedTools {
        @Tool("Refunds an order")
        public String refund(@P("the order id") String orderId) {
            return "refunded " + orderId;
        }
    }

    public static final class ClassBasedProxyLike extends AnnotatedTools {
        @Override
        public String refund(String orderId) {
            return "intercepted " + super.refund(orderId);
        }
    }

    public interface ProxiedTools {
        @Tool("Answers a ping")
        String ping(@P("a value") String value);
    }

    public static final class FailingTools {
        @Tool("Fails")
        public String fail(@P("a number") int number) {
            throw new IllegalStateException("tool failed with " + number);
        }
    }

    private static ProxiedTools jdkProxy() {
        return (ProxiedTools) Proxy.newProxyInstance(ProxiedTools.class.getClassLoader(),
                new Class<?>[] {ProxiedTools.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "ping" -> "pong " + args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "proxy";
                });
    }

    // ---- what is found

    @Test
    void findsOwnInheritedAndDefaultMethodTools_andAnOverrideReplacesTheOverriddenTool() {
        Map<String, AiServiceTool> tools = byName(scanner.tools(new ChildTools()));

        assertThat(tools).containsOnlyKeys("own", "inherited", "overridden", "fromInterface");
        assertThat(tools.get("overridden").toolSpecification().description()).isEqualTo("Child description");
        assertThat(tools.get("own").returnBehavior()).isEqualTo(ReturnBehavior.IMMEDIATE);
        assertThat(tools.get("inherited").returnBehavior()).isEqualTo(ReturnBehavior.TO_LLM);
        assertThat(run(tools.get("inherited"), "{\"value\":\"x\"}")).isEqualTo("inherited x");
        assertThat(run(tools.get("overridden"), "{\"value\":\"x\"}")).isEqualTo("child x");
        assertThat(run(tools.get("fromInterface"), "{\"value\":\"x\"}")).isEqualTo("default x");
    }

    @Test
    void overrideWithoutTheAnnotation_isTheAnnotatedTool_andRunsTheOverride() {
        Map<String, AiServiceTool> tools = byName(scanner.tools(new ClassBasedProxyLike()));

        assertThat(tools).containsOnlyKeys("refund");
        assertThat(tools.get("refund").toolSpecification().description()).isEqualTo("Refunds an order");
        assertThat(run(tools.get("refund"), "{\"orderId\":\"order-42\"}")).isEqualTo("intercepted refunded order-42");
    }

    @Test
    void jdkProxy_isScannedThroughItsInterface() {
        Map<String, AiServiceTool> tools = byName(scanner.tools(jdkProxy()));

        assertThat(tools).containsOnlyKeys("ping");
        assertThat(run(tools.get("ping"), "{\"value\":\"x\"}")).isEqualTo("pong x");
    }

    @Test
    void aToolFoundOnlyByThisScan_withoutAPolicy_failsTheWiring() {
        // On LangChain4j 1.14 and 1.15 AiServices.tools(bean) does not see "inherited"; wrap(bean) does, and asks for
        // its policy instead of running it unjournaled.
        ReliableActions withoutInherited = ReliableActions.builder(new InMemoryActionJournal(Clock.systemUTC()))
                .tool("own", ActionPolicy.none()).tool("overridden", ActionPolicy.none())
                .tool("fromInterface", ActionPolicy.none()).build();

        assertThatThrownBy(() -> ReliableTools.wrap(new ChildTools(), withoutInherited))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("tool inherited has no policy");
    }

    // ---- the executor is configured as LangChain4j's own scan configures it

    @Test
    void invalidArguments_areAToolArgumentsException_beforeTheToolRuns() {
        AiServiceTool fail = scanner.tools(new FailingTools()).get(0);

        assertThatThrownBy(() -> run(fail, "{\"number\":\"not a number\"}"))
                .isInstanceOf(ToolArgumentsException.class);
    }

    @Test
    void theToolsOwnException_isPropagated_withItAsTheCause() {
        AiServiceTool fail = scanner.tools(new FailingTools()).get(0);

        assertThatThrownBy(() -> run(fail, "{\"number\":7}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("tool failed with 7");
    }

    // ---- rejected beans, with LangChain4j's exception type

    @Test
    void aClass_anIterable_andAnObjectWithoutTools_areRejected() {
        assertThatThrownBy(() -> scanner.tools(ChildTools.class)).isInstanceOf(IllegalConfigurationException.class)
                .hasMessageContaining("must be an object, not a class");
        assertThatThrownBy(() -> scanner.tools(List.of(new ChildTools())))
                .isInstanceOf(IllegalConfigurationException.class).hasMessageContaining("Iterable");
        assertThatThrownBy(() -> scanner.tools(new Object())).isInstanceOf(IllegalConfigurationException.class)
                .hasMessageContaining("does not have any methods annotated with @Tool");
    }

    // ---- the same tools as LangChain4j's own scan

    static List<Object> flatBeans() {
        return List.of(new LangChain4jContractTest.RefundTools(), new LangChain4jContractTest.ImmediateTools(),
                new FailingTools(), jdkProxy());
    }

    static List<Object> allBeans() {
        return List.of(new LangChain4jContractTest.RefundTools(), new LangChain4jContractTest.ImmediateTools(),
                new FailingTools(), jdkProxy(), new ChildTools(), new ClassBasedProxyLike());
    }

    @ParameterizedTest
    @MethodSource("flatBeans")
    void beansWithoutSupertypeTools_giveTheSameToolsAsLangChain4jOnEverySupportedVersion(Object bean) {
        assertSameTools(bean);
    }

    @ParameterizedTest
    @MethodSource("allBeans")
    void everyBean_givesTheSameToolsAsLangChain4jFrom120On(Object bean) {
        assumeTrue(langChain4jAtLeast(1, 20), "LangChain4j before 1.20 scans fewer methods (see ToolBeanScanner)");
        assertSameTools(bean);
    }

    private void assertSameTools(Object bean) {
        Map<String, AiServiceTool> ours = byName(scanner.tools(bean));
        Map<String, AiServiceTool> theirs = byName(ToolService.findTools(bean)); // the oracle; not used in main code

        assertThat(ours).containsOnlyKeys(theirs.keySet());
        theirs.forEach((name, tool) -> {
            assertThat(ours.get(name).toolSpecification()).as(name).isEqualTo(tool.toolSpecification());
            assertThat(ours.get(name).returnBehavior()).as(name).isEqualTo(tool.returnBehavior());
        });
    }

    // ---- helpers

    private static boolean langChain4jAtLeast(int major, int minor) {
        String[] parts = System.getProperty("raa.langchain4j.version").split("\\.");
        int actualMajor = Integer.parseInt(parts[0]);
        int actualMinor = Integer.parseInt(parts[1]);
        return actualMajor > major || (actualMajor == major && actualMinor >= minor);
    }

    private static Map<String, AiServiceTool> byName(List<AiServiceTool> tools) {
        return tools.stream().collect(Collectors.toMap(AiServiceTool::name, Function.identity()));
    }

    private static String run(AiServiceTool tool, String arguments) {
        return tool.toolExecutor().execute(
                ToolExecutionRequest.builder().id("t1").name(tool.name()).arguments(arguments).build(), "memory-1");
    }
}
