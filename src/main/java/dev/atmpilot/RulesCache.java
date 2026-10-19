package dev.atmpilot;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;

/** Cache parsing, not authorization: check file identity/mtime/size on every access and fail closed. */
public final class RulesCache {
    private RulesCache(){}
    private static Path source;
    private static FileTime modified;
    private static Object fileKey;
    private static long size;
    private static JsonObject value;
    public static JsonObject read(Path path)throws Exception{
        var attrs=Files.readAttributes(path,BasicFileAttributes.class);
        if(!attrs.isRegularFile()||attrs.size()>100_000)throw new IllegalStateException("Server rules file is invalid or too large");
        if(value==null||!path.equals(source)||!attrs.lastModifiedTime().equals(modified)||attrs.size()!=size||!java.util.Objects.equals(attrs.fileKey(),fileKey)){
            JsonObject parsed=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
            if(Jsons.integer(parsed,"version",0)!=1)throw new IllegalStateException("Unsupported server rules version");
            value=parsed;source=path;modified=attrs.lastModifiedTime();size=attrs.size();fileKey=attrs.fileKey();
        }
        return value.deepCopy();
    }
}
