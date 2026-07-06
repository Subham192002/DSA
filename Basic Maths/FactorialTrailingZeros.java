// Example 1:
// Input: n = 3
// Output: 0
// Explanation: 3! = 6, no trailing zero.

// Example 2:
// Input: n = 5
// Output: 1
// Explanation: 5! = 120, one trailing zero.

//Logic 
// The number of trailing zeros is equal to the number of factors of 5 in n!.
// Example: 10!
// 10! = 10 × 9 × 8 × ... × 1

// Numbers containing a factor of 5:

// 5
// 10

// Each contributes one factor of 5.

// Total = 2

// Trailing zeros = 2


public class FactorialTrailingZeros {

    public static int trailingZeroes(int n) {
        int count = 0;

        while (n != 0) {
            n = n / 5;
            count += n;
        }

        return count;
    }

    public static void main(String[] args) {
        System.out.println(trailingZeroes(25));
    }

}
