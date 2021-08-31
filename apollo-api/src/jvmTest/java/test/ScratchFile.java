package test;

import com.apollographql.apollo3.api.Adapter;
import com.apollographql.apollo3.api.CustomScalarAdapters;
import com.apollographql.apollo3.api.json.JsonReader;
import com.apollographql.apollo3.api.json.JsonWriter;
import org.jetbrains.annotations.NotNull;

enum ScratchFile implements Adapter<Object> {
  INSTANCE;

  enum Test implements  Adapter<String> {
    INSTANCE;

    @Override public String fromJson(@NotNull JsonReader reader, @NotNull CustomScalarAdapters customScalarAdapters) {
      return null;
    }

    @Override public void toJson(@NotNull JsonWriter writer, @NotNull CustomScalarAdapters customScalarAdapters, String value) {

    }
  }
  @Override public void toJson(@NotNull JsonWriter writer, @NotNull CustomScalarAdapters customScalarAdapters, Object value) {

  }

  @Override public java.lang.Object fromJson(@NotNull JsonReader reader, @NotNull CustomScalarAdapters customScalarAdapters) {
    return null;
  }
}
