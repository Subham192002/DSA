public class NthribonacciniNumber {

        public static int tribonacci(int n) {
            if (n <= 1) return n;
            else if (n == 2) return n - 1;
            else {
                int first = 1;
                int second = 1;
                int third = 2;

                for (int i = 3; i < n; i++) {
                    int current = first + second + third;
                    first = second;
                    second = third;
                    third = current;
                }
                return third;
            }
        }

    public static void main(String[] args) {
        int n = 25;
        System.out.println(tribonacci(n));
    }
}
