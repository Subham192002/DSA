// Example 1:

// Input: digits = [4,3,2,1]
//  Output: [4,3,2,2]
//  Explanation: The array represents the integer 4321.
//  Incrementing by one gives 4321 + 1 = 4322.
//  Thus, the result should be [4,3,2,2].

//  Example 2:

//  Input: digits = [9]
//  Output: [1,0]
//  Explanation: The array represents the integer 9.
//  Incrementing by one gives 9 + 1 = 10.
//  Thus, the result should be [1,0]. 


public class PlusOne {
    public static int[] plusOne(int[] digits) {
        for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i] < 9) {
                digits[i]++;
                return digits;
            }
            digits[i] = 0;
        }
        int[] res = new int[digits.length + 1];
        res[0] = 1;
        return res;
    }

    public static void main(String[] args) {
        int digits[] = { 4, 3, 2, 1 };
        int res[] = plusOne(digits);
        for (int i = 0; i < res.length; i++) {
            System.out.print(res[i] + " ");
        }
    }
}
