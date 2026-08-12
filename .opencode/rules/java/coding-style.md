---
paths:
  - "**/*.java"
---
# Java Coding Style

> This file extends [common/coding-style.md](../common/coding-style.md) with Java-specific content.

## Formatting

- **google-java-format** or **Checkstyle** (Google or Sun style) for enforcement
- One public top-level type per file
- Consistent indent: 2 or 4 spaces (match project standard)
- Member order: constants, fields, constructors, public methods, protected, private

## Immutability

- Prefer `record` for value types (Java 16+)
- Mark fields `final` by default — use mutable state only when required
- Return defensive copies from public APIs: `List.copyOf()`, `Map.copyOf()`, `Set.copyOf()` — but see
  *When `copyOf` is the wrong copy* below before reaching for them
- Copy-on-write: return new instances rather than mutating existing ones

```java
// GOOD — immutable value type
public record OrderSummary(Long id, String customerName, BigDecimal total) {}

// GOOD — final fields, no setters
public class Order {
    private final Long id;
    private final List<LineItem> items;

    public List<LineItem> getItems() {
        return List.copyOf(items);
    }
}
```

### When `copyOf` is the wrong copy

`List.copyOf()` / `Map.copyOf()` / `Set.copyOf()` and `Collectors.toMap()` are the reflexive spelling of
"copy defensively", and they silently drop properties the caller may be relying on:

- **all of them reject `null`** — a null element, key or value throws `NullPointerException` from inside
  the JDK, replacing whatever diagnosis the caller was about to produce;
- **`Map.copyOf` and `Collectors.toMap` also lose iteration order** — both return a hash-ordered map.
  (`List.copyOf` keeps order; a `Set` has none to keep.)

Reach for them when neither property matters. When either one does, copy through an ordered, null-tolerant
collection instead:

```java
// WRONG — a diagnostics map is rendered into a report in the order it was assembled, and
// "the last value observed was null" is an ordinary thing for a step to report
this.diagnostics = Map.copyOf(diagnostics);

// CORRECT — ordered, null-tolerant, still an immutable defensive copy
this.diagnostics = Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
```

Ask two questions before copying: **does a reader see this order?** (a report, a rendered message, a
generated statement) and **can a value legitimately be absent?** (a parsed document, an observed value, a
resolver that may return null). Either "yes" rules out `copyOf`/`toMap`.

Both failure modes hide from the obvious test: order needs `containsExactly`, not `containsEntry`, and the
null case needs a test that supplies one. In this repository the same mistake has been fixed three times —
step diagnostics reaching reports shuffled and a null value replacing a real failure with an NPE, a
document's `key:` with no value turning a located message into a JDK NPE, and a resolver whose contract
allows null. Where a loop is kept for this reason, say so in a comment, or the next cleanup pass will
"finish the job".

## Naming

Follow standard Java conventions:
- `PascalCase` for classes, interfaces, records, enums
- `camelCase` for methods, fields, parameters, local variables
- `SCREAMING_SNAKE_CASE` for `static final` constants
- Packages: all lowercase, reverse domain (`com.example.app.service`)

## Modern Java Features

Use modern language features where they improve clarity:
- **Records** for DTOs and value types (Java 16+)
- **Sealed classes** for closed type hierarchies (Java 17+)
- **Pattern matching** with `instanceof` — no explicit cast (Java 16+)
- **Text blocks** for multi-line strings — SQL, JSON templates (Java 15+)
- **Switch expressions** with arrow syntax (Java 14+)
- **Pattern matching in switch** — exhaustive sealed type handling (Java 21+)

```java
// Pattern matching instanceof
if (shape instanceof Circle c) {
    return Math.PI * c.radius() * c.radius();
}

// Sealed type hierarchy
public sealed interface PaymentMethod permits CreditCard, BankTransfer, Wallet {}

// Switch expression
String label = switch (status) {
    case ACTIVE -> "Active";
    case SUSPENDED -> "Suspended";
    case CLOSED -> "Closed";
};
```

## Optional Usage

- Return `Optional<T>` from finder methods that may have no result
- Use `map()`, `flatMap()`, `orElseThrow()` — never call `get()` without `isPresent()`
- Never use `Optional` as a field type or method parameter

```java
// GOOD
return repository.findById(id)
    .map(ResponseDto::from)
    .orElseThrow(() -> new OrderNotFoundException(id));

// BAD — Optional as parameter
public void process(Optional<String> name) {}
```

## Error Handling

- Prefer unchecked exceptions for domain errors
- Create domain-specific exceptions extending `RuntimeException`
- Avoid broad `catch (Exception e)` unless at top-level handlers
- Include context in exception messages

```java
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("Order not found: id=" + id);
    }
}
```

## Streams

- Use streams for transformations; keep pipelines short (3-4 operations max)
- Prefer method references when readable: `.map(Order::getTotal)`
- Avoid side effects in stream operations
- For complex logic, prefer a loop over a convoluted stream pipeline

## References

See skill: `java-coding-standards` for full coding standards with examples.
See skill: `jpa-patterns` for JPA/Hibernate entity design patterns.
