package ru.configplatform.configserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.configplatform.configserver.dto.DiffResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DiffServiceTest {

    private DiffService diffService;

    @BeforeEach
    void setUp() {
        diffService = new DiffService(new ObjectMapper());
    }

    @Test
    void shouldDetectAddedKeys() {
        String oldJson = """
                {"a": 1}
                """;
        String newJson = """
                {"a": 1, "b": 2}
                """;

        DiffResponse diff = diffService.computeDiff(oldJson, newJson, 1, 2);

        assertThat(diff.getAdded()).containsEntry("b", 2);
        assertThat(diff.getRemoved()).isEmpty();
        assertThat(diff.getChanged()).isEmpty();
    }

    @Test
    void shouldDetectRemovedKeys() {
        String oldJson = """
                {"a": 1, "b": 2}
                """;
        String newJson = """
                {"a": 1}
                """;

        DiffResponse diff = diffService.computeDiff(oldJson, newJson, 1, 2);

        assertThat(diff.getAdded()).isEmpty();
        assertThat(diff.getRemoved()).containsEntry("b", 2);
        assertThat(diff.getChanged()).isEmpty();
    }

    @Test
    void shouldDetectChangedValues() {
        String oldJson = """
                {"a": 1, "b": "old"}
                """;
        String newJson = """
                {"a": 1, "b": "new"}
                """;

        DiffResponse diff = diffService.computeDiff(oldJson, newJson, 1, 2);

        assertThat(diff.getAdded()).isEmpty();
        assertThat(diff.getRemoved()).isEmpty();
        assertThat(diff.getChanged()).containsKey("b");
        assertThat(diff.getChanged().get("b").getOldValue()).isEqualTo("old");
        assertThat(diff.getChanged().get("b").getNewValue()).isEqualTo("new");
    }

    @Test
    void shouldHandleComplexDiff() {
        String oldJson = """
                {"keep": 1, "remove": 2, "change": "old"}
                """;
        String newJson = """
                {"keep": 1, "add": 3, "change": "new"}
                """;

        DiffResponse diff = diffService.computeDiff(oldJson, newJson, 5, 6);

        assertThat(diff.getVersionFrom()).isEqualTo(5);
        assertThat(diff.getVersionTo()).isEqualTo(6);
        assertThat(diff.getAdded()).containsEntry("add", 3);
        assertThat(diff.getRemoved()).containsEntry("remove", 2);
        assertThat(diff.getChanged()).containsKey("change");
    }

    @Test
    void shouldHandleNullOldPayload() {
        String newJson = """
                {"a": 1}
                """;

        DiffResponse diff = diffService.computeDiff(null, newJson, 0, 1);

        assertThat(diff.getAdded()).containsEntry("a", 1);
        assertThat(diff.getRemoved()).isEmpty();
    }

    @Test
    void shouldHandleNullNewPayload() {
        String oldJson = """
                {"a": 1}
                """;

        DiffResponse diff = diffService.computeDiff(oldJson, null, 1, 2);

        assertThat(diff.getRemoved()).containsEntry("a", 1);
        assertThat(diff.getAdded()).isEmpty();
    }

    @Test
    void shouldHandleBothNull() {
        DiffResponse diff = diffService.computeDiff(null, null, 0, 0);

        assertThat(diff.getAdded()).isEmpty();
        assertThat(diff.getRemoved()).isEmpty();
        assertThat(diff.getChanged()).isEmpty();
    }

    @Test
    void shouldHandleScalarValues() {
        String oldJson = "\"hello\"";
        String newJson = "\"world\"";

        DiffResponse diff = diffService.computeDiff(oldJson, newJson, 1, 2);

        assertThat(diff.getChanged()).containsKey("_value");
    }

    @Test
    void shouldSerializeDiff() {
        DiffResponse diff = diffService.computeDiff(
                "{\"a\": 1}", "{\"b\": 2}", 1, 2);

        String serialized = diffService.serializeDiff(diff);

        assertThat(serialized).contains("versionFrom");
        assertThat(serialized).contains("added");
        assertThat(serialized).contains("removed");
    }
}
