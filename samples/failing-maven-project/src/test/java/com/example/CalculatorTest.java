package com.example;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for Calculator - these will fail because of the bug in add().
 */
public class CalculatorTest {

    private final Calculator calc = new Calculator();

    @Test
    public void testAdd() {
        // This test will FAIL because add() has a bug
        assertEquals("2 + 3 should equal 5", 5, calc.add(2, 3));
    }

    @Test
    public void testAddNegatives() {
        // This test will also FAIL
        assertEquals("-1 + -1 should equal -2", -2, calc.add(-1, -1));
    }

    @Test
    public void testSubtract() {
        // This test will PASS
        assertEquals("5 - 3 should equal 2", 2, calc.subtract(5, 3));
    }

    @Test
    public void testMultiply() {
        // This test will PASS
        assertEquals("4 * 3 should equal 12", 12, calc.multiply(4, 3));
    }

    @Test
    public void testDivide() {
        // This test will PASS
        assertEquals("10 / 2 should equal 5", 5, calc.divide(10, 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDivideByZero() {
        // This test will PASS
        calc.divide(10, 0);
    }
}
