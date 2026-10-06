package net.cumba.corej.editor.engine;

import java.io.InputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The engine speaks Jackson 2 ({@code com.fasterxml.jackson}); the web layer speaks Jackson 3
 * ({@code tools.jackson}, Spring Boot 4). Both live on the class path; a node crosses from one to
 * the other through its text — never by reference, or the MVC converter serialises the Jackson 2
 * node as a bean.
 */
public final class Json
{

    private static final JsonMapper JSON3 = JsonMapper.builder().build();

    private Json()
    {
    }


    public static JsonNode toJackson3(com.fasterxml.jackson.databind.JsonNode aNode)
    {
        return JSON3.readTree(aNode.toString());
    }


    public static JsonNode parse(String aJson)
    {
        return JSON3.readTree(aJson);
    }


    public static String write(Object aValue)
    {
        return JSON3.writeValueAsString(aValue);
    }


    public static <T> T read(String aJson, Class<T> aType)
    {
        return JSON3.readValue(aJson, aType);
    }


    /** Reads from a stream (a worker's report, without an intermediate String copy). */
    public static <T> T read(InputStream aJson, Class<T> aType)
    {
        return JSON3.readValue(aJson, aType);
    }


    /** The value as UTF-8 JSON bytes (a worker's job, written straight to its stdin). */
    public static byte[] writeBytes(Object aValue)
    {
        return JSON3.writeValueAsBytes(aValue);
    }
}
