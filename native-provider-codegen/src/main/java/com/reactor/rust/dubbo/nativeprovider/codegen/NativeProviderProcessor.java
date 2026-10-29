package com.reactor.rust.dubbo.nativeprovider.codegen;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/** Closed-schema provider generation. The shared runtime owns parsing/transport, not this processor. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.reactor.rust.dubbo.provider.GenerateNativeProvider")
public final class NativeProviderProcessor extends AbstractProcessor {
    private static final String ANNOTATION = "com.reactor.rust.dubbo.provider.GenerateNativeProvider";
    private final Set<String> generated = new HashSet<>();

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (var annotation : annotations) for (var element : round.getElementsAnnotatedWith(annotation)) {
            try { generate((TypeElement) element); }
            catch (IllegalArgumentException | IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        return true;
    }

    private void generate(TypeElement definition) throws IOException {
        var mirror = definition.getAnnotationMirrors().stream().filter(a -> a.getAnnotationType().toString().equals(ANNOTATION)).findFirst().orElseThrow();
        Map<String, Object> values = new HashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).forEach((k,v) -> values.put(k.getSimpleName().toString(), v.getValue()));
        TypeElement service = (TypeElement) processingEnv.getTypeUtils().asElement((TypeMirror) values.get("service"));
        require(service != null && service.getKind() == ElementKind.INTERFACE && service.getModifiers().contains(Modifier.PUBLIC)
                && service.getTypeParameters().isEmpty(), "Native service must be a public nongeneric interface");
        String pkg = processingEnv.getElementUtils().getPackageOf(definition).getQualifiedName().toString();
        String simple = definition.getSimpleName() + "__NativeProvider";
        String qualified = pkg.isEmpty() ? simple : pkg + '.' + simple;
        if (!generated.add(qualified)) return;
        List<ExecutableElement> methods = ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(service)).stream()
                .filter(m -> !m.getModifiers().contains(Modifier.STATIC) && !m.getEnclosingElement().toString().equals("java.lang.Object"))
                .sorted(Comparator.comparing(m -> m.getSimpleName().toString())).toList();
        require(!methods.isEmpty() && methods.size() <= 64, "Native service needs 1..64 methods");
        Set<String> names = new HashSet<>();
        Codec codec = new Codec();
        for (var method : methods) {
            require(names.add(method.getSimpleName().toString()), "Overloaded methods are not supported: " + method.getSimpleName());
            require(method.getTypeParameters().isEmpty(), "Generic methods are not supported");
            method.getParameters().forEach(p -> codec.register(p.asType(), new HashSet<>(), 0));
            codec.register(method.getReturnType(), new HashSet<>(), 0);
        }
        String type = service.getQualifiedName().toString();
        StringBuilder out = new StringBuilder();
        if (!pkg.isEmpty()) line(out, "package " + pkg + ";");
        line(out, "import com.reactor.rust.dubbo.provider.*;");
        line(out, "import com.reactor.rust.dubbo.runtime.*;");
        line(out, "public final class " + simple + " implements NativeServiceContract<" + type + "> {");
        line(out, " public static final " + simple + " CONTRACT = new " + simple + "();");
        line(out, " private " + simple + "() {}");
        line(out, " public static NativeProviderBinding<" + type + "> bind(" + type + " target, PlainDubboProvider.ServiceExecutionConfig limits) { return new NativeProviderBinding<>(CONTRACT,target,limits); }");
        line(out, " public Class<" + type + "> serviceType() { return " + type + ".class; }");
        line(out, " public String group() { return " + quote(values.get("group").toString()) + "; }");
        line(out, " public String version() { return " + quote(values.get("version").toString()) + "; }");
        line(out, " private static final java.util.List<NativeServiceContract.Method> METHODS = java.util.List.of(");
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            String classes = String.join(",", method.getParameters().stream().map(p -> processingEnv.getTypeUtils().erasure(p.asType()) + ".class").toList());
            String descriptor = "(" + String.join("", method.getParameters().stream().map(p -> descriptor(p.asType())).toList()) + ")" + descriptor(method.getReturnType());
            line(out, " new NativeServiceContract.Method(" + (i+1) + "," + quote(method.getSimpleName().toString()) + "," + quote(descriptor) + ",new Class<?>[]{" + classes + "})" + (i == methods.size()-1 ? ");" : ","));
        }
        line(out, " public java.util.List<NativeServiceContract.Method> methods() { return METHODS; }");
        line(out, " public ProviderMethodDispatcher<" + type + "> dispatcher() { return (target,name,types,args) -> { switch(name) {");
        for (var method : methods) {
            String args = arguments(method);
            line(out, " case " + quote(method.getSimpleName().toString()) + " -> { if(args.length!=" + method.getParameters().size() + ") throw new IllegalArgumentException(\"Wrong arity\");");
            if (method.getReturnType().getKind() == TypeKind.VOID) line(out, " target." + method.getSimpleName() + "(" + args + "); return null; }");
            else line(out, " return target." + method.getSimpleName() + "(" + args + "); }");
        }
        line(out, " default -> throw new IllegalArgumentException(\"Unknown generated method\"); } }; }");
        line(out, " public Object[] readArguments(int id,Hessian2Input in) { return switch(id) {");
        for (int i=0;i<methods.size();i++) line(out, " case " + (i+1) + " -> new Object[]{" + String.join(",", methods.get(i).getParameters().stream().map(p -> codec.read(p.asType())).toList()) + "};");
        line(out, " default -> throw new DubboCodecException(\"Unknown method id\"); }; }");
        line(out, " public void writeArguments(int id,Hessian2Output out,Object[] args) { switch(id) {");
        for (int i=0;i<methods.size();i++) {
            var method=methods.get(i); line(out, " case " + (i+1) + " -> {");
            for (int a=0;a<method.getParameters().size();a++) {
                var t=method.getParameters().get(a).asType(); line(out, codec.write(t, cast(t,"args["+a+"]")));
            }
            line(out, " }");
        }
        line(out, " default -> throw new DubboCodecException(\"Unknown method id\"); } }");
        line(out, " public Object readResult(int id,Hessian2Input in) { return switch(id) {");
        for (int i=0;i<methods.size();i++) { var t=methods.get(i).getReturnType(); line(out, " case "+(i+1)+" -> "+(t.getKind()==TypeKind.VOID?"null":codec.read(t))+";"); }
        line(out, " default -> throw new DubboCodecException(\"Unknown method id\"); }; }");
        line(out, " public void writeResult(int id,Hessian2Output out,Object value) { switch(id) {");
        for (int i=0;i<methods.size();i++) { var t=methods.get(i).getReturnType(); line(out," case "+(i+1)+" -> {"+(t.getKind()==TypeKind.VOID?"":codec.write(t,cast(t,"value")))+"}"); }
        line(out, " default -> throw new DubboCodecException(\"Unknown method id\"); } }");
        codec.emit(out);
        line(out, "}");
        try (Writer writer = processingEnv.getFiler().createSourceFile(qualified, definition, service).openWriter()) { writer.write(out.toString()); }
    }

    private String arguments(ExecutableElement method) {
        List<String> values=new ArrayList<>();
        for(int i=0;i<method.getParameters().size();i++) values.add(cast(method.getParameters().get(i).asType(), "args["+i+"]"));
        return String.join(",",values);
    }
    private static String cast(TypeMirror type,String value) { return "("+type+")"+value; }
    private String descriptor(TypeMirror t) {
        return switch(t.getKind()) {
            case VOID -> "V"; case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case CHAR -> "C";
            case INT -> "I"; case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D";
            case ARRAY -> "["+descriptor(((ArrayType)t).getComponentType());
            case DECLARED -> "L"+binary((TypeElement)((DeclaredType)t).asElement()).replace('.','/')+";";
            default -> throw new IllegalArgumentException("Unsupported descriptor: "+t);
        };
    }
    private String binary(TypeElement t) { return processingEnv.getElementUtils().getBinaryName(t).toString(); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\""; }
    private static void line(StringBuilder s,String line) { s.append(line).append('\n'); }

    private final class Codec {
        private final Map<String,TypeMirror> complex = new LinkedHashMap<>();
        private final Map<String,Integer> ids = new HashMap<>();
        void register(TypeMirror t,Set<String> path,int depth) {
            require(depth<=16,"Codec type graph exceeds depth16");
            if(simple(t)) return;
            String key=t.toString();
            require(!path.contains(key),"Cyclic DTO type: "+key);
            if(ids.containsKey(key)) return;
            require(complex.size()<128,"At most128 codec types");
            path=new HashSet<>(path); path.add(key);
            if(t instanceof DeclaredType d) {
                TypeElement e=(TypeElement)d.asElement(); String name=e.getQualifiedName().toString();
                require(e.getModifiers().contains(Modifier.PUBLIC),"Codec types must be public: "+t);
                if(name.equals("java.util.List")) {
                    require(d.getTypeArguments().size()==1,"List requires concrete element type");
                    register(d.getTypeArguments().getFirst(),path,depth+1);
                } else if(name.equals("java.util.Map")) {
                    require(d.getTypeArguments().size()==2 && d.getTypeArguments().getFirst().toString().equals("java.lang.String"),"Only Map<String,V> is supported");
                    register(d.getTypeArguments().get(1),path,depth+1);
                } else if(e.getKind()==ElementKind.RECORD) {
                    require(d.getTypeArguments().isEmpty() && e.getTypeParameters().isEmpty(),"Generic records are not supported");
                    require(e.getRecordComponents().size()<=64,"Record needs <=64 fields");
                    for(var field:e.getRecordComponents()) register(field.asType(),path,depth+1);
                } else if(e.getKind()==ElementKind.ENUM) {
                    require(e.getEnclosedElements().stream().filter(f->f.getKind()==ElementKind.ENUM_CONSTANT).count()<=256,"Enum exceeds256 values");
                } else throw new IllegalArgumentException("Unsupported static codec type: "+t);
            } else throw new IllegalArgumentException("Unsupported static codec type: "+t);
            require(complex.size()<128,"At most128 codec types");
            ids.put(key,ids.size()); complex.put(key,t);
        }
        private String scalar(TypeMirror t) {
            return switch(t.toString()) {
                case "java.lang.Boolean" -> "boolean"; case "java.lang.Byte" -> "byte"; case "java.lang.Short" -> "short";
                case "java.lang.Character" -> "char"; case "java.lang.Integer" -> "int"; case "java.lang.Long" -> "long";
                case "java.lang.Float" -> "float"; case "java.lang.Double" -> "double"; default -> t.toString();
            };
        }
        private boolean simple(TypeMirror t) { return t.getKind().isPrimitive() || t.getKind()==TypeKind.VOID || Set.of("java.lang.String","byte[]","java.lang.Boolean","java.lang.Byte","java.lang.Short","java.lang.Character","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double").contains(t.toString()); }
        String read(TypeMirror t) {
            String op=switch(scalar(t)) {
                case "boolean" -> "in.readBoolean()"; case "int" -> "in.readInt()"; case "long" -> "in.readLong()";
                case "double" -> "in.readDouble()"; case "float" -> "(float)in.readDouble()";
                case "byte" -> "(byte)NativeProviderCodec.checkedInt(in.readInt(),-128,127)";
                case "short" -> "(short)NativeProviderCodec.checkedInt(in.readInt(),-32768,32767)";
                case "char" -> "(char)NativeProviderCodec.checkedInt(in.readInt(),0,65535)";
                case "java.lang.String" -> "in.readString()"; case "byte[]" -> "in.readBinary()";
                default -> "read"+ids.get(t.toString())+"(in)";
            };
            return !t.getKind().isPrimitive() && !scalar(t).equals(t.toString()) ? "(in.readNull()?null:"+op+")" : op;
        }
        String write(TypeMirror t,String value) {
            String scalar=scalar(t);
            String op=switch(scalar) {
                case "boolean" -> "out.writeBoolean("+value+");";
                case "byte","short","char","int" -> "out.writeInt("+value+");";
                case "long" -> "out.writeLong("+value+");";
                case "float","double" -> "NativeProviderCodec.writeDouble(out,"+value+");";
                case "java.lang.String" -> "out.writeString("+value+");"; case "byte[]" -> "out.writeBinary("+value+");";
                default -> "write"+ids.get(t.toString())+"(out,"+value+");";
            };
            return !t.getKind().isPrimitive() && !scalar.equals(t.toString()) ? "if("+value+"==null)out.writeNull();else {"+op+"}" : op;
        }
        void emit(StringBuilder s) {
            for(var t:complex.values()) {
                var d=(DeclaredType)t; var e=(TypeElement)d.asElement(); int id=ids.get(t.toString());
                String name=e.getQualifiedName().toString();
                if(name.equals("java.util.List") || name.equals("java.util.Map")) { emitCollection(s,d,id); continue; }
                if(e.getKind()==ElementKind.ENUM) { emitEnum(s,e,id); continue; }
                var fields=e.getRecordComponents();
                line(s,"private static final String[] F"+id+"=new String[]{"+String.join(",",fields.stream().map(f->quote(f.getSimpleName().toString())).toList())+"};");
                line(s,"private static "+t+" read"+id+"(Hessian2Input in) { if(in.readNull())return null; if(in.isReference())return ("+t+")in.readReference(); int ref=in.referenceCount(); String[] fs=in.readObjectFields("+quote(binary(e))+"); if(fs.length!="+fields.size()+")throw new DubboCodecException(\"Schema mismatch\"); long seen=0;");
                for(int i=0;i<fields.size();i++) { var f=fields.get(i); line(s,f.asType()+" v"+i+"="+defaultValue(f.asType())+";"); }
                line(s,"for(String f:fs) { switch(f) {");
                for(int i=0;i<fields.size();i++) {var f=fields.get(i);long bit=1L<<i;line(s,"case "+quote(f.getSimpleName().toString())+" -> { if((seen&"+bit+"L)!=0)throw new DubboCodecException(\"Duplicate field\");seen|="+bit+"L;v"+i+"="+read(f.asType())+"; }");}
                line(s,"default -> throw new DubboCodecException(\"Unknown record field\"); } }");
                long mask=fields.size()==64?-1L:(1L<<fields.size())-1;
                line(s,"if(seen!="+mask+"L)throw new DubboCodecException(\"Missing record field\");return in.completeReference(ref,new "+t+"("+String.join(",",java.util.stream.IntStream.range(0,fields.size()).mapToObj(i->"v"+i).toList())+")); }");
                line(s,"private static void write"+id+"(Hessian2Output out,"+t+" value) { if(value==null){out.writeNull();return;}out.writeObjectStart("+quote(binary(e))+",F"+id+");");
                for(var f:fields)line(s,write(f.asType(),"value."+f.getSimpleName()+"()"));
                line(s,"}");
            }
        }
        private void emitCollection(StringBuilder s,DeclaredType t,int id) {
            boolean list=((TypeElement)t.asElement()).getQualifiedName().contentEquals("java.util.List");
            TypeMirror value=t.getTypeArguments().get(list?0:1);
            line(s,"private static "+t+" read"+id+"(Hessian2Input in) { if(in.readNull())return null;if(in.isReference())throw new DubboCodecException(\"Collection aliases are unsupported\");int ref=in.referenceCount();");
            if(list) {
                line(s,"int size=in.readListStart(); java.util.ArrayList<"+value+"> values=new java.util.ArrayList<>(Math.max(size,0));int count=0;while(in.hasMoreListEntries(size,count)){NativeProviderCodec.collectionSize(++count,4096);values.add("+read(value)+");}in.readListEnd(size);return in.completeReference(ref,values);}");
                line(s,"private static void write"+id+"(Hessian2Output out,"+t+" values){if(values==null){out.writeNull();return;}int size=values.size();NativeProviderCodec.collectionSize(size,4096);out.writeListStart(size);for(int i=0;i<size;i++){"+write(value,"values.get(i)")+"}}");
            } else {
                line(s,"in.readMapStart();java.util.Map<String,"+value+"> values=new java.util.LinkedHashMap<>();int count=0;while(in.hasMoreMapEntries()){NativeProviderCodec.collectionSize(++count,4096);String k=in.readString();if(k==null||values.containsKey(k))throw new DubboCodecException(\"Invalid map key\");values.put(k,"+read(value)+");}in.readMapEnd();return in.completeReference(ref,values);}");
                line(s,"private static void write"+id+"(Hessian2Output out,"+t+" values){if(values==null){out.writeNull();return;}NativeProviderCodec.collectionSize(values.size(),4096);out.writeMapStart();int count=0;for(var e:values.entrySet()){NativeProviderCodec.collectionSize(++count,4096);if(e.getKey()==null)throw new DubboCodecException(\"Null map key\");out.writeString(e.getKey());"+write(value,"e.getValue()")+"}out.writeMapEnd();}");
            }
        }
        private void emitEnum(StringBuilder s,TypeElement e,int id) {
            String type=e.getQualifiedName().toString();
            line(s,"private static "+type+" read"+id+"(Hessian2Input in){if(in.readNull())return null;if(in.isReference())return ("+type+")in.readReference();int ref=in.referenceCount();in.expectObject("+quote(binary(e))+",new String[]{\"name\"});"+type+" value=switch(in.readString()){");
            for(var f:e.getEnclosedElements())if(f.getKind()==ElementKind.ENUM_CONSTANT)line(s,"case "+quote(f.getSimpleName().toString())+" -> "+type+"."+f.getSimpleName()+";");
            line(s,"default -> throw new DubboCodecException(\"Unknown enum constant\");};return in.completeReference(ref,value);}");
            line(s,"private static void write"+id+"(Hessian2Output out,"+type+" value){if(value==null){out.writeNull();return;}out.writeObjectStart("+quote(binary(e))+",new String[]{\"name\"});out.writeString(value.name());}");
        }
        private String defaultValue(TypeMirror t) { return t.getKind()==TypeKind.BOOLEAN?"false":t.getKind().isPrimitive()?"0":"null"; }
    }
}
