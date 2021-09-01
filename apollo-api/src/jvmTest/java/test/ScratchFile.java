package test;

import com.apollographql.apollo3.api.Adapter;
import com.apollographql.apollo3.api.CustomScalarAdapters;
import com.apollographql.apollo3.api.json.JsonReader;
import com.apollographql.apollo3.api.json.JsonWriter;
import com.google.common.collect.ImmutableList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

enum ScratchFile implements Adapter<Object> {
  // REMINDER

  INSTANCE;

  Test toto() {
    return Test.INSTANCE;
  }

  enum Test implements Adapter<String> {
    INSTANCE;

    private void listOf(String... strings) {

    }

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
