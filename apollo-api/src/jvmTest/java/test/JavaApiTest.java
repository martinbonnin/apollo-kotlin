package test;

import com.apollographql.apollo3.api.Adapters;
import com.apollographql.apollo3.api.CompiledGraphQL;
import com.apollographql.apollo3.api.CompiledType;
import com.apollographql.apollo3.api.CustomScalarAdapters;
import com.apollographql.apollo3.api.StringAdapter;
import com.apollographql.apollo3.api.internal.json.BufferedSourceJsonReader;
import okio.Okio;
import org.junit.Test;

import java.io.ByteArrayInputStream;

import static com.google.common.truth.Truth.assertThat;

public class JavaApiTest {
  @Test
  public void nonNullString() {
    String json = "\"test\"";
    BufferedSourceJsonReader jsonReader = new BufferedSourceJsonReader(Okio.buffer(Okio.source(new ByteArrayInputStream(json.getBytes()))));
    String result = StringAdapter.INSTANCE.fromJson(jsonReader, CustomScalarAdapters.Empty);

    assertThat(result).isEqualTo("test");
  }

  @Test
  public void nullString() {
    String json = "null";
    BufferedSourceJsonReader jsonReader = new BufferedSourceJsonReader(Okio.buffer(Okio.source(new ByteArrayInputStream(json.getBytes()))));
    String result = Adapters.nullable(StringAdapter.INSTANCE).fromJson(jsonReader, CustomScalarAdapters.Empty);
    assertThat(result).isEqualTo(null);
  }

  @Test
  public void nullString2() {
    String json = "null";
    BufferedSourceJsonReader jsonReader = new BufferedSourceJsonReader(Okio.buffer(Okio.source(new ByteArrayInputStream(json.getBytes()))));
    String result = Adapters.NullableStringAdapter.fromJson(jsonReader, CustomScalarAdapters.Empty);
    assertThat(result).isEqualTo(null);

  }
}

