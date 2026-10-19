package dev.atmpilot;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import net.minecraft.client.Minecraft;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Authenticated loopback transport. All world access is marshalled to the Minecraft client thread. */
public final class PilotHttp {
    private final PilotController controller;
    private final HttpServer server;
    private final String token;
    public PilotHttp(PilotController controller)throws Exception {
        this.controller=controller;
        HttpServer created;
        try{created=HttpServer.create(new InetSocketAddress("127.0.0.1",18765),16);}
        catch(BindException busy){created=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);}
        server=created;byte[] random=new byte[32];new SecureRandom().nextBytes(random);token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server.setExecutor(Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"ATM-Pilot-HTTP");t.setDaemon(true);return t;}));
        server.createContext("/",this::handle);server.start();Files.createDirectories(controller.directory);
        Files.writeString(controller.directory.resolve("session.json"),Jsons.GSON.toJson(Jsons.obj("url","http://127.0.0.1:"+server.getAddress().getPort(),
            "token",token,"pid",ProcessHandle.current().pid(),"version","0.1.0")),StandardCharsets.UTF_8);
        Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0),"ATM-Pilot-Shutdown"));
        PilotMod.LOG.info("ATM Pilot listening on 127.0.0.1:{}; session metadata saved locally",server.getAddress().getPort());
    }
    private <T>T client(Supplier<T> operation)throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{if(future.isCancelled())return;try{future.complete(operation.get());}catch(Throwable e){future.completeExceptionally(e);}});
        try{return future.get(15,TimeUnit.SECONDS);}catch(ExecutionException e){
            if(e.getCause() instanceof RuntimeException runtime)throw runtime;throw new RuntimeException(e.getCause());
        }catch(TimeoutException e){future.cancel(false);throw new IllegalStateException("Client thread timed out; inspect current job before retrying any mutation");}
    }
    private void handle(HttpExchange exchange) {
        try{
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if(exchange.getRequestHeaders().containsKey("Origin")||authorization==null||!MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))){send(exchange,403,Jsons.obj("error","Forbidden"));return;}
            String host=exchange.getRequestHeaders().getFirst("Host");
            if(host==null||!(host.equals("127.0.0.1:"+server.getAddress().getPort())||host.equals("localhost:"+server.getAddress().getPort()))){send(exchange,403,Jsons.obj("error","Invalid host"));return;}
            String path=exchange.getRequestURI().getPath();Map<String,String> query=query(exchange.getRequestURI().getRawQuery());
            String method=exchange.getRequestMethod();JsonElement result;
            if(method.equals("GET")){
                switch(path){
                    case "/state" -> result=client(()->WorldView.state(controller));
                    case "/live" -> result=client(new LiveQuery(controller,Integer.parseInt(query.getOrDefault("radius","16"))));
                    case "/scan" -> result=client(()->WorldView.scan(Integer.parseInt(query.getOrDefault("radius","16"))));
                    case "/region" -> result=client(()->WorldView.region(query));
                    case "/entities" -> result=client(()->WorldView.entities(Integer.parseInt(query.getOrDefault("radius","32"))));
                    case "/screen" -> result=client(ScreenAccess::describe);
                    case "/job" -> result=client(controller::status);
                    case "/vanilla" -> result=client(dev.atmpilot.vanilla.VanillaRun::status);
                    case "/containers" -> result=client(()->Jsons.GSON.toJsonTree(controller.observedContainers));
                    case "/quests" -> result=client(()->QuestAccess.snapshot(query.getOrDefault("filter",""),
                        Integer.parseInt(query.getOrDefault("offset","0")),Integer.parseInt(query.getOrDefault("limit","30"))));
                    case "/recipes" -> result=client(()->recipes(query));
                    case "/recipe" -> result=client(()->{OptionalJei.require();return Jsons.GSON.toJsonTree(JeiBridge.entry(query.getOrDefault("id","")).recipe());});
                    case "/plan" -> {
                        var input=client(controller::planningInput);
                        result=PilotController.plan(input,query.getOrDefault("item","allthetweaks:atm_star"),Long.parseLong(query.getOrDefault("count","1")),query.getOrDefault("components",""));
                        controller.savePlan(result.getAsJsonObject());
                    }
                    case "/roadmap" -> {try(var stream=PilotHttp.class.getResourceAsStream("/atmpilot/atm10-v8.2.json")){
                        if(stream==null)throw new IllegalStateException("Roadmap resource missing");result=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));}}
                    case "/screenshot" -> {
                        Path file=controller.directory.resolve("screenshot.png");
                        client(()->{try{ScreenAccess.screenshot(file);return true;}catch(Exception e){throw new IllegalStateException(e);}});
                        byte[] bytes=Files.readAllBytes(file);exchange.getResponseHeaders().set("Content-Type","image/png");
                        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;
                    }
                    default -> {send(exchange,404,Jsons.obj("error","Unknown endpoint"));return;}
                }
            }else if(method.equals("POST")){
                byte[] bytes=exchange.getRequestBody().readNBytes(2_000_001);
                if(bytes.length>2_000_000){send(exchange,413,Jsons.obj("error","Request too large"));return;}
                JsonObject body=bytes.length==0?new JsonObject():JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                switch(path){
                    case "/vanilla/start" -> result=client(()->dev.atmpilot.vanilla.VanillaRun.start(body));
                    case "/vanilla/stop" -> result=client(()->{dev.atmpilot.vanilla.VanillaRun.stop("Stopped by user");return dev.atmpilot.vanilla.VanillaRun.status();});
                    case "/vanilla/leave" -> result=client(dev.atmpilot.vanilla.VanillaRun::leave);
                    case "/vanilla/ui" -> result=client(new dev.atmpilot.vanilla.VanillaUi(body));
                    case "/connection/ui" -> result=client(new OfflineUi(body));
                    case "/stop" -> result=client(()->{controller.stop("Stopped by controller");return controller.status();});
                    case "/cursor/return" -> result=client(new CursorReturn(body));
                    case "/jobs" -> result=client(()->{
                        List<PilotController.Job> jobs=new ArrayList<>();
                        try{
                        if(body.has("steps"))for(JsonElement step:body.getAsJsonArray("steps"))jobs.add(PilotJobs.create(step.getAsJsonObject()));
                        else jobs.add(PilotJobs.create(body));
                        if(controller.running()||jobs.isEmpty()||jobs.size()>4096||!Actions.mc().player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Cannot replace a running job or dispatch an invalid/occupied-cursor workflow");
                        try{Files.writeString(controller.directory.resolve("last-dispatch.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",java.time.Instant.now().toString(),"dimension",Actions.mc().level.dimension().location().toString(),"request",body)),StandardCharsets.UTF_8);}
                        catch(java.io.IOException e){throw new IllegalStateException("Cannot save the workflow checkpoint; no new job was started",e);}
                        String id=controller.start(jobs);return Jsons.obj("id",id,"accepted",jobs.size(),"state","running");
                        }catch(RuntimeException|LinkageError rejected){for(var job:jobs)try{job.cancel();}catch(Exception ignored){}throw rejected;}
                    });
                    default -> {send(exchange,404,Jsons.obj("error","Unknown endpoint"));return;}
                }
            }else{send(exchange,405,Jsons.obj("error","GET or POST required"));return;}
            send(exchange,200,result);
        }catch(Exception e){
            try{send(exchange,e instanceof IllegalArgumentException?400:409,Jsons.obj("error",e.getClass().getSimpleName()+": "+e.getMessage()));}
            catch(Exception ignored){}
        }finally{exchange.close();}
    }
    private static JsonObject recipes(Map<String,String> query){
        OptionalJei.require();
        String item=query.getOrDefault("item",""),type=query.getOrDefault("type","");
        int offset=Math.max(0,Integer.parseInt(query.getOrDefault("offset","0"))),limit=Math.max(1,Math.min(100,Integer.parseInt(query.getOrDefault("limit","20"))));
        var matches=JeiBridge.entries().stream().map(JeiBridge.Entry::recipe)
            .filter(r->type.isEmpty()||r.type().contains(type))
            .filter(r->item.isEmpty()||r.outputs().stream().anyMatch(a->a.material().id().equals(item))).toList();
        return Jsons.obj("catalog",JeiBridge.status(),"total",matches.size(),"recipes",matches.stream().skip(offset).limit(limit).toList());
    }
    private static Map<String,String> query(String input){
        Map<String,String> result=new HashMap<>();if(input==null)return result;
        for(String part:input.split("&")){String[] pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==1?"":URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;
    }
    private static void send(HttpExchange exchange,int status,JsonElement value)throws Exception {
        byte[] bytes=Jsons.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
