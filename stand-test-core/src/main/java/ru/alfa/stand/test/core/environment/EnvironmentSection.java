package ru.alfa.stand.test.core.environment;

import java.util.Map;
import java.util.Optional;

/** A named, opaque environment section whose entries are addressed by alias. */
public record EnvironmentSection(String name, Map<String, SectionEntry> entries) {

    public EnvironmentSection {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("section name must not be blank");
        }
        entries = Map.copyOf(entries == null ? Map.of() : entries);
        entries.forEach((alias, entry) -> {
            if (!alias.equals(entry.alias())) {
                throw new IllegalArgumentException("section entry key '" + alias + "' differs from its alias");
            }
        });
    }

    public Optional<SectionEntry> entry(String alias) {
        return Optional.ofNullable(entries.get(alias));
    }
}
