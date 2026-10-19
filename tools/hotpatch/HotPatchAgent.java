package dev.atmpilot.tools;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Standard JVM method-body replacement, restricted to the ATM Pilot namespace. */
public final class HotPatchAgent {
    public static void agentmain(String argument, Instrumentation instrumentation) throws Exception {
        Path requestFile=Path.of(argument).toAbsolutePath();
        Properties request=new Properties(),result=new Properties();
        try(var reader=Files.newBufferedReader(requestFile,StandardCharsets.UTF_8)){request.load(reader);}
        Path resultFile=Path.of(request.getProperty("result"));
        result.setProperty("patchId",request.getProperty("patchId",""));
        try {
            if(!instrumentation.isRedefineClassesSupported())throw new UnsupportedOperationException("JVM does not support class redefinition");
            Map<String,List<Class<?>>> loaded=new HashMap<>();ClassLoader pilotLoader=null;Class<?> anchor=null;
            for(Class<?> type:instrumentation.getAllLoadedClasses()) {
                if(type.getName().startsWith("dev.atmpilot.")&&!type.getName().startsWith("dev.atmpilot.tools.")){
                    loaded.computeIfAbsent(type.getName(),ignored->new ArrayList<>()).add(type);
                    if(type.getName().equals("dev.atmpilot.WorldView")){pilotLoader=type.getClassLoader();anchor=type;}
                }
            }
            if(pilotLoader==null)throw new IllegalStateException("Running ATM Pilot class loader not found");
            int count=Integer.parseInt(request.getProperty("classCount","0"));
            if(count<1||count>64)throw new IllegalArgumentException("Expected 1..64 classes");
            List<ClassDefinition> definitions=new ArrayList<>();Set<String> names=new HashSet<>(),added=new HashSet<>();
            for(int i=0;i<count;i++){
                String name=request.getProperty("class."+i+".name","");
                if(!name.matches("dev\\.atmpilot\\.[A-Za-z_$][A-Za-z0-9_$]*")||!names.add(name))
                    throw new IllegalArgumentException("Invalid or duplicate ATM Pilot class name");
                List<Class<?>> matches=loaded.get(name);
                Path file=Path.of(request.getProperty("class."+i+".file"));
                if(Files.size(file)>4_000_000)throw new IllegalArgumentException("Class file too large");
                byte[] bytes=Files.readAllBytes(file);
                Class<?> target;
                if(matches==null){
                    try{target=Class.forName(name,false,pilotLoader);}
                    catch(ClassNotFoundException absent){
                        var agentModule=HotPatchAgent.class.getModule();var targetModule=anchor.getModule();
                        if(!targetModule.isOpen("dev.atmpilot",agentModule))instrumentation.redefineModule(targetModule,Set.of(),Map.of(),Map.of("dev.atmpilot",Set.of(agentModule)),Set.of(),Map.of());
                        target=MethodHandles.privateLookupIn(anchor,MethodHandles.lookup()).defineClass(bytes);
                        if(!target.getName().equals(name))throw new IllegalArgumentException("Defined class name mismatch");
                        added.add(name);continue;
                    }
                }
                else{if(matches.size()!=1)throw new IllegalStateException("Ambiguous class loader: "+name);target=matches.getFirst();}
                if(!instrumentation.isModifiableClass(target))throw new IllegalStateException("Class is not modifiable: "+name);
                definitions.add(new ClassDefinition(target,bytes));
            }
            // The JVM validates the entire set before installing it. Unsupported schema changes fail atomically.
            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            result.setProperty("success","true");result.setProperty("classes",String.join(",",names));
            result.setProperty("newHelperClasses",String.join(",",added));
            result.setProperty("message","Method bodies replaced; fields and runtime state retained");
        }catch(Throwable error){
            result.setProperty("success","false");result.setProperty("message",error.toString());
        }
        try(var writer=Files.newBufferedWriter(resultFile,StandardCharsets.UTF_8)){result.store(writer,"ATM Pilot hot patch result");}
    }
}
