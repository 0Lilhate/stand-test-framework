package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiSchemaResourcesTest {

    @Test
    @DisplayName("scenario schema resource is present on the classpath and non-empty")
    void schemaResource_present() {
        String schema = AiSchemaResources.scenarioSchemaJson();
        assertThat(schema).contains("stand-test-scenario").contains("\"$defs\"");
    }

    @Test
    @DisplayName("generation-rules resource is present on the classpath and non-empty")
    void rulesResource_present() {
        String rules = AiSchemaResources.generationRules();
        assertThat(rules).contains("declarative scenario document");
    }

    @Test
    @DisplayName("a missing resource fails fast with a clear error")
    void missingResource_throws() {
        assertThatThrownBy(() -> AiSchemaResources.read("/does-not-exist.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("the loader is a non-instantiable utility class")
    void constructor_isPrivate() throws Exception {
        Constructor<AiSchemaResources> ctor = AiSchemaResources.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
