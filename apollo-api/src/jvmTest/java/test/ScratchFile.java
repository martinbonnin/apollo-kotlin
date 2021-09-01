package test;

import com.apollographql.apollo3.api.Adapter;
import com.apollographql.apollo3.api.BooleanExpression;
import com.apollographql.apollo3.api.BooleanExpressionKt;
import com.apollographql.apollo3.api.CustomScalarAdapters;
import com.apollographql.apollo3.api.json.JsonReader;
import com.apollographql.apollo3.api.json.JsonWriter;
import kotlin.jvm.functions.Function1;
import kotlin.reflect.KFunction;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

enum ScratchFile implements Adapter<Object> {
  INSTANCE;

  enum Test implements  Adapter<String> {
    INSTANCE;

    @Override public String fromJson(@NotNull JsonReader reader, @NotNull CustomScalarAdapters customScalarAdapters) {

      BooleanExpressionKt.evaluate(
          BooleanExpression.True.INSTANCE,
          Collections.emptySet(),
          "toh"
      );

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
