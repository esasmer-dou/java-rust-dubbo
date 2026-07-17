package com.reactor.rust.dubbo.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient")
public final class NativeDubboClientProcessor extends AbstractProcessor {

    private final Set<String> generatedTypes = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(GenerateNativeDubboClient.class)) {
            if (!(element instanceof TypeElement definition)) {
                error(element, "@GenerateNativeDubboClient requires a type declaration");
                continue;
            }
            try {
                generate(definition);
            } catch (FilerException duplicate) {
                error(definition, "Native Dubbo client was generated more than once: " + duplicate.getMessage());
            } catch (RuntimeException | IOException failure) {
                error(definition, "Native Dubbo client generation failed: " + failure.getMessage());
            }
        }
        return false;
    }

    private void generate(TypeElement definition) throws IOException {
        GenerateNativeDubboClient annotation = definition.getAnnotation(GenerateNativeDubboClient.class);
        TypeMirror serviceMirror = serviceMirror(annotation);
        Element serviceElement = processingEnv.getTypeUtils().asElement(serviceMirror);
        if (!(serviceElement instanceof TypeElement service) || service.getKind() != ElementKind.INTERFACE) {
            throw new IllegalArgumentException("service must reference an interface");
        }
        if (!service.getTypeParameters().isEmpty()) {
            throw new IllegalArgumentException("generic service interfaces are not supported: "
                    + service.getQualifiedName());
        }

        String packageName = processingEnv.getElementUtils().getPackageOf(definition).getQualifiedName().toString();
        String generatedName = annotation.generatedName().isBlank()
                ? defaultGeneratedName(definition.getSimpleName().toString())
                : annotation.generatedName().trim();
        if (!SourceVersion.isIdentifier(generatedName)) {
            throw new IllegalArgumentException("generatedName must be a Java identifier: " + generatedName);
        }
        String qualifiedName = packageName.isEmpty() ? generatedName : packageName + "." + generatedName;
        if (!generatedTypes.add(qualifiedName)) {
            return;
        }

        List<ExecutableElement> methods = serviceMethods(service);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("service interface has no invocable methods: "
                    + service.getQualifiedName());
        }
        rejectOverloads(methods);

        JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, definition, service);
        try (Writer writer = source.openWriter()) {
            writer.write(source(packageName, generatedName, service, methods, annotation));
        }
    }

    private List<ExecutableElement> serviceMethods(TypeElement service) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        collectServiceMethods(service, methods, new HashSet<>());
        List<ExecutableElement> ordered = new ArrayList<>(methods.values());
        ordered.sort(Comparator.comparing(NativeDubboClientProcessor::methodSignature));
        return List.copyOf(ordered);
    }

    private void collectServiceMethods(
            TypeElement service,
            Map<String, ExecutableElement> methods,
            Set<String> visitedInterfaces) {
        String serviceName = service.getQualifiedName().toString();
        if (!visitedInterfaces.add(serviceName)) {
            return;
        }
        for (TypeMirror parentMirror : service.getInterfaces()) {
            Element parent = processingEnv.getTypeUtils().asElement(parentMirror);
            if (parent instanceof TypeElement parentInterface) {
                collectServiceMethods(parentInterface, methods, visitedInterfaces);
            }
        }
        for (ExecutableElement method : ElementFilter.methodsIn(service.getEnclosedElements())) {
            if (!method.getModifiers().contains(Modifier.STATIC)) {
                methods.put(methodSignature(method), method);
            }
        }
    }

    private static String methodSignature(ExecutableElement method) {
        return method.getSimpleName() + "(" + method.getParameters().stream()
                .map(parameter -> parameter.asType().toString())
                .collect(Collectors.joining(",")) + ")";
    }

    private String source(
            String packageName,
            String generatedName,
            TypeElement service,
            List<ExecutableElement> methods,
            GenerateNativeDubboClient config) {
        String serviceType = service.getQualifiedName().toString();
        StringBuilder out = new StringBuilder(8_192);
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("import com.reactor.rust.dubbo.DubboReferenceSpec;\n")
                .append("import com.reactor.rust.dubbo.NativeDubboConsumerClient;\n")
                .append("import com.reactor.rust.dubbo.NativeDubboMethodInvoker;\n")
                .append("import com.reactor.rust.dubbo.NativeResponseHandle;\n");
        if (config.retryReads()) {
            out.append("import com.reactor.rust.dubbo.policy.DubboReadRetry;\n");
        }
        out.append("import com.reactor.rust.dubbo.support.DubboConsumerSupport;\n")
                .append("import java.util.concurrent.CompletableFuture;\n\n")
                .append("public final class ").append(generatedName).append(" {\n\n");

        for (ExecutableElement method : methods) {
            out.append("    private final NativeDubboMethodInvoker<")
                    .append(boxedType(method.getReturnType()))
                    .append("> ").append(method.getSimpleName()).append(";\n");
        }
        if (config.exposeMetrics()) {
            out.append("    private final NativeDubboConsumerClient client;\n");
        }
        if (config.retryReads()) {
            out.append("    private final boolean retryReads;\n");
        }

        out.append("\n    private ").append(generatedName).append("(\n");
        for (int index = 0; index < methods.size(); index++) {
            ExecutableElement method = methods.get(index);
            out.append("            NativeDubboMethodInvoker<")
                    .append(boxedType(method.getReturnType())).append("> ")
                    .append(method.getSimpleName());
            if (index + 1 < methods.size() || config.exposeMetrics() || config.retryReads()) {
                out.append(',');
            }
            out.append('\n');
        }
        if (config.exposeMetrics()) {
            out.append("            NativeDubboConsumerClient client")
                    .append(config.retryReads() ? ",\n" : "\n");
        }
        if (config.retryReads()) {
            out.append("            boolean retryReads\n");
        }
        out.append("    ) {\n");
        for (ExecutableElement method : methods) {
            String name = method.getSimpleName().toString();
            out.append("        this.").append(name).append(" = ").append(name).append(";\n");
        }
        if (config.exposeMetrics()) {
            out.append("        this.client = client;\n");
        }
        if (config.retryReads()) {
            out.append("        this.retryReads = retryReads;\n");
        }
        out.append("    }\n\n")
                .append("    public static ").append(generatedName).append(" create(\n")
                .append("            NativeDubboConsumerClient client,\n")
                .append("            DubboConsumerSupport support) {\n")
                .append("        DubboReferenceSpec<").append(serviceType).append("> spec = ")
                .append(referenceExpression(serviceType, config)).append(";\n")
                .append("        return new ").append(generatedName).append("(\n");
        for (int index = 0; index < methods.size(); index++) {
            out.append("                ").append(invokerExpression(methods.get(index)));
            if (index + 1 < methods.size() || config.exposeMetrics() || config.retryReads()) {
                out.append(',');
            }
            out.append('\n');
        }
        if (config.exposeMetrics()) {
            out.append("                client").append(config.retryReads() ? ",\n" : "\n");
        }
        if (config.retryReads()) {
            out.append("                support.booleanProperty(\"")
                    .append(escape(config.retryProperty())).append("\", false)\n");
        }
        out.append("        );\n    }\n\n");

        for (ExecutableElement method : methods) {
            appendAsyncMethod(out, method, config.retryReads(), false);
            if (method.getReturnType().getKind() == TypeKind.ARRAY
                    && ((ArrayType) method.getReturnType()).getComponentType().getKind() == TypeKind.BYTE) {
                appendAsyncMethod(out, method, config.retryReads(), true);
            }
        }
        if (config.exposeMetrics()) {
            out.append("    public String nativeMetricsJson() {\n")
                    .append("        return client.nativeMetricsJson();\n")
                    .append("    }\n\n");
        }
        if (methods.stream().anyMatch(method -> isParameterized(method.getReturnType()))) {
            out.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n")
                    .append("    private static <T> NativeDubboMethodInvoker<T> typed(NativeDubboMethodInvoker invoker) {\n")
                    .append("        return (NativeDubboMethodInvoker<T>) invoker;\n")
                    .append("    }\n\n");
        }
        return out.append("}\n").toString();
    }

    private static String referenceExpression(String serviceType, GenerateNativeDubboClient config) {
        if (config.group().isBlank() && config.version().isBlank()) {
            return "support.reference(" + serviceType + ".class)";
        }
        StringBuilder expression = new StringBuilder("support.referenceBuilder(")
                .append(serviceType).append(".class)");
        if (!config.group().isBlank()) {
            expression.append(".group(\"").append(escape(config.group().trim())).append("\")");
        }
        if (!config.version().isBlank()) {
            expression.append(".version(\"").append(escape(config.version().trim())).append("\")");
        }
        return expression.append(".build()").toString();
    }

    private void appendAsyncMethod(
            StringBuilder out,
            ExecutableElement method,
            boolean retryReads,
            boolean nativeJson) {
        String methodName = method.getSimpleName().toString();
        String resultType = nativeJson ? "NativeResponseHandle" : boxedType(method.getReturnType());
        String suffix = nativeJson ? "NativeJsonAsync" : "Async";
        out.append("    public CompletableFuture<").append(resultType).append("> ")
                .append(methodName).append(suffix).append('(')
                .append(parameterDeclarations(method)).append(") {\n");
        String invocation = methodName + "."
                + (nativeJson ? "invokeNativeJsonResponseAsync" : "invokeAsync")
                + "(" + argumentNames(method) + ")";
        if (retryReads) {
            out.append("        if (!retryReads) {\n")
                    .append("            return ").append(invocation).append(";\n")
                    .append("        }\n")
                    .append("        return DubboReadRetry.onceOnConnectionAbort(true, () -> ")
                    .append(invocation).append(");\n");
        } else {
            out.append("        return ").append(invocation).append(";\n");
        }
        out.append("    }\n\n");
    }

    private String invokerExpression(ExecutableElement method) {
        StringBuilder expression = new StringBuilder();
        boolean parameterized = isParameterized(method.getReturnType());
        if (parameterized) {
            expression.append("typed(");
        }
        expression.append("client.method(spec, \"")
                .append(method.getSimpleName()).append("\", ")
                .append(classLiteral(method.getReturnType()));
        for (VariableElement parameter : method.getParameters()) {
            expression.append(", ").append(parameterClassLiteral(parameter.asType()));
        }
        expression.append(')');
        if (parameterized) {
            expression.append(')');
        }
        return expression.toString();
    }

    private String parameterDeclarations(ExecutableElement method) {
        return method.getParameters().stream()
                .map(parameter -> parameter.asType() + " " + parameter.getSimpleName())
                .collect(Collectors.joining(", "));
    }

    private String argumentNames(ExecutableElement method) {
        return method.getParameters().stream()
                .map(parameter -> parameter.getSimpleName().toString())
                .collect(Collectors.joining(", "));
    }

    private String boxedType(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return processingEnv.getTypeUtils().boxedClass((PrimitiveType) type).getQualifiedName().toString();
        }
        return type.toString();
    }

    private String classLiteral(TypeMirror type) {
        TypeMirror classType = type.getKind().isPrimitive()
                ? processingEnv.getTypeUtils().boxedClass((PrimitiveType) type).asType()
                : processingEnv.getTypeUtils().erasure(type);
        return classType + ".class";
    }

    private String parameterClassLiteral(TypeMirror type) {
        TypeMirror classType = type.getKind().isPrimitive()
                ? type
                : processingEnv.getTypeUtils().erasure(type);
        return classType + ".class";
    }

    private static boolean isParameterized(TypeMirror type) {
        return type.toString().indexOf('<') >= 0;
    }

    private static TypeMirror serviceMirror(GenerateNativeDubboClient annotation) {
        try {
            annotation.service();
            throw new IllegalStateException("service type mirror was not provided by javac");
        } catch (MirroredTypeException mirrored) {
            return mirrored.getTypeMirror();
        }
    }

    private static String defaultGeneratedName(String definitionName) {
        return definitionName.endsWith("Definition")
                ? definitionName.substring(0, definitionName.length() - "Definition".length())
                : definitionName + "Generated";
    }

    private static void rejectOverloads(List<ExecutableElement> methods) {
        Set<String> names = new HashSet<>();
        for (ExecutableElement method : methods) {
            String name = method.getSimpleName().toString();
            if (!names.add(name)) {
                throw new IllegalArgumentException("overloaded service methods are not supported: " + name);
            }
            if (method.getReturnType().getKind() == TypeKind.VOID) {
                throw new IllegalArgumentException("void service methods are not supported: " + name);
            }
            if (!method.getTypeParameters().isEmpty()) {
                throw new IllegalArgumentException("generic service methods are not supported: " + name);
            }
            if (containsTypeVariable(method.getReturnType())
                    || method.getParameters().stream()
                    .anyMatch(parameter -> containsTypeVariable(parameter.asType()))) {
                throw new IllegalArgumentException(
                        "unresolved generic service types are not supported: " + methodSignature(method));
            }
        }
    }

    private static boolean containsTypeVariable(TypeMirror type) {
        return switch (type.getKind()) {
            case TYPEVAR, WILDCARD, INTERSECTION, UNION -> true;
            case ARRAY -> containsTypeVariable(((ArrayType) type).getComponentType());
            case DECLARED -> ((javax.lang.model.type.DeclaredType) type).getTypeArguments().stream()
                    .anyMatch(NativeDubboClientProcessor::containsTypeVariable);
            default -> false;
        };
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
