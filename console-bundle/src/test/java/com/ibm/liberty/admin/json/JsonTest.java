package com.ibm.liberty.admin.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.liberty.admin.model.ApplicationInfo;
import com.ibm.liberty.admin.model.MemoryInfo;
import com.ibm.liberty.admin.model.UserSettings;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesBeansWithSortedPropertiesAndWithoutNulls() {
        ApplicationInfo app = new ApplicationInfo("demo", "/demo", "STARTED");
        app.setDeployedTime(LocalDateTime.of(2026, 10, 16, 12, 30, 5));
        String json = Json.write(app);
        assertEquals("{\"activeRequests\":0,\"contextRoot\":\"/demo\",\"deployedTime\":\"2026-10-16T12:30:05\","
            + "\"name\":\"demo\",\"size\":0,\"state\":\"STARTED\",\"totalRequests\":0}", json);
    }

    @Test
    void writesNumbersBooleansListsAndMaps() {
        MemoryInfo memory = new MemoryInfo(200, 50, 150, 300);
        Map<String, Object> value = Json.object("memory", memory, "flags", List.of(true, false), "nan", Double.NaN);
        String json = Json.write(value);
        assertTrue(json.startsWith("{\"memory\":{\"freeMemory\":50,\"maxMemory\":300,\"totalMemory\":200,"
            + "\"usagePercentage\":50.0,\"usedMemory\":150}"), json);
        assertTrue(json.endsWith("\"flags\":[true,false],\"nan\":null}"), json);
    }

    @Test
    void escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script\\u003e\"", Json.write("a\"b\\c\n</script>"));
    }

    @Test
    void parsesAndBindsUserSettings() {
        UserSettings settings = Json.read("{\"theme\":\"dark\",\"showCpuChart\":false,"
            + "\"dynamicCharts\":[\"heap\",\"cpu\"],\"unknown\":{\"x\":[1,2]}}", UserSettings.class);
        assertEquals("dark", settings.getTheme());
        assertFalse(settings.isShowCpuChart());
        assertTrue(settings.isShowThreadChart()); // default kept
        assertEquals(Arrays.asList("heap", "cpu"), settings.getDynamicCharts());
    }

    @Test
    void roundTripsUserSettings() {
        UserSettings settings = new UserSettings();
        settings.setTheme("dark");
        settings.setDynamicCharts(List.of("a\"b"));
        UserSettings copy = Json.read(Json.write(settings), UserSettings.class);
        assertEquals("dark", copy.getTheme());
        assertEquals(List.of("a\"b"), copy.getDynamicCharts());
    }

    @Test
    void bindsNumbersAndDates() {
        ApplicationInfo app = Json.read("{\"name\":\"x\",\"size\":12.0,\"activeRequests\":\"3\","
            + "\"deployedTime\":\"2026-01-02T03:04:05\"}", ApplicationInfo.class);
        assertEquals(12L, app.getSize());
        assertEquals(3, app.getActiveRequests());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), app.getDeployedTime());
    }

    @Test
    void parsesUnicodeEscapesAndNestedValues() {
        Object parsed = Json.parse(" {\"a\": [1, -2.5e3, \"\\u00e9\", null, {\"b\": true}]} ");
        assertEquals("{\"a\":[1,-2.5E+3,\"\u00e9\",null,{\"b\":true}]}", Json.write(parsed));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1, 2"));
        assertThrows(Json.JsonException.class, () -> Json.read("[1]", UserSettings.class));
        assertThrows(Json.JsonException.class, () -> Json.read("{\"showCpuChart\":[1]}", UserSettings.class));
    }
}
