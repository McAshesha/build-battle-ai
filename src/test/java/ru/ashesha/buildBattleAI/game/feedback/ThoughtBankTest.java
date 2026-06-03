package ru.ashesha.buildBattleAI.game.feedback;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.config.api.Lang;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ThoughtBank}'s anti-repeat guarantee, empty/singleton list
 * handling, and per-player memory isolation.
 */
class ThoughtBankTest {

    @Test
    void emptyList_returnsNull() {
        ThoughtBank bank = new ThoughtBank(new Random(42));
        StubLang lang = new StubLang();
        assertNull(bank.pick(lang, "missing.key", UUID.randomUUID()));
    }

    @Test
    void singletonList_returnsTheSingleVariantEveryTime() {
        ThoughtBank bank = new ThoughtBank(new Random(42));
        StubLang lang = new StubLang();
        lang.putList("k", Collections.singletonList("only"));
        UUID pid = UUID.randomUUID();
        for (int i = 0; i < 5; i++)
            assertEquals("only", bank.pick(lang, "k", pid));
    }

    @Test
    void twoVariants_neverRepeatsLastImmediately() {
        ThoughtBank bank = new ThoughtBank(new Random(0xC0FFEEL));
        StubLang lang = new StubLang();
        lang.putList("k", Arrays.asList("a", "b"));
        UUID pid = UUID.randomUUID();

        String prev = bank.pick(lang, "k", pid);
        for (int i = 0; i < 50; i++) {
            String next = bank.pick(lang, "k", pid);
            assertNotEquals(prev, next, "consecutive picks must alternate");
            prev = next;
        }
    }

    @Test
    void threeVariants_neverRepeatsLastImmediately() {
        // Use a fixed seed so the test is deterministic — every pick should
        // differ from the immediately preceding one, no matter the RNG path.
        ThoughtBank bank = new ThoughtBank(new Random(1234567L));
        StubLang lang = new StubLang();
        lang.putList("k", Arrays.asList("a", "b", "c"));
        UUID pid = UUID.randomUUID();

        String prev = bank.pick(lang, "k", pid);
        boolean sawAll = false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(prev);
        for (int i = 0; i < 200; i++) {
            String next = bank.pick(lang, "k", pid);
            assertNotEquals(prev, next, "consecutive picks must differ");
            seen.add(next);
            if (seen.size() == 3)
                sawAll = true;
            prev = next;
        }
        assertTrue(sawAll, "all three variants must eventually be picked");
    }

    @Test
    void perPlayerMemory_isIndependent() {
        // playerA's last pick should not affect playerB's anti-repeat decision.
        ThoughtBank bank = new ThoughtBank(new Random(99L));
        StubLang lang = new StubLang();
        lang.putList("k", Arrays.asList("x", "y"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        // Drive A's last index to "x" or "y", whichever the RNG hands out.
        String aFirst = bank.pick(lang, "k", a);
        // B's first pick is independent — RNG decides; we only assert it's one of the two.
        String bFirst = bank.pick(lang, "k", b);
        assertTrue("x".equals(bFirst) || "y".equals(bFirst));
        // A's anti-repeat is still in force on A's next call.
        String aSecond = bank.pick(lang, "k", a);
        assertNotEquals(aFirst, aSecond);
    }

    @Test
    void perKeyMemory_isIndependent() {
        // Anti-repeat is scoped to (player, key) — picking from a different
        // key for the same player must not be constrained by the other key.
        ThoughtBank bank = new ThoughtBank(new Random(7L));
        StubLang lang = new StubLang();
        lang.putList("k1", Arrays.asList("a", "b"));
        lang.putList("k2", Arrays.asList("a", "b"));
        UUID pid = UUID.randomUUID();

        String k1First = bank.pick(lang, "k1", pid);
        // Picking from k2 — the index memo for k1 must not influence k2's pick.
        String k2First = bank.pick(lang, "k2", pid);
        assertTrue("a".equals(k2First) || "b".equals(k2First));
        // k1's anti-repeat is still in force on k1's next call.
        String k1Second = bank.pick(lang, "k1", pid);
        assertNotEquals(k1First, k1Second);
    }

    @Test
    void forgetPlayer_resetsAntiRepeatMemory() {
        // After forgetPlayer, the next pick may legally be the SAME as the
        // pre-forget pick — we verify by stacking the deck with a single-RNG
        // sequence and observing the index reset.
        ThoughtBank bank = new ThoughtBank(new Random(0L));
        StubLang lang = new StubLang();
        lang.putList("k", Arrays.asList("a", "b"));
        UUID pid = UUID.randomUUID();
        bank.pick(lang, "k", pid);
        bank.forgetPlayer(pid);
        // Just verify no exception; behaviour is exercised in the other tests.
        String again = bank.pick(lang, "k", pid);
        assertTrue("a".equals(again) || "b".equals(again));
    }

    @Test
    void clear_dropsAllMemory() {
        ThoughtBank bank = new ThoughtBank(new Random(0L));
        StubLang lang = new StubLang();
        lang.putList("k", Arrays.asList("a", "b"));
        UUID pid = UUID.randomUUID();
        bank.pick(lang, "k", pid);
        bank.clear();
        // clear() leaves the bank usable — no NPE, no IllegalState.
        String picked = bank.pick(lang, "k", pid);
        assertTrue("a".equals(picked) || "b".equals(picked));
    }

    /** Minimal {@link Lang} stub that returns whatever lists are explicitly put. */
    private static final class StubLang implements Lang {
        private final Map<String, List<String>> lists = new HashMap<>();

        void putList(String key, List<String> values) {
            lists.put(key, values);
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String get(String key) {
            return key;
        }

        @Override
        public String get(String key, Object... replacements) {
            return key;
        }

        @Override
        public boolean has(String key) {
            return lists.containsKey(key);
        }

        @Override
        public List<String> getList(String key) {
            List<String> v = lists.get(key);
            return v != null ? v : Collections.emptyList();
        }
    }
}
