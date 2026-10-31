public class Bench {
    static long arith(long n) {
        long x = 0;
        for (long i = 0; i < n; i++) x = x + i % 7;
        return x;
    }
    static long arrSum(Long[] xs) {
        long s = 0;
        for (Long v : xs) s += v;
        return s;
    }
    static long arrSumPrim(long[] xs) {
        long s = 0;
        for (long v : xs) s += v;
        return s;
    }
    enum Color { Red, Green, Blue }
    static long matchLoop(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) {
            Color c = Color.Green;
            int r;
            switch (c) { case Red -> r = 1; case Green -> r = 2; default -> r = 3; }
            s += r;
        }
        return s;
    }
    public static void main(String[] a) {
        long n = 500_000_000L;
        long t0 = System.nanoTime(); arith(n); long t1 = System.nanoTime();
        System.out.println("arith " + (t1 - t0));
        Long[] box = new Long[10_000_000];
        for (int i = 0; i < box.length; i++) box[i] = (long) (i % 100);
        long t2 = System.nanoTime(); arrSum(box); long t3 = System.nanoTime();
        System.out.println("arrSumBox " + (t3 - t2));
        long[] prim = new long[10_000_000];
        for (int i = 0; i < prim.length; i++) prim[i] = i % 100;
        long t4 = System.nanoTime(); arrSumPrim(prim); long t5 = System.nanoTime();
        System.out.println("arrSumPrim " + (t5 - t4));
        long t6 = System.nanoTime(); matchLoop(50_000_000L); long t7 = System.nanoTime();
        System.out.println("matchEnum " + (t7 - t6));
    }
}
