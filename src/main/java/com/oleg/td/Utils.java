package com.oleg.td;
import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper; import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream; import java.io.IOException;
public class Utils {
  private static final ObjectMapper OM=new ObjectMapper();
  public static ObjectNode obj(String t){ ObjectNode o=OM.createObjectNode(); o.put("@type", t); return o; }
  public static JsonNode parse(String s){ try{return OM.readTree(s);}catch(IOException e){throw new RuntimeException(e);} }
  public static <T>T loadJsonResource(String p, Class<T> c){ try(InputStream is=Utils.class.getResourceAsStream(p)){ if(is==null) throw new RuntimeException("Resource not found: "+p); return OM.readValue(is,c);}catch(IOException e){throw new RuntimeException(e);} }
}
