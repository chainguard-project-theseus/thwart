package dev.chainguard.thwart.benchmarks;

import java.util.ArrayList;
import java.util.List;

/** Deterministic benchmark data shared by the benchmarks and the equivalence test. */
public final class BenchmarkData {
  /** Rows in the owner table. */
  public static final int OWNER_COUNT = 100;

  /** Cards on the layout page. */
  public static final int CARD_COUNT = 20;

  private static final String[] FIRST = {
    "George", "Betty", "Eduardo", "Harold", "Peter", "Jean", "Jeff", "Maria", "David", "Carlos"
  };
  // Some names need escaping, so both engines do real escaping work.
  private static final String[] LAST = {
    "Franklin", "Davis", "Rodriquez", "O'Brien", "McTavish",
    "Coleman", "Black & Sons", "Escobito", "Schroeder", "<Estaban>"
  };
  private static final String[] CITY = {
    "Madison", "Sun Prairie", "McFarland", "Windsor", "Monona", "Waunakee"
  };
  private static final String[] PET = {
    "Leo", "Basil", "Rosy", "Jewel", "Iggy", "George",
    "Samantha", "Max", "Lucky", "Mulligan", "Freddy", "Sly"
  };
  private static final String[] TYPE = {"cat", "dog", "lizard", "snake", "bird", "hamster"};

  private BenchmarkData() {}

  /** {@value #OWNER_COUNT} owners with 0 to 3 pets each. */
  public static List<Owner> owners() {
    List<Owner> owners = new ArrayList<>(OWNER_COUNT);
    for (int i = 0; i < OWNER_COUNT; i++) {
      List<Pet> pets = new ArrayList<>();
      for (int p = 0; p < i % 4; p++) {
        pets.add(new Pet(PET[(i + p) % PET.length], TYPE[(i * 3 + p) % TYPE.length]));
      }
      owners.add(
          new Owner(
              i + 1,
              FIRST[i % FIRST.length],
              LAST[(i / FIRST.length) % LAST.length],
              (100 + i) + " W. Liberty St.",
              CITY[i % CITY.length],
              String.format("608555%04d", i),
              i % 3 != 0,
              pets));
    }
    return List.copyOf(owners);
  }

  /** The first {@value #CARD_COUNT} owners, for the layout page. */
  public static List<Owner> featured() {
    return owners().subList(0, CARD_COUNT);
  }
}
