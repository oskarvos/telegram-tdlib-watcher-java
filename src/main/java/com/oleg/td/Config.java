package com.oleg.td;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
@JsonIgnoreProperties(ignoreUnknown=true)
public class Config {
  public Tdlib tdlib; public List<String> groups; public List<PatternDef> patterns; public boolean case_insensitive=true;
  @JsonIgnoreProperties(ignoreUnknown=true) public static class Tdlib {
    public int api_id; public String api_hash; public String lib_path; public String database_directory="tdlib"; public String files_directory="tdlib/files";
  }
  @JsonIgnoreProperties(ignoreUnknown=true) public static class PatternDef { public String name; public String regex; }
}
