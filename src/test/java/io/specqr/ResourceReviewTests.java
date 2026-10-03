package io.specqr;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent-review regressions for public factories and bounded diagnostic/CLI helpers. */
public final class ResourceReviewTests {
  private ResourceReviewTests() {}

  private static int assertions;

  private static void reject(String code, Runnable action) {
    assertions++;
    try {
      action.run();
    } catch (SpecQrException error) {
      if (code.equals(error.code())) return;
      throw new AssertionError("Expected " + code + ", got " + error.code(), error);
    }
    throw new AssertionError("Expected " + code);
  }

  private static List<Object> lazyNested(int depth, AtomicInteger reads) {
    Object child = depth == 1 ? null : lazyNested(depth - 1, reads);
    return new AbstractList<>() {
      @Override public int size() { return 900_000; }
      @Override public Object get(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        reads.incrementAndGet();
        if (index > 0) throw new AssertionError("Traversed after aggregate preflight should reject");
        return child;
      }
    };
  }

  public static int run() throws ReflectiveOperationException {
    assertions = 0;
    AtomicInteger reads = new AtomicInteger();
    Map<String, Object> nested = Map.of("nested", lazyNested(32, reads));
    reject("INVALID_INPUT", () -> SpecQr.freeze(nested));
    assertions++;
    if (reads.get() != 1)
      throw new AssertionError("Expected aggregate rejection before reading second list, got " + reads);

    AtomicInteger yielded = new AtomicInteger();
    List<Object> misleadingSize = new AbstractList<>() {
      @Override public int size() { return 1; }
      @Override public Object get(int index) {
        if (index != 0) throw new IndexOutOfBoundsException(index);
        return 0;
      }
      @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
          @Override public boolean hasNext() { return yielded.get() < 1_000_001; }
          @Override public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            yielded.incrementAndGet();
            return 0;
          }
        };
      }
    };
    reject("INVALID_INPUT", () -> SpecQr.freeze(Map.of("items", misleadingSize)));
    assertions++;
    if (yielded.get() > 1_000_000)
      throw new AssertionError("Actual traversal exceeded node budget: " + yielded);

    Map<String, Object> cycle = new HashMap<>();
    cycle.put("self", cycle);
    reject("INVALID_INPUT", () -> Json.write(cycle));

    for (String mode : Arrays.asList(null, "", "auto", "fnc1", "eci", "structured-append", "invalid"))
      reject("INVALID_MODE", () -> Segment.fromText(mode, "x"));
    byte[] supplied = {1, 2};
    StructuredAppend.Part part = new StructuredAppend.Part(1, 2, 3, supplied);
    supplied[0] = 9;
    assertions++;
    if (((byte[]) part.data())[0] != 1) throw new AssertionError("Part retained caller bytes");
    // Kotlin internal properties may compile to public mangled Java getters. Exercise all
    // public zero-argument accessors so no exposed getter can leak the backing byte array.
    for (var method : part.getClass().getMethods()) {
      if (method.getParameterCount() == 0
          && (method.getReturnType() == Object.class || method.getReturnType() == byte[].class)) {
        Object result = method.invoke(part);
        if (result instanceof byte[] returned) {
          returned[0] = 7;
          assertions++;
          if (((byte[]) part.data())[0] != 1)
            throw new AssertionError("Part backing bytes leaked through " + method.getName());
        }
      }
    }
    return assertions;
  }
}
