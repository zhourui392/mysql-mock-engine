package cn.vv.test.mysqlmock.storage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-16：_ci + PAD SPACE 归一化语义。 */
class CollationNormalizerTest {

    @Test
    void caseInsensitiveEquality() {
        assertTrue(CollationNormalizer.equalsNormalized("Alex", "alex"));
        assertTrue(CollationNormalizer.equalsNormalized("ABC", "abc"));
        assertFalse(CollationNormalizer.equalsNormalized("a", "b"));
    }

    @Test
    void trailingSpacesIgnored() {
        assertTrue(CollationNormalizer.equalsNormalized("a ", "a"));
        assertTrue(CollationNormalizer.equalsNormalized("a", "a   "));
    }

    @Test
    void bigDecimalKeyEqualityIgnoresScale() {
        assertTrue(CollationNormalizer.equalsNormalized(new BigDecimal("1.0"), new BigDecimal("1.00")));
    }

    @Test
    void nullSemantics() {
        assertTrue(CollationNormalizer.equalsNormalized(null, null));
        assertFalse(CollationNormalizer.equalsNormalized(null, "a"));
        assertFalse(CollationNormalizer.equalsNormalized("a", null));
    }

    @Test
    void caseInsensitiveOrdering() {
        assertEquals(-1, Integer.signum(CollationNormalizer.compareNormalized("apple", "BANANA")));
        assertEquals(0, CollationNormalizer.compareNormalized("abc", "ABC"));
    }
}
