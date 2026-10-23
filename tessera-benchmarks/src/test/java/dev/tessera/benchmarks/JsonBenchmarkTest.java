package dev.tessera.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonBenchmarkTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 100})
  void benchmarkProducesCompleteExpectedJson(int size) {
    JsonBenchmark benchmark = new JsonBenchmark();
    benchmark.size = size;
    benchmark.setUp();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", i);
      row.put("label", "Row </script> & ${" + i + "}");
      row.put("values", List.of(i, i + 1, i + 2));
      rows.add(row);
    }
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("rows", rows);
    page.put("selected", rows.get(0));
    page.put("byName", Map.of("first", rows.get(0)));
    StringOutput json = new StringOutput();
    Escapers.json(json, page);
    assertEquals("<script>const page = " + json + ";</script>\n", benchmark.embeddedBeans());
  }
}
