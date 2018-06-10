package dummypackage;

public class MyClass {

    // Dummy values to pollute constant pool
    int nine = 9;
    long many = 100000000000L;
    double almostZero = 0.1;
    String string = "WORKER";

    public class MyInnerClass {
        double 𝓁𝒾𝓉𝓁ℯ = 31415926535897932384.6e-10;
    }

    /*
     * Last part of name contains UTF16 surrogate pairs, which are broken in JVM.
     * https://bugs.java.com/bugdatabase/view_bug.do?bug_id=8079633
     *
     * Uncomment when fixed.
     */
    public class ÓťhéřHíghłýŮñÏçộḓễ/*𝓒𝕃𝙰𝔰𝖲*/ {
        double 𝓛𝓸𝓽𝓼 = 31415926535897932384.6e10;
    }
}
