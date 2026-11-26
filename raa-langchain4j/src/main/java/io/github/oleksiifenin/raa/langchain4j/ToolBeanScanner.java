package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.IllegalConfigurationException;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the {@code @Tool} methods of a bean with public LangChain4j API only (ADR-0009): the specification comes from
 * {@link ToolSpecifications#toolSpecificationFrom(Method)}, the executor from {@link DefaultToolExecutor#builder()}
 * configured as LangChain4j's own scan configures it.
 *
 * <p>Methods: the concrete methods of the bean's class and its superclasses (not {@link Object}) and the default and
 * static methods of its interfaces; an override hides the overridden method; bridge and synthetic methods are skipped.
 * A method without {@code @Tool} is a tool if a supertype declares the same signature with {@code @Tool}, as for a
 * JDK or class-based proxy. This is the scan of LangChain4j 1.20 and later. Earlier versions find fewer: 1.14 and
 * 1.15 only the methods the class itself declares, 1.16 to 1.19 also inherited ones; before 1.20 an override without
 * {@code @Tool} counts only in a JDK proxy.
 */
final class ToolBeanScanner {

    List<AiServiceTool> tools(Object toolBean) {
        if (toolBean instanceof Class) {
            throw new IllegalConfigurationException("Tool '" + toolBean + "' must be an object, not a class");
        }
        if (toolBean instanceof Iterable) {
            throw new IllegalConfigurationException("Tool '" + toolBean.getClass().getName() + "' is an Iterable;"
                    + " pass the tool objects one by one, not in a collection");
        }
        List<AiServiceTool> tools = new ArrayList<>();
        for (Method method : concreteMethods(toolBean.getClass())) {
            Optional<Method> toolMethod = toolMethod(method);
            toolMethod.ifPresent(m -> tools.add(tool(toolBean, m)));
        }
        if (tools.isEmpty()) {
            throw new IllegalConfigurationException("Object '" + toolBean.getClass().getName()
                    + "' does not have any methods annotated with @Tool");
        }
        return tools;
    }

    private static AiServiceTool tool(Object toolBean, Method toolMethod) {
        return AiServiceTool.builder()
                .toolSpecification(ToolSpecifications.toolSpecificationFrom(toolMethod))
                .toolExecutor(DefaultToolExecutor.builder()
                        .object(toolBean)
                        .originalMethod(toolMethod)
                        .methodToInvoke(toolMethod)
                        .wrapToolArgumentsExceptions(true)
                        .propagateToolExecutionExceptions(true)
                        .build())
                .returnBehavior(toolMethod.getAnnotation(Tool.class).returnBehavior())
                .build();
    }

    private static List<Method> concreteMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Set<Signature> seen = new HashSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                addIfNew(method, seen, methods);
            }
        }
        Set<Class<?>> visited = new HashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            addInterfaceMethods(current, seen, methods, visited);
        }
        return methods;
    }

    private static void addInterfaceMethods(Class<?> type, Set<Signature> seen, List<Method> methods,
                                            Set<Class<?>> visited) {
        for (Class<?> anInterface : type.getInterfaces()) {
            if (!visited.add(anInterface)) {
                continue;
            }
            for (Method method : anInterface.getDeclaredMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) {
                    addIfNew(method, seen, methods);
                }
            }
            addInterfaceMethods(anInterface, seen, methods, visited);
        }
    }

    private static void addIfNew(Method method, Set<Signature> seen, List<Method> methods) {
        if (!method.isBridge() && !method.isSynthetic() && seen.add(Signature.of(method))) {
            methods.add(method);
        }
    }

    private static Optional<Method> toolMethod(Method method) {
        if (method.isAnnotationPresent(Tool.class)) {
            return Optional.of(method);
        }
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> supertypes = new ArrayDeque<>();
        addSupertypes(method.getDeclaringClass(), supertypes, visited);
        while (!supertypes.isEmpty()) {
            Class<?> supertype = supertypes.poll();
            try {
                Method declared = supertype.getDeclaredMethod(method.getName(), method.getParameterTypes());
                if (declared.isAnnotationPresent(Tool.class)) {
                    return Optional.of(declared);
                }
            } catch (NoSuchMethodException notDeclaredHere) {
                // look further up
            }
            addSupertypes(supertype, supertypes, visited);
        }
        return Optional.empty();
    }

    private static void addSupertypes(Class<?> type, Deque<Class<?>> supertypes, Set<Class<?>> visited) {
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class && visited.add(superclass)) {
            supertypes.add(superclass);
        }
        for (Class<?> anInterface : type.getInterfaces()) {
            if (visited.add(anInterface)) {
                supertypes.add(anInterface);
            }
        }
    }

    private record Signature(String name, List<Class<?>> parameterTypes) {

        static Signature of(Method method) {
            return new Signature(method.getName(), List.of(method.getParameterTypes()));
        }
    }
}
