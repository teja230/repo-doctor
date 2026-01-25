# Failing Demo Project

This is a sample Maven project with intentionally failing tests for demo purposes.

## The Bug

`Calculator.add(a, b)` returns `a - b` instead of `a + b`.

## Tests

- ✅ `testSubtract` - passes
- ✅ `testMultiply` - passes  
- ✅ `testDivide` - passes
- ✅ `testDivideByZero` - passes
- ❌ `testAdd` - FAILS (expects 5, gets -1)
- ❌ `testAddNegatives` - FAILS (expects -2, gets 0)

## Usage

```bash
# Run tests (will fail)
mvn test

# Expected output:
# Tests run: 6, Failures: 2, Errors: 0
```

## Expected Fix

Change line 13 in `Calculator.java`:
```diff
-        return a - b;
+        return a + b;
```
